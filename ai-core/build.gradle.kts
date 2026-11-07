plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * Jazykový model v zařízení bez ohledu na aplikaci: rozhraní `OnDeviceAi` a jeho poskytovatelé —
 * Gemini Nano přes ML Kit GenAI (Android), Gemma, Qwen, EuroLLM a Aya stahované na žádost
 * a počítané přes LiteRT-LM nebo llama.cpp (Android a desktop), Apple Intelligence přes most do
 * Swiftu (iOS). Stahování modelu, jeho stav pro obrazovku a správa enginu jsou tady.
 *
 * **Nic z aplikace**: prompty, volba modelu pro úlohu, nastavení, texty a UI nese aplikace a co
 * od ní knihovna potřebuje (síť, notifikace stahování, dekódování zvuku, adresář modelů), dostane
 * přes rozhraní. Viz README a docs/cs/ai-v-zarizeni.md, „Modul :client:ai-core“ (z doby v Plume).
 *
 * Runtime modelů (:ai-litert, :ai-llamacpp) tu není ani při překladu, jen jejich
 * rozhraní (:ai-engine); na Androidu jsou to součásti z Google Play, které deklaruje
 * aplikace, na desktopu je přibalí vstupní bod.
 */
kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    android {
        namespace = "eu.tintera.ai.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Keep pravidla pro R8 aplikace, která knihovnu použije (viz soubor).
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-rules.pro")
            }
        }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Stahované modely (LiteRT-LM, llama.cpp): Android a desktop. Stahování, stav a engine
        // jsou společné, platforma je za `LiteRtPlatform`.
        val liteRtMain = create("liteRtMain") {
            dependsOn(commonMain.get())
            dependencies {
                // `LiteRtRuntime.engines()` vrací továrnu z ai-engine.
                api(project(":ai-engine"))
            }
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }

        androidMain {
            dependsOn(liteRtMain)
        }

        androidMain.dependencies {
            // Gemini Nano v AICore. Jediné místo v projektu, kde smí být ML Kit GenAI.
            implementation(libs.mlkit.genai.prompt)
            // Popis tvaru pro strukturovaný výstup (`generableStringField`); typy odpovědí
            // a jejich `GenerableProvider` nese aplikace.
            api(libs.mlkit.genai.schema)
            // Stahování modelu na pozadí (`AiModelDownloadWorker`, podtřídu dává aplikace).
            api(libs.androidx.work.runtime)
            implementation(libs.androidx.core.ktx)
            // Runtime modelu jako součást z Google Play (`PlayLiteRtRuntime`); `api`, protože
            // žádost o souhlas (`SplitInstallConfirmations.Request`) nese stav instalace.
            api(libs.play.feature.delivery)
        }

        jvmMain {
            dependsOn(liteRtMain)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
