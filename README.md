# ai-engine

On-device language models for Kotlin Multiplatform apps: one `OnDeviceAi` interface over

- **Gemini Nano** on Android through ML Kit GenAI (Prompt API, Android AICore),
- **downloaded models** on Android and desktop (JVM): `.litertlm` files through
  [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM), GGUF files through
  [llama.cpp](https://github.com/ggml-org/llama.cpp); on iOS GGUF files through llama.cpp
  (LiteRT-LM on iOS not yet),
- **Apple Intelligence** (Foundation Models) on iOS 26+ through a small Swift bridge.

The library downloads models (resumable, Wi-Fi/metered aware, in a WorkManager job on Android),
reports their state for a settings screen, loads and unloads engines, falls back from GPU to CPU and
routes each call to the right runtime. **The library knows runtimes, the app knows models:** the
library contains no model catalog. Everything app-specific stays in the app: which models exist,
prompts, which model handles which task, settings, user-facing texts and UI.

It was extracted from the [Plume](https://github.com/VladimirTintera/telegram) Telegram client and
is shared with other apps by the same author. The API is not stable yet; there are no published
artifacts — use it as a git submodule.

## Modules

| Module | Platforms | What it is |
| --- | --- | --- |
| `:ai-engine` | Android, JVM, iOS | Interface of an in-process model runtime (`LlmEngineFactory`, `LlmEngine`, `LlmConversation`). No dependencies besides coroutines. |
| `:ai-core` | Android, JVM, iOS | `OnDeviceAi` and its providers: `MlKitOnDeviceAi` (Gemini Nano), `LiteRtOnDeviceAi` (downloaded models, all platforms), `AppleOnDeviceAi` (iOS), `RoutingOnDeviceAi`. Downloads, typed failures (`OnDeviceAiException`), Play Feature Delivery runtime loader. Depends only on `:ai-engine`, never on a runtime. |
| `:ai-litert` | Android, JVM | `LlmEngineFactory` on top of LiteRT-LM (`.litertlm` files). The only module with `com.google.ai.edge.litertlm`. |
| `:ai-llamacpp` | JVM (Android + desktop) | `LlmEngineFactory` on top of llama.cpp (GGUF files): Kotlin code, JNI bridge, `CMakeLists.txt` and the llama.cpp submodule. Builds nothing native itself. |
| `:ai-llamacpp-android` | Android | Builds `libai_engine_llama.so` with the NDK (arm64-v8a, x86_64 for the emulator; 16 KB pages). |
| `:ai-llamacpp-desktop` | JVM | Builds `ai_engine_llama` for the host OS/arch with CMake and puts it into the jar (Metal on Apple Silicon). Skipped locally without CMake or a compiler; an error on CI. |
| `:ai-llamacpp-ios` | iOS | `LlmEngineFactory` on top of llama.cpp for Kotlin/Native: builds a static library (device with Metal, simulator CPU only) from the same `CMakeLists.txt` and calls the bridge's C API (`ai_engine_llama.h`) through cinterop. The library ends up in the app's framework on its own. |

Packages are `eu.tintera.ai.engine`, `eu.tintera.ai.core`, `eu.tintera.ai.litert` and
`eu.tintera.ai.llamacpp` (both llama.cpp modules).

On Android and desktop, runtimes are found **by class name at run time** (`LITERT_ENGINE_FACTORY`,
`LLAMACPP_ENGINE_FACTORY` in `:ai-engine`), so the base of an Android app never has to see them at
compile time — on Android they normally live in on-demand Play feature modules (see below). The R8
keep rules for this are shipped as consumer rules of `:ai-core` and `:ai-litert`.

## Using it in an app

### 1. Submodule and composite build

```bash
git submodule add https://github.com/VladimirTintera/ai-engine.git external/ai-engine
git submodule update --init --recursive --depth 1
```

llama.cpp is a nested (shallow) submodule, so clones of the app need
`git submodule update --init --recursive`, and CI checkouts need `submodules: recursive`.

```kotlin
// settings.gradle.kts of the app
includeBuild("external/ai-engine")
```

Gradle substitutes the coordinates `eu.tintera.ai:<module>` with the projects of the included build:

```kotlin
// a KMP module of the app
commonMain.dependencies {
    api("eu.tintera.ai:ai-core")
}
```

Requirements:

- **The same AGP and Kotlin versions as the app** (`gradle/libs.versions.toml` here): one Gradle
  build cannot load two versions of AGP. Bump them together.
- The included build looks for the Android SDK on its own: `external/ai-engine/local.properties`
  (`sdk.dir=…`) or `ANDROID_HOME`. AGP needs the NDK version from the catalog (`ndk`) when it
  configures `:ai-llamacpp-android`, and CMake (`cmake`) for llama.cpp.
- JDK toolchain 17 (resolved through foojay if missing).
- For iOS: Xcode, and CMake for `:ai-llamacpp-ios` (`cmake;<version>` from the Android SDK, or
  `brew install cmake ninja`).

### 2. Define your models

The app owns the catalog. Each downloadable model is a `DownloadedModel`:

```kotlin
val SmallModel = DownloadedModel(
    id = "small-model", // stable: it is stored and is part of the download job's name
    displayName = "Small model",
    // Pins the commit, so the same bytes are always downloaded.
    url = huggingFaceUrl(repo = "some-org/some-model", commit = "<commit sha>", file = "model.litertlm"),
    fileName = "model.litertlm",
    sizeBytes = 343_490_560,
    minMemoryGb = 4, // not offered on devices with less memory
    runtime = ModelRuntime.LiteRt, // or ModelRuntime.LlamaCpp for GGUF files
    contextTokens = 4_096, // default 8,192, which is also the library's ceiling
    multimodal = false, // true = also takes audio and images
)
val Catalog = listOf(SmallModel)
```

Every call takes a `ModelVariant`, and its type selects the runtime:

```kotlin
ai.generate(ModelVariant.GeminiNano(fast = true), prompt) // Android, system model
ai.generate(ModelVariant.Apple, prompt) // iOS, system model
ai.generate(ModelVariant.Downloaded(SmallModel), prompt) // or SmallModel.variant
```

**Do not change `id`** of a shipped model: a pending download job and any stored choice refer to it.

### 3. Wire the providers

Android (the same shape works with any DI):

```kotlin
val confirmations = SplitInstallConfirmations()
val liteRt = LiteRtOnDeviceAi(
    // The app subclasses AiModelDownloadWorker: WorkManager remembers the class NAME.
    AndroidLiteRtPlatform(context, MyModelDownloadWorker::class.java, audio = myAudioDecoder),
    mapOf(
        // One Play feature module per runtime, declared by the app (see below).
        ModelRuntime.LiteRt to PlayLiteRtRuntime(
            context,
            module = "litertFeature",
            nativeLibrary = "litertlm_jni",
            factoryClass = LITERT_ENGINE_FACTORY,
            confirmations = confirmations,
        ),
        ModelRuntime.LlamaCpp to PlayLiteRtRuntime(
            context,
            module = "llamacppFeature",
            nativeLibrary = LLAMACPP_NATIVE_LIBRARY,
            factoryClass = LLAMACPP_ENGINE_FACTORY,
            confirmations = confirmations,
        ),
    )::getValue,
    myConnectivity,
    meteredNetwork = AndroidMeteredNetwork(context),
)
val ai: OnDeviceAi = RoutingOnDeviceAi(geminiNano = MlKitOnDeviceAi(), liteRt = liteRt)
```

The worker's `dependencies()` returns `AiModelDownloadWorker.Dependencies(ai, notifications, modelById)`.
WorkManager restarts the job after the process dies with nothing but the model's `id`, so
`modelById = { id -> Catalog.find { it.id == id } }` has to find it again. An unknown id fails the job.

Desktop (JVM): `LiteRtOnDeviceAi(DesktopLiteRtPlatform(modelsDirectory, audio), runtimes, …)` with
`BundledLiteRtRuntime(LITERT_ENGINE_FACTORY)` / `BundledLiteRtRuntime(LLAMACPP_ENGINE_FACTORY)`;
the app puts `:ai-litert` and `:ai-llamacpp-desktop` on its runtime classpath.

iOS: export `:ai-core` from the app's framework (`export("eu.tintera.ai:ai-core")` with an `api`
dependency), implement `OnDeviceTextModel` in Swift on top of Foundation Models and assign it to
`IosOnDeviceModel.model` before creating `AppleOnDeviceAi`. A reference implementation is in
[`ios/AppleTextModel.swift`](ios/AppleTextModel.swift).

Downloaded GGUF models on iOS: add `eu.tintera.ai:ai-llamacpp-ios` to the framework's `iosMain`
(it does not need to be exported) and pass the factory directly, there is no reflection on iOS:

```kotlin
val liteRt = LiteRtOnDeviceAi(
    IosLiteRtPlatform(), // models in Application Support/ai-models, excluded from iCloud backup
    mapOf(ModelRuntime.LlamaCpp to LinkedLiteRtRuntime { LlamaCppEngineFactory() })::getValue,
    myConnectivity,
    meteredNetwork = IosMeteredNetwork(), // cellular / hotspot, for "Wi-Fi only"
)
val ai: OnDeviceAi = RoutingOnDeviceAi(
    geminiNano = NoopOnDeviceAi,
    liteRt = liteRt,
    apple = IosOnDeviceModel.model?.let(::AppleOnDeviceAi) ?: NoopOnDeviceAi,
)
```

Downloads on iOS run in a background `URLSession`, so they continue while the app is suspended or
after the system terminates it. Forward the session's events from the app delegate (in SwiftUI
through `@UIApplicationDelegateAdaptor`), otherwise a download that finishes while the app is not
running is only picked up at the next launch:

