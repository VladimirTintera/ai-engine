plugins {
    alias(libs.plugins.kotlinJvm)
}

/**
 * Modely GGUF (EuroLLM a další, které LiteRT-LM neumí) přes llama.cpp: implementace
 * [eu.tintera.plume.ai.engine.LlmEngineFactory]. Jediné místo s llama.cpp — submodul
 * (`llama.cpp/`), vlastní JNI most i CMakeLists (`src/main/cpp`) jsou tady.
 *
 * - **Čistě JVM modul, společný Androidu i desktopu.** Kód nesahá na nic z Androidu; nativní
 *   knihovnu pro každou platformu staví modul, který ji balí: :client:ai-llamacpp-android (NDK,
 *   do součásti z Google Play :client:llamacppFeature) a :client:ai-llamacpp-desktop (CMake
 *   systému, do balíčku desktopu). Proto se tady nic nativního nepřekládá — knihovna pro Mac
 *   by jinak skončila i v součásti pro Android.
 * - **Jak se knihovna načte**, rozhoduje to, jestli leží v jaru (`LlamaNative.loadLibrary`).
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.client.aiEngine)
    implementation(libs.kotlinx.coroutines.core)
}
