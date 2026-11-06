cmake_minimum_required(VERSION 3.22.1)

# Jedna knihovna `libplume_llama.so`: llama.cpp a ggml staticky, k tomu naše JNI (plume_llama.cpp).
#
# Proč ne jako příklad `examples/llama.android`: ten staví backendy jako samostatné knihovny
# (GGML_BACKEND_DL) a CPU variantu vybírá za běhu načtením z ADRESÁŘE nativních knihoven. Tahle
# knihovna jde do součásti z Google Play a split APK nese knihovny uvnitř APK, ne v adresáři —
# načíst by nebylo odkud. Jedna knihovna se načte přes `System.loadLibrary` jako každá jiná.
project("plume_llama" LANGUAGES C CXX)

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
# OpenMP by do součásti přidal sdílenou `libomp.so`, na kterou `libplume_llama.so` odkazuje.
# V součásti doinstalované za běhu (SplitCompat) ji linker hledat nemusí — jmenný prostor
# knihoven aplikace vznikl dřív než součást. ggml má vlastní pool vláken.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
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

add_library(plume_llama SHARED plume_llama.cpp)
target_link_libraries(plume_llama PRIVATE llama ggml android log)