```swift
func application(_ application: UIApplication,
                 handleEventsForBackgroundURLSession identifier: String,
                 completionHandler: @escaping () -> Void) {
    if !IosModelDownloads.shared.handleEventsForBackgroundURLSession(
        identifier: identifier, completionHandler: completionHandler) {
        completionHandler() // not the library's session
    }
}
```

No capability or `Info.plist` entry is needed (background `URLSession` is not a background mode).
The library uses two sessions: `<bundle id>.ai-models` (`IosModelDownloads.sessionIdentifier`) and
`<bundle id>.ai-models.wifi` (`wifiSessionIdentifier`) for "Wi-Fi only" downloads
(`download(variant, mobileData = false)`). In that session iOS does not use cellular data or a
personal hotspot and the download waits for Wi-Fi on its own, even while the app is suspended.
Changing the option during a download (`setDownloadNetwork`) moves it to the other session and
continues from the downloaded part. `IosMeteredNetwork` reports the same "expensive" network
(`NWPath.isExpensive`), so the screen can show that the download waits for Wi-Fi. Only
`IosLiteRtPlatform` downloads in the background; a custom `LiteRtPlatform` on iOS downloads in the
foreground through the shared code.

Models over roughly half of the device memory need the
`com.apple.developer.kernel.increased-memory-limit` entitlement in the app, otherwise iOS kills the
app while the model loads. Models of other runtimes (`ModelRuntime.LiteRt`) report `Unsupported`
on iOS.

