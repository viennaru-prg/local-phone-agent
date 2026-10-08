#include <jni.h>
#include <llama.h>
#include <atomic>
#include <algorithm>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <chrono>

struct Runtime {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::atomic<bool> cancelled{false};
    std::chrono::steady_clock::time_point deadline;
    int64_t timing[5] = {}; // prompt, reused input, output, prefill ms, generation ms
    // Computed KV only, never a response cache. Changed user/screen tokens are removed.
    std::vector<llama_token> previous_input;
    ~Runtime() { if (context) llama_free(context); if (model) llama_model_free(model); }
};
static bool abort_generation(void * data) {
    auto * runtime = static_cast<Runtime *>(data);
    return runtime->cancelled.load() || std::chrono::steady_clock::now() >= runtime->deadline;
}
static void check_running(Runtime * runtime) {
    if (runtime->cancelled.load()) throw std::runtime_error("Inference cancelled");
    if (std::chrono::steady_clock::now() >= runtime->deadline)
        throw std::runtime_error("Qwen inference time limit reached; no incomplete plan executed");
}
// End as soon as the grammar has generated a complete JSON root. Waiting for EOG
// can waste the output budget on trailing whitespace or reject a valid finished plan.
static bool complete_json(const std::string & value) {
    int depth = 0; bool quoted = false, escaped = false, started = false;
    for (char ch : value) {
        if (quoted) {
            if (escaped) escaped = false;
            else if (ch == '\\') escaped = true;
            else if (ch == '"') quoted = false;
        } else if (ch == '"') quoted = true;
        else if (ch == '{' || ch == '[') { ++depth; started = true; }
        else if (ch == '}' || ch == ']') { if (--depth == 0 && started) return true; }
    }
    return false;
}
static void fail(JNIEnv * env, const std::exception & failure) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), failure.what());
}
static std::string bytes(JNIEnv * env, jbyteArray value) {
    std::string out(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, out.size(), reinterpret_cast<jbyte *>(out.data()));
    return out;
}
extern "C" JNIEXPORT jlong JNICALL
Java_dev_localphone_agent_runtime_LlamaNative_load(JNIEnv * env, jobject, jstring path, jint threads) {
    try {
        static std::once_flag initialized;
        std::call_once(initialized, [] { llama_backend_init(); });
        auto runtime = std::make_unique<Runtime>();
        runtime->deadline = std::chrono::steady_clock::time_point::max();
        const char * location = env->GetStringUTFChars(path, nullptr);
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        runtime->model = llama_model_load_from_file(location, params);
        env->ReleaseStringUTFChars(path, location);
        if (!runtime->model) throw std::runtime_error("Qwen GGUF model initialization failed");
        auto context_params = llama_context_default_params();
        context_params.n_ctx = 4096;
        context_params.n_batch = 512;
        context_params.n_ubatch = 128;
        context_params.n_threads = threads;
        context_params.n_threads_batch = threads;
        context_params.offload_kqv = false;
        context_params.op_offload = false;
        context_params.abort_callback = abort_generation;
        context_params.abort_callback_data = runtime.get();
        runtime->context = llama_init_from_model(runtime->model, context_params);
        if (!runtime->context) throw std::runtime_error("Qwen context initialization failed");
        return reinterpret_cast<jlong>(runtime.release());
    } catch (const std::exception & error) { fail(env, error); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_localphone_agent_runtime_LlamaNative_infer(JNIEnv * env, jobject, jlong handle, jbyteArray prompt, jint limit, jbyteArray grammar, jlong timeout_ms, jbyteArray snapshot_path) {
    auto * runtime = reinterpret_cast<Runtime *>(handle);
    try {
        if (!runtime) throw std::runtime_error("Qwen is unloaded");
        const auto started = std::chrono::steady_clock::now();
        runtime->deadline = started + std::chrono::milliseconds(timeout_ms);
        std::fill(std::begin(runtime->timing), std::end(runtime->timing), 0);
        check_running(runtime);
        const auto * vocab = llama_model_get_vocab(runtime->model);
        const std::string input = bytes(env, prompt);
        int count = -llama_tokenize(vocab, input.data(), input.size(), nullptr, 0, false, true);
        if (count <= 0 || count + limit > 4096) throw std::runtime_error("Qwen context limit exceeded");
        std::vector<llama_token> tokens(count);
        count = llama_tokenize(vocab, input.data(), input.size(), tokens.data(), tokens.size(), false, true);
        if (count <= 0) throw std::runtime_error("Qwen tokenization failed");
        int common = 0;
        while (common < count - 1 && common < static_cast<int>(runtime->previous_input.size()) &&
               tokens[common] == runtime->previous_input[common]) ++common;
        auto * memory = llama_get_memory(runtime->context);
        if (common == 0 || !llama_memory_seq_rm(memory, -1, common, -1)) {
            llama_memory_clear(memory, true); common = 0;
        }
        runtime->previous_input.clear(); // Aborted/failed generations cannot seed the next request.
        runtime->timing[0] = count; runtime->timing[1] = common;
        for (int offset = common; offset < count; offset += 512) {
            check_running(runtime);
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(512, count - offset));
            if (llama_decode(runtime->context, batch) != 0) { check_running(runtime); throw std::runtime_error("Qwen prompt decode failed"); }
        }
        const auto decoded_at = std::chrono::steady_clock::now();
        runtime->timing[3] = std::chrono::duration_cast<std::chrono::milliseconds>(decoded_at - started).count();
        // Only the adapter's constant startup probe supplies this private path. Save the
        // INPUT prefill before generating any answer; never persist user command/response KV.
        const auto snapshot = bytes(env, snapshot_path);
        if (!snapshot.empty()) {
            try { llama_state_save_file(runtime->context, snapshot.c_str(), tokens.data(), tokens.size()); }
            catch (const std::exception &) { /* Optional startup acceleration. */ }
        }
        auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        const std::string grammar_text = bytes(env, grammar);
        auto * constraint = llama_sampler_init_grammar(vocab, grammar_text.c_str(), "root");
        if (!constraint) throw std::runtime_error("Qwen tool grammar initialization failed");
        llama_sampler_chain_add(sampler.get(), constraint);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        std::string output;
        bool ended = false;
        for (int step = 0; step < limit && !runtime->cancelled.load(); ++step) {
            check_running(runtime);
            auto token = llama_sampler_sample(sampler.get(), runtime->context, -1);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            char piece[256];
            int length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (length < 0) throw std::runtime_error("Qwen token piece exceeded buffer");
            output.append(piece, length);
            runtime->timing[2] = step + 1;
            if (complete_json(output)) { ended = true; break; }
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(runtime->context, batch) != 0) { check_running(runtime); throw std::runtime_error("Qwen generation decode failed"); }
        }
        check_running(runtime);
        runtime->timing[4] = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - decoded_at).count();
        if (!ended) throw std::runtime_error("Qwen output token limit reached; no plan executed");
        runtime->previous_input = tokens;
        auto result = env->NewByteArray(output.size());
        env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception & error) {
        if (runtime) runtime->previous_input.clear();
        fail(env, error); return nullptr;
    }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_localphone_agent_runtime_LlamaNative_restoreInput(JNIEnv * env, jobject, jlong handle, jbyteArray path, jbyteArray prompt) {
    auto * runtime = reinterpret_cast<Runtime *>(handle);
    if (!runtime) return false;
    try {
        const auto input = bytes(env, prompt);
        const auto * vocab = llama_model_get_vocab(runtime->model);
        const int count = -llama_tokenize(vocab, input.data(), input.size(), nullptr, 0, false, true);
        if (count <= 0 || count >= 4096) return false;
        std::vector<llama_token> expected(count), restored(4096);
        if (llama_tokenize(vocab, input.data(), input.size(), expected.data(), count, false, true) != count) return false;
        size_t restored_count = 0;
        if (!llama_state_load_file(runtime->context, bytes(env, path).c_str(), restored.data(), restored.size(), &restored_count) ||
            restored_count != expected.size() || !std::equal(expected.begin(), expected.end(), restored.begin()))
            throw std::runtime_error("Incompatible input prefix");
        runtime->previous_input = expected;
        return true;
    } catch (const std::exception &) {
        runtime->previous_input.clear(); llama_memory_clear(llama_get_memory(runtime->context), true); return false;
    }
}
extern "C" JNIEXPORT void JNICALL
Java_dev_localphone_agent_runtime_LlamaNative_begin(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Runtime *>(handle)->cancelled.store(false);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_localphone_agent_runtime_LlamaNative_metrics(JNIEnv * env, jobject, jlong handle) {
    auto result = env->NewLongArray(5);
    if (handle) env->SetLongArrayRegion(result, 0, 5, reinterpret_cast<Runtime *>(handle)->timing);
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_localphone_agent_runtime_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Runtime *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_localphone_agent_runtime_LlamaNative_unload(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Runtime *>(handle);
}
