package com.fado.karaoke.render

import com.fado.karaoke.core.VisualTheme

/** Paleta renderera. FADO = kolory logo; CLASSIC = kolory z pierwotnej specyfikacji. */
internal class RenderTheme(
    val background: Int,
    val backgroundGlow: Int,
    val fillTop: Int,
    val fillBottom: Int,
    val glow: Int,
    val activeBase: Int,
    val upcoming: Int,
    val sungTop: Int,
    val sungBottom: Int,
    val ballColors: IntArray,
    val ballStops: FloatArray,
    val ballRim: Int,
    val halo: Int,
    val trail: Int,
    val accent: Int,
    val statusText: Int,
    val statusDim: Int
) {
    companion object {
        val FADO = RenderTheme(
            background = 0xFF071120.toInt(),
            backgroundGlow = 0xFF0E2238.toInt(),
            fillTop = 0xFF4ADE80.toInt(),
            fillBottom = 0xFF06B6D4.toInt(),
            glow = 0xCC10B981.toInt(),
            activeBase = 0x55FFFFFF,
            upcoming = 0xFFCFE3F5.toInt(),
            sungTop = 0xFF34D399.toInt(),
            sungBottom = 0xFF0891B2.toInt(),
            ballColors = intArrayOf(0xFFD9F99D.toInt(), 0xFF4ADE80.toInt(), 0xFF06B6D4.toInt(), 0xFF1E3A8A.toInt()),
            ballStops = floatArrayOf(0f, 0.28f, 0.65f, 1f),
            ballRim = 0xFFBBF7D0.toInt(),
            halo = 0xFF34D399.toInt(),
            trail = 0xFF6EE7B7.toInt(),
            accent = 0xFF67E8F9.toInt(),
            statusText = 0xFFE6F1FF.toInt(),
            statusDim = 0xFF8BA3C0.toInt()
        )

        val CLASSIC = RenderTheme(
            background = 0xFF090712.toInt(),
            backgroundGlow = 0xFF1C1033.toInt(),
            fillTop = 0xFF00F2FE.toInt(),
            fillBottom = 0xFF4FACFE.toInt(),
            glow = 0xCC00F2FE.toInt(),
            activeBase = 0x55FFFFFF,
            upcoming = 0xFFE5DEF5.toInt(),
            sungTop = 0xFF00C9D6.toInt(),
            sungBottom = 0xFF3D8BD9.toInt(),
            ballColors = intArrayOf(0xFFFFC2B3.toInt(), 0xFFFF0844.toInt(), 0xFFB0002F.toInt(), 0xFF4A0013.toInt()),
            ballStops = floatArrayOf(0f, 0.3f, 0.7f, 1f),
            ballRim = 0xFFFF8FA8.toInt(),
            halo = 0xFFFF0844.toInt(),
            trail = 0xFFFF5C7C.toInt(),
            accent = 0xFF00F2FE.toInt(),
            statusText = 0xFFF2ECFF.toInt(),
            statusDim = 0xFF9C90B8.toInt()
        )

        fun of(theme: VisualTheme): RenderTheme = if (theme == VisualTheme.CLASSIC) CLASSIC else FADO

        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }
}
