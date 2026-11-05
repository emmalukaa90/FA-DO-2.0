package com.fado.karaoke.lyrics

import com.fado.karaoke.model.Lyrics
import com.fado.karaoke.model.TrackInfo

/**
 * Wbudowane demo (autorski tekst FA-DO) do testowania piłki i wypełniania liter
 * bez Spotify i bez internetu. Pierwsza zwrotka: Enhanced LRC (czasy sylab),
 * druga: zwykłe LRC — pokazuje estymator liter.
 */
object DemoSong {

    const val DURATION_MS = 42_000L

    private const val LRC = """
[ti:FA-DO Demo]
[ar:FA-DO]
[00:02.00]<00:02.00>Śpie<00:02.35>waj <00:02.70>ze <00:03.00>mną, <00:03.60>gdy <00:03.90>mia<00:04.20>sto <00:04.55>śpi<00:05.60>
[00:06.00]<00:06.00>Świa<00:06.35>tła <00:06.70>mi<00:06.95>ja<00:07.20>ją <00:07.55>nas <00:07.90>jak <00:08.25>nu<00:08.60>ty<00:09.60>
[00:10.00]<00:10.00>Pił<00:10.30>ka <00:10.60>ska<00:10.90>cze <00:11.30>sło<00:11.60>wo <00:12.00>po <00:12.30>sło<00:12.60>wie<00:13.60>
[00:14.00]<00:14.00>Każ<00:14.30>da <00:14.60>li<00:14.85>te<00:15.10>ra <00:15.50>świe<00:15.85>ci <00:16.20>jak <00:16.55>ne<00:16.90>on<00:18.00>
[00:18.20]
[00:23.00]FA-DO, FA-DO, śpiewaj głośniej
[00:27.00]Droga niesie nas aż po horyzont
[00:31.00]Zostań w rytmie jeszcze jeden raz
[00:35.00]Każda nuta prowadzi nas do domu
[00:39.50]
"""

    val track = TrackInfo(
        id = "fado-demo",
        title = "FA-DO Demo",
        artist = "FA-DO",
        album = "",
        durationMs = DURATION_MS,
        packageName = "com.fado.karaoke"
    )

    val lyrics: Lyrics by lazy {
        EnhancedLrcParser.parse(LRC, DURATION_MS, "Demo")
            ?: throw IllegalStateException("Demo LRC nie dało się sparsować")
    }
}
