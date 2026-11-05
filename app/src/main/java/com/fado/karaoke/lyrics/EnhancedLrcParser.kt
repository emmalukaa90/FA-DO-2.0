package com.fado.karaoke.lyrics

import com.fado.karaoke.model.LyricChar
import com.fado.karaoke.model.LyricLine
import com.fado.karaoke.model.LyricWord
import com.fado.karaoke.model.Lyrics
import com.fado.karaoke.model.SyncKind
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Parser LRC w dwóch trybach:
 *
 * 1. **Enhanced LRC** (`[00:12.50]<00:12.50>Sing <00:13.10>along<00:14.00>`):
 *    czasy słów/sylab z pliku, w obrębie fragmentu czas dzielony na litery
 *    z wagą samogłosek.
 * 2. **Zwykłe LRC** (`[00:12.50] Sing along`): estymator rozkłada linię na słowa
 *    i litery według liczby sylab, długości słów i pauz po interpunkcji.
 *    Linia nie "rozciąga się" na całą przerwę do następnej (np. przed solówką) —
 *    czas śpiewu jest ograniczony do naturalnego tempa.
 *
 * Obsługuje też: wiele znaczników czasu w jednej linii, `[offset:±ms]`,
 * puste linie (przerwy instrumentalne), BOM, tekst CJK bez spacji.
 */
object EnhancedLrcParser {

    private val LINE_TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIME = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META = Regex("""^\[([A-Za-z#]+)\s*:(.*)]$""")
    private val SECTION_MARKER = Regex("""^[\[(].{1,40}[\])]$""")

    /** Średni czas śpiewania jednej sylaby — baza estymatora. */
    private const val MS_PER_SYLLABLE = 270.0
    private const val LAST_LINE_MAX_MS = 9000L
    private const val MIN_PIECE_MS = 40.0

    private class RawEntry(val timeMs: Long, val body: String, val order: Int)
    private class Piece(val startMs: Long, val text: String)
    /** [piece] = indeks fragmentu Enhanced LRC (-1 = czas estymowany). */
    private class TimedChar(val char: Char, val startMs: Double, val endMs: Double, val piece: Int)
    private class Token(val text: String, val joinNext: Boolean)

    fun parse(lrc: String, trackDurationMs: Long = 0L, sourceLabel: String = "LRC"): Lyrics? {
        var offsetMs = 0L
        val entries = ArrayList<RawEntry>()
        var order = 0

        for (rawLine in lrc.lines()) {
            val line = rawLine.trim().trimStart('﻿').trim()
            if (line.isEmpty()) continue

            val meta = META.matchEntire(line)
            if (meta != null) {
                if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                    offsetMs = meta.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: 0L
                }
                continue
            }

            var pos = 0
            val times = ArrayList<Long>(1)
            while (pos < line.length) {
                val m = LINE_TIME.find(line, pos)
                if (m == null || m.range.first != pos) break
                times += timeOf(m)
                pos = m.range.last + 1
                while (pos < line.length && line[pos] == ' ') pos++
            }
            if (times.isEmpty()) continue
            val body = line.substring(pos)
            for (t in times) entries += RawEntry(t - offsetMs, body, order++)
        }
        if (entries.isEmpty()) return null

        entries.sortWith(compareBy<RawEntry>({ it.timeMs }, { it.order }))

