package com.fado.karaoke.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.fado.karaoke.R
import com.fado.karaoke.core.BounceMode
import com.fado.karaoke.core.ConnectionState
import com.fado.karaoke.core.FadoSettings
import com.fado.karaoke.core.FadoState
import com.fado.karaoke.core.LyricsState
import com.fado.karaoke.model.LyricWord
import com.fado.karaoke.model.Lyrics
import com.fado.karaoke.model.SyncKind
import com.fado.karaoke.model.TrackInfo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Cała grafika jednej klatki: tło, pasek statusu, płynnie przewijane linie,
 * wypełnianie liter (clipRect co do piksela) i odbijająca się piłka.
 * Używana wyłącznie z wątku renderera (bez synchronizacji, bez alokacji w typowej klatce).
 */
internal class KaraokeScene(private val context: Context) {

    private enum class LineKind { SUNG, ACTIVE, UPCOMING }

    /** Najbliższe punkty lądowania piłki: A = ostatni osiągnięty, B = następny. */
    private class AnchorPick {
        var aT = Double.NaN
        var aX = 0f
        var aY = 0f
        var aLine = 0
        var aRow = 0
        var bT = Double.NaN
        var bX = 0f
        var bY = 0f
        var bLine = 0
        var bRow = 0

        fun reset() {
            aT = Double.NaN
            bT = Double.NaN
        }

        fun consider(time: Double, x: Float, y: Float, line: Int, row: Int, now: Double) {
            if (time <= now) {
                aT = time; aX = x; aY = y; aLine = line; aRow = row
            } else if (bT.isNaN()) {
                bT = time; bX = x; bY = y; bLine = line; bRow = row
            }
        }
    }

    private val lyricTypeface: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val statusTypeface: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val bgPaint = Paint()
    private val measurePaint = textPaint(lyricTypeface)
    private val basePaint = textPaint(lyricTypeface)
    private val fillPaint = textPaint(lyricTypeface)
    private val sungPaint = textPaint(lyricTypeface)
    private val upcomingPaint = textPaint(lyricTypeface)
    private val introTitlePaint = textPaint(lyricTypeface).apply { textAlign = Paint.Align.CENTER }
    private val introArtistPaint = textPaint(statusTypeface).apply { textAlign = Paint.Align.CENTER }
    private val statusPaint = textPaint(statusTypeface)
    private val statusDimPaint = textPaint(statusTypeface)
    private val badgeTextPaint = textPaint(statusTypeface)
    private val messagePaint = textPaint(lyricTypeface)
    private val hintPaint = textPaint(statusTypeface)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // --- cache
    private var bgW = -1
    private var bgH = -1
    private var bgTheme: RenderTheme? = null
    private var layout: LyricsLayout? = null
    private var layoutLyrics: Lyrics? = null
    private var layoutWidth = 0f
    private var layoutTextSize = 0f
    private var lyricPaintsLayout: LyricsLayout? = null
    private var lyricPaintsTheme: RenderTheme? = null
    private var ballRadius = 0f
    private var ballTheme: RenderTheme? = null
    private var wordmark: Bitmap? = null
    private var wordmarkTried = false
    private var messageKey: String? = null
    private var messageWidth = 0
    private var messageSize = 0f
    private var messageTitleLayout: StaticLayout? = null
    private var messageHintLayout: StaticLayout? = null
    private var titleKey: String? = null
    private var titleWidth = -1f
    private var titleSize = -1f
    private var titleText: CharSequence = ""
    private var introKey: String? = null
    private var introWidth = -1f
    private var introTitle: CharSequence = ""
    private var introArtist: CharSequence = ""
    private var badgeState: LyricsState? = null
    private var badgeCache: String? = null

    // --- animacja
    private var scrollY = Float.NaN
    private var lastElapsed = Double.NaN
    private var fast = true
    private val anchors = AnchorPick()
    private val trailX = FloatArray(TRAIL_SIZE)
    private val trailY = FloatArray(TRAIL_SIZE)
    private var trailCount = 0
    private var trailHead = 0

    private val boxRect = RectF()
    private val regionRect = RectF()
    private val tmpRect = RectF()
    private val sb = StringBuilder(32)

