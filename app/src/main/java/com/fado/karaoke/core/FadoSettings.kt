package com.fado.karaoke.core

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

enum class BounceMode { WORD, SYLLABLE }

enum class VisualTheme { FADO, CLASSIC }

/**
 * Ustawienia wspólne dla telefonu i Android Auto.
 * Pola są @Volatile — renderery czytają je w każdej klatce na własnych wątkach.
 * Zmiany i słuchacze: wątek główny.
 */
object FadoSettings {
    const val OFFSET_MIN = -2000
    const val OFFSET_MAX = 2000

    private const val PREFS = "fado_settings"
    private const val KEY_OFFSET = "offset_ms"
    private const val KEY_BOUNCE = "bounce_mode"
    private const val KEY_THEME = "theme"

    private var prefs: SharedPreferences? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** Dodawane do pozycji odtwarzania: ujemne = tekst później (kompensacja Bluetooth). */
    @Volatile
    var offsetMs: Int = 0
        private set

    @Volatile
    var bounceMode: BounceMode = BounceMode.WORD
        private set

    @Volatile
    var theme: VisualTheme = VisualTheme.FADO
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        offsetMs = p.getInt(KEY_OFFSET, 0).coerceIn(OFFSET_MIN, OFFSET_MAX)
        bounceMode = runCatching { BounceMode.valueOf(p.getString(KEY_BOUNCE, null) ?: "") }
            .getOrDefault(BounceMode.WORD)
        theme = runCatching { VisualTheme.valueOf(p.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(VisualTheme.FADO)
    }

    fun setOffset(value: Int) {
        val v = value.coerceIn(OFFSET_MIN, OFFSET_MAX)
        if (v == offsetMs) return
        offsetMs = v
        prefs?.edit()?.putInt(KEY_OFFSET, v)?.apply()
        notifyChanged()
    }

    fun adjustOffset(delta: Int) = setOffset(offsetMs + delta)

    fun toggleBounceMode() {
        bounceMode = if (bounceMode == BounceMode.WORD) BounceMode.SYLLABLE else BounceMode.WORD
        prefs?.edit()?.putString(KEY_BOUNCE, bounceMode.name)?.apply()
        notifyChanged()
    }

    fun toggleTheme() {
        theme = if (theme == VisualTheme.FADO) VisualTheme.CLASSIC else VisualTheme.FADO
        prefs?.edit()?.putString(KEY_THEME, theme.name)?.apply()
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        for (l in listeners) l()
    }
}
