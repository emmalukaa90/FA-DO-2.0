package com.fado.karaoke.lyrics

/** Czyści tytuły ze Spotify ("- Remastered 2011", "(feat. X)"), żeby LRCLIB częściej trafiał. */
internal object TitleCleaner {

    private val BRACKETED = Regex(
        """\s*[(\[][^)\]]*\b(feat\.?|ft\.?|with|remaster(ed)?|live|version|edit|mix|mono|stereo|bonus|deluxe|explicit|acoustic|radio|demo|instrumental|from)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val DASH_SUFFIX = Regex(
        """\s+[-–—]\s+.*\b(remaster(ed)?|live|version|edit|mix|mono|stereo|single|radio|acoustic|from|bonus|demo|recorded)\b.*$""",
        RegexOption.IGNORE_CASE
    )
    private val ARTIST_SPLIT = Regex("""\s*(,|&|;|\bfeat\.?|\bft\.)\s*""", RegexOption.IGNORE_CASE)

    fun clean(title: String): String {
        var t = BRACKETED.replace(title, "")
        t = DASH_SUFFIX.replace(t, "")
        return t.trim().ifEmpty { title.trim() }
    }

    fun primaryArtist(artist: String): String =
        artist.split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()
}
