package com.fado.karaoke.util

import java.text.Normalizer
import java.util.Locale

object TextUtil {
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    /** Małe litery, bez polskich znaków i interpunkcji — do porównań i kluczy cache. */
    fun normalize(s: String): String {
        val lower = s.lowercase(Locale.ROOT).replace('ł', 'l')
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return NON_ALNUM.replace(MARKS.replace(decomposed, ""), " ").trim()
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "—"
        val total = ms / 1000
        val minutes = total / 60
        val seconds = total % 60
        return if (seconds < 10) "$minutes:0$seconds" else "$minutes:$seconds"
    }

    fun formatOffset(ms: Int): String = when {
        ms > 0 -> "+$ms"
        ms < 0 -> "−${-ms}"
        else -> "0"
    }
}
