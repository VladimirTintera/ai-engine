plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * Gemma a Qwen přes LiteRT-LM: implementace [eu.tintera.plume.ai.engine.LlmEngineFactory].
 * Jediné místo v projektu, kde smí být `com.google.ai.edge.litertlm`.
 *
 * Na Androidu ho do aplikace nedává `shared`, ale součást z Google Play stahovaná až na požádání
 * (:client:litertFeature): nativní knihovna má 23 MB a model v zařízení si zapne málokdo.
 * Desktop ho má přímo (:client:desktopApp). Obě knihovny (`litertlm-android`, `litertlm-jvm`)
 * mají shodné API, kód je proto jen v `commonMain`; knihovnu si bere každá platforma sama —
 * dvě kopie týchž tříd na jedné classpath by se srazily.
 */
kotlin {
    jvmToolchain(17)

    android {
        namespace = "eu.tintera.plume.ai.litert"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(projects.client.aiEngine)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.litertlm.android)
            // Gson z LiteRT-LM připnutý pod 2.14 — novější shazuje strukturovaný výstup ML Kitu
            // (viz komentář u verze v libs.versions.toml). Součást z Play se po instalaci načte
            // do TÉHOŽ classloaderu jako ML Kit v základu aplikace, takže připnutí musí být tady.
            implementation(libs.gson)
        }
        jvmMain.dependencies {
            // Jar nese nativní knihovny všech systémů; do balíčku jde jen ta jeho (plugin
            // `plume.litertlm-desktop` v desktopApp).
            implementation(libs.litertlm.jvm)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
