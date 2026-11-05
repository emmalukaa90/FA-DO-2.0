package com.fado.karaoke.core

import com.fado.karaoke.lyrics.DemoSong
import com.fado.karaoke.model.Lyrics
import com.fado.karaoke.model.TrackInfo
import com.fado.karaoke.sync.PlaybackClock
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max

enum class ConnectionState {
    /** Brak dostępu do powiadomień i brak broadcastów Spotify. */
    NO_ACCESS,

    /** Dostęp jest, ale nic nie gra. */
    WAITING_FOR_PLAYER,

    /** MediaSession Spotify — najdokładniejsze źródło. */
    SPOTIFY_SESSION,

    /** Inny odtwarzacz (fallback, gdy Spotify nie gra). */
    OTHER_PLAYER,

    /** Tylko broadcasty Spotify ("Device Broadcast Status"). */
    SPOTIFY_BROADCAST
}

sealed class LyricsState {
    object Idle : LyricsState()
    data class Loading(val track: TrackInfo) : LyricsState()
    data class Ready(val track: TrackInfo, val lyrics: Lyrics) : LyricsState()
    data class NotFound(val track: TrackInfo) : LyricsState()
    data class Instrumental(val track: TrackInfo) : LyricsState()
    data class Failed(val track: TrackInfo, val message: String) : LyricsState()
}

/**
 * Wspólny stan aplikacji, czytany przez oba renderery (telefon i auto) w każdej klatce.
 * Zapisy: wątek główny. Odczyty: dowolny wątek (pola @Volatile, obiekty niezmienne).
 */
object FadoState {

    val clock = PlaybackClock()

    @Volatile
    var track: TrackInfo? = null
        internal set

    @Volatile
    var lyricsState: LyricsState = LyricsState.Idle
        internal set

    @Volatile
    var connection: ConnectionState = ConnectionState.NO_ACCESS
        internal set

    @Volatile
    var playerPackage: String? = null
        internal set

    /** Okładka bieżącego utworu z sesji Spotify (dla kafelka multimediów). */
    @Volatile
    var artwork: android.graphics.Bitmap? = null
        internal set

    /** >= 0 gdy działa demo (czas startu w zegarze elapsedRealtime). */
    @Volatile
    var demoStartElapsedMs: Long = -1L
        internal set

    val isDemo: Boolean get() = demoStartElapsedMs >= 0

    val isPlaying: Boolean get() = isDemo || clock.snapshot.isPlaying

    /** Pozycja odtwarzania (ms) w chwili [elapsedMs] (zegar SystemClock.elapsedRealtime). */
    fun positionAt(elapsedMs: Double): Double {
        val demoStart = demoStartElapsedMs
        if (demoStart >= 0) {
            val d = DemoSong.DURATION_MS.toDouble()
            val p = (elapsedMs - demoStart) % d
            return if (p < 0) p + d else p
        }
        val p = clock.positionAt(elapsedMs)
        val duration = track?.durationMs ?: 0L
        return if (duration > 0) p.coerceIn(0.0, duration.toDouble()) else max(0.0, p)
    }

    /** Teksty pasujące do bieżącego utworu albo null. */
    fun currentLyrics(): Lyrics? {
        val st = lyricsState
        val t = track
        return if (st is LyricsState.Ready && st.track.sameIdentity(t)) st.lyrics else null
    }

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Wywoływać na wątku głównym. */
    fun notifyChanged() {
        for (l in listeners) l()
    }
}
