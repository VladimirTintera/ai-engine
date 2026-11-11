plugins {
    alias(libs.plugins.androidDynamicFeature)
}

/**
 * Runtime LiteRT-LM (`.litertlm`) jako součást z Google Play stahovaná až na požádání
 * (`PlayLiteRtRuntime(module = "litertFeature")`). Bez vlastního kódu, jen balí :ai-litert.
 * Jméno modulu je jméno součásti; přejmenování by instalaci tiše rozbilo.
 */
android {
    namespace = "eu.tintera.ai.playground.litert"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":androidApp"))
    implementation("eu.tintera.ai:ai-litert")
}
