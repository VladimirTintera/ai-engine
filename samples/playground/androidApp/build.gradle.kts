plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinCompose)
}

/**
 * Android aplikace playgroundu. Zapojení knihovny (README, „Wire the providers"): podtřída
 * `AiModelDownloadWorker`, notifikace stahování, `AndroidMeteredNetwork`, runtime jako součásti
 * z Google Play (`PlayLiteRtRuntime`, moduly :litertFeature a :llamacppFeature níž v
 * `dynamicFeatures`) a Gemini Nano přes `MlKitOnDeviceAi`.
 *
 * Mimo Google Play se součást nestáhne; pro vývoj se instaluje spolu s aplikací:
 * `./gradlew :androidApp:installWithRuntimes` (viz úloha dole).
 */
android {
    namespace = "eu.tintera.ai.playground"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "eu.tintera.ai.playground"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        // Stejné architektury jako runtime v součástech (LiteRT-LM a llama.cpp jen arm64 a x86_64);
        // bundle (AAB i univerzální APK) jinak odmítne moduly s různou sadou ABI.
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }

    dynamicFeatures += setOf(":litertFeature", ":llamacppFeature")

    buildFeatures {
        compose = true
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
    implementation(project(":composeApp"))
    implementation(playground.androidx.activity.compose)
    implementation(libs.play.feature.delivery)
}

/**
 * Debug APK aplikace i obou součástí naráz (`adb install-multiple`): součást je pak nainstalovaná
 * rovnou a `PlayLiteRtRuntime.installed()` vrátí `true`, jako by ji dodal Google Play.
 */
tasks.register<Exec>("installWithRuntimes") {
    group = "install"
    description = "Installs the debug app together with the runtime feature modules (adb install-multiple)."
    dependsOn("assembleDebug", ":litertFeature:assembleDebug", ":llamacppFeature:assembleDebug")
    val root = rootProject.layout.projectDirectory
    executable = androidComponents.sdkComponents.adb.get().asFile.absolutePath
    args(
        "install-multiple",
        "-r",
        layout.buildDirectory.file("outputs/apk/debug/androidApp-debug.apk").get().asFile.absolutePath,
        root.file("litertFeature/build/outputs/apk/debug/litertFeature-debug.apk").asFile.absolutePath,
        root.file("llamacppFeature/build/outputs/apk/debug/llamacppFeature-debug.apk").asFile.absolutePath,
    )
}
