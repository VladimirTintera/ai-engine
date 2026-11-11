// Jádro mostu k llama.cpp (C API v `ai_engine_llama.h`): načtení modelu, šablona promptu,
// generování po tokenech. Model počítá stabilní C API z `llama.h`; celou knihovnu `common`
// z příkladů nebereme — mění se s každým vydáním a táhne si vlastní závislosti (stahování, HTTP).
//
// Z `common` jde jen engine Jinja (`common/jinja`, s `json` a `unicode`), kterým se prompt
// skládá šablonou z GGUF. Vestavěné šablony `llama_chat_apply_template` neznají novější modely
// (Gemma 4 by dostala ChatML a odpovídala s `<|im_end|>`) a neumí vypnout přemýšlení
// (`enable_thinking` u Qwen3). Vestavěná šablona zůstává jako záloha, když Jinja selže.
//
// Volá ho JNI most (`ai_engine_llama_jni.cpp`, Android a desktop) i Kotlin/Native na iOS
// (:ai-llamacpp-ios, cinterop). Liší se jen logování.

#include "ai_engine_llama.h"

#include <algorithm>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "jinja/caps.h"
#include "jinja/lexer.h"
#include "jinja/parser.h"
#include "jinja/runtime.h"
#include "jinja/value.h"
#include "json.h"
#include "llama.h"

#define LOG_TAG "AiEngineLlama"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
// Desktop a iOS: na standardní chybový výstup, který ladicí běh (Xcode) ukáže v konzoli.
#include <cstdio>
#define LOGW(...) (std::fprintf(stderr, LOG_TAG " W: " __VA_ARGS__), std::fputc('\n', stderr))
#define LOGE(...) (std::fprintf(stderr, LOG_TAG " E: " __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

/** Šablona chatu z GGUF (Jinja), přeložená jednou při načtení modelu. */
struct ChatTemplate {
    /** Zdroj, jak ho vrátil lexer; pro hlášky chyb za běhu šablony. */
    std::string source;
    jinja::program program;
    /** Šablona bere obsah zprávy jen jako seznam částí (`[{type: text, text: …}]`), ne řetězec. */
    bool typedContent = false;
};

}  // namespace

struct ael_session {
    llama_model *model = nullptr;
    /** `nullptr`, když model šablonu nemá nebo ji engine Jinja nepřečetl. */
    std::unique_ptr<ChatTemplate> chatTemplate;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    int32_t generated = 0;
    int32_t maxTokens = 0;
    bool finished = true;
    /** Bajty posledního tokenu, které ještě netvoří celý znak UTF-8. */
    std::string pending;
    /** Kus vydaný posledním `ael_next`; ukazatel na něj platí do dalšího volání. */
    std::string out;
};

namespace {

constexpr int32_t kBatchSize = 512;

void logCallback(ggml_log_level level, const char *text, void *) {
#ifdef __ANDROID__
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, LOG_TAG, text);
    }
#else
    // Text od llama.cpp končí koncem řádku sám.
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) std::fputs(text, stderr);
#endif
}

/** Délka nejdelšího začátku [text], který končí celým znakem UTF-8. */
size_t completeUtf8Prefix(const std::string &text) {
    size_t i = text.size();
    // Nejvýš tři bajty zpátky může začínat neúplný znak.
    for (size_t back = 1; back <= 3 && back <= text.size(); back++) {
        const auto c = static_cast<unsigned char>(text[text.size() - back]);
        if ((c & 0xC0) == 0x80) continue; // pokračovací bajt
        size_t needed = 1;
        if ((c & 0xE0) == 0xC0) needed = 2;
        else if ((c & 0xF0) == 0xE0) needed = 3;
        else if ((c & 0xF8) == 0xF0) needed = 4;
        if (needed > back) i = text.size() - back;
        break;
    }
    return i;
}

std::string tokenText(const llama_vocab *vocab, llama_token token) {
    if (token == LLAMA_TOKEN_NULL) return {};
    const char *text = llama_vocab_get_text(vocab, token);
    return text != nullptr ? text : "";
}

