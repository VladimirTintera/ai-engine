package eu.tintera.ai.core

import eu.tintera.plume.ai.engine.LlmEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Runtime LiteRT-LM pro testy [LiteRtOnDeviceAi]: [installed] přepíná test, instalace hlásí
 * [progress] a skončí, až test dokončí [finish] (nebo hned, když [finish] je hotový předem).
 * Enginy dává [engines]; bez něj test model nenačítá.
 */
class FakeLiteRtRuntime(
    var installed: Boolean = true,
    private val engines: LlmEngineFactory? = null,
) : LiteRtRuntime {

    var installs = 0
        private set

    val progress = listOf(ModelDownload(4, 10), ModelDownload(10, 10))

    /** `null` = instalace se povede, výjimka = selže s ní. */
    val finish = CompletableDeferred<Exception?>()

    override fun installed() = installed

    override fun install(): Flow<ModelDownload> = flow {
        installs++
        progress.forEach { emit(it) }
        finish.await()?.let { throw it }
        installed = true
    }

    override fun engines(): LlmEngineFactory = engines ?: error("This test never loads a model")
}
