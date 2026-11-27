package com.yuklive.app.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.LruCache
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.sin

/**
 * Menggambar layer overlay ke Canvas Android. Dipakai bersama oleh:
 * - compositor stream (hasilnya "dibakar" ke video lewat pipeline OpenGL), dan
 * - editor overlay di aplikasi (preview yang sama persis).
 * Bukan singleton: tiap pemakai (thread compositor / UI) punya instance sendiri.
 */
class OverlayRenderer @Inject constructor(
    private val fonts: FontRegistry,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Status animasi masuk/keluar per layer. */
    private data class Vis(val visible: Boolean, val changedAt: Long)
    private val vis = HashMap<String, Vis>()

    /**
     * @param liveStartedAt waktu mulai live (elapsedRealtime) untuk mode Timer; null = belum live.
     * @return true kalau masih ada animasi (perlu frame berikutnya).
     */
    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        layers: List<OverlayLayer>,
        liveStartedAt: Long?,
        now: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        var animating = false
        val ids = HashSet<String>()
        for (layer in layers) {
            ids += layer.id
            val prev = vis[layer.id]
            val state = when {
                prev == null -> Vis(layer.visible, if (layer.visible) now else 0L).also { vis[layer.id] = it }
                prev.visible != layer.visible -> Vis(layer.visible, now).also { vis[layer.id] = it }
                else -> prev
            }
            val t = ((now - state.changedAt) / ANIM_MS.toFloat()).coerceIn(0f, 1f)
            val anim = if (layer.visible) layer.animIn else layer.animOut
            val progress = if (layer.visible) t else 1f - t // 1 = tampil penuh
            if (progress <= 0f) continue
            if (t < 1f && anim != LayerAnim.None) animating = true
            if (layer.type == LayerType.Ticker || layer.type == LayerType.Clock || layer.type == LayerType.LiveBadge) {
                animating = true // teks berjalan / jam berdetak / badge berkedip
            }
            drawLayer(canvas, width, height, layer, if (anim == LayerAnim.None) 1f else progress, anim, liveStartedAt, now)
        }
        vis.keys.retainAll(ids)
        return animating
    }

    /** Kotak batas layer (koordinat piksel) untuk seleksi di editor. */
    fun bounds(layer: OverlayLayer, width: Float, height: Float): RectF {
        val size = contentSize(layer, width, height)
        val cx = if (layer.type == LayerType.Ticker) width / 2 else layer.x * width
        val cy = layer.y * height
        val hw = size.first * layer.scale / 2
        val hh = size.second * layer.scale / 2
        return RectF(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    private fun drawLayer(
        canvas: Canvas, w: Float, h: Float, layer: OverlayLayer,
        progress: Float, anim: LayerAnim, liveStartedAt: Long?, now: Long,
    ) {
        val alpha = (layer.opacity * when (anim) {
            LayerAnim.Fade, LayerAnim.Slide -> progress
            LayerAnim.Pop -> progress.coerceAtMost(1f)
            LayerAnim.None -> 1f
        }).coerceIn(0f, 1f)
        val cx = if (layer.type == LayerType.Ticker) w / 2 else layer.x * w
        val cy = layer.y * h
        val slide = if (anim == LayerAnim.Slide) -(1f - easeOut(progress)) * w * 0.15f else 0f
        val pop = if (anim == LayerAnim.Pop) popScale(progress) else 1f

        canvas.save()
        canvas.translate(cx + slide, cy)
        if (layer.type != LayerType.Ticker) {
            canvas.rotate(layer.rotation)
            canvas.scale(layer.scale * pop, layer.scale * pop)
        }
        val a = (alpha * 255).toInt()
        when (layer.type) {
            LayerType.Text -> drawText(canvas, layer, h, layer.text, a)
            LayerType.Image -> drawImage(canvas, layer, w, a)
            LayerType.LowerThird -> drawLowerThird(canvas, layer, h, a)
            LayerType.Clock -> drawText(canvas, layer, h, clockText(layer, liveStartedAt, now), a)
            LayerType.LiveBadge -> drawLiveBadge(canvas, layer, h, a, now)
            LayerType.Ticker -> drawTicker(canvas, layer, w, h, a, now)
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------- Jenis layer

    private fun preparePaint(layer: OverlayLayer, h: Float, sizeFactor: Float = 1f, weight: Int = layer.fontWeight) {
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = fonts.typeface(layer.fontKey, weight)
        paint.textSize = layer.textSize * h * sizeFactor
    }

    private fun drawText(canvas: Canvas, layer: OverlayLayer, h: Float, text: String, alpha: Int) {
        preparePaint(layer, h)
        val lines = text.ifEmpty { " " }.split('\n')
        val lineH = paint.textSize * 1.2f
        val widths = lines.map { paint.measureText(it) }
        val maxW = widths.maxOrNull() ?: 0f
        val totalH = lineH * lines.size
        val pad = paint.textSize * 0.35f
        if (layer.bgEnabled) {
            bgPaint.color = layer.bgColor
            bgPaint.alpha = Color.alpha(layer.bgColor) * alpha / 255
            canvas.drawRect(-maxW / 2 - pad, -totalH / 2 - pad * 0.6f, maxW / 2 + pad, totalH / 2 + pad * 0.6f, bgPaint)
        }
        val fm = paint.fontMetrics
        lines.forEachIndexed { i, line ->
            val baseline = -totalH / 2 + lineH * i + (lineH - (fm.descent - fm.ascent)) / 2 - fm.ascent
            strokeFill(canvas, line, -widths[i] / 2, baseline, layer, alpha)
        }
    }

    private fun strokeFill(canvas: Canvas, text: String, x: Float, y: Float, layer: OverlayLayer, alpha: Int) {
        if (layer.strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = layer.strokeWidth * paint.textSize
            paint.color = layer.strokeColor
            paint.alpha = Color.alpha(layer.strokeColor) * alpha / 255
            canvas.drawText(text, x, y, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = layer.textColor
        paint.alpha = Color.alpha(layer.textColor) * alpha / 255
        canvas.drawText(text, x, y, paint)
    }

    private fun drawImage(canvas: Canvas, layer: OverlayLayer, w: Float, alpha: Int) {
        val bmp = layer.imagePath?.let { image(it) } ?: return
        val targetW = w * BASE_IMAGE_WIDTH
        val targetH = targetW * bmp.height / bmp.width
        paint.reset()
        paint.isFilterBitmap = true
        paint.alpha = alpha
        canvas.drawBitmap(bmp, null, RectF(-targetW / 2, -targetH / 2, targetW / 2, targetH / 2), paint)
    }

    private fun drawLowerThird(canvas: Canvas, layer: OverlayLayer, h: Float, alpha: Int) {
        val (bw, bh) = contentSize(layer, 0f, h)
        val left = -bw / 2
        val top = -bh / 2
        if (layer.bgEnabled) {
            bgPaint.color = layer.bgColor
            bgPaint.alpha = Color.alpha(layer.bgColor) * alpha / 255
            canvas.drawRect(left, top, bw / 2, bh / 2, bgPaint)
        }
        // Garis aksen kiri
        bgPaint.color = layer.textColor
        bgPaint.alpha = alpha
        canvas.drawRect(left, top, left + h * 0.006f, bh / 2, bgPaint)

        val pad = layer.textSize * h * 0.45f
        preparePaint(layer, h)
        val fm1 = paint.fontMetrics
        val y1 = top + pad * 0.7f - fm1.ascent
        strokeFill(canvas, layer.text, left + pad, y1, layer, alpha)
        preparePaint(layer, h, sizeFactor = 0.62f, weight = 400)
        val fm2 = paint.fontMetrics
        val y2 = y1 + fm1.descent + pad * 0.2f - fm2.ascent
        paint.style = Paint.Style.FILL
        paint.color = layer.textColor
        paint.alpha = (alpha * 0.8f).toInt()
        canvas.drawText(layer.subText, left + pad, y2, paint)
    }

    private fun drawLiveBadge(canvas: Canvas, layer: OverlayLayer, h: Float, alpha: Int, now: Long) {
        preparePaint(layer, h, weight = 800)
        val text = layer.text.ifBlank { "LIVE" }
        val tw = paint.measureText(text)
        val dot = paint.textSize * 0.42f
        val pad = paint.textSize * 0.4f
        val bw = tw + dot + pad * 3
        val bh = paint.textSize * 1.5f
        bgPaint.color = LIVE_RED
        bgPaint.alpha = alpha
        canvas.drawRect(-bw / 2, -bh / 2, bw / 2, bh / 2, bgPaint)
        val blink = 0.55f + 0.45f * ((sin(now / 300.0) + 1) / 2).toFloat()
        bgPaint.color = Color.WHITE
        bgPaint.alpha = (alpha * blink).toInt()
        canvas.drawCircle(-bw / 2 + pad + dot / 2, 0f, dot / 2, bgPaint)
        val fm = paint.fontMetrics
        paint.color = Color.WHITE
        paint.alpha = alpha
        canvas.drawText(text, -bw / 2 + pad * 2 + dot, -(fm.ascent + fm.descent) / 2, paint)
    }

    private fun drawTicker(canvas: Canvas, layer: OverlayLayer, w: Float, h: Float, alpha: Int, now: Long) {
        preparePaint(layer, h)
        val bandH = paint.textSize * 1.6f
        if (layer.bgEnabled) {
            bgPaint.color = layer.bgColor
            bgPaint.alpha = Color.alpha(layer.bgColor) * alpha / 255
            canvas.drawRect(-w / 2, -bandH / 2, w / 2, bandH / 2, bgPaint)
        }
        val text = layer.text.ifBlank { " " } + "     •     "
        val tw = paint.measureText(text).coerceAtLeast(1f)
        val speedPx = layer.tickerSpeed * w // piksel per detik
        val offset = ((now / 1000f) * speedPx) % tw
        val fm = paint.fontMetrics
        val baseline = -(fm.ascent + fm.descent) / 2
        canvas.save()
        canvas.clipRect(-w / 2, -bandH / 2, w / 2, bandH / 2)
        var x = -w / 2 - offset
        while (x < w / 2) {
            strokeFill(canvas, text, x, baseline, layer, alpha)
            x += tw
        }
        canvas.restore()
    }

    private fun clockText(layer: OverlayLayer, liveStartedAt: Long?, now: Long): String = when (layer.clockMode) {
        ClockMode.Clock -> clockFormat.format(Date())
        ClockMode.Timer -> hms(if (liveStartedAt != null) now - liveStartedAt else 0L)
        ClockMode.Countdown -> {
            val elapsed = System.currentTimeMillis() - layer.timerStartedAt
            hms(max(0L, layer.countdownSeconds * 1000L - elapsed))
        }
    }

    // ---------------------------------------------------------------- Ukuran

    private fun contentSize(layer: OverlayLayer, w: Float, h: Float): Pair<Float, Float> {
        return when (layer.type) {
            LayerType.Text, LayerType.Clock -> {
                preparePaint(layer, h)
                val text = if (layer.type == LayerType.Clock) "00:00:00" else layer.text.ifEmpty { " " }
                val lines = text.split('\n')
                val pad = paint.textSize * 0.35f
                val maxW = lines.maxOf { paint.measureText(it) }
                Pair(maxW + pad * 2, paint.textSize * 1.2f * lines.size + pad * 1.2f)
            }
            LayerType.Image -> {
                val bmp = layer.imagePath?.let { image(it) }
                val tw = (if (w > 0f) w else h * 16 / 9) * BASE_IMAGE_WIDTH
                Pair(tw, if (bmp != null) tw * bmp.height / bmp.width else tw)
            }
            LayerType.LowerThird -> {
                preparePaint(layer, h)
                val w1 = paint.measureText(layer.text)
                val l1 = paint.fontMetrics.let { it.descent - it.ascent }
                preparePaint(layer, h, sizeFactor = 0.62f, weight = 400)
                val w2 = paint.measureText(layer.subText)
                val l2 = paint.fontMetrics.let { it.descent - it.ascent }
                val pad = layer.textSize * h * 0.45f
                Pair(max(w1, w2) + pad * 2.2f, l1 + l2 + pad * 1.6f)
            }
            LayerType.LiveBadge -> {
                preparePaint(layer, h, weight = 800)
                val tw = paint.measureText(layer.text.ifBlank { "LIVE" })
                Pair(tw + paint.textSize * 1.6f, paint.textSize * 1.5f)
            }
            LayerType.Ticker -> {
                preparePaint(layer, h)
                Pair(if (w > 0f) w else h * 16 / 9, paint.textSize * 1.6f)
            }
        }
    }

    private fun image(path: String): Bitmap? {
        images.get(path)?.let { return it }
        val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        images.put(path, bmp)
        return bmp
    }

    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    private fun popScale(t: Float): Float = when {
        t < 0.6f -> 0.6f + 0.55f * (t / 0.6f)          // 0.6 -> 1.15
        else -> 1.15f - 0.15f * ((t - 0.6f) / 0.4f)    // 1.15 -> 1.0
    }

    private fun hms(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    companion object {
        const val ANIM_MS = 350L
        const val BASE_IMAGE_WIDTH = 0.25f
        val LIVE_RED = Color.rgb(0xFF, 0x3B, 0x30)
    }
}
