package com.fado.karaoke.sync

import kotlin.math.abs
import kotlin.math.max

/**
 * Zegar odtwarzania z interpolacją wysokiej rozdzielczości.
 *
 *   position(t) = basePosition + (t − baseElapsed) × speed
 *
 * gdzie t to SystemClock.elapsedRealtime() (renderer podaje czas klatki vsync,
 * przeliczony na tę samą bazę). Ulepszenie względem specyfikacji: drobne korekty
 * pozycji od Spotify (do ±400 ms) nie powodują skoku piłki — różnica jest
 * "wygładzana" przez 450 ms. Duże zmiany (przewinięcie) są stosowane od razu.
 */
class PlaybackClock {

    class Snapshot(
        val basePositionMs: Double,
        val baseElapsedMs: Double,
        val speed: Double,
        val isPlaying: Boolean,
        val slewMs: Double,
        val slewStartMs: Double
    )

    @Volatile
    var snapshot: Snapshot = Snapshot(0.0, 0.0, 1.0, false, 0.0, 0.0)
        private set

    fun positionAt(elapsedMs: Double): Double = positionAt(snapshot, elapsedMs)

    /**
     * @param positionMs pozycja zgłoszona przez odtwarzacz
     * @param updateElapsedMs chwila (elapsedRealtime), w której pozycja była aktualna
     * @param nowElapsedMs bieżący elapsedRealtime
     */
    @Synchronized
    fun update(positionMs: Long, updateElapsedMs: Long, speed: Float, playing: Boolean, nowElapsedMs: Long) {
        val old = snapshot
        val now = nowElapsedMs.toDouble()
        val effectiveSpeed = if (speed > 0f) speed.toDouble() else 1.0
        val fresh = Snapshot(positionMs.toDouble(), updateElapsedMs.toDouble(), effectiveSpeed, playing, 0.0, now)

        val shown = positionAt(old, now)
        val target = positionAt(fresh, now)
        val delta = target - shown
        val smallCorrection = abs(delta) > 1.0 && abs(delta) <= SLEW_MAX_MS
        snapshot = if (old.isPlaying && playing && smallCorrection) {
            Snapshot(fresh.basePositionMs, fresh.baseElapsedMs, effectiveSpeed, true, delta, now)
        } else {
            fresh
        }
    }

    /** Zmiana tylko stanu play/pauza (np. broadcast bez pozycji). */
    @Synchronized
    fun setPlaying(playing: Boolean, nowElapsedMs: Long) {
        val old = snapshot
        if (old.isPlaying == playing) return
        val pos = positionAt(old, nowElapsedMs.toDouble())
        snapshot = Snapshot(pos, nowElapsedMs.toDouble(), old.speed, playing, 0.0, nowElapsedMs.toDouble())
    }

    @Synchronized
    fun reset() {
        snapshot = Snapshot(0.0, 0.0, 1.0, false, 0.0, 0.0)
    }

    companion object {
        const val SLEW_MAX_MS = 400.0
        const val SLEW_DURATION_MS = 450.0

        fun positionAt(s: Snapshot, elapsedMs: Double): Double {
            val raw = s.basePositionMs + if (s.isPlaying) (elapsedMs - s.baseElapsedMs) * s.speed else 0.0
            val k = if (s.slewMs != 0.0) {
                (1.0 - (elapsedMs - s.slewStartMs) / SLEW_DURATION_MS).coerceIn(0.0, 1.0)
            } else 0.0
            return max(0.0, raw - s.slewMs * k)
        }
    }
}
