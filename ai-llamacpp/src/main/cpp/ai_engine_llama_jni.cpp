// JNI most mezi `LlamaNative` (Kotlin, :ai-llamacpp) a jádrem nad llama.cpp (`ai_engine_llama.h`).
// Android a desktop; iOS volá jádro přímo přes cinterop.
//
// Text jde přes hranici jako BAJTY UTF-8, ne jako `jstring`: JNI `NewStringUTF`
// a `GetStringUTFChars` pracují s „modified UTF-8", ve kterém emoji (čtyřbajtové znaky)
// vypadají jinak, a CheckJNI na ně aplikaci shodí. Konverzace bývají emoji plné. Totéž platí
// pro cestu k modelu: na Windows ji ggml otevírá jako UTF-8 (`ggml_fopen`) a jméno uživatele
// v `%LOCALAPPDATA%` bývá s diakritikou.

#include <jni.h>

#include <string>

#include "ai_engine_llama.h"

namespace {

std::string bytes(JNIEnv *env, jbyteArray array) {
    const jsize length = env->GetArrayLength(array);
    std::string out(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_eu_tintera_ai_llamacpp_LlamaNative_init(JNIEnv *, jclass) {
    ael_init();
}

/** Má build GPU backend (Metal na Macu) a našel zařízení? Volá se po `init`, nic nenačítá. */
JNIEXPORT jboolean JNICALL
Java_eu_tintera_ai_llamacpp_LlamaNative_hasGpu(JNIEnv *, jclass) {
    return ael_has_gpu() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_eu_tintera_ai_llamacpp_LlamaNative_load(JNIEnv *env, jclass, jbyteArray jpath, jint nCtx, jint nThreads, jboolean gpu) {
    const std::string path = bytes(env, jpath);
    return reinterpret_cast<jlong>(ael_load(path.c_str(), nCtx, nThreads, gpu == JNI_TRUE));
}

/** Viz `ael_start`: 0 = v pořádku, záporné číslo = chyba. */
JNIEXPORT jint JNICALL
Java_eu_tintera_ai_llamacpp_LlamaNative_start(JNIEnv *env, jclass, jlong handle, jbyteArray jprompt,
                                                     jint topK, jfloat topP, jfloat temperature, jint maxTokens) {
    const std::string prompt = bytes(env, jprompt);
    return ael_start(reinterpret_cast<ael_session *>(handle), prompt.data(), prompt.size(),
                     topK, topP, temperature, maxTokens);
}

/**
 * Další kus odpovědi jako bajty UTF-8; prázdné pole, když token ještě nedotvořil znak, `null`
 * na konci (konec věty modelu, strop tokenů, plný kontext nebo chyba).
 */
JNIEXPORT jbyteArray JNICALL
Java_eu_tintera_ai_llamacpp_LlamaNative_next(JNIEnv *env, jclass, jlong handle) {
    const char *out = nullptr;
    const int32_t length = ael_next(reinterpret_cast<ael_session *>(handle), &out);
    if (length < 0) return nullptr;
    jbyteArray array = env->NewByteArray(length);
    if (length > 0) env->SetByteArrayRegion(array, 0, length, reinterpret_cast<const jbyte *>(out));
    return array;
}

JNIEXPORT void JNICALL
Java_eu_tintera_ai_llamacpp_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    ael_free(reinterpret_cast<ael_session *>(handle));
}

}  // extern "C"
