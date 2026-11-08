import java.util.Properties

plugins {
    alias(libs.plugins.kotlinJvm)
}

/**
 * Nativní knihovna llama.cpp pro desktop (`libai_engine_llama.dylib` / `ai_engine_llama.dll` /
 * `libai_engine_llama.so`) v jaru; kód nad ní je v :ai-llamacpp. Protějšek
 * :ai-llamacpp-android, ze stejného CMakeLists (`ai-llamacpp/src/main/cpp`).
 *
 * - **Jen pro systém a architekturu, na kterých se staví** (jako TDLib a LiteRT-LM v desktopApp Plume):
 *   jpackage cizí systém zabalit neumí, takže macOS balíček vzniká na Macu, MSI na Windows.
 *   Knihovna leží v jaru pod `eu/tintera/ai/llamacpp/native/<systém>-<arch>/`, odkud ji
 *   `LlamaNative` vybalí; co v jaru není, pozná `DesktopLiteRtPlatform` a model nenabídne.
 * - **CMake z Android SDK** (`cmake;<verze z libs.versions.toml>`, s ním i Ninja), stejný jako
 *   pro Android; jinak `cmake` z PATH. Překladač je systémový: Xcode Command Line Tools na Macu,
 *   MSVC na Windows (najde ho `vswhere`, statické CRT, jako toasty v Plume :client:shared), GCC nebo
 *   Clang na Linuxu.
 * - **Bez submodulu, CMake nebo překladače se lokálně přeskočí** (desktop se přeloží i bez nich,
 *   jen modely GGUF nenabídnou); **na CI (`CI`) je to chyba**, ať release nevyjde potichu bez modelu.
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":ai-llamacpp"))
    testImplementation(project(":ai-engine"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.core)
}

// `-PllamaCppTestModel=<soubor.gguf>`: test nechá model i odpovědět (LlamaCppDesktopTest).
tasks.test {
    providers.gradleProperty("llamaCppTestModel").orNull?.let { systemProperty("llamaCppTestModel", it) }
}

val llamaCppDir = rootProject.layout.projectDirectory.dir("ai-llamacpp")

/** Hlavičky JNI z toolchainu projektu, ne z JVM démona Gradle (viz `jniToolchainHome` v Plume :client:shared). */
val jniHome: Provider<String> = javaToolchains
    .launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
    .map { it.metadata.installationPath.asFile.absolutePath }

/** Android SDK z `local.properties` nebo z prostředí, kvůli CMake. */
val androidSdk: Provider<String> = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty("sdk.dir") ?: "" }
    .filter { it.isNotBlank() }
    .orElse(providers.environmentVariable("ANDROID_HOME"))
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse("")

