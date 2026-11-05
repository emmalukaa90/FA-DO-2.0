package com.fado.karaoke.media

import com.fado.karaoke.model.LyricWord
import com.fado.karaoke.model.Lyrics
import kotlin.math.ceil

/**
 * Kafelek multimediów Android Auto pokazuje tytuł w jednym wierszu, więc długie linie
 * dzielimy na krótsze fragmenty (po słowach), każdy z własnym czasem startu.
 * Tytuł kafelka = fragment śpiewany teraz, podtytuł = następny fragment.
 */
object LyricChunker {

    data class Chunk(val startMs: Long, val endMs: Long, val text: String, val lineIndex: Int)

    data class Card(val title: String, val subtitle: String?)

    const val DEFAULT_MAX_CHARS = 30
    const val BREAK_SYMBOL = "♪ ♪ ♪"

    fun chunks(lyrics: Lyrics, maxChars: Int = DEFAULT_MAX_CHARS): List<Chunk> {
        val out = ArrayList<Chunk>(lyrics.lines.size * 2)
        lyrics.lines.forEachIndexed { index, line ->
            val words = line.words
            if (words.isEmpty()) return@forEachIndexed
            val full = join(words)
            if (full.length <= maxChars) {
                out += Chunk(line.startTimeMs, line.endTimeMs, full, index)
                return@forEachIndexed
            }
            // Podział na n podobnych długością fragmentów, granice tylko między słowami.
            val parts = ceil(full.length / maxChars.toDouble()).toInt().coerceAtLeast(2)
            val target = full.length / parts.toDouble()
            var group = ArrayList<LyricWord>()
            var groupLen = 0
            var produced = 0
            for (w in words) {
                group += w
                groupLen += w.text.length + if (w.joinNext) 0 else 1
                val remainingParts = parts - produced - 1
                if (groupLen >= target && remainingParts > 0 && w !== words.last()) {
                    val start = if (produced == 0) line.startTimeMs else group.first().startTimeMs
                    out += Chunk(start, group.last().endTimeMs, join(group), index)
                    produced++
                    group = ArrayList()
                    groupLen = 0
                }
            }
            if (group.isNotEmpty()) {
                val start = if (produced == 0) line.startTimeMs else group.first().startTimeMs
                out += Chunk(start, group.last().endTimeMs, join(group), index)
            }
        }
        return out
    }

    /**
     * Co pokazać w kafelku w chwili [t] (ms, z przesunięciem).
     * @return null przed pierwszym fragmentem (kafelek pokazuje wtedy tytuł utworu).
     */
    fun cardAt(chunks: List<Chunk>, t: Double): Card? {
        if (chunks.isEmpty()) return null
        var lo = 0
        var hi = chunks.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chunks[mid].startMs <= t) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (idx < 0) return null
        val current = chunks[idx]
        val next = chunks.getOrNull(idx + 1)
        // Dłuższa przerwa (solówka): zamiast starej linii pokazujemy nuty i zapowiedź następnej.
        val longBreak = next != null && next.startMs - current.endMs >= 4000 && t > current.endMs + 2500
        return if (longBreak) Card(BREAK_SYMBOL, next?.text) else Card(current.text, next?.text)
    }

    private fun join(words: List<LyricWord>): String {
        val sb = StringBuilder()
        words.forEachIndexed { i, w ->
            sb.append(w.text)
            if (i < words.size - 1 && !w.joinNext) sb.append(' ')
        }
        return sb.toString()
    }
}
