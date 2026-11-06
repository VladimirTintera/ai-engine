plugins {
    alias(libs.plugins.androidLibrary)
}

/**
 * Modely GGUF (EuroLLM a další, které LiteRT-LM neumí) přes llama.cpp: implementace
 * [eu.tintera.plume.ai.engine.LlmEngineFactory]. Jediné místo s llama.cpp.
 *
 * - **llama.cpp je git submodul** (`llama.cpp/`, připnutý na vydání `bNNNNN`) a překládá se
 *   z NDK přes CMake (`src/main/cpp`). Žádný hotový wrapper z Maven: ty dělají jednotlivci
 *   a projekt by s nimi měl stejné riziko jako s wrapperem TDLib, jen bez hranice kolem.
 *   Bump = `git -C client/ai-llamacpp/llama.cpp fetch --depth 1 origin tag bNNNNN` a checkout.
 * - **Jen Android** (`com.android.library`, ne KMP — KMP plugin pro Android nativní build
 *   z CMake neumí). Desktop zatím nemá; model se tam nenabízí (`DesktopLiteRtPlatform.canRun`).
 * - **Do aplikace ho dává součást z Google Play** (:client:llamacppFeature), ne `shared`.
 * - **Jen arm64-v8a a x86_64** (x86_64 kvůli emulátoru). Na 32bitových telefonech se model
 *   nenabízí a součást má pro jejich ABI prázdný soubor, viz client/llamacppFeature.
 * - **NDK r28+ kvůli 16KB stránkám**, které Play od Androidu 15 vyžaduje u nativních knihoven.
 */
android {
    namespace = "eu.tintera.plume.ai.llamacpp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin/Android je v AGP 9 vestavěný — plugin org.jetbrains.kotlin.android se neaplikuje.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.client.aiEngine)
    implementation(libs.kotlinx.coroutines.core)
}
