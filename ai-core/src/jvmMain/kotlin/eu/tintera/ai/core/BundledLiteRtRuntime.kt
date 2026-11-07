package eu.tintera.ai.core

import eu.tintera.ai.engine.LlmEngineFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Desktop: runtime je v balíčku aplikace (v Plume :client:desktopApp závisí na :ai-litert
 * a :ai-llamacpp-desktop), nic se nedoinstalovává. Chybí jen v buildu, který modul
 * nepřibalil — pak se model nenabídne stejně jako bez nativní knihovny
 * (`DesktopLiteRtPlatform.canRun`). Jedna instance na runtime ([factoryClass]).
 */
class BundledLiteRtRuntime(
    /** Implementace [LlmEngineFactory], viz konstanty v :ai-engine. */
    private val factoryClass: String,
) : LiteRtRuntime {

    private val engines: LlmEngineFactory? by lazy {
        runCatching { loadEngineFactory(BundledLiteRtRuntime::class.java.classLoader, factoryClass) }.getOrNull()
    }

    override fun installed(): Boolean = present

    override fun install(): Flow<ModelDownload> = flow {
        if (!present) throw RuntimeInstallException()
    }

    override fun engines(): LlmEngineFactory = engines ?: throw IllegalStateException("$factoryClass is missing")

    private val present: Boolean by lazy {
        runCatching { Class.forName(factoryClass, false, BundledLiteRtRuntime::class.java.classLoader) }.isSuccess
    }
}
