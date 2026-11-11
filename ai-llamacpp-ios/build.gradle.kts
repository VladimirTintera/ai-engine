import java.io.IOException
import java.util.Properties
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

/**
 * llama.cpp (modely GGUF) na iOS: [eu.tintera.ai.engine.LlmEngineFactory] v Kotlin/Native nad
 * týmž jádrem jako na Androidu a desktopu (`ai-llamacpp/src/main/cpp/ai_engine_llama.h`), jen bez
 * JNI — volá ho cinterop. Protějšek :ai-llamacpp + :ai-llamacpp-android/-desktop.
 *
 * - **Statická knihovna pro zařízení i simulátor** (arm64), z téhož CMakeLists s
 *   `CMAKE_SYSTEM_NAME=iOS`. Úloha `buildLlamaCpp<Target>` slije knihovny jádra, llama.cpp a ggml
 *   do jedné (`libtool`) a cinterop ji vloží do klibu — framework aplikace ji slinkuje sám.
 * - **GPU přes Metal jen na zařízení** (shadery vložené v knihovně); simulátor počítá na CPU.
 * - **CMake z Android SDK** (`cmake;<verze z libs.versions.toml>`, s Ninjou), jinak `cmake`
 *   z PATH nebo z Homebrew. Překladač z Xcode. Na rozdíl od desktopu se bez nich nepřeskakuje:
 *   bez knihovny by modul nešel slinkovat, a kdo iOS nestaví, tyhle úlohy nepouští.
 * - **Nejnižší iOS jako Kotlin/Native** ([IOS_DEPLOYMENT_TARGET]), ať linker frameworku nehlásí
 *   knihovnu pro novější systém.
 */
val llamaCppDir: Directory = rootProject.layout.projectDirectory.dir("ai-llamacpp")

/** Jako `minVersion.ios` v konan.properties Kotlin/Native 2.4. */
val IOS_DEPLOYMENT_TARGET = "15.0"

/** Android SDK z `local.properties` nebo z prostředí, kvůli CMake. */
val androidSdk: Provider<String> = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty("sdk.dir") ?: "" }
    .filter { it.isNotBlank() }
    .orElse(providers.environmentVariable("ANDROID_HOME"))
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse("")

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val build = registerLlamaCppBuild(target)
        target.compilations.getByName("main").cinterops.create("llama") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/llama.def"))
            includeDirs(llamaCppDir.dir("src/main/cpp"))
            extraOpts("-libraryPath", llamaCppLibraryDir(target).get().asFile.absolutePath)
        }
        tasks.named(target.compilations.getByName("main").cinterops.getByName("llama").interopProcessingTaskName) {
            dependsOn(build)
        }
    }

    sourceSets {
        iosMain.dependencies {
            implementation(project(":ai-engine"))
            implementation(libs.kotlinx.coroutines.core)
        }
        iosTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// `-PllamaCppTestModel=<soubor.gguf>`: test v simulátoru nechá model i odpovědět (LlamaCppIosTest).
// Simulátor čte soubory Macu; proměnnou mu předá `simctl` (předpona SIMCTL_CHILD_).
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("llamaCppTestModel").orNull?.let { environment("SIMCTL_CHILD_LLAMA_TEST_MODEL", it) }
}

/** Kam `buildLlamaCpp<Target>` dá `libai_engine_llama_ios.a`; odtud ji bere cinterop. */
fun llamaCppLibraryDir(target: KotlinNativeTarget): Provider<Directory> = layout.buildDirectory.dir("llamacpp/${target.name}/lib")

