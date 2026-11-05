package com.fado.karaoke.model

/**
 * Pojedyncza litera z oknem czasowym, w którym jest "śpiewana".
 *
 * W przeciwieństwie do pierwotnej specyfikacji modele nie przechowują współrzędnych
 * ekranowych — telefon i auto rysują ten sam tekst jednocześnie w różnych rozmiarach,
 * więc układ (x, szerokość) liczy osobno każdy renderer (patrz render/LyricsLayout.kt).
 */
data class LyricChar(
    val char: Char,
    val startTimeMs: Long,
    val endTimeMs: Long
)

data class LyricWord(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val chars: List<LyricChar>,
    /** Zakresy indeksów liter tworzących sylaby — dla trybu "piłka po sylabach". */
    val syllables: List<IntRange>,
    /** true = brak spacji po słowie (np. kolejne znaki CJK). */
    val joinNext: Boolean = false
)

data class LyricLine(
    val startTimeMs: Long,
    /** Koniec śpiewania linii (pełne wypełnienie). */
    val endTimeMs: Long,
    val rawText: String,
    val words: List<LyricWord>
)

enum class SyncKind {
    /** Enhanced LRC: znaczniki czasu dla słów/sylab. */
    WORD_SYNCED,

    /** Zwykłe LRC: czasy linii, słowa i litery estymowane. */
    LINE_SYNCED,

    /** Tekst bez czasów — linie rozłożone proporcjonalnie na długość utworu. */
    UNSYNCED
}

/** Zwykła klasa (nie data class): porównywana po tożsamości, co ułatwia cache układu. */
class Lyrics(
    val lines: List<LyricLine>,
    val kind: SyncKind,
    val sourceLabel: String
) {
    private val starts = LongArray(lines.size) { lines[it].startTimeMs }

    /** Indeks ostatniej linii, która zaczęła się przed [timeMs], albo -1. */
    fun lineIndexAt(timeMs: Double): Int {
        var lo = 0
        var hi = starts.size - 1
        var answer = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= timeMs) {
                answer = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return answer
    }
}
