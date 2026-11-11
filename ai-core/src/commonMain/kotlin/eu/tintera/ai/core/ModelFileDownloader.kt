package eu.tintera.ai.core

import kotlinx.io.IOException

/**
 * Stažení souboru modelu, které si platforma dělá po svém místo společného stahování do `.part`
 * přes [httpDownload]: na iOS sezení `NSURLSession` na pozadí, které stahuje dál, i když systém
 * aplikaci uspí nebo ukončí (`BackgroundModelDownloads`). Nahrazuje jen přenos souboru
 * (`LiteRtOnDeviceAi.fetchFile`); stav pro obrazovku, navázání po výpadku sítě, kontrola místa
 * a to, co stahování drží naživu ([LiteRtPlatform.startDownload]), zůstávají společné.
 *
 * Interní, ne součást [LiteRtPlatform]: dává ho jen platforma z knihovny ([modelFileDownloader]),
 * na Androidu a desktopu žádný není a stahuje se jako dřív.
 */
internal interface ModelFileDownloader {

    /**
     * Stáhne [model] a hotový ho položí do [LiteRtPlatform.modelsDir] pod [DownloadedModel.fileName];
     * vrátí se, až tam leží. Stahování, které už běží (i z minulého běhu procesu), nespouští znovu,
     * jen se k němu připojí. Průběh hlásí [onProgress].
     *
     * Výpadek spojení = [IOException]: společný kód počká na síť a zavolá znovu, a stahování naváže.
     * Odpověď serveru, kterou opakování nespraví = [HttpStatusException]. Zrušení korutiny stahování
     * zastaví a stažená část zůstane ([partialBytes]).
     */
    suspend fun download(model: DownloadedModel, onProgress: (ModelDownload) -> Unit)

    /** Kolik bajtů [model] už má platforma stažených mimo `.part`; `null`, když nic. Levný dotaz. */
    fun partialBytes(model: DownloadedModel): Long?

    /** Zahodí rozpracované stahování [model] i s jeho staženou částí (smazání modelu). */
    suspend fun discard(model: DownloadedModel)
}

/** Vlastní stahování souboru pro [platform]; `null` = společné stahování přes [httpDownload]. */
internal expect fun modelFileDownloader(platform: LiteRtPlatform): ModelFileDownloader?

/** Server odpověděl jinak než daty. Čekáním na síť se to nespraví, takže se neopakuje. */
internal class HttpStatusException(val code: Int, what: String) : IOException("HTTP $code for $what")