### 4. What the app provides

- **The model catalog** (`DownloadedModel`), including showing a model's license where it requires
  that. `idleUnloadMs` takes the model, so the app can set how long each one stays loaded.
- **Prompts and task selection.** `generate`, `transcribe(variant, audioPath, prompt)` and
  `describeImage(variant, imagePath, prompt)` take a finished prompt.
- **Interfaces:** `Connectivity`, `AudioClipDecoder` (WAV 16 kHz mono, max 30 s; optional),
  `ModelDownloadNotifications` (channel, icon and texts of the download notification), a models
  directory on desktop, `MeteredNetwork`, `DeviceLoad`. A custom `LiteRtPlatform` gives
  `modelsDir` and `cacheDir` as absolute path strings.
- **User-facing texts.** Failures come as `OnDeviceAiException` with an `AiFailureReason` and the
  `variant` that failed; the app maps them to messages.
- **The Play consent dialog.** Observe `SplitInstallConfirmations.pending` in the root activity,
  call `markShown`, and pass the result back from `takeShown()`.
- **Play Feature Delivery modules.** The app declares one `com.android.dynamic-feature` module per
  runtime (no code, it only depends on `eu.tintera.ai:ai-litert` resp.
  `eu.tintera.ai:ai-llamacpp-android`) and lists them in `dynamicFeatures`. The module name is the
  `module` passed to `PlayLiteRtRuntime`. For development installs without Play, install the
  feature together with the app.
- **Structured output types** for Gemini Nano: the app's response classes, their
  `GenerableProvider`s (`generableStringField` helps), the `META-INF/services` entries and keep
  rules for the classes Gson fills.

## Limitations

- **Gemini Nano only on devices with Android AICore** (recent Pixel, Galaxy and other flagships)
  and **not on the emulator**. ML Kit GenAI is a proprietary beta SDK and its API may change.
- **Gemini Nano only in the foreground.** AICore refuses inference from background work; downloads
  of other models run in WorkManager, inference does not.
