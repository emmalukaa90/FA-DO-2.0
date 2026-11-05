package com.fado.karaoke.sync

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.fado.karaoke.core.ConnectionState
import com.fado.karaoke.core.FadoState
import com.fado.karaoke.model.TrackInfo

/**
 * Silnik synchronizacji ze Spotify — dwie niezależne warstwy wejścia:
 *
 * 1. **MediaSessionManager** (wymaga dostępu do powiadomień): blokuje się na sesji
 *    `com.spotify.music`, a gdy Spotify nie gra — na innym aktywnym odtwarzaczu.
 *    Daje pozycję + `lastPositionUpdateTime` + prędkość, czyli najdokładniejszy zegar.
 * 2. **Broadcasty Spotify** (`metadatachanged`, `playbackstatechanged`): działają nawet
 *    bez dostępu do powiadomień, jeśli w Spotify włączono "Device Broadcast Status".
 *    Gdy sesja Spotify jest dostępna, broadcast służy tylko jako szybki sygnał do
 *    ponownego odczytu stanu sesji (sesja pozostaje źródłem prawdy — brak "skakania").
 *
 * Trzecia warstwa (interpolacja 60/120 FPS) to [PlaybackClock] + renderer.
 * Wszystkie metody: wątek główny.
 */
class SpotifySyncEngine(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onTrackChanged(track: TrackInfo?)
        fun onTrackUpdated(track: TrackInfo)
        fun onPlaybackStarted()
        fun onStateChanged()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val listenerComponent: ComponentName = NotificationAccess.component(context)
    private val sessionManager: MediaSessionManager? = context.getSystemService(MediaSessionManager::class.java)

    private var started = false
    private var sessionsListenerRegistered = false
    private var receiverRegistered = false
    private var controller: MediaController? = null
    private var lastBroadcastTrack: TrackInfo? = null
    private var wasPlaying = false

    var currentTrack: TrackInfo? = null
        private set

    /** Sterowanie odtwarzaczem źródłowym (Spotify) — używane przez kafelek multimediów FA-DO. */
    fun sourceTransportControls(): MediaController.TransportControls? = controller?.transportControls

    fun start() {
        if (!started) {
            started = true
            registerSpotifyBroadcasts()
            handler.postDelayed(resyncRunnable, RESYNC_INTERVAL_MS)
        }
        attachToSessions()
    }

    fun onListenerConnected() = start()

    fun onListenerDisconnected() {
        detachSessions()
        updateConnection()
    }

    // ------------------------------------------------------------------ MediaSession

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onActiveSessionsChanged(controllers)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val c = controller ?: return
            applyMetadata(metadata, c.packageName)
            applyPlaybackState(c.playbackState)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            applyPlaybackState(state)
            // Zmiana play/pauza może oznaczać, że warto przełączyć się na inny odtwarzacz.
            refreshSessions()
        }

        override fun onSessionDestroyed() {
            switchController(null)
            refreshSessions()
        }
    }

    private fun attachToSessions() {
        if (sessionsListenerRegistered) {
            refreshSessions()
            return
        }
        val msm = sessionManager
        if (msm == null || !NotificationAccess.isGranted(context)) {
            updateConnection()
            return
        }
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            sessionsListenerRegistered = true
            onActiveSessionsChanged(msm.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            Log.w(TAG, "Brak dostępu do sesji multimediów", e)
            sessionsListenerRegistered = false
            updateConnection()
        }
    }

    private fun refreshSessions() {
        if (!sessionsListenerRegistered) return
        try {
            onActiveSessionsChanged(sessionManager?.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            detachSessions()
            updateConnection()
        }
    }

    private fun detachSessions() {
        if (sessionsListenerRegistered) {
            try {
                sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (_: Exception) {
            }
            sessionsListenerRegistered = false
        }
        switchController(null)
    }

    private fun isPlaying(c: MediaController): Boolean =
        c.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        // Własna sesja FA-DO (kafelek multimediów) odzwierciedla Spotify — nigdy jej nie czytamy.
        val ownPackage = context.packageName
        val list = controllers.orEmpty().filter { it.packageName != ownPackage }
        val spotify = list.filter { it.packageName == TrackInfo.SPOTIFY_PACKAGE }
        val best = spotify.firstOrNull { isPlaying(it) }
            ?: list.firstOrNull { isPlaying(it) }
            ?: spotify.firstOrNull()
            ?: list.firstOrNull()

        val current = controller
        val currentInList = current?.let { cur -> list.firstOrNull { it.sessionToken == cur.sessionToken } }
        if (currentInList != null && best != null && best.sessionToken != currentInList.sessionToken) {
            val currentPlaying = isPlaying(currentInList)
            val bestPlaying = isPlaying(best)
            val spotifyTakesOver = bestPlaying && best.packageName == TrackInfo.SPOTIFY_PACKAGE &&
                currentInList.packageName != TrackInfo.SPOTIFY_PACKAGE
            // Zostań przy bieżącym odtwarzaczu, chyba że inny faktycznie gra (Spotify ma pierwszeństwo).
            val keepCurrent = (currentPlaying && !spotifyTakesOver) || !bestPlaying
            if (keepCurrent) {
                updateConnection()
                return
            }
        }
        if (best == null) {
            if (current != null) switchController(null)
            updateConnection()
            return
        }
        if (current != null && current.sessionToken == best.sessionToken) {
            updateConnection()
            return
        }
        switchController(best)
    }

    private fun switchController(newController: MediaController?) {
        controller?.let {
            try {
                it.unregisterCallback(controllerCallback)
            } catch (_: Exception) {
            }
        }
        controller = newController
        if (newController != null) {
            newController.registerCallback(controllerCallback, handler)
            applyMetadata(newController.metadata, newController.packageName)
            applyPlaybackState(newController.playbackState)
        } else {
            FadoState.artwork = null
            setTrack(lastBroadcastTrack)
        }
        updateConnection()
    }

    private fun applyMetadata(md: MediaMetadata?, packageName: String) {
        if (md == null) return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        if (title.isBlank()) return
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        FadoState.artwork = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        setTrack(TrackInfo.create(mediaId, title, artist, album, duration, packageName))
    }

    private fun applyPlaybackState(state: PlaybackState?) {
        if (state == null) return
        val playing = state.state == PlaybackState.STATE_PLAYING
        val now = SystemClock.elapsedRealtime()
        val position = state.position
        if (position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
            FadoState.clock.setPlaying(playing, now)
        } else {
            val updateTime = state.lastPositionUpdateTime.takeIf { it in 1..now } ?: now
            FadoState.clock.update(position, updateTime, state.playbackSpeed, playing, now)
        }
        onPlayingChanged(playing)
    }

    // ------------------------------------------------------------------ Broadcasty Spotify

    private val spotifyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            handleSpotifyBroadcast(intent)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSpotifyBroadcasts() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_SPOTIFY_METADATA)
            addAction(ACTION_SPOTIFY_PLAYBACK)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(spotifyReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(spotifyReceiver, filter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Nie udało się zarejestrować odbiornika Spotify", e)
        }
    }

    private fun handleSpotifyBroadcast(intent: Intent) {
        val extras = intent.extras ?: return
        val sessionIsSpotify = controller?.packageName == TrackInfo.SPOTIFY_PACKAGE
        when (intent.action) {
            ACTION_SPOTIFY_METADATA -> {
                val title = extras.getString("track")?.takeIf { it.isNotBlank() } ?: return
                val raw = extras.number("length") ?: 0L
                // Starsze wersje Spotify podają sekundy, nowsze milisekundy.
                val durationMs = if (raw in 1L..5000L) raw * 1000L else raw
                val track = TrackInfo.create(
                    extras.getString("id"),
                    title,
                    extras.getString("artist") ?: "",
                    extras.getString("album") ?: "",
                    durationMs,
                    TrackInfo.SPOTIFY_PACKAGE
                )
                lastBroadcastTrack = track
                when {
                    sessionIsSpotify -> controller?.let { applyPlaybackState(it.playbackState) }
                    controller == null -> {
                        setTrack(track)
                        applyBroadcastPlayback(extras)
                    }
                }
            }

            ACTION_SPOTIFY_PLAYBACK -> {
                when {
                    sessionIsSpotify -> controller?.let { applyPlaybackState(it.playbackState) }
                    controller == null -> applyBroadcastPlayback(extras)
                }
            }
        }
        updateConnection()
    }

    private fun applyBroadcastPlayback(extras: Bundle) {
        if (!extras.containsKey("playing") && !extras.containsKey("playbackPosition")) return
        val playing = extras.getBoolean("playing", FadoState.clock.snapshot.isPlaying)
        val now = SystemClock.elapsedRealtime()
        val position = extras.number("playbackPosition")
        if (position != null && position >= 0) {
            val timeSent = extras.number("timeSent") ?: 0L
            val age = if (timeSent > 0) (System.currentTimeMillis() - timeSent).coerceIn(0L, 5000L) else 0L
            FadoState.clock.update(position, now - age, 1f, playing, now)
        } else {
            FadoState.clock.setPlaying(playing, now)
        }
        onPlayingChanged(playing)
    }

    /** Odporne na typ: Spotify w różnych wersjach wysyła Int albo Long. */
    private fun Bundle.number(key: String): Long? {
        if (!containsKey(key)) return null
        @Suppress("DEPRECATION")
        val value = get(key)
        return (value as? Number)?.toLong()
    }

    // ------------------------------------------------------------------ wspólne

    private fun setTrack(track: TrackInfo?) {
        val previous = currentTrack
        currentTrack = track
        when {
            track == null -> if (previous != null) listener.onTrackChanged(null)
            previous == null || !previous.sameIdentity(track) -> listener.onTrackChanged(track)
            previous != track -> listener.onTrackUpdated(track)
        }
    }

    private fun onPlayingChanged(playing: Boolean) {
        val changed = playing != wasPlaying
        wasPlaying = playing
        if (changed) {
            if (playing) listener.onPlaybackStarted()
            listener.onStateChanged()
        }
    }

    private fun updateConnection() {
        val c = controller
        val state = when {
            c?.packageName == TrackInfo.SPOTIFY_PACKAGE -> ConnectionState.SPOTIFY_SESSION
            c != null -> ConnectionState.OTHER_PLAYER
            lastBroadcastTrack != null -> ConnectionState.SPOTIFY_BROADCAST
            !NotificationAccess.isGranted(context) -> ConnectionState.NO_ACCESS
            else -> ConnectionState.WAITING_FOR_PLAYER
        }
        val pkg = c?.packageName ?: if (lastBroadcastTrack != null) TrackInfo.SPOTIFY_PACKAGE else null
        if (state != FadoState.connection || pkg != FadoState.playerPackage) {
            FadoState.connection = state
            FadoState.playerPackage = pkg
            listener.onStateChanged()
        }
    }

    /** Okresowa kontrola: wykrywa nadany dostęp, przegapione callbacki i zmianę odtwarzacza. */
    private val resyncRunnable = object : Runnable {
        override fun run() {
            if (sessionsListenerRegistered) {
                refreshSessions()
                controller?.let { applyPlaybackState(it.playbackState) }
            } else if (NotificationAccess.isGranted(context)) {
                attachToSessions()
            } else {
                updateConnection()
            }
            handler.postDelayed(this, RESYNC_INTERVAL_MS)
        }
    }

    companion object {
        private const val TAG = "FadoSync"
        private const val RESYNC_INTERVAL_MS = 2000L
        const val ACTION_SPOTIFY_METADATA = "com.spotify.music.metadatachanged"
        const val ACTION_SPOTIFY_PLAYBACK = "com.spotify.music.playbackstatechanged"
    }
}
