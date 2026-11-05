package com.fado.karaoke.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.fado.karaoke.R
import com.fado.karaoke.core.BounceMode
import com.fado.karaoke.core.ConnectionState
import com.fado.karaoke.core.FadoCore
import com.fado.karaoke.core.FadoSettings
import com.fado.karaoke.core.FadoState
import com.fado.karaoke.core.LyricsState
import com.fado.karaoke.core.VisualTheme
import com.fado.karaoke.media.FadoMediaRelay
import com.fado.karaoke.model.SyncKind
import com.fado.karaoke.model.TrackInfo
import com.fado.karaoke.render.KaraokeSurfaceRenderer
import com.fado.karaoke.sync.NotificationAccess
import com.fado.karaoke.util.TextUtil

/**
 * Ekran telefonu: nadanie uprawnień, status połączenia ze Spotify,
 * podgląd na żywo (ten sam renderer co w aucie) i ustawienia synchronizacji.
 */
class MainActivity : Activity() {

    private lateinit var renderer: KaraokeSurfaceRenderer
    private lateinit var root: View
    private lateinit var header: View
    private lateinit var previewFrame: View
    private lateinit var previewHint: View
    private lateinit var controlsScroll: View
    private lateinit var statusAccess: TextView
    private lateinit var statusPlayer: TextView
    private lateinit var statusTrack: TextView
    private lateinit var statusLyrics: TextView
    private lateinit var statusTile: TextView
    private lateinit var offsetValue: TextView
    private lateinit var restrictedHint: TextView
    private lateinit var btnAccess: Button
    private lateinit var btnRestricted: Button
    private lateinit var btnMode: Button
    private lateinit var btnTheme: Button
    private lateinit var btnDemo: Button
    private lateinit var btnSpotify: Button

    private var fullscreen = false
    private var backCallback: Any? = null
    private var previewHeightPx = 0

    private val stateListener: () -> Unit = { refreshUi() }
    private val settingsListener: () -> Unit = { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        FadoCore.init(this)

        root = findViewById(R.id.root)
        header = findViewById(R.id.header)
        previewFrame = findViewById(R.id.previewFrame)
        previewHint = findViewById(R.id.previewHint)
        controlsScroll = findViewById(R.id.controlsScroll)
        statusAccess = findViewById(R.id.statusAccess)
        statusPlayer = findViewById(R.id.statusPlayer)
        statusTrack = findViewById(R.id.statusTrack)
        statusLyrics = findViewById(R.id.statusLyrics)
        statusTile = findViewById(R.id.statusTile)
        offsetValue = findViewById(R.id.offsetValue)
        restrictedHint = findViewById(R.id.restrictedHint)
        btnAccess = findViewById(R.id.btnAccess)
        btnRestricted = findViewById(R.id.btnRestricted)
        btnMode = findViewById(R.id.btnMode)
        btnTheme = findViewById(R.id.btnTheme)
        btnDemo = findViewById(R.id.btnDemo)
        btnSpotify = findViewById(R.id.btnSpotify)

        setupInsets()
        setupPreview()
        setupButtons()
    }

    override fun onStart() {
        super.onStart()
        FadoState.addListener(stateListener)
        FadoSettings.addListener(settingsListener)
        FadoCore.ensureSyncStarted()
        renderer.start()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        // Po powrocie z ustawień dostęp mógł zostać nadany.
        FadoCore.ensureSyncStarted()
        refreshUi()
    }

    override fun onStop() {
        FadoState.removeListener(stateListener)
        FadoSettings.removeListener(settingsListener)
        renderer.stop()
        super.onStop()
    }

