package eu.tintera.ai.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import eu.tintera.ai.engine.LlmBackend
import eu.tintera.ai.engine.LlmContent
import eu.tintera.ai.engine.LlmConversation
import eu.tintera.ai.engine.LlmEngine
import eu.tintera.ai.engine.LlmEngineConfig
import eu.tintera.ai.engine.LlmEngineFactory
import eu.tintera.ai.engine.LlmSampling
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * LiteRT-LM za rozhraním [LlmEngineFactory]. Jen překlad typů; co s enginem dělat (výběr
 * backendu, uvolňování, stahování modelu), řeší aplikace (`LiteRtOnDeviceAi`).
 *
 * Vytváří se reflexí podle jména ([eu.tintera.ai.engine.LITERT_ENGINE_FACTORY]), proto
 * veřejný konstruktor bez parametrů.
 */
class LiteRtEngineFactory : LlmEngineFactory {

    override fun open(config: LlmEngineConfig): LlmEngine {
        val backend = config.backend.toLiteRt()
        val engine = Engine(
            EngineConfig(
                modelPath = config.modelPath,
                backend = backend,
                // Obraz počítá týž backend jako text (Gallery: GPU, když jde).
                visionBackend = if (config.vision) backend else null,
                // Zvuk jen na CPU — pro Gemmu 3n „must be CPU" i v Gallery.
                audioBackend = if (config.audio) Backend.CPU() else null,
                maxNumImages = if (config.vision) 1 else null,
                maxNumTokens = config.maxNumTokens,
                cacheDir = config.cacheDir,
            ),
        )
        engine.initialize()
        return LiteRtEngine(engine)
    }

    private fun LlmBackend.toLiteRt(): Backend = when (this) {
        LlmBackend.Gpu -> Backend.GPU()
        LlmBackend.Cpu -> Backend.CPU()
    }
}

private class LiteRtEngine(private val engine: Engine) : LlmEngine {

    override fun conversation(sampling: LlmSampling): LlmConversation = LiteRtConversation(
        engine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = sampling.topK, topP = sampling.topP, temperature = sampling.temperature),
            ),
        ),
    )

    override fun close() = engine.close()
}

private class LiteRtConversation(private val conversation: Conversation) : LlmConversation {

    override fun send(content: List<LlmContent>, maxOutputTokens: Int?): Flow<String> =
        conversation.sendMessageAsync(
            Contents.of(content.map { it.toLiteRt() }),
            // Model s přemýšlením by jinak psal úvahy do zvláštního kanálu a odpověď by přišla mnohem později.
            extraContext = mapOf("enable_thinking" to false),
            maxOutputToken = maxOutputTokens,
        ).map { it.toString() }

    override fun cancel() = conversation.cancelProcess()

    override fun close() = conversation.close()

    private fun LlmContent.toLiteRt(): Content = when (this) {
        is LlmContent.Text -> Content.Text(text)
        is LlmContent.Audio -> Content.AudioBytes(wav)
        is LlmContent.Image -> Content.ImageBytes(png)
    }
}
