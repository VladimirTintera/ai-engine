package eu.tintera.ai.core

import kotlin.math.ceil

/**
 * Horní odhad počtu tokenů [text] pro model, který je spočítat neumí (LiteRT-LM v Kotlinu:
 * `Conversation.getTokenCount` zná tokeny až rozběhnuté konverzace, předem tokenizovat nejde).
 *
 * **Odhad je z bajtů UTF-8, ne ze znaků**, a schválně přestřelený. Změřeno na českém chatu
 * s odkazy a čísly na modelech přes LiteRT-LM: kolem 2,2 bajtu (2 znaků) na token. Dřívější
 * předpoklad tří znaků na token poslal 16 330 znaků = 7 547 tokenů do keše 8 192, a na odpověď
 * zbylo místo pro pár vět.
 * Bajty zvládnou i jiná písma lip než znaky: azbuka má dva bajty na znak, čínština tři.
 * Přestřelení stojí jen to, že se dlouhý chat rozdělí na části o něco dřív.
 */
fun estimatedTokenCount(text: String): Int = ceil(text.encodeToByteArray().size / ESTIMATED_BYTES_PER_TOKEN).toInt()

/** S rezervou pod naměřenými kolem 2,2 bajtu na token (viz [estimatedTokenCount]). */
private const val ESTIMATED_BYTES_PER_TOKEN = 1.8
