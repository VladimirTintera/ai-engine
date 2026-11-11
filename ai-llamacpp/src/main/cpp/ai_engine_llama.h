// C API nad llama.cpp, které sdílí všechny platformy: JNI most pro Android a desktop
// (`ai_engine_llama_jni.cpp`) a cinterop Kotlin/Native na iOS (:ai-llamacpp-ios).
//
// Text jde dovnitř i ven jako bajty UTF-8 s délkou, ne jako řetězec ukončený nulou: odpověď se
// vydává po tokenech a znak UTF-8 se může rozdělit mezi dva (`ael_next` drží neúplný konec).

#ifndef AI_ENGINE_LLAMA_H
#define AI_ENGINE_LLAMA_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Načtený model s kontextem. Konverzace jde jedna po druhé (aplikace drží zámek enginu). */
typedef struct ael_session ael_session;

/** Jednou za proces, před vším ostatním: logování a backendy llama.cpp. */
void ael_init(void);

/** Má build GPU backend (Metal) a našel zařízení? Po `ael_init`, nic nenačítá. */
bool ael_has_gpu(void);

/**
 * Načte model z [path] (UTF-8, ukončené nulou). [gpu] = všechny vrstvy na GPU; jinak se GPU
 * nepoužije vůbec. `NULL`, když se model načíst nepodařilo.
 */
ael_session *ael_load(const char *path, int32_t n_ctx, int32_t n_threads, bool gpu);

/**
 * Začne odpověď na [prompt] ([length] bajtů UTF-8) v nové konverzaci: smaže paměť kontextu,
 * prompt zabalí do šablony modelu a spočítá. 0 = v pořádku, −1 dlouhý prompt, −2 dekódování selhalo.
 */
int32_t ael_start(ael_session *session, const char *prompt, size_t length,
                  int32_t top_k, float top_p, float temperature, int32_t max_tokens);

/**
 * Další kus odpovědi: do [out] dá ukazatel na bajty UTF-8 (platí do dalšího volání) a vrátí jejich
 * počet — 0, když token ještě nedotvořil znak. −1 na konci (konec věty modelu, strop tokenů,
 * plný kontext nebo chyba).
 */
int32_t ael_next(ael_session *session, const char **out);

/** Uvolní model i kontext. `NULL` nic nedělá. */
void ael_free(ael_session *session);

#ifdef __cplusplus
}
#endif

#endif  // AI_ENGINE_LLAMA_H
