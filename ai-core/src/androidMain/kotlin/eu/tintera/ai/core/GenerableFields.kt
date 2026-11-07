package eu.tintera.ai.core

import com.google.mlkit.genai.schema.guided.GenerableDetail

/*
 * Pomůcky pro popisy tvaru strukturovaného výstupu Gemini Nano (`OnDeviceAi.generateStructured`).
 *
 * ML Kit k typu odpovědi hledá `GenerableProvider` přes `ServiceLoader` (mapa `targetClass` →
 * provider, ověřeno v bajtkódu `genai-prompt` 1.0.0-beta4 i beta5) a z jeho popisu skládá schéma,
 * které dá modelu. Normálně by providery vygeneroval KSP procesor `genai-schema-compiler`
 * z anotací `@Generable` / `@Guide` — jenže typy odpovědí v KMP leží v `commonMain` (čte je
 * ViewModel) a anotace ML Kitu tam být nemůžou. Providery proto píše aplikace ručně, po vzoru
 * toho, co procesor generuje; typy odpovědí jsou její, knihovna dává jen tyhle pomůcky.
 *
 * Nový typ v aplikaci = třída `GenerableProvider`, řádek v `src/androidMain/resources/META-INF/
 * services/com.google.mlkit.genai.schema.guided.GenerableProvider` a keep pravidlo na jeho pole
 * (Gson ML Kitu plní objekt podle JMEN polí). Bez řádku ve services ML Kit typ nezná a generování
 * selže; bez keep pravidla R8 pole přejmenuje a objekt zůstane prázdný.
 */

/** Textové pole schématu; [values] = povolené hodnoty, které model nesmí opustit. */
fun generableStringField(name: String, description: String, values: List<String>? = null) =
    GenerableDetail.GuideDetail(
        name = name,
        type = String::class,
        nullable = false,
        isList = false,
        listItemType = null,
        description = description,
        maxItems = null,
        minItems = null,
        maximum = null,
        minimum = null,
        enumValues = values?.toTypedArray(),
    )
