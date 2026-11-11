package eu.tintera.ai.playground.chat

import eu.tintera.ai.playground.catalog.HuggingFace
import eu.tintera.ai.playground.catalog.suggestedMinMemoryGb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ConversationPromptTest {

    private val history = listOf(Turn("first question", "first answer"), Turn("second question", "second answer"))

    @Test
    fun firstMessageGoesAsIs() {
        assertEquals("Hello", ConversationPrompt.build(emptyList(), "Hello"))
    }

    @Test
    fun historyIsATranscriptEndingWithTheNewMessage() {
        val prompt = ConversationPrompt.build(history, "third question")
        assertTrue(prompt.indexOf("first question") < prompt.indexOf("second answer"))
        assertTrue(prompt.endsWith("User: third question"))
    }

    @Test
    fun fitDropsOldestTurnsFirst() = runTest {
        val full = ConversationPrompt.build(history, "third").length
        val withoutFirst = ConversationPrompt.build(history.drop(1), "third").length
        // Limit mezi oběma délkami: vejde se jen bez prvního tahu.
        val fitted = ConversationPrompt.fit(history, "third", limit = (full + withoutFirst) / 2) { it.length }
        assertEquals(1, fitted?.droppedTurns)
        assertTrue(fitted!!.prompt.contains("second question"))
        assertTrue(!fitted.prompt.contains("first question"))
    }

    @Test
    fun fitFailsWhenTheMessageAloneIsTooLong() = runTest {
        assertNull(ConversationPrompt.fit(history, "x".repeat(100), limit = 50) { it.length })
    }

    @Test
    fun parsesRepositoryAndBrowserLinks() {
        assertEquals(Triple("Qwen/Qwen3-0.6B-GGUF", null, null), HuggingFace.parse(" Qwen/Qwen3-0.6B-GGUF "))
        assertEquals(
            Triple("unsloth/Qwen3-0.6B-GGUF", "main", "Qwen3-0.6B-Q4_K_M.gguf"),
            HuggingFace.parse("https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/blob/main/Qwen3-0.6B-Q4_K_M.gguf"),
        )
        assertEquals(Triple("a/b", "abc123", "dir/m.gguf"), HuggingFace.parse("https://huggingface.co/a/b/resolve/abc123/dir/m.gguf?download=true"))
        assertNull(HuggingFace.parse("justaname"))
    }

    @Test
    fun suggestedMemoryGrowsWithTheFile() {
        assertEquals(2, suggestedMinMemoryGb(396_705_472))
        assertEquals(12, suggestedMinMemoryGb(5_027_783_488))
    }
}