    fun invalidateLayout() {
        layout = null
        layoutLyrics = null
        scrollY = Float.NaN
        bgW = -1
    }

    /** false = nic się nie rusza (pauza, komunikat) → renderer zwalnia do kilku FPS. */
    fun needsFastFrames(): Boolean = fast

    fun draw(canvas: Canvas, width: Int, height: Int, contentArea: Rect?, density: Float, elapsedMs: Double) {
        val th = RenderTheme.of(FadoSettings.theme)
        val dt = if (lastElapsed.isNaN()) 1.0 / 60.0 else ((elapsedMs - lastElapsed) / 1000.0).coerceIn(0.0, 0.25)
        lastElapsed = elapsedMs

        drawBackground(canvas, width, height, th)

        boxRect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (contentArea != null && !contentArea.isEmpty) {
            boxRect.set(
                max(0, contentArea.left).toFloat(),
                max(0, contentArea.top).toFloat(),
                min(width, contentArea.right).toFloat(),
                min(height, contentArea.bottom).toFloat()
            )
            if (boxRect.width() < 80f || boxRect.height() < 60f) boxRect.set(0f, 0f, width.toFloat(), height.toFloat())
        }
        val pad = 10f * density
        boxRect.inset(pad, pad * 0.6f)

        val track = FadoState.track
        val state = FadoState.lyricsState
        val position = FadoState.positionAt(elapsedMs)
        val t = position + FadoSettings.offsetMs

        val statusH = (boxRect.height() * 0.12f).coerceIn(20f * density, 40f * density)
        drawStatus(canvas, boxRect, statusH, density, th, track, state, position)
        regionRect.set(boxRect.left, boxRect.top + statusH, boxRect.right, boxRect.bottom)

        val lyrics = FadoState.currentLyrics()
        if (lyrics == null || lyrics.lines.isEmpty() || track == null) {
            drawMessage(canvas, regionRect, density, th, state, track)
            fast = false
            scrollY = Float.NaN
            trailCount = 0
            return
        }
        drawLyrics(canvas, regionRect, lyrics, t, dt, density, th, track)
    }

    // ================================================================== tło i status

