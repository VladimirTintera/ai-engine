# Keep rules shipped with :ai-litert; R8 of the consuming app applies them automatically.

# LiteRT-LM does NOT ship keep rules (no `proguard.txt` in the AAR). Its native side reaches into
# Kotlin by NAME: it reads config getters (`SamplerConfig.getTopK()`), throws
# `LiteRtLmJniException` and calls back into callbacks. Without this a release build crashes on
# first use with `NoSuchMethodError … SamplerConfig.getTopK()` and SIGABRT (and a GPU error shows
# up as `ClassNotFoundException`). The whole package, not a list of classes: the JNI boundary is
# defined by the library and a list would silently drift on a bump. Remove once LiteRT-LM ships
# its own rules.
-keep class com.google.ai.edge.litertlm.** { *; }