val buildLlamaCppDesktop = tasks.register("buildLlamaCppDesktop") {
    description = "Přeloží llama.cpp a JNI most pro tenhle systém do resources jaru."
    val outputDir = layout.buildDirectory.dir("generated/llamacpp")
    val cmakeDir = layout.buildDirectory.dir("tmp/llamacpp")
    val sources = llamaCppDir.dir("src/main/cpp")
    val submodule = llamaCppDir.dir("llama.cpp")
    val jdkHome = jniHome
    val sdk = androidSdk
    val cmakeVersion = libs.versions.cmake.get()
    val requireToolchain = System.getenv("CI") != null

    val osName = System.getProperty("os.name").lowercase()
    val os = when {
        "mac" in osName -> "darwin"
        "windows" in osName -> "windows"
        else -> "linux"
    }
    val arm64 = System.getProperty("os.arch").lowercase().let { "aarch64" in it || "arm64" in it }
    // Složka jako u LiteRT-LM (`darwin-aarch64`, `windows-x86_64`…); čte ji `LlamaNative` i `DesktopLiteRtPlatform`.
    val platform = "$os-" + if (arm64) "aarch64" else "x86_64"
    val library = System.mapLibraryName("ai_engine_llama")

    inputs.dir(sources)
    // Jen to, z čeho se knihovna skládá — celý submodul má tisíce souborů (testy, dokumentace).
    listOf("src", "include", "ggml/src", "ggml/include", "cmake", "ggml/cmake").forEach { path ->
        inputs.files(fileTree(submodule.dir(path)))
    }
    inputs.files(submodule.file("CMakeLists.txt"), submodule.file("ggml/CMakeLists.txt"))
    inputs.property("platform", platform)
    outputs.dir(outputDir)

    doLast {
        val out = outputDir.get().dir("eu/tintera/ai/llamacpp/native/$platform").asFile
        out.deleteRecursively()
        fun skip(message: String) {
            if (requireToolchain) throw GradleException(message)
            logger.warn("$message Modely GGUF se na desktopu nenabídnou.")
        }
        if (!submodule.file("CMakeLists.txt").asFile.isFile) {
            skip("Chybí submodul llama.cpp (git submodule update --init --depth 1).")
            return@doLast
        }

        val exe = if (os == "windows") ".exe" else ""
        val sdkCmake = sdk.get().takeIf { it.isNotBlank() }
            ?.let { File(it, "cmake/$cmakeVersion/bin/cmake$exe") }
            ?.takeIf { it.isFile }
        val sdkNinja = sdkCmake?.resolveSibling("ninja$exe")?.takeIf { it.isFile }
        val cmake = sdkCmake?.path ?: "cmake"

        val work = cmakeDir.get().asFile.apply { mkdirs() }
        val jdk = File(jdkHome.get()).invariantSeparatorsPath
        val jniOs = when (os) {
            "darwin" -> "darwin"
            "windows" -> "win32"
            else -> "linux"
        }
        val configure = buildList {
            add(cmake)
            add("-S"); add(sources.asFile.path)
            add("-B"); add(work.path)
            add("-DCMAKE_BUILD_TYPE=Release")
            add("-DAI_ENGINE_JNI_INCLUDE_DIRS=$jdk/include;$jdk/include/$jniOs")
            if (sdkNinja != null) {
                add("-G"); add("Ninja")
                add("-DCMAKE_MAKE_PROGRAM=${sdkNinja.path}")
            } else if (os == "windows") {
                // Ninja z Visual Studia, na PATH ho dá vcvarsall.
                add("-G"); add("Ninja")
            }
            if (os == "darwin") {
                add("-DCMAKE_OSX_ARCHITECTURES=" + if (arm64) "arm64" else "x86_64")
                // Jako `minimumSystemVersion` v desktopApp Plume.
                add("-DCMAKE_OSX_DEPLOYMENT_TARGET=" + if (arm64) "14.0" else "12.0")
            }
            if (os == "windows") {
                // Statické CRT (/MT): DLL nepotřebuje VC++ Redistributable.
                add("-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded")
            }
        }
        val build = listOf(cmake, "--build", work.path, "--target", "ai_engine_llama", "--config", "Release", "--parallel")

        fun runCommand(command: List<String>): Pair<Int, String> {
            val process = try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (e: java.io.IOException) {
                return -1 to (e.message ?: "")
            }
            val output = process.inputStream.bufferedReader().readText()
            return process.waitFor() to output
        }

        if (os == "windows") {
            val vswhere = File(System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)", "Microsoft Visual Studio\\Installer\\vswhere.exe")
            val installation = vswhere.takeIf { it.isFile }?.let {
                runCommand(listOf(it.path, "-latest", "-products", "*", "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath"))
                    .second.trim()
            }?.takeIf { it.isNotEmpty() }
            val vcvars = installation?.let { File(it, "VC\\Auxiliary\\Build\\vcvarsall.bat") }?.takeIf { it.isFile }
            if (vcvars == null) {
                skip("Chybí MSVC (Visual Studio s C++), llama.cpp se nepřeloží.")
                return@doLast
            }
            // vcvarsall nastaví prostředí jen pro cmd, ve kterém běží — obojí proto jde jedním skriptem.
            fun quoted(command: List<String>) = command.joinToString(" ") { "\"$it\"" }
            val script = File(work, "build.cmd")
            script.writeText(
                listOf(
                    "@echo off",
                    "call \"${vcvars.path}\" ${if (arm64) "arm64" else "x64"} >nul || exit /b 1",
                    "${quoted(configure)} || exit /b 1",
                    quoted(build),
                ).joinToString("\r\n"),
            )
            val (code, output) = runCommand(listOf("cmd", "/c", script.path))
            check(code == 0) { "Překlad llama.cpp selhal:\n$output" }
        } else {
            val (version, _) = runCommand(listOf(cmake, "--version"))
            if (version != 0) {
                skip("Chybí CMake (sdkmanager \"cmake;$cmakeVersion\" nebo cmake na PATH), llama.cpp se nepřeloží.")
                return@doLast
            }
            val (configured, configureOutput) = runCommand(configure)
            check(configured == 0) { "Konfigurace llama.cpp selhala:\n$configureOutput" }
            val (built, buildOutput) = runCommand(build)
            check(built == 0) { "Překlad llama.cpp selhal:\n$buildOutput" }
        }

        val built = work.walkTopDown().firstOrNull { it.isFile && it.name == library }
            ?: throw GradleException("CMake nevyrobil $library v ${work.path}.")
        out.mkdirs()
        val target = File(out, library)
        built.copyTo(target, overwrite = true)
        if (os == "darwin") {
            val (signed, output) = runCommand(listOf("codesign", "--force", "--sign", "-", target.path))
            check(signed == 0) { "codesign selhal:\n$output" }
        }
    }
}

sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/llamacpp")).builtBy(buildLlamaCppDesktop))
}