/** Přeloží šablonu z GGUF; `nullptr`, když žádná není nebo jí engine Jinja nerozumí. */
std::unique_ptr<ChatTemplate> parseTemplate(const char *source) {
    if (source == nullptr || *source == '\0') return nullptr;
    try {
        auto tmpl = std::make_unique<ChatTemplate>();
        jinja::lexer lexer;
        const auto tokens = lexer.tokenize(source);
        tmpl->program = jinja::parse_from_tokens(tokens);
        tmpl->source = tokens.source;
        const jinja::caps caps = jinja::caps_get(tmpl->program);
        tmpl->typedContent = !caps.supports_string_content && caps.supports_typed_content;
        return tmpl;
    } catch (const std::exception &e) {
        LOGW("Chat template could not be parsed (%s), using the built-in one", e.what());
        return nullptr;
    }
}

/**
 * Prompt jako jedna zpráva uživatele ve šabloně modelu, s výzvou k odpovědi a vypnutým
 * přemýšlením (`enable_thinking=false`, jak slibuje `LlmConversation`). `false`, když šablona
 * za běhu selhala.
 */
bool renderTemplate(const ChatTemplate &tmpl, const llama_vocab *vocab, const std::string &prompt,
                    std::string &out) {
    try {
        const common_json content = tmpl.typedContent
            ? common_json::array({common_json{{"type", "text"}, {"text", prompt}}})
            : common_json(prompt);
        const common_json inputs{
            {"messages", common_json::array({common_json{{"role", "user"}, {"content", content}}})},
            {"add_generation_prompt", true},
            {"enable_thinking", false},
        };
        jinja::context ctx(tmpl.source);
        jinja::global_from_json(ctx, inputs, false);
        ctx.set_val("bos_token", jinja::mk_val<jinja::value_string>(tokenText(vocab, llama_vocab_bos(vocab))));
        ctx.set_val("eos_token", jinja::mk_val<jinja::value_string>(tokenText(vocab, llama_vocab_eos(vocab))));
        jinja::runtime runtime(ctx);
        out = jinja::runtime::gather_string_parts(runtime.execute(tmpl.program))->as_string().str();
    } catch (const std::exception &e) {
        LOGW("Chat template failed (%s), using the built-in one", e.what());
        return false;
    }
    // Šablona obvykle začíná `bos_token` a `llama_tokenize` ho přidá znovu — model by dostal dva.
    const std::string bos = tokenText(vocab, llama_vocab_bos(vocab));
    if (!bos.empty() && llama_vocab_get_add_bos(vocab) && out.compare(0, bos.size(), bos) == 0) {
        out.erase(0, bos.size());
    }
    return true;
}

/**
 * Prompt v šabloně modelu: šablonou z GGUF přes Jinja, a když to nejde, vestavěnou šablonou
 * llama.cpp; neznámou vestavěnou nahradí ChatML, které používá většina modelů.
 */
std::string applyTemplate(const ael_session *session, const std::string &prompt) {
    const llama_model *model = session->model;
    if (session->chatTemplate != nullptr) {
        std::string rendered;
        if (renderTemplate(*session->chatTemplate, llama_model_get_vocab(model), prompt, rendered)) return rendered;
    }
    llama_chat_message message{"user", prompt.c_str()};
    const char *tmpl = llama_model_chat_template(model, nullptr);
    std::vector<char> buffer(prompt.size() * 2 + 512);
    for (const char *candidate : {tmpl, "chatml"}) {
        if (candidate == nullptr) continue;
        int32_t length = llama_chat_apply_template(candidate, &message, 1, true, buffer.data(),
                                                   static_cast<int32_t>(buffer.size()));
        if (length > static_cast<int32_t>(buffer.size())) {
            buffer.resize(static_cast<size_t>(length));
            length = llama_chat_apply_template(candidate, &message, 1, true, buffer.data(), length);
        }
        if (length >= 0) return {buffer.data(), static_cast<size_t>(length)};
        LOGW("Chat template not supported, trying ChatML");
    }
    return prompt;
}

}  // namespace

extern "C" {

void ael_init(void) {
    llama_log_set(logCallback, nullptr);
    llama_backend_init();
}

bool ael_has_gpu(void) {
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        const auto type = ggml_backend_dev_type(ggml_backend_dev_get(i));
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) return true;
    }
    return false;
}