fun registerLlamaCppBuild(target: KotlinNativeTarget): TaskProvider<Task> {
    val name = target.name.replaceFirstChar { it.uppercase() }
    val simulator = target.name.contains("Simulator")
    val outputDir = llamaCppLibraryDir(target)
    val cmakeDir = layout.buildDirectory.dir("llamacpp/${target.name}/cmake")
    val sources = llamaCppDir.dir("src/main/cpp")
    val submodule = llamaCppDir.dir("llama.cpp")
    val sdk = androidSdk
    val cmakeVersion = libs.versions.cmake.get()
    val deploymentTarget = IOS_DEPLOYMENT_TARGET

    return tasks.register("buildLlamaCpp$name") {
        description = "Přeloží llama.cpp a jádro mostu pro ${target.name} do jedné statické knihovny."
        inputs.dir(sources)
        // Jen to, z čeho se knihovna skládá — celý submodul má tisíce souborů (testy, dokumentace).
        listOf("src", "include", "ggml/src", "ggml/include", "cmake", "ggml/cmake").forEach { path ->
            inputs.files(fileTree(submodule.dir(path)))
        }
        inputs.files(submodule.file("CMakeLists.txt"), submodule.file("ggml/CMakeLists.txt"))
        inputs.property("deploymentTarget", deploymentTarget)
        outputs.dir(outputDir)

        doLast {
            if (!submodule.file("CMakeLists.txt").asFile.isFile) {
                throw GradleException("Chybí submodul llama.cpp (git submodule update --init --depth 1).")
            }
            val sdkCmake = sdk.get().takeIf { it.isNotBlank() }
                ?.let { File(it, "cmake/$cmakeVersion/bin/cmake") }
                ?.takeIf { it.isFile }
            // Gradle démon nemusí mít PATH ze shellu (spuštěný z IDE), proto i obvyklá místa Homebrew.
            val cmake = sdkCmake
                ?: listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake").map(::File).firstOrNull { it.isFile }
            val ninja = (sdkCmake?.resolveSibling("ninja")?.takeIf { it.isFile }
                ?: listOf("/opt/homebrew/bin/ninja", "/usr/local/bin/ninja").map(::File).firstOrNull { it.isFile })

            fun run(command: List<String>) {
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) throw GradleException("${command.first()} selhal:\n$output")
            }

            val work = cmakeDir.get().asFile.apply { mkdirs() }
            val cmakeCommand = cmake?.path ?: "cmake"
            try {
                run(listOf(cmakeCommand, "--version"))
            } catch (e: IOException) {
                throw GradleException("Chybí CMake (sdkmanager \"cmake;$cmakeVersion\" nebo brew install cmake).", e)
            }
            run(
                buildList {
                    add(cmakeCommand)
                    add("-S"); add(sources.asFile.path)
                    add("-B"); add(work.path)
                    add("-DCMAKE_BUILD_TYPE=Release")
                    add("-DCMAKE_SYSTEM_NAME=iOS")
                    add("-DCMAKE_OSX_SYSROOT=" + if (simulator) "iphonesimulator" else "iphoneos")
                    add("-DCMAKE_OSX_ARCHITECTURES=arm64")
                    add("-DCMAKE_OSX_DEPLOYMENT_TARGET=$deploymentTarget")
                    if (ninja != null) {
                        add("-G"); add("Ninja")
                        add("-DCMAKE_MAKE_PROGRAM=${ninja.path}")
                    }
                },
            )
            run(listOf(cmakeCommand, "--build", work.path, "--target", "ai_engine_llama", "--config", "Release", "--parallel"))

            // Jádro, llama a všechny části ggml (base, cpu, metal, blas…) v jedné knihovně pro cinterop.
            val libraries = work.walkTopDown()
                .filter { it.isFile && it.extension == "a" && (it.name.startsWith("libggml") || it.name in setOf("libllama.a", "libai_engine_llama.a")) }
                .map { it.path }
                .sorted()
                .toList()
            if (libraries.none { it.endsWith("/libai_engine_llama.a") }) throw GradleException("CMake nevyrobil libai_engine_llama.a v ${work.path}.")
            val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
            run(listOf("xcrun", "libtool", "-static", "-no_warning_for_no_symbols", "-o", File(out, "libai_engine_llama_ios.a").path) + libraries)
        }
    }
}
