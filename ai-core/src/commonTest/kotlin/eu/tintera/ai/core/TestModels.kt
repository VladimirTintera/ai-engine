package eu.tintera.ai.core

/** Obsah souboru [testModel]: velikost i hash v katalogu sedí jen s ním. */
internal val TEST_MODEL_BYTES = ByteArray(1_000) { (it % 251).toByte() }

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
    sizeBytes = TEST_MODEL_BYTES.size.toLong(),
    sha256 = TEST_MODEL_BYTES.sha256Hex(),
    minMemoryGb = 8,
    runtime = runtime,
    multimodal = multimodal,
)

/** SHA-256 obsahu, jak ho do katalogu napíše aplikace. */
internal fun ByteArray.sha256Hex(): String = sha256Digest().also { it.update(this, size) }.hex()
