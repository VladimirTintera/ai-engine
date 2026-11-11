import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.kotlinSerialization)
    alias(playground.plugins.compose.multiplatform)
}

/**
 * Společné UI a logika playgroundu: katalog modelů, správa stahování, konverzace. Platformní
 * vstupy jsou tu taky (desktop `main` v jvmMain, iOS `MainViewController` v iosMain); Android
 * aplikace je zvlášť v :androidApp, protože AGP 9 nedovolí plugin aplikace v modulu KMP.
 *
 * Knihovna se bere souřadnicemi `eu.tintera.ai:*` jako v aplikaci (includeBuild v settings).
 */
kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        // BackHandler ve společném kódu (ui-backhandler).
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }

    android {
        namespace = "eu.tintera.ai.playground.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            // Jméno, které importuje ios/AppleTextModel.swift z knihovny (`import ComposeApp`).
            baseName = "ComposeApp"
            isStatic = true
            // Swift volá IosModelDownloads, IosOnDeviceModel a OnDeviceTextModel z :ai-core.
            export("eu.tintera.ai:ai-core")
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api("eu.tintera.ai:ai-core")
            implementation(playground.compose.runtime)
            implementation(playground.compose.foundation)
            implementation(playground.compose.ui)
            implementation(playground.compose.ui.backhandler)
            implementation(playground.compose.material3)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(playground.kotlinx.serialization.json)
            implementation(playground.ktor.client.core)
        }
        androidMain.dependencies {
            implementation(playground.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.kotlinx.coroutines.get()}")
            implementation(playground.ktor.client.okhttp)
            // Runtime přibalené v aplikaci (BundledLiteRtRuntime je najde podle jména třídy).
            runtimeOnly("eu.tintera.ai:ai-litert")
            runtimeOnly("eu.tintera.ai:ai-llamacpp-desktop")
        }
        iosMain.dependencies {
            implementation(playground.ktor.client.darwin)
            // llama.cpp slinkovaný do frameworku; továrnu předává LinkedLiteRtRuntime.
            implementation("eu.tintera.ai:ai-llamacpp-ios")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(playground.compose.ui.test)
        }
    }
}

// `-Pplayground.e2e=true`: jvmTest pustí i DesktopEndToEndTest — v opravdovém UI stáhne Qwen3 0.6B
// (≈ 400 MB, do build/e2e-data, příště už ne) a vede krátkou konverzaci; snímky do build/e2e.
tasks.named<Test>("jvmTest") {
    providers.gradleProperty("playground.e2e").orNull?.let { systemProperty("playground.e2e", it) }
    systemProperty("playground.e2e.dir", layout.buildDirectory.dir("e2e").get().asFile.absolutePath)
    systemProperty("playground.e2e.data", layout.buildDirectory.dir("e2e-data").get().asFile.absolutePath)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

compose.desktop {
    application {
        mainClass = "eu.tintera.ai.playground.MainKt"
        // LiteRT-LM pro desktop (litertlm-jvm) je přeložený pro Javu 21 (DesktopLiteRtPlatform).
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AI Engine Playground"
            packageVersion = "1.0.0"
        }
    }
}
