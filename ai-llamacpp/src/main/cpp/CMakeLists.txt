cmake_minimum_required(VERSION 3.22.1)

# Jedna knihovna `libai_engine_llama.so`: llama.cpp a ggml staticky, k tomu naše JNI (ai_engine_llama.cpp).
# Na Androidu ji staví AGP (externalNativeBuild v :ai-llamacpp), na desktopu úloha
# `buildLlamaCppDesktop` (:ai-llamacpp-desktop) pro systém, na kterém se staví.
#
# Proč ne jako příklad `examples/llama.android`: ten staví backendy jako samostatné knihovny
# (GGML_BACKEND_DL) a CPU variantu vybírá za běhu načtením z ADRESÁŘE nativních knihoven. Tahle
# knihovna jde do součásti z Google Play a split APK nese knihovny uvnitř APK, ne v adresáři —
# načíst by nebylo odkud. Jedna knihovna se načte přes `System.loadLibrary` jako každá jiná.
project("ai_engine_llama" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# KleidiAI by si zdrojáky stahoval z GitHubu při konfiguraci; build nemá sahat do sítě.
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
# OpenMP by do součásti přidal sdílenou `libomp.so`, na kterou `libai_engine_llama.so` odkazuje.
# V součásti doinstalované za běhu (SplitCompat) ji linker hledat nemusí — jmenný prostor
# knihoven aplikace vznikl dřív než součást. ggml má vlastní pool vláken.
# Na desktopu by navíc chtěla vlastní runtime (vcomp140.dll z VC++ Redistributable, libomp).
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# GPU jen přes Metal na Macu s Apple Silicon (níž); jinde se žádný GPU backend nepřekládá
# a aplikace o GPU nežádá (`LiteRtPlatform.gpu`). Shadery jsou vložené v knihovně
# (GGML_METAL_EMBED_LIBRARY) a překládají se při prvním načtení — jinak by Metal hledal
# soubor `default.metallib` vedle knihovny, která leží vybalená v keši.
set(GGML_METAL OFF CACHE BOOL "" FORCE)

if(NOT ANDROID)
    # Desktop: JNI hlavičky dodá úloha Gradle z toolchainu projektu (AI_ENGINE_JNI_INCLUDE_DIRS).
    # CPU varianta je stejná jako na Androidu: na arm64 dot product (všechny Apple Silicon),
    # na x86_64 výchozí sada ggml bez GGML_NATIVE, tedy AVX2, FMA, F16C a BMI2 (Haswell a novější).
    # Starší procesor by spadl na SIGILL, proto se tam model nenabízí (`DesktopLiteRtPlatform`).
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64|ARM64)$")
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
        if(APPLE)
            set(GGML_METAL ON CACHE BOOL "" FORCE)
            set(GGML_METAL_EMBED_LIBRARY ON CACHE BOOL "" FORCE)
            set(GGML_METAL_NDEBUG ON CACHE BOOL "" FORCE)
        endif()
    endif()
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    # Dot product (ARMv8.2) mají všechny telefony, na kterých se model nabízí (Android 12+,
    # 6 GB paměti a víc); bez něj by kvantizované násobení jelo několikrát pomaleji. Novější
    # instrukce (i8mm, SVE) by na starších jádrech skončily SIGILL.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # Jen kvůli emulátoru: model se na x86_64 nenabízí (AndroidLiteRtPlatform.canRun).
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(ai_engine_llama SHARED ai_engine_llama.cpp)
if(ANDROID)
    target_link_libraries(ai_engine_llama PRIVATE llama ggml android log)
else()
    target_include_directories(ai_engine_llama PRIVATE ${AI_ENGINE_JNI_INCLUDE_DIRS})
    target_link_libraries(ai_engine_llama PRIVATE llama ggml)
endif()
