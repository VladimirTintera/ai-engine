plugins {
    alias(libs.plugins.androidDynamicFeature)
}

/**
 * Runtime llama.cpp (GGUF) jako součást z Google Play stahovaná až na požádání
 * (`PlayLiteRtRuntime(module = "llamacppFeature")`). Bez vlastního kódu, jen balí
 * :ai-llamacpp-android (nativní knihovna z NDK, arm64-v8a a x86_64).
 */
android {
    namespace = "eu.tintera.ai.playground.llamacpp"
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
    implementation("eu.tintera.ai:ai-llamacpp-android")
}
