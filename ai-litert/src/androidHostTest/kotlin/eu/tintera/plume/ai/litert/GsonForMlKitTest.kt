package eu.tintera.plume.ai.litert

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Gson v aplikaci nesmí mít třídu, kterou si pod původním jménem hledá Gson přibalený v ML Kitu
 * (`genai-prompt` 1.0.0-beta4 i beta5) — jinak strukturovaný výstup Gemini Nano spadne na
 * `ClassCastException` v inicializaci třídy. Proč a odkud se Gson bere: komentář u verze Gsonu
 * v `libs.versions.toml`. Test padne, když bump LiteRT-LM (nebo čehokoli jiného) připnutí obejde.
 *
 * Leží tady, ne v `shared`: Gson do aplikace přináší LiteRT-LM, tedy součást z Google Play, a ta
 * se po instalaci načte do téhož classloaderu jako ML Kit v základu aplikace.
 */
class GsonForMlKitTest {

    @Test
    fun appGsonHasNoJavaTimeAdapters() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.google.gson.internal.bind.JavaTimeTypeAdapters")
        }
    }
}
