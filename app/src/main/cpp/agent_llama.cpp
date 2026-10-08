#include <jni.h>
#include <llama.h>
#include <atomic>
#include <algorithm>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

struct Runtime {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::atomic<bool> cancelled{false};
    // Computed KV only, never a response cache. Changed user/screen tokens are removed.
    std::vector<llama_token> previous_input;
    ~Runtime() { if (context) llama_free(context); if (model) llama_model_free(model); }
};
static bool abort_generation(void * data) { return static_cast<Runtime *>(data)->cancelled.load(); }
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
Java_dev_localphone_agent_runtime_LlamaNative_infer(JNIEnv * env, jobject, jlong handle, jbyteArray prompt, jint limit, jbyteArray grammar) {
    auto * runtime = reinterpret_cast<Runtime *>(handle);
    try {
        if (!runtime) throw std::runtime_error("Qwen is unloaded");
        runtime->cancelled.store(false);
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
        for (int offset = common; offset < count; offset += 512) {
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(512, count - offset));
            if (llama_decode(runtime->context, batch) != 0) throw std::runtime_error(runtime->cancelled ? "Inference cancelled" : "Qwen prompt decode failed");
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
            auto token = llama_sampler_sample(sampler.get(), runtime->context, -1);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            char piece[256];
            int length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (length < 0) throw std::runtime_error("Qwen token piece exceeded buffer");
            output.append(piece, length);
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(runtime->context, batch) != 0) throw std::runtime_error(runtime->cancelled ? "Inference cancelled" : "Qwen generation decode failed");
        }
        if (runtime->cancelled.load()) throw std::runtime_error("Inference cancelled");
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
extern "C" JNIEXPORT void JNICALL
Java_dev_localphone_agent_runtime_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Runtime *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_localphone_agent_runtime_LlamaNative_unload(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Runtime *>(handle);
}