    private fun drawBackground(c: Canvas, w: Int, h: Int, th: RenderTheme) {
        if (w != bgW || h != bgH || th !== bgTheme) {
            val radius = max(1f, max(w, h) * 0.75f)
            bgPaint.shader = RadialGradient(
                w * 0.5f, h * 0.42f, radius,
                intArrayOf(th.backgroundGlow, th.background, darken(th.background)),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            bgW = w
            bgH = h
            bgTheme = th
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
    }

    private fun drawStatus(
        c: Canvas, box: RectF, h: Float, density: Float, th: RenderTheme,
        track: TrackInfo?, state: LyricsState, positionMs: Double
    ) {
        val size = h * 0.44f
        statusPaint.textSize = size
        statusPaint.color = th.statusText
        statusDimPaint.textSize = size * 0.92f
        statusDimPaint.color = th.statusDim
        badgeTextPaint.textSize = size * 0.8f
        val centerY = box.top + h * 0.5f
        val baseline = centerY - (statusPaint.descent() + statusPaint.ascent()) / 2f
        var right = box.right

        val badge = badgeText(state)
        if (badge != null) {
            val tw = badgeTextPaint.measureText(badge)
            val bh = size * 1.45f
            val bw = tw + size * 1.1f
            tmpRect.set(right - bw, centerY - bh / 2f, right, centerY + bh / 2f)
            badgePaint.color = RenderTheme.withAlpha(th.accent, 0x2E)
            c.drawRoundRect(tmpRect, bh / 2f, bh / 2f, badgePaint)
            badgeTextPaint.color = th.accent
            val badgeBaseline = centerY - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            c.drawText(badge, tmpRect.left + size * 0.55f, badgeBaseline, badgeTextPaint)
            right = tmpRect.left - size * 0.7f
        }

        sb.setLength(0)
        if (track != null) appendTime(sb, positionMs)
        val off = FadoSettings.offsetMs
        if (off != 0) {
            if (sb.isNotEmpty()) sb.append("  ")
            sb.append(if (off > 0) "+" else "−").append(abs(off)).append(" ms")
        }
        if (sb.isNotEmpty()) {
            val tw = statusDimPaint.measureText(sb, 0, sb.length)
            c.drawText(sb, 0, sb.length, right - tw, baseline, statusDimPaint)
            right -= tw + size * 0.9f
        }

        val dotR = h * 0.13f
        dotPaint.color = th.halo
        c.drawCircle(box.left + dotR, centerY, dotR, dotPaint)
        val titleX = box.left + dotR * 2f + size * 0.6f
        val avail = right - titleX
        val full = when {
            track == null -> context.getString(R.string.app_name)
            track.artist.isBlank() -> track.title
            else -> track.title + "  —  " + track.artist
        }
        if (full != titleKey || abs(avail - titleWidth) > 0.5f || size != titleSize) {
            titleText = if (avail > 0f) TextUtils.ellipsize(full, statusPaint, avail, TextUtils.TruncateAt.END) else ""
            titleKey = full
            titleWidth = avail
            titleSize = size
        }
        if (titleText.isNotEmpty()) c.drawText(titleText, 0, titleText.length, titleX, baseline, statusPaint)

        dividerPaint.color = RenderTheme.withAlpha(th.accent, 0x26)
        c.drawRect(box.left, box.top + h - density, box.right, box.top + h, dividerPaint)
    }

    private fun badgeText(state: LyricsState): String? {
        if (state === badgeState) return badgeCache
        badgeState = state
        badgeCache = when (state) {
            is LyricsState.Ready -> {
                val kind = when (state.lyrics.kind) {
                    SyncKind.WORD_SYNCED -> R.string.badge_word
                    SyncKind.LINE_SYNCED -> R.string.badge_line
                    SyncKind.UNSYNCED -> R.string.badge_unsynced
                }
                state.lyrics.sourceLabel + " · " + context.getString(kind)
            }
            is LyricsState.Loading -> context.getString(R.string.badge_loading)
            else -> null
        }
        return badgeCache
    }

    // ================================================================== komunikaty

    private fun drawMessage(c: Canvas, r: RectF, density: Float, th: RenderTheme, state: LyricsState, track: TrackInfo?) {
        val title: String
        val hint: String?
        when {
            track == null && FadoState.connection == ConnectionState.NO_ACCESS -> {
                title = context.getString(R.string.msg_no_access)
                hint = context.getString(R.string.msg_no_access_hint)
            }
            track == null -> {
                title = context.getString(R.string.msg_no_track)
                hint = context.getString(R.string.msg_no_track_hint)
            }
            state is LyricsState.NotFound -> {
                title = context.getString(R.string.msg_not_found)
                hint = trackLine(track)
            }
            state is LyricsState.Instrumental -> {
                title = context.getString(R.string.msg_instrumental)
                hint = trackLine(track)
            }
            state is LyricsState.Failed -> {
                title = context.getString(R.string.msg_error)
                hint = context.getString(R.string.msg_error_hint)
            }
            else -> {
                title = context.getString(R.string.msg_loading)
                hint = trackLine(track)
            }
        }

        val size = (r.height() * 0.08f).coerceIn(14f * density, 30f * density)
        var y = r.top + r.height() * 0.1f
        val bmp = wordmark()
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            val maxH = r.height() * 0.38f
            val maxW = r.width() * 0.55f
            var bh = maxH
            var bw = bh * bmp.width / bmp.height
            if (bw > maxW) {
                bw = maxW
                bh = bw * bmp.height / bmp.width
            }
            tmpRect.set(r.centerX() - bw / 2f, y, r.centerX() + bw / 2f, y + bh)
            c.drawBitmap(bmp, null, tmpRect, bitmapPaint)
            y += bh + size * 0.6f
        }

        val width = max(1, (r.width() * 0.9f).toInt())
        val key = title + "\u0001" + (hint ?: "")
        messagePaint.textSize = size
        hintPaint.textSize = size * 0.62f
        if (key != messageKey || width != messageWidth || size != messageSize) {
            messageTitleLayout = staticLayout(title, messagePaint, width)
            messageHintLayout = hint?.let { staticLayout(it, hintPaint, width) }
            messageKey = key
            messageWidth = width
            messageSize = size
        }
        messagePaint.color = th.statusText
        hintPaint.color = th.statusDim
        val titleLayout = messageTitleLayout ?: return
        c.save()
        c.translate(r.centerX() - width / 2f, y)
        titleLayout.draw(c)
        c.translate(0f, titleLayout.height + size * 0.4f)
        messageHintLayout?.draw(c)
        c.restore()
    }

    private fun trackLine(track: TrackInfo): String =
        if (track.artist.isBlank()) track.title else track.title + " — " + track.artist

    private fun staticLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.08f)
            .setMaxLines(4)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun wordmark(): Bitmap? {
        if (!wordmarkTried) {
            wordmarkTried = true
            wordmark = try {
                BitmapFactory.decodeResource(context.resources, R.drawable.fado_wordmark)
            } catch (e: Throwable) {
                null
            }
        }
        return wordmark
    }

