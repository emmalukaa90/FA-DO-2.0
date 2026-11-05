package com.fado.karaoke.lyrics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fado.karaoke.BuildConfig
import com.fado.karaoke.model.Lyrics
import com.fado.karaoke.model.TrackInfo
import com.fado.karaoke.util.TextUtil
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToLong

sealed class LyricsResult {
    data class Found(val lyrics: Lyrics) : LyricsResult()
    object NotFound : LyricsResult()
    object Instrumental : LyricsResult()
    data class Error(val message: String) : LyricsResult()
}

/**
 * Pobiera teksty z LRCLIB, z cache w pamięci i na dysku.
 *
 * Strategia dopasowania (od najdokładniejszej):
 * 1. /api/get z pełną sygnaturą (wykonawca, tytuł, album, czas trwania),
 * 2. /api/search po tytule i wykonawcy (oryginalny i "oczyszczony" tytuł),
 * 3. /api/search?q=… (wyszukiwanie swobodne).
 * Wybierany jest wynik z synchronizacją i czasem trwania najbliższym utworowi.
 */
class LyricsRepository(context: Context) {

    private val cache = LyricsCache(File(context.filesDir, "lyrics_cache"))
    private val client = LrclibClient("FA-DO/${BuildConfig.VERSION_NAME} (Android karaoke lyrics app)")
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FADO-Lyrics").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Dostęp wyłącznie z wątku executora. */
    private val memory = object : LinkedHashMap<String, LyricsResult>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsResult>?): Boolean = size > 40
    }

    /** Wynik trafia do [callback] na wątku głównym. */
    fun load(track: TrackInfo, forceRefresh: Boolean, callback: (LyricsResult) -> Unit) {
        executor.execute {
            val result = try {
                resolve(track, forceRefresh)
            } catch (e: Exception) {
                Log.w(TAG, "Błąd pobierania tekstu", e)
                LyricsResult.Error(e.message ?: e.javaClass.simpleName)
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun resolve(track: TrackInfo, forceRefresh: Boolean): LyricsResult {
        val key = track.lyricsKey
        val cached = cache.read(key)
        if (!forceRefresh) {
            memory[key]?.let { return it }
            if (cached != null && !isStale(cached)) {
                val fromCache = toResult(cached, track)
                memory[key] = fromCache
                return fromCache
            }
        }

        val fetched = try {
            fetchWithRetry(track)
        } catch (e: IOException) {
            // Brak sieci: lepszy stary wpis niż nic.
            if (cached != null) return toResult(cached, track).also { memory[key] = it }
            throw e
        }
        cache.write(key, fetched)
        return toResult(fetched, track).also { memory[key] = it }
    }

    private fun isStale(entry: LyricsCache.Entry): Boolean =
        entry.status == LyricsCache.Status.NOT_FOUND &&
            System.currentTimeMillis() - entry.savedAtMs > NOT_FOUND_TTL_MS

    private fun toResult(entry: LyricsCache.Entry, track: TrackInfo): LyricsResult {
        return when (entry.status) {
            LyricsCache.Status.INSTRUMENTAL -> LyricsResult.Instrumental
            LyricsCache.Status.NOT_FOUND -> LyricsResult.NotFound
            LyricsCache.Status.FOUND -> {
                val synced = entry.synced?.let { EnhancedLrcParser.parse(it, track.durationMs, entry.source) }
                val lyrics = synced ?: entry.plain?.let { EnhancedLrcParser.fromPlainText(it, track.durationMs, entry.source) }
                if (lyrics != null) LyricsResult.Found(lyrics) else LyricsResult.NotFound
            }
        }
    }

    @Throws(IOException::class)
    private fun fetchWithRetry(track: TrackInfo): LyricsCache.Entry {
        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                return fetch(track)
            } catch (e: IOException) {
                lastError = e
                if (attempt == 0) Thread.sleep(1500)
            }
        }
        throw lastError ?: IOException("Nieznany błąd sieci")
    }

    @Throws(IOException::class)
    private fun fetch(track: TrackInfo): LyricsCache.Entry {
        val durationSec: Long? = if (track.durationMs > 0) (track.durationMs / 1000.0).roundToLong() else null
        var fallback: LrclibClient.Record? = null

        // 1. Dokładna sygnatura
        if (track.album.isNotBlank() && durationSec != null) {
            val exact = client.get(track.artist, track.title, track.album, durationSec)
            if (exact != null) {
                if (exact.syncedLyrics != null) return found(exact)
                fallback = exact
            }
        }

        // 2. Wyszukiwanie po tytule (oryginalnym i oczyszczonym)
        val titles = linkedSetOf(track.title, TitleCleaner.clean(track.title)).filter { it.isNotBlank() }
        for (title in titles) {
            val best = pickBest(client.search(title, track.artist), track, durationSec)
            if (best != null) {
                if (best.syncedLyrics != null) return found(best)
                if (fallback == null) fallback = best
            }
        }

        // 3. Wyszukiwanie swobodne
        val query = TitleCleaner.primaryArtist(track.artist) + " " + TitleCleaner.clean(track.title)
        val loose = pickBest(client.searchQuery(query.trim()), track, durationSec)
        if (loose != null) {
            if (loose.syncedLyrics != null) return found(loose)
            if (fallback == null) fallback = loose
        }

        val fb = fallback
        return when {
            fb == null -> entry(LyricsCache.Status.NOT_FOUND, null, null)
            fb.plainLyrics != null -> entry(LyricsCache.Status.FOUND, null, fb.plainLyrics)
            fb.instrumental -> entry(LyricsCache.Status.INSTRUMENTAL, null, null)
            else -> entry(LyricsCache.Status.NOT_FOUND, null, null)
        }
    }

    private fun pickBest(
        candidates: List<LrclibClient.Record>,
        track: TrackInfo,
        durationSec: Long?
    ): LrclibClient.Record? {
        val wantTitle = TextUtil.normalize(TitleCleaner.clean(track.title))
        val wantArtist = TextUtil.normalize(TitleCleaner.primaryArtist(track.artist))
        var best: LrclibClient.Record? = null
        var bestScore = Int.MIN_VALUE
        for (c in candidates) {
            if (c.syncedLyrics == null && c.plainLyrics == null && !c.instrumental) continue
            var score = 0
            if (c.syncedLyrics != null) score += 100
            else if (c.plainLyrics != null) score += 10
            if (durationSec != null && c.durationSec > 0) {
                val diff = abs(c.durationSec - durationSec)
                score += when {
                    diff <= 2 -> 60
                    diff <= 5 -> 30
                    diff <= 10 -> 5
                    diff <= 20 -> -30
                    else -> -200 // inna wersja utworu
                }
            }
            val title = TextUtil.normalize(TitleCleaner.clean(c.trackName))
            if (title == wantTitle) score += 30 else if (title.contains(wantTitle) || wantTitle.contains(title)) score += 10
            val artist = TextUtil.normalize(c.artistName)
            if (wantArtist.isNotEmpty() && (artist.contains(wantArtist) || wantArtist.contains(artist))) score += 25 else score -= 40
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return if (bestScore > -60) best else null
    }

    private fun found(r: LrclibClient.Record): LyricsCache.Entry =
        entry(LyricsCache.Status.FOUND, r.syncedLyrics, r.plainLyrics)

    private fun entry(status: LyricsCache.Status, synced: String?, plain: String?) =
        LyricsCache.Entry(status, synced, plain, "LRCLIB", System.currentTimeMillis())

    companion object {
        private const val TAG = "FadoLyrics"
        private const val NOT_FOUND_TTL_MS = 24L * 60 * 60 * 1000
    }
}
