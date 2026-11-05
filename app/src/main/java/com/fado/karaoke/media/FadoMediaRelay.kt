package com.fado.karaoke.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.fado.karaoke.R
import com.fado.karaoke.core.ConnectionState
import com.fado.karaoke.core.FadoCore
import com.fado.karaoke.core.FadoSettings
import com.fado.karaoke.core.FadoState
import com.fado.karaoke.core.LyricsState
import com.fado.karaoke.model.Lyrics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "Przekaźnik" do kafelka multimediów Android Auto.
 *
 * FA-DO wystawia własną sesję multimediów, która odzwierciedla odtwarzanie Spotify:
 * - tytuł = fragment tekstu śpiewany teraz, wykonawca = następny fragment,
 * - okładka i stan play/pauza/pozycja = jak w Spotify,
 * - przyciski z kafelka (play, pauza, następny, poprzedni, przewijanie) trafiają do Spotify.
 * Dzięki temu Mapy Google zostają w dużym kafelku, a tekst jest widoczny obok.
 *
 * Działa tylko, gdy Android Auto jest połączony z [FadoMediaBrowserService]. Wątek główny.
 */
object FadoMediaRelay {

    private const val TAG = "FadoMedia"
    private const val TICK_MS = 120L
    /** Tekst zmienia się minimalnie przed wokalem — host potrzebuje chwili na odświeżenie. */
    private const val TEXT_LEAD_MS = 180.0
    private const val ART_MAX_PX = 320

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var session: MediaSession? = null
    private var connections = 0

    private var chunksFor: Lyrics? = null
    private var chunks: List<LyricChunker.Chunk> = emptyList()
    private var lastMetadataKey: String? = null
    private var artSource: Bitmap? = null
    private var artScaled: Bitmap? = null

    private var publishedPlaying: Boolean? = null
    private var publishedPositionMs = 0L
    private var publishedAtMs = 0L
    private var publishedSpeed = 1.0
    private var publishedTrackId: String? = null

    /** true, gdy Android Auto korzysta z kafelka multimediów FA-DO. */
    val isAttached: Boolean get() = connections > 0

    fun attach(context: Context): MediaSession.Token {
        val app = context.applicationContext
        appContext = app
        FadoCore.init(app)
        FadoCore.ensureSyncStarted()
        val s = session ?: MediaSession(app, "FADO-Lyrics").also {
            it.setCallback(callback, handler)
            session = it
        }
        connections++
        if (connections == 1) {
            s.isActive = true
            lastMetadataKey = null
            publishedPlaying = null
            handler.post(tick)
            FadoState.notifyChanged()
        }
        return s.sessionToken
    }

    fun detach() {
        if (connections == 0) return
        connections--
        if (connections > 0) return
        handler.removeCallbacks(tick)
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        artSource = null
        artScaled = null
        FadoState.notifyChanged()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (connections <= 0) return
            try {
                publish()
            } catch (e: Exception) {
                Log.w(TAG, "Aktualizacja kafelka nie powiodła się", e)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------------ metadane (tekst)

    private fun publish() {
        val s = session ?: return
        val ctx = appContext ?: return
        val now = SystemClock.elapsedRealtime()
        val track = FadoState.track
        val position = FadoState.positionAt(now.toDouble())

        val title: String
        val subtitle: String
        if (track == null) {
            title = ctx.getString(
                if (FadoState.connection == ConnectionState.NO_ACCESS) R.string.media_open_phone else R.string.media_no_track
            )
            subtitle = ctx.getString(R.string.app_name)
        } else {
            val lyrics = FadoState.currentLyrics()
            if (lyrics != null) {
                if (lyrics !== chunksFor) {
                    chunks = LyricChunker.chunks(lyrics)
                    chunksFor = lyrics
                }
                val t = position + FadoSettings.offsetMs + TEXT_LEAD_MS
                val card = LyricChunker.cardAt(chunks, t)
                if (card == null) {
                    title = "♪ " + track.title
                    subtitle = chunks.firstOrNull()?.text ?: track.artist
                } else {
                    title = card.title
                    subtitle = card.subtitle ?: (track.title + " — " + track.artist)
                }
            } else {
                title = track.title
                subtitle = when (FadoState.lyricsState) {
                    is LyricsState.Loading -> ctx.getString(R.string.media_loading)
                    is LyricsState.NotFound -> ctx.getString(R.string.media_not_found)
                    is LyricsState.Instrumental -> ctx.getString(R.string.media_instrumental)
                    is LyricsState.Failed -> ctx.getString(R.string.media_not_found)
                    else -> track.artist
                }
            }
        }

        val art = FadoState.artwork
        if (art !== artSource) {
            artSource = art
            artScaled = art?.let { scaleArt(it) }
        }

        val key = title + "\u0001" + subtitle + "\u0001" + (track?.identity ?: "") + "\u0001" + System.identityHashCode(artScaled)
        if (key != lastMetadataKey) {
            lastMetadataKey = key
            val b = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track?.id ?: "fado-idle")
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putString(
                    MediaMetadata.METADATA_KEY_ALBUM,
                    if (track == null) "" else track.title + " — " + track.artist
                )
                .putLong(MediaMetadata.METADATA_KEY_DURATION, track?.durationMs ?: 0L)
            artScaled?.let {
                b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                b.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
            s.setMetadata(b.build())
        }

        publishPlaybackState(s, track?.id, position.toLong(), now)
    }

    // ------------------------------------------------------------------ stan odtwarzania

    private fun publishPlaybackState(s: MediaSession, trackId: String?, positionMs: Long, now: Long) {
        val playing = FadoState.isPlaying
        val speed = if (playing) max(0.01, FadoState.clock.snapshot.speed) else 0.0
        // Host sam ekstrapoluje pozycję — wysyłamy nowy stan tylko przy zmianie lub dryfie > 1,2 s.
        val expected = publishedPositionMs + if (publishedPlaying == true) (now - publishedAtMs) * publishedSpeed else 0.0
        val drift = abs(expected - positionMs)
        val changed = playing != publishedPlaying || trackId != publishedTrackId || drift > 1200.0
        if (!changed) return

        publishedPlaying = playing
        publishedTrackId = trackId
        publishedPositionMs = positionMs
        publishedAtMs = now
        publishedSpeed = speed
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(state, positionMs, speed.toFloat(), now)
                .build()
        )
    }

    private val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_STOP

    // ------------------------------------------------------------------ przyciski → Spotify

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = forward { it.play() }
        override fun onPause() = forward { it.pause() }
        override fun onStop() = forward { it.pause() }
        override fun onSkipToNext() = forward { it.skipToNext() }
        override fun onSkipToPrevious() = forward { it.skipToPrevious() }
        override fun onSeekTo(pos: Long) = forward { it.seekTo(pos) }
        override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) = forward { it.play() }
    }

    private inline fun forward(action: (android.media.session.MediaController.TransportControls) -> Unit) {
        val controls = FadoCore.syncEngine.sourceTransportControls()
        if (controls == null) {
            Log.w(TAG, "Brak sesji Spotify do sterowania (dostęp do powiadomień?)")
            return
        }
        try {
            action(controls)
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się przekazać polecenia do odtwarzacza", e)
        }
        // Wymuś szybkie odświeżenie stanu po akcji.
        publishedPlaying = null
    }

    private fun scaleArt(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= ART_MAX_PX && h <= ART_MAX_PX) return src
        val scale = ART_MAX_PX / max(w, h).toFloat()
        return Bitmap.createScaledBitmap(src, max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()), true)
    }
}