    // ================================================================== tekst

    private fun drawLyrics(
        c: Canvas, r: RectF, lyrics: Lyrics, t: Double, dt: Double,
        density: Float, th: RenderTheme, track: TrackInfo
    ) {
        val textSize = min(
            (r.height() * 0.13f).coerceIn(16f * density, 64f * density),
            max(14f * density, r.width() * 0.09f)
        )
        ensureLayout(lyrics, r.width() * 0.92f, textSize)
        val lay = layout ?: return
        if (lay.lines.isEmpty()) return
        ensureLyricPaints(th, lay)

        val activeIdx = lyrics.lineIndexAt(t)
        var focusIdx = activeIdx
        val nextIdx = activeIdx + 1
        if (nextIdx < lyrics.lines.size && lyrics.lines[nextIdx].startTimeMs - t <= leadFor(lyrics, nextIdx)) {
            focusIdx = nextIdx
        }
        val focusY = r.top + r.height() * 0.54f
        val target = if (focusIdx >= 0) lay.lines[focusIdx].centerY else lay.lines[0].centerY - lay.textSize * 2.6f
        val previous = scrollY
        scrollY = if (previous.isNaN() || abs(target - previous) > r.height() * 2.5f) {
            target
        } else {
            previous + (target - previous) * (1.0 - exp(-dt / SCROLL_TAU_S)).toFloat()
        }
        val scrolling = abs(target - scrollY) > 0.5f
        val cx = r.centerX()

        c.save()
        c.clipRect(r)
        for (i in lay.lines.indices) {
            val ll = lay.lines[i]
            val cy = focusY + (ll.centerY - scrollY)
            val half = ll.height * 0.5f
            if (cy + half < r.top - lay.textSize || cy - half > r.bottom + lay.textSize) continue
            val dist = abs(ll.centerY - scrollY) / (lay.textSize * 2.6f)
            val alpha = if (dist <= 1f) 1f - 0.42f * dist else max(0f, 0.58f * (1f - (dist - 1f) / 1.3f))
            if (alpha <= 0.01f) continue
            val kind = when {
                i < activeIdx -> LineKind.SUNG
                i == activeIdx -> LineKind.ACTIVE
                else -> LineKind.UPCOMING
            }
            drawLine(c, lay, ll, cx, cy, lineScale(ll, lay), alpha, kind, t)
        }
        if (activeIdx < 0) drawIntro(c, r, lay, lyrics, t, th, track, focusY)
        drawCountdown(c, r, lay, lyrics, activeIdx, t, th, cx, focusY)
        val ballMoving = drawBall(c, lay, lyrics, activeIdx, t, cx, focusY, th)
        c.restore()

        fast = FadoState.isPlaying || scrolling || ballMoving
    }

    private fun ensureLayout(lyrics: Lyrics, wrapWidth: Float, textSize: Float) {
        if (layout != null && layoutLyrics === lyrics &&
            abs(layoutWidth - wrapWidth) < 0.5f && abs(layoutTextSize - textSize) < 0.25f
        ) return
        measurePaint.textSize = textSize
        layout = LyricsLayout.build(lyrics, measurePaint, wrapWidth)
        layoutLyrics = lyrics
        layoutWidth = wrapWidth
        layoutTextSize = textSize
        scrollY = Float.NaN
        trailCount = 0
    }