- **Gson must stay below 2.14** (`strictly 2.13.2` in `:ai-litert`). LiteRT-LM pulls Gson 2.14,
  whose new class breaks the relocated Gson inside ML Kit and crashes Gemini Nano structured
  output. Guarded by `GsonForMlKitTest`. Apps that add Gson themselves must respect it.
- **Play Feature Delivery modules must be declared by the apps** — a library cannot declare
  dynamic features. Outside Google Play (sideloaded APK, other stores) on-demand install fails and
  the downloaded models are unavailable.
- **Apple Intelligence only through the Swift bridge** — Kotlin/Native cannot call the Swift-only
  `FoundationModels`. Text generation only (no structured output, audio or images on iOS yet).
- **Downloaded models on iOS: llama.cpp only, text only.** LiteRT-LM has only an early-preview
  Swift API so far. Downloads continue in a background `URLSession` while the app is suspended or
  terminated by the system, but **a user who force-quits the app from the app switcher cancels
  them**: iOS keeps the downloaded part (resume data) and the download resumes when the app calls
  `download` again after the next launch. Resuming needs a server with `Range` and an `ETag` or
  `Last-Modified` (Hugging Face's CDN has both); without them a broken download starts over.
  "Wi-Fi only" follows iOS's "expensive" network (cellular, personal hotspot), not Low Data Mode,
  and does not apply when the app finishes a `.part` file left by a library version without
  background downloads. Metal on the device, CPU in the simulator.
- Downloaded models need arm64, Android 12+ and enough RAM (`minMemoryGb`); llama.cpp on
  desktop needs AVX2 (x86_64) or dot product (arm64). GPU for llama.cpp only via Metal on macOS
  and iOS devices.
- Model weights are never bundled, and the library names no models. Each model has its own license,
  which is the concern of the app that offers it; see [NOTICE](NOTICE).

## Playground app

[`samples/playground`](samples/playground) is a small Compose Multiplatform app (Android, desktop,
iOS; Material 3 Expressive) to download on-device models and try a conversation with them. It is
also the reference wiring of the library on every platform: a **separate Gradle build** that
includes this repository with `includeBuild("../..")`, exactly like an app does, so it never runs
in this build or its CI and is never published.

- **Catalog** of Gemma, EuroLLM, Aya and Qwen variants (size, quantization, runtime, minimum RAM,
  license), each pinned to a Hugging Face commit, plus **any GGUF / `.litertlm` file from Hugging
  Face** (repository or link, optional revision; gated repositories are refused with a message).
  Variants a device cannot run (`.litertlm` on iOS, too little RAM) are shown greyed with the reason.
- **Model management**: download, pause, resume, delete, "Wi-Fi only" (Android, iOS), the system
  models (Gemini Nano, Apple Intelligence) where available.
- **Conversation test**: streamed answer with model load time, time to first token, tokens/s
  (estimated from runtime chunks) and chars/s, and a Stop button. The history is put into the
  prompt by the app and the oldest turns are dropped to fit `ModelInfo.tokenLimit`.

```bash
cd samples/playground
./gradlew :composeApp:run                       # desktop (Java 21 toolchain)
./gradlew :androidApp:installWithRuntimes       # Android: app + runtime feature modules (adb install-multiple)
open iosApp/iosApp.xcodeproj                    # iOS: run the iosApp scheme
./gradlew :composeApp:jvmTest -Pplayground.e2e=true  # desktop UI end-to-end: downloads Qwen3 0.6B and chats
```

The playground needs its own `samples/playground/local.properties` (`sdk.dir=…`) or `ANDROID_HOME`,
and the same SDK, NDK and CMake as the library (the included build configures all its modules).

## Building

```bash
git clone --recurse-submodules --shallow-submodules https://github.com/VladimirTintera/ai-engine.git
./gradlew build
```

`build` compiles all targets, runs the JVM, Android host and (on macOS) iOS simulator tests, builds
the Android libraries including llama.cpp from the NDK and the desktop llama.cpp library for the
host. iOS klibs need macOS with Xcode (and CMake for llama.cpp). `-PllamaCppTestModel=<file.gguf>`
lets `LlamaCppDesktopTest` and the simulator test `LlamaCppIosTest` actually run a model.

Engineering notes (in Czech) are in [docs/cs/ai-v-zarizeni.md](docs/cs/ai-v-zarizeni.md), an
English overview in [docs/on-device-ai.md](docs/on-device-ai.md).

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE) for third-party components
(llama.cpp under MIT, LiteRT-LM under Apache 2.0, ML Kit GenAI as a proprietary dependency).
