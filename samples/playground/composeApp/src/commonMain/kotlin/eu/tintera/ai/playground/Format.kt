package eu.tintera.ai.playground

import eu.tintera.ai.core.ModelRuntime
import kotlin.math.roundToLong

/** „396 MB", „3.1 GB" (desítkové jednotky jako Hugging Face). */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "${oneDecimal(bytes / 1_000_000_000.0)} GB"
    bytes >= 1_000_000 -> "${(bytes / 1_000_000.0).roundToLong()} MB"
    bytes >= 1_000 -> "${(bytes / 1_000.0).roundToLong()} kB"
    else -> "$bytes B"
}

/** „0.4 s", „12 s". */
fun formatSeconds(ms: Long): String =
    if (ms < 10_000) "${oneDecimal(ms / 1000.0)} s" else "${(ms / 1000.0).roundToLong()} s"

/** Jedno desetinné místo bez `String.format` (ve společném kódu není). */
fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}

val ModelRuntime.label: String
    get() = when (this) {
        ModelRuntime.LiteRt -> "LiteRT-LM"
        ModelRuntime.LlamaCpp -> "llama.cpp"
    }