    private fun ensureLyricPaints(th: RenderTheme, lay: LyricsLayout) {
        if (lyricPaintsLayout === lay && lyricPaintsTheme === th) return
        val size = lay.textSize
        basePaint.textSize = size
        fillPaint.textSize = size
        sungPaint.textSize = size
        upcomingPaint.textSize = size
        basePaint.color = th.activeBase
        upcomingPaint.color = th.upcoming
        val gradTop = lay.baseline - size * 0.78f
        val gradBottom = lay.baseline + size * 0.12f
        fillPaint.color = Color.WHITE
        fillPaint.shader = LinearGradient(0f, gradTop, 0f, gradBottom, th.fillTop, th.fillBottom, Shader.TileMode.CLAMP)
        fillPaint.setShadowLayer(size * 0.22f, 0f, 0f, th.glow)
        sungPaint.color = Color.WHITE
        sungPaint.shader = LinearGradient(0f, gradTop, 0f, gradBottom, th.sungTop, th.sungBottom, Shader.TileMode.CLAMP)
        lyricPaintsLayout = lay
        lyricPaintsTheme = th
    }

    private fun lineScale(ll: LineLayout, lay: LyricsLayout): Float {
        val dist = abs(ll.centerY - scrollY) / (lay.textSize * 2.6f)
        return 1f - 0.15f * min(dist, 1f)
    }

    private fun drawLine(
        c: Canvas, lay: LyricsLayout, ll: LineLayout, cx: Float, cy: Float,
        scale: Float, alpha: Float, kind: LineKind, t: Double
    ) {
        c.save()
        c.translate(cx, cy)
        if (scale != 1f) c.scale(scale, scale)
        val top = -ll.height * 0.5f
        for (row in ll.rows) {
            c.save()
            c.translate(-row.width * 0.5f, top + row.index * lay.rowHeight)
            when (kind) {
                LineKind.UPCOMING -> {
                    upcomingPaint.alpha = (alpha * 235f).toInt()
                    drawRowText(c, row, lay.baseline, upcomingPaint)
                }
                LineKind.SUNG -> {
                    sungPaint.alpha = (alpha * 215f).toInt()
                    drawRowText(c, row, lay.baseline, sungPaint)
                }
                LineKind.ACTIVE -> {
                    basePaint.alpha = (Color.alpha(lyricPaintsTheme?.activeBase ?: 0x55FFFFFF) * alpha).toInt()
                    drawRowText(c, row, lay.baseline, basePaint)
                    val fillX = rowFillX(row, t)
                    if (fillX > 0f) {
                        c.save()
                        // Wycinek: wszystko na lewo od bieżącej pozycji śpiewu — litera po literze.
                        c.clipRect(-lay.textSize, -lay.rowHeight * 0.6f, fillX, lay.rowHeight * 1.6f)
                        fillPaint.alpha = (alpha * 255f).toInt()
                        drawRowText(c, row, lay.baseline, fillPaint)
                        c.restore()
                    }
                }
            }
            c.restore()
        }
        c.restore()
    }

    private fun drawRowText(c: Canvas, row: RowLayout, baseline: Float, paint: Paint) {
        for (w in row.words) c.drawText(w.word.text, w.x, baseline, paint)
    }

    /** Pozycja X (w układzie wiersza), do której wiersz jest już "zaśpiewany". */
    private fun rowFillX(row: RowLayout, t: Double): Float {
        var fill = 0f
        for (wl in row.words) {
            val word = wl.word
            if (t >= word.endTimeMs) {
                fill = wl.x + wl.width
                continue
            }
            if (t >= word.startTimeMs) {
                val ci = charIndexAt(word, t)
                val ch = word.chars[ci]
                val span = max(1L, ch.endTimeMs - ch.startTimeMs).toDouble()
                val p = ((t - ch.startTimeMs) / span).coerceIn(0.0, 1.0).toFloat()
                fill = wl.x + wl.charX[ci] + wl.charW[ci] * p
            }
            break
        }
        return fill
    }

    private fun charIndexAt(word: LyricWord, t: Double): Int {
        val chars = word.chars
        for (i in chars.indices) {
            if (t < chars[i].endTimeMs) return i
        }
        return chars.size - 1
    }