        val lines = ArrayList<LyricLine>()
        var wordSynced = false
        for (i in entries.indices) {
            val entry = entries[i]
            val visible = WORD_TIME.replace(entry.body, "").trim()
            if (visible.isEmpty()) continue // pusta linia = przerwa instrumentalna
            val start = max(0L, entry.timeMs)
            val boundary = boundaryAfter(entries, i, start, trackDurationMs)
            val built = if (WORD_TIME.containsMatchIn(entry.body)) {
                wordSynced = true
                buildEnhancedLine(entry.body, visible, start, boundary, offsetMs)
            } else {
                buildEstimatedLine(visible, start, boundary)
            }
            if (built != null) lines += built
        }
        if (lines.isEmpty()) return null
        return Lyrics(lines, if (wordSynced) SyncKind.WORD_SYNCED else SyncKind.LINE_SYNCED, sourceLabel)
    }

    /** Tekst bez czasów (plainLyrics): linie rozłożone na długość utworu. */
    fun fromPlainText(text: String, trackDurationMs: Long, sourceLabel: String): Lyrics? {
        val raw = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !SECTION_MARKER.matches(it) }
        if (raw.isEmpty()) return null

        val weights = raw.map { line -> tokenize(line).sumOf { Syllables.count(it.text) } + 3.0 }
        val totalWeight = weights.sum()
        val duration = if (trackDurationMs >= 30_000L) trackDurationMs.toDouble() else raw.size * 4000.0 + 10_000.0
        val intro = min(duration * 0.08, 15_000.0)
        val outro = min(duration * 0.06, 12_000.0)
        val usable = max(duration - intro - outro, raw.size * 1500.0)

        var cursor = intro
        val lines = ArrayList<LyricLine>(raw.size)
        for (i in raw.indices) {
            val span = usable * weights[i] / totalWeight
            buildEstimatedLine(raw[i], cursor.roundToLong(), (cursor + span).roundToLong())?.let { lines += it }
            cursor += span
        }
        return if (lines.isEmpty()) null else Lyrics(lines, SyncKind.UNSYNCED, sourceLabel)
    }

    // ---------------------------------------------------------------- linie

    private fun boundaryAfter(entries: List<RawEntry>, index: Int, start: Long, trackDurationMs: Long): Long {
        for (j in index + 1 until entries.size) {
            val t = entries[j].timeMs
            if (t > start) return t
        }
        return if (trackDurationMs > start + 500) min(trackDurationMs, start + LAST_LINE_MAX_MS)
        else start + LAST_LINE_MAX_MS
    }

    private fun buildEnhancedLine(
        body: String,
        visible: String,
        lineStart: Long,
        boundary: Long,
        offsetMs: Long
    ): LyricLine? {
        val pieces = ArrayList<Piece>()
        var cursor = 0
        var currentStart = lineStart
        for (m in WORD_TIME.findAll(body)) {
            val text = body.substring(cursor, m.range.first)
            if (text.isNotEmpty()) pieces += Piece(currentStart, text)
            currentStart = max(currentStart, timeOf(m) - offsetMs)
            cursor = m.range.last + 1
        }
        val tail = body.substring(cursor)
        val explicitEnd: Long? = if (tail.isBlank()) currentStart else null
        if (!tail.isBlank()) pieces += Piece(currentStart, tail)
        if (pieces.isEmpty()) return null

        val timed = ArrayList<TimedChar>(visible.length + 8)
        for (pi in pieces.indices) {
            val piece = pieces[pi]
            val start = piece.startMs.toDouble()
            val rawEnd: Double = when {
                pi + 1 < pieces.size -> pieces[pi + 1].startMs.toDouble()
                explicitEnd != null && explicitEnd > piece.startMs -> explicitEnd.toDouble()
                else -> min(boundary.toDouble(), start + estimateNaturalMs(piece.text))
            }
            val letters = piece.text.count { it.isLetterOrDigit() }
            val end = max(rawEnd, start + MIN_PIECE_MS * max(1, letters).coerceAtMost(4))
            distribute(piece.text, start, end, timed, pi)
        }
        val words = wordsFromTimedChars(timed)
        if (words.isEmpty()) return null
        return LyricLine(lineStart, words.last().endTimeMs, visible, words)
    }

    private fun buildEstimatedLine(text: String, start: Long, boundary: Long): LyricLine? {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null
        val n = tokens.size

        val weights = DoubleArray(n) { wordWeight(tokens[it].text) }
        val pauses = DoubleArray(n) { i -> if (i == n - 1) 0.0 else pauseAfter(tokens[i]) }
        val units = weights.sum() + pauses.sum()
        val syllables = tokens.sumOf { Syllables.count(it.text) }

        val gap = max(300L, boundary - start).toDouble()
        val natural = max(400.0, (syllables + pauses.sum()) * MS_PER_SYLLABLE)
        // Śpiew trwa "naturalnie" długo, ale nie dłużej niż 92% przerwy do następnej linii
        // i nie krócej niż 60% tej przerwy (o ile to nie więcej niż 1.6× tempa naturalnego).
        val sung = min(gap * 0.92, max(natural, min(gap * 0.6, natural * 1.6)))
        val msPerUnit = sung / units

        var cursor = start.toDouble()
        val words = ArrayList<LyricWord>(n)
        for (i in 0 until n) {
            val wordStart = cursor
            val wordEnd = cursor + weights[i] * msPerUnit
            val timed = ArrayList<TimedChar>(tokens[i].text.length)
            distribute(tokens[i].text, wordStart, wordEnd, timed, -1)
            words += makeWord(timed, tokens[i].joinNext)
            cursor = wordEnd + pauses[i] * msPerUnit
        }
        return LyricLine(start, words.last().endTimeMs, text, words)
    }

    // ---------------------------------------------------------------- słowa i litery

    private fun distribute(text: String, start: Double, end: Double, out: MutableList<TimedChar>, piece: Int) {
        val weights = DoubleArray(text.length) { Syllables.charWeight(text[it]) }
        val total = weights.sum()
        val span = end - start
        var acc = start
        for (k in text.indices) {
            val d = if (total > 0.0) span * weights[k] / total else 0.0
            out += TimedChar(text[k], acc, acc + d, piece)
            acc += d
        }
    }

    private fun wordsFromTimedChars(chars: List<TimedChar>): List<LyricWord> {
        val words = ArrayList<LyricWord>()
        val buffer = ArrayList<TimedChar>()
        fun flush(joinNext: Boolean) {
            if (buffer.isEmpty()) return
            words += makeWord(ArrayList(buffer), joinNext)
            buffer.clear()
        }
        for (i in chars.indices) {
            val tc = chars[i]
            when {
                tc.char.isWhitespace() -> flush(false)
                Syllables.isCjk(tc.char) -> {
                    flush(true)
                    buffer += tc
                    val nextIsSpace = i + 1 >= chars.size || chars[i + 1].char.isWhitespace()
                    flush(!nextIsSpace)
                }
                else -> buffer += tc
            }
        }
        flush(false)
        return words
    }

    private fun makeWord(chars: List<TimedChar>, joinNext: Boolean): LyricWord {
        val text = String(CharArray(chars.size) { chars[it].char })
        val lyricChars = chars.map {
            val s = it.startMs.roundToLong()
            LyricChar(it.char, s, max(s, it.endMs.roundToLong()))
        }
        return LyricWord(
            text = text,
            startTimeMs = lyricChars.first().startTimeMs,
            endTimeMs = lyricChars.last().endTimeMs,
            chars = lyricChars,
            syllables = syllablesOf(chars, text),
            joinNext = joinNext
        )
    }

    /**
     * Sylaby słowa: w Enhanced LRC granice wyznaczają znaczniki czasu z pliku
     * (np. `<..>ne<..>on` = 2 sylaby), w pozostałych przypadkach heurystyka samogłosek.
     */
    private fun syllablesOf(chars: List<TimedChar>, text: String): List<IntRange> {
        val first = chars.firstOrNull()?.piece ?: -1
        if (first < 0 || chars.all { it.piece == first }) return Syllables.ranges(text)
        val out = ArrayList<IntRange>()
        var start = 0
        for (i in 1 until chars.size) {
            if (chars[i].piece != chars[i - 1].piece) {
                out += start until i
                start = i
            }
        }
        out += start until chars.size
        return out
    }

    private fun tokenize(text: String): List<Token> {
        val out = ArrayList<Token>()
        val sb = StringBuilder()
        fun flush(joinNext: Boolean) {
            if (sb.isEmpty()) return
            out += Token(sb.toString(), joinNext)
            sb.setLength(0)
        }
        for (i in text.indices) {
            val c = text[i]
            when {
                c.isWhitespace() -> flush(false)
                Syllables.isCjk(c) -> {
                    flush(true)
                    sb.append(c)
                    val nextIsSpace = i + 1 >= text.length || text[i + 1].isWhitespace()
                    flush(!nextIsSpace)
                }
                else -> sb.append(c)
            }
        }
        flush(false)
        return out
    }

    private fun wordWeight(word: String): Double {
        val letters = word.count { it.isLetterOrDigit() }
        return max(0.3, 0.7 * Syllables.count(word) + 0.3 * letters / 3.0)
    }

    /** Pauza po słowie, w jednostkach "sylab". */
    private fun pauseAfter(token: Token): Double {
        if (token.joinNext) return 0.05
        return when (token.text.lastOrNull()) {
            ',', ';', ':' -> 0.55
            '.', '!', '?', '…' -> 0.9
            '-', '–', '—' -> 0.35
            else -> 0.12
        }
    }

    private fun estimateNaturalMs(text: String): Double =
        max(250.0, tokenize(text).sumOf { Syllables.count(it.text) } * MS_PER_SYLLABLE)

    private fun timeOf(m: MatchResult): Long {
        val minutes = m.groupValues[1].toLong()
        val seconds = m.groupValues[2].toLong()
        val fraction = m.groupValues[3]
        val ms = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return (minutes * 60 + seconds) * 1000 + ms
    }
}