ael_session *ael_load(const char *path, int32_t nCtx, int32_t nThreads, bool gpu) {
    llama_model_params modelParams = llama_model_default_params();
    // Výchozí je „všechny vrstvy na GPU, je-li nějaké"; na CPU se GPU musí vypnout výslovně, jinak
    // by model na Macu šel na Metal i tehdy, když ho aplikace po selhání GPU chce na CPU.
    // Nestačí na to vrstvy: prázdný seznam zařízení (jen ukončovací nullptr) GPU modelu vůbec
    // nedá a `op_offload` níž mu nedovolí poslat tam velké dávky vah z paměti CPU.
    ggml_backend_dev_t noDevices[] = {nullptr};
    if (!gpu) modelParams.devices = noDevices;
    modelParams.n_gpu_layers = gpu ? -1 : 0;
    llama_model *model = llama_model_load_from_file(path, modelParams);
    if (model == nullptr) return nullptr;

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = static_cast<uint32_t>(nCtx);
    ctxParams.n_batch = kBatchSize;
    ctxParams.n_ubatch = kBatchSize;
    ctxParams.n_threads = nThreads;
    ctxParams.n_threads_batch = nThreads;
    ctxParams.offload_kqv = gpu;
    ctxParams.op_offload = gpu;
    llama_context *ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return nullptr;
    }
    auto *session = new ael_session();
    session->model = model;
    session->ctx = ctx;
    session->chatTemplate = parseTemplate(llama_model_chat_template(model, nullptr));
    return session;
}

int32_t ael_start(ael_session *session, const char *promptBytes, size_t length,
                  int32_t topK, float topP, float temperature, int32_t maxTokens) {
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    const std::string prompt = applyTemplate(session, std::string(promptBytes, length));

    const int32_t needed = -llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    std::vector<llama_token> tokens(static_cast<size_t>(std::max(needed, 0)));
    if (llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(),
                       static_cast<int32_t>(tokens.size()), true, true) < 0) {
        return -2;
    }
    const auto contextSize = static_cast<int32_t>(llama_n_ctx(session->ctx));
    if (static_cast<int32_t>(tokens.size()) + 1 >= contextSize) {
        LOGE("Prompt has %d tokens, context %d", static_cast<int>(tokens.size()), contextSize);
        return -1;
    }

    llama_memory_clear(llama_get_memory(session->ctx), true);
    for (size_t i = 0; i < tokens.size(); i += kBatchSize) {
        const auto count = static_cast<int32_t>(std::min(tokens.size() - i, static_cast<size_t>(kBatchSize)));
        if (llama_decode(session->ctx, llama_batch_get_one(tokens.data() + i, count)) != 0) return -2;
    }

    if (session->sampler != nullptr) llama_sampler_free(session->sampler);
    session->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(session->sampler, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    session->generated = 0;
    session->maxTokens = std::min(maxTokens, contextSize - static_cast<int32_t>(tokens.size()) - 1);
    session->finished = false;
    session->pending.clear();
    return 0;
}

int32_t ael_next(ael_session *session, const char **out) {
    if (session->finished) return -1;
    const llama_vocab *vocab = llama_model_get_vocab(session->model);

    if (session->generated >= session->maxTokens) {
        session->finished = true;
        return -1;
    }
    llama_token token = llama_sampler_sample(session->sampler, session->ctx, -1);
    if (llama_vocab_is_eog(vocab, token)) {
        session->finished = true;
        return -1;
    }
    session->generated++;

    char piece[256];
    const int32_t length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
    if (length > 0) session->pending.append(piece, static_cast<size_t>(length));

    if (llama_decode(session->ctx, llama_batch_get_one(&token, 1)) != 0) {
        LOGE("Decoding a generated token failed");
        session->finished = true;
    }

    const size_t complete = completeUtf8Prefix(session->pending);
    session->out.assign(session->pending, 0, complete);
    session->pending.erase(0, complete);
    *out = session->out.data();
    return static_cast<int32_t>(session->out.size());
}

void ael_free(ael_session *session) {
    if (session == nullptr) return;
    if (session->sampler != nullptr) llama_sampler_free(session->sampler);
    llama_free(session->ctx);
    llama_model_free(session->model);
    delete session;
}

}  // extern "C"