    private fun drawIntro(
        c: Canvas, r: RectF, lay: LyricsLayout, lyrics: Lyrics, t: Double,
        th: RenderTheme, track: TrackInfo, focusY: Float
    ) {
        val firstStart = lyrics.lines[0].startTimeMs.toDouble()
        val a = ((firstStart - t - leadFor(lyrics, 0) - 250.0) / 700.0).coerceIn(0.0, 1.0).toFloat()
        if (a <= 0.01f) return
        introTitlePaint.textSize = lay.textSize * 1.05f
        introArtistPaint.textSize = lay.textSize * 0.6f
        val maxW = r.width() * 0.9f
        if (track.identity != introKey || abs(maxW - introWidth) > 0.5f) {
            introTitle = TextUtils.ellipsize(track.title, introTitlePaint, maxW, TextUtils.TruncateAt.END)
            introArtist = TextUtils.ellipsize(track.artist, introArtistPaint, maxW, TextUtils.TruncateAt.END)
            introKey = track.identity
            introWidth = maxW
        }
        introTitlePaint.color = th.statusText
        introTitlePaint.alpha = (a * 255f).toInt()
        introArtistPaint.color = th.accent
        introArtistPaint.alpha = (a * 230f).toInt()
        val titleY = focusY - lay.textSize * 0.6f
        c.drawText(introTitle, 0, introTitle.length, r.centerX(), titleY, introTitlePaint)
        c.drawText(introArtist, 0, introArtist.length, r.centerX(), titleY + lay.textSize * 0.95f, introArtistPaint)
    }

    /** Odliczanie 3-2-1 przed linią po dłuższej przerwie (intro / solówka). */
    private fun drawCountdown(
        c: Canvas, r: RectF, lay: LyricsLayout, lyrics: Lyrics, activeIdx: Int, t: Double,
        th: RenderTheme, cx: Float, focusY: Float
    ) {
        val nextIdx = activeIdx + 1
        if (nextIdx >= lyrics.lines.size || nextIdx >= lay.lines.size) return
        val nextStart = lyrics.lines[nextIdx].startTimeMs.toDouble()
        val prevEnd = if (activeIdx >= 0) lyrics.lines[activeIdx].endTimeMs.toDouble() else 0.0
        if (nextStart - prevEnd < 4000.0) return
        val remaining = nextStart - t
        if (remaining <= 0.0 || remaining > 3000.0) return
        val lit = ceil(remaining / 1000.0).toInt().coerceIn(1, 3)

        val ll = lay.lines[nextIdx]
        val row = ll.rows.firstOrNull() ?: return
        val sc = lineScale(ll, lay)
        val cy = focusY + (ll.centerY - scrollY)
        val rowTopScreen = cy - ll.height * 0.5f * sc
        val dotR = lay.textSize * 0.1f * sc
        val spacing = dotR * 3.2f
        val rowLeft = cx - row.width * 0.5f * sc
        // Przed pierwszym słowem, na wysokości małych liter; gdy brak miejsca — nad wierszem.
        var x0 = rowLeft - lay.textSize * 0.35f * sc - spacing * 2f
        var y = rowTopScreen + (lay.baseline - lay.textSize * 0.32f) * sc
        if (x0 - dotR < r.left) {
            x0 = cx - spacing
            y = rowTopScreen + lay.letterTop * sc * 0.45f
        }
        for (k in 0 until 3) {
            dotPaint.color = if (k < lit) th.accent else RenderTheme.withAlpha(th.statusDim, 0x50)
            c.drawCircle(x0 + k * spacing, y, dotR, dotPaint)
        }
    }

    /** Ile wcześniej linia ma wjechać na środek: po dłuższej przerwie ~2 s, żeby dało się ją przeczytać. */
    private fun leadFor(lyrics: Lyrics, index: Int): Double {
        val start = lyrics.lines[index].startTimeMs
        val prevEnd = if (index > 0) lyrics.lines[index - 1].endTimeMs else 0L
        return if (start - prevEnd >= 3000L) LONG_LEAD_MS else SCROLL_LEAD_MS
    }

    // ================================================================== piłka

