# Keep rules shipped with :ai-core; R8 of the consuming app applies them automatically.

# Model runtimes (:ai-litert, :ai-llamacpp) usually live in an on-demand Google Play module and
# :ai-core creates them by REFLECTION from their name (`LITERT_ENGINE_FACTORY`,
# `LLAMACPP_ENGINE_FACTORY` in :ai-engine; `LiteRtRuntime`, `PlayLiteRtRuntime`). R8 processes the
# base and feature modules together, so without these rules it would rename or drop the classes
# and the model would fail after download.
-keep class eu.tintera.ai.litert.LiteRtEngineFactory { public <init>(); }
-keep class eu.tintera.ai.llamacpp.LlamaCppEngineFactory { public <init>(); }
