package eu.tintera.ai.core

import kotlin.math.ceil

/**
 * Horní odhad počtu tokenů [text] pro model, který je spočítat neumí (LiteRT-LM v Kotlinu:
 * `Conversation.getTokenCount` zná tokeny až rozběhnuté konverzace, předem tokenizovat nejde).
 *
 * **Odhad je z bajtů UTF-8, ne ze znaků**, a schválně přestřelený. Změřeno na českém chatu
 * s odkazy a čísly (`summaryPrompt`): Gemma 4 E2B 2,2 znaku (2,3 bajtu) na token, Qwen3 2,0
 * znaku (2,2 bajtu). Dřívější předpoklad tří znaků na token (`transcriptCharsFor`) tak u Gemmy
 * poslal 16 330 znaků = 7 547 tokenů do keše 8 192, a na odpověď zbylo místo pro pár vět.
 * Bajty zvládnou i jiná písma lip než znaky: azbuka má dva bajty na znak, čínština tři.
 * Přestřelení stojí jen to, že se dlouhý chat rozdělí na části o něco dřív.
 */
fun estimatedTokenCount(text: String): Int = ceil(text.encodeToByteArray().size / ESTIMATED_BYTES_PER_TOKEN).toInt()

/** S rezervou pod naměřenými 2,2–2,3 bajtu na token (viz [estimatedTokenCount]). */
private const val ESTIMATED_BYTES_PER_TOKEN = 1.8
