plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * Rozhraní runtime jazykového modelu v procesu (`LlmEngineFactory`), bez jediné závislosti na
 * runtime samotném.
 *
 * Runtime (LiteRT-LM v :ai-litert) je na Androidu součást stahovaná z Google Play až na
 * požádání (v Plume :client:litertFeature), takže na něj základ aplikace nesmí vidět ani při překladu.
 * Tohle je to jediné, co o něm `shared` ví; implementaci si najde za běhu podle jména třídy.
 * Další runtime (třeba llama.cpp) = další modul s implementací týchž rozhraní.
 *
 * Na iOS ho implementuje :ai-llamacpp-ios (llama.cpp přes cinterop); aplikace továrnu předá
 * přímo (`LinkedLiteRtRuntime`), reflexe v Kotlin/Native není.
 */
kotlin {
    jvmToolchain(17)

    android {
        namespace = "eu.tintera.ai.engine"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
    }
}
