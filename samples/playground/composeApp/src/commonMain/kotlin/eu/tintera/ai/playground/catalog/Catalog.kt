package eu.tintera.ai.playground.catalog

import eu.tintera.ai.core.DownloadedModel
import eu.tintera.ai.core.ModelRuntime
import eu.tintera.ai.core.huggingFaceUrl

/**
 * Rodina modelů na obrazovce (Gemma, Qwen…) s variantami, které se stahují každá zvlášť.
 *
 * Knihovna žádný model nezná; katalog je věc aplikace (README, „Define your models"). Tady je
 * u každé varianty i to, co knihovna nepotřebuje, ale člověk ano: licence a co znamená kvantizace.
 */
data class ModelFamily(
    val id: String,
    val name: String,
    val description: String,
    val models: List<CatalogModel>,
)

/** Jeden model (velikost) v rodině s variantami kvantizace a runtime. */
data class CatalogModel(
    val name: String,
    val license: License,
    val variants: List<CatalogVariant>,
)

/** Licence modelu; aplikace, která model nabízí, ji má ukázat (README, „What the app provides"). */
data class License(val name: String, val url: String, val nonCommercial: Boolean = false)

/**
 * Stahovatelná varianta. [label] je kvantizace nebo formát („Q4_K_M", „int8"), [model] je to,
 * co dostane knihovna.
 */
data class CatalogVariant(
    val label: String,
    val model: DownloadedModel,
    /** Odkud soubor je, pro obrazovku („unsloth/Qwen3-0.6B-GGUF"). */
    val source: String,
)

private val Apache2 = License("Apache 2.0", "https://www.apache.org/licenses/LICENSE-2.0")
private val GemmaTerms = License("Gemma Terms of Use", "https://ai.google.dev/gemma/terms")
private val CcByNc4 = License("CC BY-NC 4.0", "https://creativecommons.org/licenses/by-nc/4.0/", nonCommercial = true)

/**
 * Varianta z Hugging Face. [commit] pinuje soubor (stahuje se pořád tentýž, i když autor nahraje
 * novější), [sizeBytes] je skutečná velikost souboru z API Hugging Face. Všechny soubory níž jdou
 * stáhnout bez přihlášení (ověřeno 2026-11-11: `resolve` vrací 302, ne 401/403); modely za
 * souhlasem s licencí knihovna stáhnout neumí.
 *
 * [minMemoryGb] je odhad: soubor se mapuje do paměti celý a k němu KV keš a runtime, takže zhruba
 * 1,5× soubor plus rezerva pro systém, zaokrouhleno na běžné velikosti RAM telefonů.
 */
private fun hf(
    id: String,
    displayName: String,
    label: String,
    repo: String,
    commit: String,
    file: String,
    sizeBytes: Long,
    minMemoryGb: Int,
    contextTokens: Int = 8_192,
): CatalogVariant {
    val runtime = runtimeForFile(file) ?: error("Unknown model format: $file")
    return CatalogVariant(
        label = label,
        source = repo,
        model = DownloadedModel(
            id = id,
            displayName = "$displayName $label",
            url = huggingFaceUrl(repo, commit, file),
            fileName = file,
            sizeBytes = sizeBytes,
            minMemoryGb = minMemoryGb,
            runtime = runtime,
            contextTokens = contextTokens,
        ),
    )
}

/** Runtime podle přípony souboru; `null`, když ho knihovna neumí. */
fun runtimeForFile(file: String): ModelRuntime? = when {
    file.endsWith(".gguf", ignoreCase = true) -> ModelRuntime.LlamaCpp
    file.endsWith(".litertlm", ignoreCase = true) -> ModelRuntime.LiteRt
    else -> null
}

/** Odhad [DownloadedModel.minMemoryGb] pro vlastní model podle velikosti souboru (viz [hf]). */
fun suggestedMinMemoryGb(sizeBytes: Long): Int {
    val needed = sizeBytes / 1_073_741_824.0 * 1.5 + 1.25
    return listOf(2, 3, 4, 6, 8, 12, 16, 24, 32).firstOrNull { it >= needed } ?: 64
}

/**
 * Přednastavený katalog. Na iOS jdou jen GGUF (llama.cpp); `.litertlm` (LiteRT-LM) jen Android
 * a desktop — `status` je jinde vrátí jako `Unsupported` a obrazovka je tak označí.
 */
