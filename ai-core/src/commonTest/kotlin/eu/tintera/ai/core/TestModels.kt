package eu.tintera.ai.core

/** Model pro testy: knihovna žádný skutečný nezná, katalog dodává aplikace. */
internal fun testModel(
    id: String = "test-model",
    runtime: ModelRuntime = ModelRuntime.LiteRt,
    fileName: String = "$id.${if (runtime == ModelRuntime.LiteRt) "litertlm" else "gguf"}",
    multimodal: Boolean = false,
) = DownloadedModel(
    id = id,
    displayName = "Test $id",
    url = "https://example.invalid/$fileName",
    fileName = fileName,
    sizeBytes = 1_000,
    minMemoryGb = 8,
    runtime = runtime,
    multimodal = multimodal,
)
