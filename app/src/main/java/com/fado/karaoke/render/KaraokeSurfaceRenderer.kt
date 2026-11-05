package com.fado.karaoke.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Silnik renderujący na dowolny [Surface] — ten sam dla SurfaceView w telefonie
 * i dla SurfaceContainer z Android Auto.
 *
 * - Własny wątek o priorytecie DISPLAY + Choreographer (tempo vsync, 60/90/120 Hz).
 * - Czas klatki = znacznik vsync przeliczony na zegar elapsedRealtime, więc ruch jest
 *   równy niezależnie od obciążenia (brak "szarpania" od opóźnień wątku).
 * - Canvas sprzętowy (GPU) z automatycznym przejściem na programowy, gdyby host go nie wspierał.
 * - Gdy nic się nie rusza (pauza, komunikat) — 10 FPS zamiast 60, mniej prądu.
 *
 * @param maxFps limit klatek (0 = tempo ekranu). Dla Android Auto 60.
 */
class KaraokeSurfaceRenderer(context: Context, name: String, private val maxFps: Int = 0) {

    private val scene = KaraokeScene(context.applicationContext)
    private val thread = HandlerThread("FADO-Render-$name", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    // --- stan wątku renderera
    private var choreographer: Choreographer? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var density = 1f
    private var contentArea: Rect? = null
    private var running = false
    private var frameScheduled = false
    private var useHardwareCanvas = true
    private var lastDrawNanos = 0L

    @Volatile
    private var released = false

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos -> onFrame(frameTimeNanos) }

    init {
        handler.post { choreographer = Choreographer.getInstance() }
    }

    /**
     * Podłącza/odłącza powierzchnię. Wywołanie blokuje do chwili, gdy wątek renderera
     * przestanie używać poprzedniej powierzchni — wymagane przez SurfaceHolder.Callback
     * i SurfaceCallback.onSurfaceDestroyed.
     */
    fun setSurface(newSurface: Surface?, width: Int, height: Int, densityDpi: Int) {
        runBlocking {
            surface = newSurface?.takeIf { it.isValid }
            surfaceWidth = width
            surfaceHeight = height
            if (densityDpi > 0) density = densityDpi / 160f
            useHardwareCanvas = true
            lastDrawNanos = 0L
            scene.invalidateLayout()
            scheduleFrame()
        }
    }

    /** Obszar, który na pewno jest widoczny (Android Auto: stable area). null = cała powierzchnia. */
    fun setContentArea(area: Rect?) {
        val copy = area?.let { Rect(it) }
        handler.post {
            contentArea = copy
            scene.invalidateLayout()
            scheduleFrame()
        }
    }

    fun start() {
        handler.post {
            running = true
            scheduleFrame()
        }
    }

    fun stop() {
        handler.post { running = false }
    }

    fun release() {
        if (released) return
        runBlocking {
            running = false
            surface = null
        }
        released = true
        thread.quitSafely()
    }

    private fun runBlocking(block: () -> Unit) {
        if (released) return
        if (Looper.myLooper() == thread.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return
        try {
            latch.await(BLOCKING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun scheduleFrame() {
        if (released || !running || surface == null || frameScheduled) return
        val ch = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        frameScheduled = true
        ch.postFrameCallback(frameCallback)
    }

    private fun onFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (released || !running) return
        val target = surface ?: return
        val fps = when {
            !scene.needsFastFrames() -> IDLE_FPS
            maxFps > 0 -> maxFps
            else -> 0
        }
        val minInterval = if (fps > 0) 1_000_000_000L / fps - FRAME_TOLERANCE_NANOS else 0L
        if (lastDrawNanos == 0L || frameTimeNanos - lastDrawNanos >= minInterval) {
            lastDrawNanos = frameTimeNanos
            drawFrame(target, frameTimeNanos)
        }
        scheduleFrame()
    }

    private fun drawFrame(target: Surface, frameTimeNanos: Long) {
        if (!target.isValid) return
        val canvas: Canvas = try {
            if (useHardwareCanvas) target.lockHardwareCanvas() else target.lockCanvas(null)
        } catch (e: Exception) {
            if (useHardwareCanvas) {
                Log.w(TAG, "Canvas sprzętowy niedostępny — przełączam na programowy", e)
                useHardwareCanvas = false
            } else {
                Log.w(TAG, "Nie można zablokować powierzchni", e)
            }
            return
        }
        try {
            // Czas vsync (System.nanoTime) → baza SystemClock.elapsedRealtime, jak w PlaybackState.
            val offsetNanos = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
            val elapsedMs = (frameTimeNanos + offsetNanos) / 1_000_000.0
            val w = if (surfaceWidth > 0) surfaceWidth else canvas.width
            val h = if (surfaceHeight > 0) surfaceHeight else canvas.height
            scene.draw(canvas, w, h, contentArea, density, elapsedMs)
        } catch (e: Exception) {
            Log.e(TAG, "Błąd rysowania klatki", e)
        } finally {
            try {
                target.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.w(TAG, "unlockCanvasAndPost nie powiódł się", e)
            }
        }
    }

    companion object {
        private const val TAG = "FadoRenderer"
        private const val IDLE_FPS = 10
        private const val FRAME_TOLERANCE_NANOS = 2_000_000L
        private const val BLOCKING_TIMEOUT_MS = 600L
    }
}
