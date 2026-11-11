# On-device AI — overview

A short English digest of the engineering notes. The full notes, with measurements and the
reasoning behind each decision, are in Czech in [cs/ai-v-zarizeni.md](cs/ai-v-zarizeni.md).

## Providers and routing

- `OnDeviceAi` is the only boundary the app talks to. `ModelVariant` is a sealed type:
  `GeminiNano(preview, fast)`, `Apple` and `Downloaded(model)`. `RoutingOnDeviceAi` sends each call
  to the runtime for that type. A variant carries only its own fields, so ML Kit (one client per
  variant) never gets a second client for the same Gemini Nano model.
- **The library knows runtimes, the app knows models.** The app supplies the catalog
  (`DownloadedModel`) and decides which variant handles which task. The library knows nothing about
  specific models or tasks.
- Failures are typed: `OnDeviceAiException(reason, variant, code)` with `AiFailureReason` and
  `isReportable`; no user-facing strings in the library.

## Gemini Nano (ML Kit GenAI, Android)

- System model in Android AICore: the OS downloads and updates it. Only on supported devices
  (`Unsupported` elsewhere, including the emulator).
- Inference is allowed **only for the app in the foreground** (`BACKGROUND_USE_BLOCKED` otherwise).
- `genai-prompt` 1.0.0-beta5: beta4 silently ran the stable model for part of the preview-variant
  requests.
- Structured output (`generateStructured`) only where AICore reports the feature; the text path
  stays fully supported. Response shapes are hand-written `GenerableProvider`s found through
  `ServiceLoader` (`META-INF/services` in the app); the classes Gson fills need keep rules in the app.

## Downloaded models (LiteRT-LM and llama.cpp; Android, desktop, iOS)

- `DownloadedModel` describes a file: stable `id`, `url`, `fileName`, size, memory needed, context,
  runtime (`ModelRuntime.LiteRt` / `LlamaCpp`). `huggingFaceUrl(repo, commit, file)` pins the
  **commit**, so the same bytes are always downloaded; a new version is a deliberate catalog change.
- Downloads go to `.part` and are renamed when complete (file present = complete model), resume
  with `Range`, check free space first, and respect metered networks (`MeteredNetwork`). On Android
  they run in a WorkManager job (`AiModelDownloadWorker`, subclassed by the app because WorkManager
  persists the class name, and finds the model again through `Dependencies.modelById`, since the job
  keeps only its id) with a `dataSync` foreground service when the system allows it.
- Models are offered only where they can run: arm64, Android 12+, enough RAM per entry; on desktop
  llama.cpp needs AVX2 (x86_64) or dot product (arm64).
- The whole layer is common code (`commonMain` of `:ai-core`): files through kotlinx-io, HTTP behind
  `httpDownload` (`HttpURLConnection` on Android and desktop, `NSURLSession` on iOS). On iOS
  (`IosLiteRtPlatform`) models live in `Application Support/ai-models` excluded from iCloud backup,
  downloads run in the process while the app is in the foreground and resume from `.part`, and only
  llama.cpp is available (`LinkedLiteRtRuntime` with the factory from `:ai-llamacpp-ios`).
- Engines are loaded lazily, unloaded after an idle period (`idleUnloadMs`), and fall back from GPU
  to CPU when GPU loading fails.

## Runtimes as Play feature modules

- LiteRT-LM (~23 MB native library on arm64) and llama.cpp would grow the app for everyone, so on
  Android each runtime is an **on-demand Play Feature Delivery module declared by the app**.
  `PlayLiteRtRuntime` installs it together with (and before) the model, attaches to an install
  already in progress, calls `SplitCompat.install`, loads the native library with
  `SplitInstallHelper.loadLibrary` and creates the factory by reflection.
- Play can ask for user consent (`REQUIRES_USER_CONFIRMATION`); that is not an error.
  `SplitInstallConfirmations` hands the request to the app's dialog.
- Outside Google Play the install fails (`APP_NOT_OWNED`, -15). For development, install the
  feature modules together with the app.
- On desktop the runtimes are on the classpath (`BundledLiteRtRuntime`).

## llama.cpp

- Git submodule `ai-llamacpp/llama.cpp`, pinned to release `b12170`, shallow. Bump:
  `git -C ai-llamacpp/llama.cpp fetch --depth 1 origin tag bNNNNN`, check out, commit the pointer.
- One shared library `ai_engine_llama` with llama.cpp and ggml linked statically, our bridge core
  with a small C API (`ai_engine_llama.h`, only the stable C API from `llama.h` underneath) and a
  thin JNI layer on top. iOS (`:ai-llamacpp-ios`) builds the same core as a static library without
  JNI and calls it through cinterop. No `GGML_BACKEND_DL` (split APKs keep libraries inside the
  APK), no OpenMP (an extra `libomp.so` may not be found from a split installed at run time), no
  KleidiAI (it would download sources at configure time).
- Text crosses JNI as UTF-8 bytes, not `jstring` (modified UTF-8 breaks emoji and CheckJNI).
- CPU: `armv8.2-a+dotprod+fp16` on arm64; default ggml x86_64 set (AVX2/FMA/F16C/BMI2) on desktop.
  GPU only via Metal on Apple Silicon and iOS devices (not the simulator), shaders embedded in the
  library.

## Gson and ML Kit

LiteRT-LM 0.19.1 pulls Gson 2.14.0, which added `JavaTimeTypeAdapters`. ML Kit's relocated Gson
looks that class up by its original name, finds the foreign one and crashes structured output with
`ClassCastException`. `:ai-litert` pins Gson `strictly 2.13.2`; the pin must be in the module that
the Play feature brings, because it is loaded into the same classloader as ML Kit.
`GsonForMlKitTest` guards it.

## Apple Intelligence (iOS 26+)

- Kotlin/Native cannot reach the Swift-only `FoundationModels`, so `:ai-core` exposes
  `OnDeviceTextModel` (an Objective-C protocol in the framework) and the app implements it in
  Swift ([../ios/AppleTextModel.swift](../ios/AppleTextModel.swift)), registered in
  `IosOnDeviceModel.model` at startup.
- Streams are snapshots (whole text so far), as `OnDeviceAi.generate` promises. Errors are mapped
  by case *name*, because iOS 27 renamed the error types. Context is 4,096 tokens.
- The simulator uses the host Mac's model; with Apple Intelligence off on the Mac, generation fails
  with `ModelManagerError` 1026.
