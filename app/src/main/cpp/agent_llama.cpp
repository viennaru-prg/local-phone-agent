#include <jni.h>
#include <llama.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "AgentLlama", __VA_ARGS__)

struct Runtime {
    llama_model * model = nullptr;
    struct Context {
        llama_context * value = nullptr;
        std::vector<llama_token> cached;
    };
    // Action and completion prompts have different systems. Keep their prefixes independently
    // while sharing one set of model weights; switching roles must not discard the other cache.
    std::array<Context, 2> contexts;
    llama_context_params params = {};
    int n_ctx = 0;
    std::atomic<bool> cancelled{false};
    std::chrono::steady_clock::time_point deadline;
    // prompt tokens, reused prefix tokens, generated tokens, prefill ms, generation ms
    int64_t timing[5] = {};
    // Tokens currently held in the KV cache. A new prompt reuses the shared prefix (system prompt,
    // goal, history) and only decodes the changed tail (the new screen).
    ~Runtime() {
        for (auto & context : contexts) if (context.value) llama_free(context.value);
        if (model) llama_model_free(model);
    }
};

static bool should_abort(void * data) {
    auto * rt = static_cast<Runtime *>(data);
    return rt->cancelled.load() || std::chrono::steady_clock::now() >= rt->deadline;
}
static void check(Runtime * rt) {
    if (rt->cancelled.load()) throw std::runtime_error("cancelled");
    if (std::chrono::steady_clock::now() >= rt->deadline) throw std::runtime_error("inference timeout");
}
// Stop as soon as the grammar produced one complete JSON object (after any <think> block).
static bool complete_json(const std::string & all) {
    if (all.rfind("<think>", 0) == 0 && all.find("</think>") == std::string::npos) return false;
    const auto end_think = all.find("</think>");
    const std::string s = end_think == std::string::npos ? all : all.substr(end_think + 8);
    int depth = 0; bool quoted = false, escaped = false, started = false;
    for (char c : s) {
        if (quoted) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false; }
        else if (c == '"') quoted = true;
        else if (c == '{' || c == '[') { ++depth; started = true; }
        else if (c == '}' || c == ']') { if (--depth == 0 && started) return true; }
    }
    return false;
}
static std::string bytes(JNIEnv * env, jbyteArray v) {
    std::string out(env->GetArrayLength(v), '\0');
    env->GetByteArrayRegion(v, 0, out.size(), reinterpret_cast<jbyte *>(out.data()));
    return out;
}
static void throw_java(JNIEnv * env, const std::exception & e) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_localphone_agent_llm_LlamaNative_load(JNIEnv * env, jobject, jstring path, jint threads, jint n_ctx, jint gpu_layers) {
    try {
        static std::once_flag once;
        std::call_once(once, [] { llama_backend_init(); });
        auto rt = std::make_unique<Runtime>();
        rt->deadline = std::chrono::steady_clock::time_point::max();
        const char * p = env->GetStringUTFChars(path, nullptr);
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = gpu_layers;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        rt->model = llama_model_load_from_file(p, mp);
        env->ReleaseStringUTFChars(path, p);
        if (!rt->model) throw std::runtime_error("GGUF model load failed");
        auto cp = llama_context_default_params();
        cp.n_ctx = n_ctx;
        // Smaller batches shrink the GPU compute buffer (it scales with n_ubatch); prompts are only ~1-2k tokens.
        cp.n_batch = 256;
        cp.n_ubatch = 256;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.abort_callback = should_abort;
        cp.abort_callback_data = rt.get();
        rt->params = cp;
        rt->contexts[0].value = llama_init_from_model(rt->model, cp);
        if (!rt->contexts[0].value) throw std::runtime_error("context init failed");
        rt->n_ctx = n_ctx;
        LOG("loaded model, ctx=%d threads=%d gpu_layers=%d devices=%zu", n_ctx, threads, gpu_layers, ggml_backend_dev_count());
        return reinterpret_cast<jlong>(rt.release());
    } catch (const std::exception & e) { throw_java(env, e); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_localphone_agent_llm_LlamaNative_infer(JNIEnv * env, jobject, jlong handle, jbyteArray prompt_bytes,
                                                jint max_tokens, jbyteArray grammar_bytes, jlong timeout_ms, jint context_slot) {
    auto * rt = reinterpret_cast<Runtime *>(handle);
    try {
        if (!rt) throw std::runtime_error("model not loaded");
        if (context_slot < 0 || context_slot >= (int) rt->contexts.size()) throw std::runtime_error("invalid context slot");
        const auto t0 = std::chrono::steady_clock::now();
        rt->deadline = t0 + std::chrono::milliseconds(timeout_ms);
        std::fill(std::begin(rt->timing), std::end(rt->timing), 0);
        auto & slot = rt->contexts[context_slot];
        if (!slot.value) {
            slot.value = llama_init_from_model(rt->model, rt->params);
            if (!slot.value) throw std::runtime_error("context init failed");
        }
        const auto * vocab = llama_model_get_vocab(rt->model);
        const std::string prompt = bytes(env, prompt_bytes);
        int n = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, false, true);
        if (n <= 0 || n + max_tokens > rt->n_ctx) throw std::runtime_error("prompt too long for context");
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), n, false, true) != n)
            throw std::runtime_error("tokenize failed");

        int common = 0;
        while (common < n - 1 && common < (int) slot.cached.size() && tokens[common] == slot.cached[common]) ++common;
        auto * mem = llama_get_memory(slot.value);
        if (common == 0 || !llama_memory_seq_rm(mem, -1, common, -1)) { llama_memory_clear(mem, true); common = 0; }
        slot.cached.assign(tokens.begin(), tokens.begin() + common);
        rt->timing[0] = n; rt->timing[1] = common;

        for (int off = common; off < n; off += 256) {
            check(rt);
            int len = std::min(256, n - off);
            if (llama_decode(slot.value, llama_batch_get_one(tokens.data() + off, len)) != 0) { check(rt); throw std::runtime_error("prompt decode failed"); }
            slot.cached.insert(slot.cached.end(), tokens.begin() + off, tokens.begin() + off + len);
        }
        const auto t1 = std::chrono::steady_clock::now();
        rt->timing[3] = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

        const std::string grammar_text = bytes(env, grammar_bytes);
        auto grammar = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
            grammar_text.empty() ? nullptr : llama_sampler_init_grammar(vocab, grammar_text.c_str(), "root"), llama_sampler_free);
        if (!grammar_text.empty() && !grammar) throw std::runtime_error("grammar init failed");
        const int n_vocab = llama_vocab_n_tokens(vocab);
        std::vector<llama_token_data> candidates(n_vocab);

        // Greedy decoding with a lazily applied grammar: check only the model's top token, and run the
        // (expensive, whole-vocabulary) grammar filter only when that token is not allowed.
        auto pick = [&]() -> llama_token {
            const float * logits = llama_get_logits_ith(slot.value, -1);
            llama_token best = 0;
            for (llama_token t = 1; t < n_vocab; ++t) if (logits[t] > logits[best]) best = t;
            if (!grammar) return best;
            llama_token_data one = { best, logits[best], 0.0f };
            llama_token_data_array single = { &one, 1, -1, false };
            llama_sampler_apply(grammar.get(), &single);
            if (single.data[0].logit != -INFINITY) return best;
            for (llama_token t = 0; t < n_vocab; ++t) candidates[t] = { t, logits[t], 0.0f };
            llama_token_data_array all = { candidates.data(), (size_t) n_vocab, -1, false };
            llama_sampler_apply(grammar.get(), &all);
            llama_token chosen = -1; float top = -INFINITY;
            for (size_t k = 0; k < all.size; ++k) if (all.data[k].logit > top) { top = all.data[k].logit; chosen = all.data[k].id; }
            if (chosen < 0) throw std::runtime_error("grammar allows no token");
            return chosen;
        };

        std::string out;
        bool finished = false;
        for (int i = 0; i < max_tokens; ++i) {
            check(rt);
            llama_token tok = pick();
            if (grammar) llama_sampler_accept(grammar.get(), tok);
            if (llama_vocab_is_eog(vocab, tok)) { finished = true; break; }
            char piece[256];
            int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
            if (len < 0) throw std::runtime_error("token piece too long");
            out.append(piece, len);
            rt->timing[2] = i + 1;
            if (complete_json(out)) { finished = true; break; }
            if (llama_decode(slot.value, llama_batch_get_one(&tok, 1)) != 0) { check(rt); throw std::runtime_error("decode failed"); }
            slot.cached.push_back(tok);
        }
        rt->timing[4] = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t1).count();
        if (!finished) throw std::runtime_error("output token limit reached");
        LOG("slot=%d prompt=%lld reused=%lld out=%lld prefill=%lldms gen=%lldms", context_slot, (long long) rt->timing[0], (long long) rt->timing[1],
            (long long) rt->timing[2], (long long) rt->timing[3], (long long) rt->timing[4]);
        auto result = env->NewByteArray(out.size());
        env->SetByteArrayRegion(result, 0, out.size(), reinterpret_cast<const jbyte *>(out.data()));
        return result;
    } catch (const std::exception & e) {
        if (rt && context_slot >= 0 && context_slot < (int) rt->contexts.size()) {
            auto & slot = rt->contexts[context_slot];
            slot.cached.clear();
            if (slot.value) llama_memory_clear(llama_get_memory(slot.value), true);
        }
        throw_java(env, e); return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_localphone_agent_llm_LlamaNative_metrics(JNIEnv * env, jobject, jlong handle) {
    auto r = env->NewLongArray(5);
    if (handle) env->SetLongArrayRegion(r, 0, 5, reinterpret_cast<Runtime *>(handle)->timing);
    return r;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_localphone_agent_llm_LlamaNative_reset(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Runtime *>(handle)->cancelled.store(false);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_localphone_agent_llm_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Runtime *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_localphone_agent_llm_LlamaNative_unload(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Runtime *>(handle);
}