    override fun onDestroy() {
        renderer.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ podgląd

    private fun setupPreview() {
        renderer = KaraokeSurfaceRenderer(this, "phone")
        val preview = findViewById<SurfaceView>(R.id.preview)
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                renderer.setSurface(holder.surface, width, height, resources.displayMetrics.densityDpi)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                renderer.setSurface(null, 0, 0, 0)
            }
        })
        preview.setOnClickListener { setFullscreen(!fullscreen) }
        previewHeightPx = previewFrame.layoutParams.height
    }

    private fun setFullscreen(on: Boolean) {
        if (on == fullscreen) return
        fullscreen = on
        header.visibility = if (on) View.GONE else View.VISIBLE
        controlsScroll.visibility = if (on) View.GONE else View.VISIBLE
        previewHint.visibility = if (on) View.GONE else View.VISIBLE

        val lp = previewFrame.layoutParams as LinearLayout.LayoutParams
        if (on) {
            lp.height = 0
            lp.weight = 1f
            lp.setMargins(0, 0, 0, 0)
        } else {
            val margin = (12 * resources.displayMetrics.density).toInt()
            val vertical = (8 * resources.displayMetrics.density).toInt()
            lp.height = previewHeightPx
            lp.weight = 0f
            lp.setMargins(margin, vertical, margin, vertical)
        }
        previewFrame.layoutParams = lp

        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applySystemBars(on)
        updateBackCallback(on)
        root.requestApplyInsets()
    }

    private fun applySystemBars(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController ?: return
            if (hidden) {
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (hidden) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    /** Wstecz w trybie pełnoekranowym = wyjście z pełnego ekranu (Android 13+: predictive back). */
    private fun updateBackCallback(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val existing = backCallback as? OnBackInvokedCallback
        if (enabled && existing == null) {
            val cb = OnBackInvokedCallback { setFullscreen(false) }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!enabled && existing != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(existing)
            backCallback = null
        }
    }

    @Deprecated("Obsługa dla Androida < 13")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (fullscreen) setFullscreen(false) else super.onBackPressed()
    }

    private fun setupInsets() {
        if (Build.VERSION.SDK_INT < 30) return
        // Android 15+ wymusza edge-to-edge; tu robimy to jawnie od Androida 11.
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            if (fullscreen) v.setPadding(0, 0, 0, 0)
            else v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    // ------------------------------------------------------------------ przyciski

    private fun setupButtons() {
        btnAccess.setOnClickListener {
            if (!safeStart(NotificationAccess.settingsIntent(this))) safeStart(NotificationAccess.genericSettingsIntent())
        }
        btnRestricted.setOnClickListener { safeStart(NotificationAccess.appDetailsIntent(this)) }
        findViewById<Button>(R.id.btnMinus100).setOnClickListener { FadoSettings.adjustOffset(-100) }
        findViewById<Button>(R.id.btnMinus10).setOnClickListener { FadoSettings.adjustOffset(-10) }
        findViewById<Button>(R.id.btnPlus10).setOnClickListener { FadoSettings.adjustOffset(10) }
        findViewById<Button>(R.id.btnPlus100).setOnClickListener { FadoSettings.adjustOffset(100) }
        findViewById<Button>(R.id.btnOffsetReset).setOnClickListener { FadoSettings.setOffset(0) }
        findViewById<Button>(R.id.btnReload).setOnClickListener { FadoCore.reloadLyrics() }
        btnMode.setOnClickListener { FadoSettings.toggleBounceMode() }
        btnTheme.setOnClickListener { FadoSettings.toggleTheme() }
        btnDemo.setOnClickListener { FadoCore.toggleDemo() }
        btnSpotify.setOnClickListener { openSpotify() }
    }

    private fun openSpotify() {
        val launch = packageManager.getLaunchIntentForPackage(TrackInfo.SPOTIFY_PACKAGE)
        if (launch != null) {
            safeStart(launch)
            return
        }
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${TrackInfo.SPOTIFY_PACKAGE}"))
        if (!safeStart(market)) {
            safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${TrackInfo.SPOTIFY_PACKAGE}")))
        }
    }

    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, R.string.toast_settings_missing, Toast.LENGTH_SHORT).show()
        false
    } catch (e: SecurityException) {
        Toast.makeText(this, R.string.toast_settings_missing, Toast.LENGTH_SHORT).show()
        false
    }

    // ------------------------------------------------------------------ status

    private fun refreshUi() {
        val granted = NotificationAccess.isGranted(this)
        statusAccess.setText(if (granted) R.string.status_access_ok else R.string.status_access_missing)
        btnAccess.visibility = if (granted) View.GONE else View.VISIBLE
        val showRestricted = !granted && Build.VERSION.SDK_INT >= 33
        btnRestricted.visibility = if (showRestricted) View.VISIBLE else View.GONE
        restrictedHint.visibility = if (showRestricted) View.VISIBLE else View.GONE

        val playState = getString(if (FadoState.isPlaying) R.string.playing_suffix else R.string.paused_suffix)
        statusPlayer.text = when (FadoState.connection) {
            ConnectionState.SPOTIFY_SESSION -> getString(R.string.player_spotify_session) + " · " + playState
            ConnectionState.SPOTIFY_BROADCAST -> getString(R.string.player_spotify_broadcast) + " · " + playState
            ConnectionState.OTHER_PLAYER -> getString(R.string.player_other, FadoState.playerPackage ?: "?") + " · " + playState
            ConnectionState.WAITING_FOR_PLAYER -> getString(R.string.player_waiting)
            ConnectionState.NO_ACCESS -> getString(R.string.player_no_access)
        }

        val track = FadoState.track
        statusTrack.text = if (track == null) {
            getString(R.string.track_none)
        } else {
            getString(R.string.track_format, track.title, track.artist.ifBlank { "?" }, TextUtil.formatDuration(track.durationMs))
        }

        statusLyrics.text = when (val st = FadoState.lyricsState) {
            is LyricsState.Idle -> getString(R.string.lyrics_idle)
            is LyricsState.Loading -> getString(R.string.lyrics_loading)
            is LyricsState.Ready -> {
                val kind = when (st.lyrics.kind) {
                    SyncKind.WORD_SYNCED -> R.string.kind_word
                    SyncKind.LINE_SYNCED -> R.string.kind_line
                    SyncKind.UNSYNCED -> R.string.kind_unsynced
                }
                getString(R.string.lyrics_ready, st.lyrics.sourceLabel, getString(kind), st.lyrics.lines.size)
            }
            is LyricsState.NotFound -> getString(R.string.lyrics_not_found)
            is LyricsState.Instrumental -> getString(R.string.lyrics_instrumental)
            is LyricsState.Failed -> getString(R.string.lyrics_failed, st.message)
        }

        statusTile.setText(if (FadoMediaRelay.isAttached) R.string.status_tile_active else R.string.status_tile_idle)

        offsetValue.text = getString(R.string.offset_format, TextUtil.formatOffset(FadoSettings.offsetMs))
        btnMode.setText(if (FadoSettings.bounceMode == BounceMode.WORD) R.string.mode_word else R.string.mode_syllable)
        btnTheme.setText(if (FadoSettings.theme == VisualTheme.FADO) R.string.theme_fado else R.string.theme_classic)
        btnDemo.setText(if (FadoState.isDemo) R.string.btn_demo_stop else R.string.btn_demo_start)
        val spotifyInstalled = packageManager.getLaunchIntentForPackage(TrackInfo.SPOTIFY_PACKAGE) != null
        btnSpotify.setText(if (spotifyInstalled) R.string.btn_spotify_open else R.string.btn_spotify_install)
    }
}