val Catalog: List<ModelFamily> = listOf(
    ModelFamily(
        id = "gemma",
        name = "Gemma",
        description = "Google's open models. Gemma 4 E2B/E4B are built for phones; Gemma 3 1B is the small text-only one.",
        models = listOf(
            CatalogModel(
                name = "Gemma 3 1B IT",
                license = GemmaTerms,
                variants = listOf(
                    hf("gemma3-1b-q4km", "Gemma 3 1B", "Q4_K_M", "unsloth/gemma-3-1b-it-GGUF",
                        "f0b45be0aac41bd6a100a4b5734cad5f67255bfb", "gemma-3-1b-it-Q4_K_M.gguf", 806_058_272, 3),
                    hf("gemma3-1b-q8", "Gemma 3 1B", "Q8_0", "unsloth/gemma-3-1b-it-GGUF",
                        "f0b45be0aac41bd6a100a4b5734cad5f67255bfb", "gemma-3-1b-it-Q8_0.gguf", 1_069_306_400, 4),
                ),
            ),
            CatalogModel(
                name = "Gemma 4 E2B IT",
                license = Apache2,
                variants = listOf(
                    hf("gemma4-e2b-q4km", "Gemma 4 E2B", "Q4_K_M", "unsloth/gemma-4-E2B-it-GGUF",
                        "0314792d7f1f7e229411f620751375812bb9faf2", "gemma-4-E2B-it-Q4_K_M.gguf", 3_106_738_272, 8),
                    hf("gemma4-e2b-litertlm", "Gemma 4 E2B", "LiteRT-LM", "litert-community/gemma-4-E2B-it-litert-lm",
                        "5b472bf479ec0e944b01c214b46fec082b3e8c01", "gemma-4-E2B-it.litertlm", 2_588_147_712, 6),
                ),
            ),
            CatalogModel(
                name = "Gemma 4 E4B IT",
                license = Apache2,
                variants = listOf(
                    hf("gemma4-e4b-q4km", "Gemma 4 E4B", "Q4_K_M", "unsloth/gemma-4-E4B-it-GGUF",
                        "bfc15c382204943c3a8fff0c750b94ae2364d7a3", "gemma-4-E4B-it-Q4_K_M.gguf", 4_977_171_584, 12),
                    hf("gemma4-e4b-litertlm", "Gemma 4 E4B", "LiteRT-LM", "litert-community/gemma-4-E4B-it-litert-lm",
                        "e75b1386809cda8e0fda1ef93a39f75b70d400b9", "gemma-4-E4B-it.litertlm", 3_659_530_240, 8),
                ),
            ),
        ),
    ),
    ModelFamily(
        id = "eurollm",
        name = "EuroLLM",
        description = "Multilingual models for all official EU languages (utter-project).",
        models = listOf(
            CatalogModel(
                name = "EuroLLM 1.7B Instruct",
                license = Apache2,
                variants = listOf(
                    // Kontext souboru je jen 4 096 tokenů (max_position_embeddings).
                    hf("eurollm-1.7b-q4km", "EuroLLM 1.7B", "Q4_K_M", "mradermacher/EuroLLM-1.7B-Instruct-GGUF",
                        "2951f08f66429c934c8b01a94347161362430808", "EuroLLM-1.7B-Instruct.Q4_K_M.gguf", 1_045_157_088, 4,
                        contextTokens = 4_096),
                    hf("eurollm-1.7b-q8", "EuroLLM 1.7B", "Q8_0", "mradermacher/EuroLLM-1.7B-Instruct-GGUF",
                        "2951f08f66429c934c8b01a94347161362430808", "EuroLLM-1.7B-Instruct.Q8_0.gguf", 1_763_775_712, 6,
                        contextTokens = 4_096),
                ),
            ),
            CatalogModel(
                name = "EuroLLM 9B Instruct (2512)",
                license = Apache2,
                variants = listOf(
                    hf("eurollm-9b-2512-q4km", "EuroLLM 9B", "Q4_K_M", "mradermacher/EuroLLM-9B-Instruct-2512-GGUF",
                        "5138603fccb9dc1f35c25cfd1a4981a78617e9ff", "EuroLLM-9B-Instruct-2512.Q4_K_M.gguf", 5_582_838_912, 12),
                ),
            ),
        ),
    ),
    ModelFamily(
        id = "aya",
        name = "Aya",
        description = "Cohere Labs' multilingual models. Non-commercial license (CC BY-NC 4.0).",
        models = listOf(
            CatalogModel(
                name = "Tiny Aya Global 3.35B",
                license = CcByNc4,
                variants = listOf(
                    hf("tiny-aya-global-q4km", "Tiny Aya Global", "Q4_K_M", "CohereLabs/tiny-aya-global-GGUF",
                        "a602ea7eeec3a4ad6f77a1b8cf6a53512824922b", "tiny-aya-global-q4_k_m.gguf", 2_143_977_056, 6),
                    hf("tiny-aya-global-q8", "Tiny Aya Global", "Q8_0", "CohereLabs/tiny-aya-global-GGUF",
                        "a602ea7eeec3a4ad6f77a1b8cf6a53512824922b", "tiny-aya-global-q8_0.gguf", 3_570_654_816, 8),
                ),
            ),
            CatalogModel(
                name = "Aya Expanse 8B",
                license = CcByNc4,
                variants = listOf(
                    hf("aya-expanse-8b-q4km", "Aya Expanse 8B", "Q4_K_M", "bartowski/aya-expanse-8b-GGUF",
                        "f9d62ed0c58e6f2ae17975df990b1b8a4013b596", "aya-expanse-8b-Q4_K_M.gguf", 5_056_982_720, 12),
                ),
            ),
        ),
    ),
    ModelFamily(
        id = "qwen",
        name = "Qwen",
        description = "Alibaba's Qwen3 and Qwen3.5. Qwen3 0.6B Q4 is the quickest way to try a model.",
        models = listOf(
            CatalogModel(
                name = "Qwen3 0.6B",
                license = Apache2,
                variants = listOf(
                    hf("qwen3-0.6b-q4km", "Qwen3 0.6B", "Q4_K_M", "unsloth/Qwen3-0.6B-GGUF",
                        "50968a4468ef4233ed78cd7c3de230dd1d61a56b", "Qwen3-0.6B-Q4_K_M.gguf", 396_705_472, 2),
                    hf("qwen3-0.6b-q8", "Qwen3 0.6B", "Q8_0", "Qwen/Qwen3-0.6B-GGUF",
                        "23749fefcc72300e3a2ad315e1317431b06b590a", "Qwen3-0.6B-Q8_0.gguf", 639_446_688, 3),
                    // Statický model s KV keší 4 096 (LiteRT-LM to hlásí při načtení).
                    hf("qwen3-0.6b-litertlm", "Qwen3 0.6B", "LiteRT-LM", "litert-community/Qwen3-0.6B",
                        "8768ea43e6d801ee0d353e5036007e6790a2ba05", "Qwen3-0.6B.litertlm", 613_722_264, 3,
                        contextTokens = 4_096),
                ),
            ),
            CatalogModel(
                name = "Qwen3 1.7B",
                license = Apache2,
                variants = listOf(
                    hf("qwen3-1.7b-q4km", "Qwen3 1.7B", "Q4_K_M", "unsloth/Qwen3-1.7B-GGUF",
                        "d7f544eead698dbd1f15126ef60b45a1e1933222", "Qwen3-1.7B-Q4_K_M.gguf", 1_107_409_472, 4),
                    hf("qwen3-1.7b-q8", "Qwen3 1.7B", "Q8_0", "Qwen/Qwen3-1.7B-GGUF",
                        "90862c4b9d2787eaed51d12237eafdfe7c5f6077", "Qwen3-1.7B-Q8_0.gguf", 1_834_426_016, 6),
                ),
            ),
            CatalogModel(
                name = "Qwen3 4B",
                license = Apache2,
                variants = listOf(
                    hf("qwen3-4b-q4km", "Qwen3 4B", "Q4_K_M", "Qwen/Qwen3-4B-GGUF",
                        "bc640142c66e1fdd12af0bd68f40445458f3869b", "Qwen3-4B-Q4_K_M.gguf", 2_497_280_256, 6),
                ),
            ),
            CatalogModel(
                name = "Qwen3 8B",
                license = Apache2,
                variants = listOf(
                    hf("qwen3-8b-q4km", "Qwen3 8B", "Q4_K_M", "Qwen/Qwen3-8B-GGUF",
                        "7c41481f57cb95916b40956ab2f0b139b296d974", "Qwen3-8B-Q4_K_M.gguf", 5_027_783_488, 12),
                ),
            ),
            CatalogModel(
                name = "Qwen3.5 0.8B",
                license = Apache2,
                variants = listOf(
                    hf("qwen3.5-0.8b-q4km", "Qwen3.5 0.8B", "Q4_K_M", "unsloth/Qwen3.5-0.8B-GGUF",
                        "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0", "Qwen3.5-0.8B-Q4_K_M.gguf", 532_517_120, 3),
                    hf("qwen3.5-0.8b-litertlm", "Qwen3.5 0.8B", "LiteRT-LM int8", "litert-community/Qwen3.5-0.8B",
                        "658b849e745b6ba2a0e4b52a3a23e2a3892221e7", "Qwen3.5-0.8B_int8.litertlm", 964_657_152, 4),
                ),
            ),
            CatalogModel(
                name = "Qwen3.5 2B",
                license = Apache2,
                variants = listOf(
                    hf("qwen3.5-2b-q4km", "Qwen3.5 2B", "Q4_K_M", "unsloth/Qwen3.5-2B-GGUF",
                        "f6d5376be1edb4d416d56da11e5397a961aca8ae", "Qwen3.5-2B-Q4_K_M.gguf", 1_280_835_840, 4),
                ),
            ),
        ),
    ),
)