    /** @return true, gdy piłka jest w ruchu (potrzebne pełne FPS). */
    private fun drawBall(
        c: Canvas, lay: LyricsLayout, lyrics: Lyrics, activeIdx: Int, t: Double,
        cx: Float, focusY: Float, th: RenderTheme
    ): Boolean {
        val radius = lay.textSize * 0.2f
        ensureBallShaders(th, radius)
        val mode = FadoSettings.bounceMode
        val first = max(0, activeIdx)
        val last = min(first + 1, lay.lines.size - 1)
        val surfaceGap = radius * 0.25f

        anchors.reset()
        for (li in first..last) {
            val ll = lay.lines[li]
            val cy = focusY + (ll.centerY - scrollY)
            val sc = lineScale(ll, lay)
            val top = -ll.height * 0.5f
            for (row in ll.rows) {
                val x0 = -row.width * 0.5f
                val sy = cy + (top + row.index * lay.rowHeight + lay.letterTop) * sc - surfaceGap
                for (w in row.words) {
                    if (mode == BounceMode.WORD || w.word.syllables.size <= 1) {
                        val lx = x0 + w.x + w.width * 0.5f
                        anchors.consider(w.word.startTimeMs.toDouble(), cx + lx * sc, sy, li, row.index, t)
                    } else {
                        for (syl in w.word.syllables) {
                            val a = syl.first
                            val b = syl.last
                            val lx = x0 + w.x + (w.charX[a] + w.charX[b] + w.charW[b]) * 0.5f
                            anchors.consider(w.word.chars[a].startTimeMs.toDouble(), cx + lx * sc, sy, li, row.index, t)
                        }
                    }
                }
            }
        }

        var x: Float
        var y: Float
        var sx = 1f
        var sy = 1f
        var visibility = 1f
        var moving = false
        var inFlight = false
        val p0 = anchors

        if (p0.aT.isNaN() && p0.bT.isNaN()) return false
        if (p0.aT.isNaN()) {
            // Przed pierwszym słowem: piłka czeka nad pierwszym słowem.
            x = p0.bX
            y = p0.bY - radius
            visibility = ((2600.0 - (p0.bT - t)) / 1200.0).coerceIn(0.0, 1.0).toFloat()
        } else if (p0.bT.isNaN()) {
            // Koniec utworu: odpoczywa i znika.
            x = p0.aX
            val squash = landingSquash(t - p0.aT)
            sx = 1f + squash
            sy = 1f - squash
            y = p0.aY - radius * sy
            moving = squash > 0.01f
            val songEnd = lyrics.lines.last().endTimeMs.toDouble()
            visibility = ((songEnd + 4000.0 - t) / 1500.0).coerceIn(0.0, 1.0).toFloat()
        } else {
            val interval = p0.bT - p0.aT
            val hop = min(interval, HOP_MAX_MS)
            val hopStart = p0.bT - hop
            if (t < hopStart) {
                x = p0.aX
                val squash = landingSquash(t - p0.aT)
                sx = 1f + squash
                sy = 1f - squash
                y = p0.aY - radius * sy
                moving = squash > 0.01f
            } else {
                val p = ((t - hopStart) / hop).coerceIn(0.0, 1.0).toFloat()
                val distance = abs(p0.bX - p0.aX)
                // W drugim i kolejnym wierszu linii nad literami jest tylko "ballRoom" — niższy łuk.
                val lowerRow = p0.aLine == p0.bLine && (p0.aRow > 0 || p0.bRow > 0)
                val cap = if (lowerRow) lay.textSize * 0.5f else lay.textSize * 1.05f
                var height = (distance * 0.5f).coerceIn(min(lay.textSize * 0.4f, cap), cap)
                height *= (hop / 420.0).coerceIn(0.45, 1.0).toFloat()
                x = p0.aX + (p0.bX - p0.aX) * p
                y = p0.aY + (p0.bY - p0.aY) * p - radius - height * 4f * p * (1f - p)
                if (p < 0.1f) {
                    val s = 0.16f * (1f - p / 0.1f) // odbicie: spłaszczenie przy starcie
                    sx = 1f + s
                    sy = 1f - s
                } else {
                    val v = abs(1f - 2f * p) // prędkość pionowa → rozciągnięcie
                    sy = 1f + 0.12f * v
                    sx = 1f - 0.07f * v
                }
                moving = true
                inFlight = true
            }
        }

        if (inFlight) pushTrail(x, y) else if (trailCount > 0) trailCount--
        if (visibility <= 0.01f) return moving

        val alpha = (visibility * 255f).toInt()
        drawTrail(c, radius, th, alpha)

        val pulse = 0.5f + 0.5f * sin((t / 1600.0 * 2.0 * PI).toFloat())
        c.save()
        c.translate(x, y)
        haloPaint.alpha = (alpha * (0.7f + 0.3f * pulse)).toInt()
        c.drawCircle(0f, 0f, radius * 2.6f, haloPaint)
        c.scale(sx, sy)
        ballPaint.alpha = alpha
        c.drawCircle(0f, 0f, radius, ballPaint)
        rimPaint.alpha = (alpha * 0.85f).toInt()
        c.drawCircle(0f, 0f, radius * 0.96f, rimPaint)
        // połysk jak na piłce z logo
        specPaint.alpha = (alpha * 0.85f).toInt()
        c.save()
        c.rotate(-28f, -radius * 0.345f, -radius * 0.41f)
        tmpRect.set(-radius * 0.69f, -radius * 0.6f, 0f, -radius * 0.22f)
        c.drawOval(tmpRect, specPaint)
        c.restore()
        specPaint.alpha = (alpha * 0.6f).toInt()
        c.drawCircle(-radius * 0.62f, -radius * 0.07f, radius * 0.1f, specPaint)
        c.restore()
        return moving
    }

