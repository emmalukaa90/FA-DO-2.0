package com.fado.karaoke.render

import android.graphics.Paint
import com.fado.karaoke.model.LyricLine
import com.fado.karaoke.model.LyricWord
import com.fado.karaoke.model.Lyrics
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Słowo w wierszu: x względem lewej krawędzi wiersza, pozycje liter z Paint.getTextWidths(). */
internal class WordLayout(
    val word: LyricWord,
    val x: Float,
    val width: Float,
    val charX: FloatArray,
    val charW: FloatArray
)

internal class RowLayout(val index: Int, val words: List<WordLayout>, val width: Float)

/** Linia w układzie "dokumentu" (y rośnie w dół); renderer przewija dokument płynnie. */
internal class LineLayout(val line: LyricLine, val rows: List<RowLayout>, val top: Float, val height: Float) {
    val centerY: Float = top + height / 2f
}

internal class LyricsLayout(
    val lines: List<LineLayout>,
    val textSize: Float,
    val rowHeight: Float,
    val baseline: Float,
    /** Górna krawędź wielkich liter względem góry wiersza — tu ląduje piłka. */
    val letterTop: Float
) {
    companion object {

        fun build(lyrics: Lyrics, paint: Paint, maxWidth: Float): LyricsLayout {
            val textSize = paint.textSize
            val fm = paint.fontMetrics
            val glyphHeight = fm.descent - fm.ascent
            // Każdy wiersz ma nad literami miejsce na piłkę — także drugi wiersz zawiniętej linii.
            val ballRoom = textSize * 0.5f
            val rowHeight = ballRoom + glyphHeight * 1.06f
            val baseline = ballRoom + glyphHeight * 0.03f - fm.ascent
            val letterTop = baseline - textSize * 0.74f
            val space = paint.measureText(" ")
            val lineGap = textSize * 0.75f // + ballRoom pierwszego wiersza = miejsce na łuk

            var cursor = 0f
            val lines = ArrayList<LineLayout>(lyrics.lines.size)
            for (line in lyrics.lines) {
                val measured = line.words.map { measure(it, paint) }
                val rows = wrap(measured, maxWidth, space)
                val height = rows.size * rowHeight
                val top = cursor + lineGap
                lines += LineLayout(line, rows, top, height)
                cursor = top + height
            }
            return LyricsLayout(lines, textSize, rowHeight, baseline, letterTop)
        }

        private class Measured(val word: LyricWord, val width: Float, val charX: FloatArray, val charW: FloatArray)

        private fun measure(word: LyricWord, paint: Paint): Measured {
            val n = word.text.length
            val widths = FloatArray(n)
            if (n > 0) paint.getTextWidths(word.text, widths)
            val xs = FloatArray(n)
            var acc = 0f
            for (i in 0 until n) {
                xs[i] = acc
                acc += widths[i]
            }
            return Measured(word, max(acc, paint.measureText(word.text)), xs, widths)
        }

        /** Zawijanie "zbalansowane": dwa wiersze o podobnej długości zamiast długiego + sieroty. */
        private fun wrap(words: List<Measured>, maxWidth: Float, space: Float): List<RowLayout> {
            if (words.isEmpty()) return listOf(RowLayout(0, emptyList(), 0f))
            var total = 0f
            for (i in words.indices) {
                total += words[i].width
                if (i < words.size - 1 && !words[i].word.joinNext) total += space
            }
            val needed = max(1, ceil(total / maxWidth).toInt())
            if (needed == 1) return greedy(words, maxWidth, space)
            val balanced = greedy(words, min(maxWidth, total / needed * 1.1f), space)
            return if (balanced.size > needed) greedy(words, maxWidth, space) else balanced
        }

        private fun greedy(words: List<Measured>, limit: Float, space: Float): List<RowLayout> {
            val rows = ArrayList<RowLayout>()
            var current = ArrayList<WordLayout>()
            var x = 0f
            var pendingGap = 0f
            for (m in words) {
                val startX = if (current.isEmpty()) 0f else x + pendingGap
                if (current.isNotEmpty() && startX + m.width > limit) {
                    rows += RowLayout(rows.size, current, x)
                    current = ArrayList()
                    current += WordLayout(m.word, 0f, m.width, m.charX, m.charW)
                    x = m.width
                } else {
                    current += WordLayout(m.word, startX, m.width, m.charX, m.charW)
                    x = startX + m.width
                }
                pendingGap = if (m.word.joinNext) 0f else space
            }
            if (current.isNotEmpty()) rows += RowLayout(rows.size, current, x)
            return rows
        }
    }
}
