package com.fado.karaoke.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.fado.karaoke.lyrics.DemoSong
import com.fado.karaoke.lyrics.LyricsRepository
import com.fado.karaoke.lyrics.LyricsResult
import com.fado.karaoke.model.TrackInfo
import com.fado.karaoke.sync.SpotifySyncEngine

/**
 * Spina całość: silnik synchronizacji → zmiana utworu → pobranie tekstu → [FadoState].
 * Działa w jednym procesie dla telefonu, usługi powiadomień i Android Auto.
 */
object FadoCore : SpotifySyncEngine.Listener {

    private val lock = Any()

    @Volatile
    private var initialized = false

    private lateinit var repository: LyricsRepository
    private lateinit var engine: SpotifySyncEngine
    private val mainHandler = Handler(Looper.getMainLooper())

    val syncEngine: SpotifySyncEngine get() = engine

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val app = context.applicationContext
            FadoSettings.init(app)
            repository = LyricsRepository(app)
            engine = SpotifySyncEngine(app, this)
            initialized = true
        }
    }

    fun ensureSyncStarted() = onMain { engine.start() }

    fun reloadLyrics() = onMain {
        val track = FadoState.track ?: return@onMain
        if (FadoState.isDemo) return@onMain
        requestLyrics(track, force = true)
    }

    fun startDemo() = onMain {
        FadoState.demoStartElapsedMs = SystemClock.elapsedRealtime()
        FadoState.track = DemoSong.track
        FadoState.lyricsState = LyricsState.Ready(DemoSong.track, DemoSong.lyrics)
        FadoState.notifyChanged()
    }

    fun stopDemo() = onMain {
        if (!FadoState.isDemo) return@onMain
        FadoState.demoStartElapsedMs = -1L
        val track = engine.currentTrack
        FadoState.track = track
        if (track == null) {
            FadoState.lyricsState = LyricsState.Idle
            FadoState.notifyChanged()
        } else {
            requestLyrics(track, force = false)
        }
    }

    fun toggleDemo() = if (FadoState.isDemo) stopDemo() else startDemo()

    // --------------------------------------------------------- SpotifySyncEngine.Listener

    override fun onTrackChanged(track: TrackInfo?) {
        if (track != null && FadoState.isDemo) FadoState.demoStartElapsedMs = -1L
        if (FadoState.isDemo) return
        FadoState.track = track
        if (track == null) {
            FadoState.lyricsState = LyricsState.Idle
            FadoState.notifyChanged()
            return
        }
        requestLyrics(track, force = false)
    }

    override fun onTrackUpdated(track: TrackInfo) {
        if (FadoState.isDemo) return
        val previous = FadoState.track
        FadoState.track = track
        val state = FadoState.lyricsState
        val durationArrived = previous != null && previous.durationMs <= 0 && track.durationMs > 0
        if (durationArrived && state !is LyricsState.Ready && state !is LyricsState.Loading) {
            requestLyrics(track, force = false)
        } else {
            FadoState.notifyChanged()
        }
    }

    override fun onPlaybackStarted() {
        // Prawdziwa muzyka ma pierwszeństwo przed demem.
        if (FadoState.isDemo && engine.currentTrack != null) stopDemo()
    }

    override fun onStateChanged() {
        FadoState.notifyChanged()
    }

    // --------------------------------------------------------- wewnętrzne

    private fun requestLyrics(track: TrackInfo, force: Boolean) {
        FadoState.lyricsState = LyricsState.Loading(track)
        FadoState.notifyChanged()
        repository.load(track, force) { result ->
            val current = FadoState.track
            if (FadoState.isDemo || !track.sameIdentity(current) || current == null) return@load
            FadoState.lyricsState = when (result) {
                is LyricsResult.Found -> LyricsState.Ready(current, result.lyrics)
                LyricsResult.NotFound -> LyricsState.NotFound(current)
                LyricsResult.Instrumental -> LyricsState.Instrumental(current)
                is LyricsResult.Error -> LyricsState.Failed(current, result.message)
            }
            FadoState.notifyChanged()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }
}