    private fun landingSquash(sinceLandingMs: Double): Float =
        if (sinceLandingMs < 0) 0f else (0.26 * exp(-sinceLandingMs / 65.0)).toFloat()

    private fun ensureBallShaders(th: RenderTheme, radius: Float) {
        if (radius == ballRadius && th === ballTheme) return
        val r = max(radius, 1f)
        haloPaint.shader = RadialGradient(
            0f, 0f, r * 2.6f,
            intArrayOf(RenderTheme.withAlpha(th.halo, 0x90), RenderTheme.withAlpha(th.halo, 0x30), 0),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        ballPaint.shader = RadialGradient(-r * 0.3f, -r * 0.44f, r * 1.36f, th.ballColors, th.ballStops, Shader.TileMode.CLAMP)
        rimPaint.color = th.ballRim
        rimPaint.strokeWidth = r * 0.09f
        ballRadius = radius
        ballTheme = th
    }

    private fun pushTrail(x: Float, y: Float) {
        trailX[trailHead] = x
        trailY[trailHead] = y
        trailHead = (trailHead + 1) % TRAIL_SIZE
        if (trailCount < TRAIL_SIZE) trailCount++
    }

    private fun drawTrail(c: Canvas, radius: Float, th: RenderTheme, alpha: Int) {
        if (trailCount < 2) return
        trailPaint.color = th.trail
        for (k in 2..trailCount) {
            val idx = (trailHead - k + TRAIL_SIZE * 2) % TRAIL_SIZE
            val f = k.toFloat() / (TRAIL_SIZE + 1)
            trailPaint.alpha = (alpha * 0.55f * (1f - f)).toInt()
            c.drawCircle(trailX[idx], trailY[idx], radius * 0.5f * (1f - f), trailPaint)
        }
    }

    // ================================================================== pomocnicze

    private fun textPaint(tf: Typeface): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = tf }

    private fun darken(color: Int): Int = Color.rgb(
        (Color.red(color) * 0.55f).toInt(),
        (Color.green(color) * 0.55f).toInt(),
        (Color.blue(color) * 0.55f).toInt()
    )

    private fun appendTime(out: StringBuilder, ms: Double) {
        val total = max(0.0, ms).toLong()
        val minutes = total / 60_000
        val seconds = (total / 1000) % 60
        val tenths = (total / 100) % 10
        out.append(minutes).append(':')
        if (seconds < 10) out.append('0')
        out.append(seconds).append('.').append(tenths)
    }

    companion object {
        /** Przewijanie zaczyna się trochę przed linią, żeby była na środku, gdy wokal wchodzi. */
        private const val SCROLL_LEAD_MS = 260.0
        private const val LONG_LEAD_MS = 2200.0
        private const val SCROLL_TAU_S = 0.14
        private const val HOP_MAX_MS = 620.0
        private const val TRAIL_SIZE = 7
    }
}
