package eu.tintera.ai.playground.chat

/** Tah konverzace: zpráva uživatele a odpověď modelu (může být prázdná, když se nepovedla). */
data class Turn(val user: String, val assistant: String)

/** Prompt pro model a kolik nejstarších tahů se do limitu nevešlo. */
data class FittedPrompt(val prompt: String, val droppedTurns: Int, val tokens: Int)

/**
 * Konverzace v jednom promptu. `OnDeviceAi.generate` bere jediný prompt a runtime z něj udělá
 * jednu zprávu uživatele (šablonu chatu modelu použije engine), takže historii skládá aplikace:
 * dosavadní tahy jako přepis a pod ním nová zpráva. Bez historie jde zpráva modelu tak, jak je.
 *
 * Limit hlídá [fit]: vstup se musí vejít do `ModelInfo.tokenLimit` (místo na odpověď si knihovna
 * nechává sama), a když ne, vypadávají nejstarší tahy.
 */
object ConversationPrompt {

    fun build(history: List<Turn>, message: String): String {
        if (history.isEmpty()) return message
        return buildString {
            append("This is a conversation between a user and you, the assistant. ")
            append("Reply to the user's last message; do not repeat the conversation or prefix your reply.\n\n")
            for (turn in history) {
                append("User: ").append(turn.user.trim()).append("\n\n")
                append("Assistant: ").append(turn.assistant.trim()).append("\n\n")
            }
            append("User: ").append(message.trim())
        }
    }

    /**
     * Nejdelší prompt z [history] a [message], který se vejde do [limit] tokenů podle [count].
     * `null`, když se nevejde ani samotná zpráva.
     */
    suspend fun fit(history: List<Turn>, message: String, limit: Int, count: suspend (String) -> Int): FittedPrompt? {
        for (drop in 0..history.size) {
            val prompt = build(history.drop(drop), message)
            val tokens = count(prompt)
            if (tokens <= limit) return FittedPrompt(prompt, drop, tokens)
        }
        return null
    }
}
