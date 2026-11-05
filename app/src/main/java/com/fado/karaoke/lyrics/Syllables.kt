package com.fado.karaoke.lyrics

/**
 * Prosta, wielojęzyczna heurystyka sylab (PL/EN/DE/ES/FR/RU...).
 * Sylaba = grupa samogłosek; wystarcza do rozłożenia czasu śpiewu
 * i do wyznaczenia punktów lądowania piłki.
 */
internal object Syllables {

    private const val VOWELS = "aeiouyąęóàáâãäåæèéêëìíîïòôõöøùúûüýÿœаеёиоуыэюяіїє"

    fun isVowel(c: Char): Boolean = VOWELS.indexOf(c.lowercaseChar()) >= 0

    /** Znaki CJK są traktowane jak osobne "słowa" (brak spacji w tekście). */
    fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x3040..0x30FF ||   // hiragana, katakana
            code in 0x3400..0x4DBF ||      // CJK ext A
            code in 0x4E00..0x9FFF ||      // CJK
            code in 0xF900..0xFAFF ||      // CJK compatibility
            code in 0xFF66..0xFF9F         // katakana połówkowa
    }

    fun count(word: String): Int {
        var groups = 0
        var inVowel = false
        for (c in word) {
            val v = isVowel(c)
            if (v && !inVowel) groups++
            inVowel = v
        }
        return if (groups == 0) 1 else groups
    }

    /** Waga litery przy rozkładaniu czasu: samogłoski są "przeciągane" dłużej. */
    fun charWeight(c: Char): Double = when {
        c.isWhitespace() -> 0.0
        isVowel(c) -> 1.6
        c.isLetterOrDigit() -> 1.0
        c == '\'' || c == '’' || c == '-' -> 0.25
        else -> 0.15
    }

    /**
     * Dzieli słowo na sylaby (zakresy indeksów znaków).
     * Reguła: jedna spółgłoska między samogłoskami idzie do następnej sylaby (ko|ta),
     * przy dwóch i więcej pierwsza zostaje w bieżącej (kos|ta).
     */
    fun ranges(word: String): List<IntRange> {
        val n = word.length
        if (n == 0) return emptyList()
        val groups = ArrayList<IntRange>()
        var i = 0
        while (i < n) {
            if (isVowel(word[i])) {
                var j = i
                while (j + 1 < n && isVowel(word[j + 1])) j++
                groups += i..j
                i = j + 1
            } else {
                i++
            }
        }
        if (groups.size <= 1) return listOf(0 until n)

        val result = ArrayList<IntRange>(groups.size)
        var segmentStart = 0
        for (g in 0 until groups.size - 1) {
            val vowelEnd = groups[g].last
            val nextVowel = groups[g + 1].first
            val consonants = nextVowel - vowelEnd - 1
            val cut = if (consonants <= 1) vowelEnd + 1 else vowelEnd + 2
            result += segmentStart until cut
            segmentStart = cut
        }
        result += segmentStart until n
        return result
    }
}
