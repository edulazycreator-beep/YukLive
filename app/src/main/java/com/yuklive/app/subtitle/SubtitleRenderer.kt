package com.yuklive.app.subtitle

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.yuklive.app.overlay.FontRegistry
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Menggambar subtitle kinetik ke Canvas: hanya kata baru yang dianimasikan,
 * kata terakhir diberi warna aksen. Dipakai compositor stream dan preview di aplikasi
 * (bukan singleton: tiap thread punya instance sendiri).
 */
class SubtitleRenderer @Inject constructor(
    private val fonts: FontRegistry,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** @return true kalau masih ada kata yang sedang beranimasi. */
    fun draw(canvas: Canvas, width: Float, height: Float, frame: SubtitleFrame, now: Long): Boolean {
        val words = frame.words
        if (words.isEmpty()) return false
        val style = frame.style
        val textSize = min(width, height) * style.size
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = fonts.typeface(style.fontKey, style.fontWeight)
        paint.textSize = textSize

        val space = paint.measureText(" ")
        val lineH = textSize * 1.18f
        val lines = words.chunked(style.maxWordsPerLine.coerceAtLeast(1)).take(style.maxLines)
        val blockH = lineH * lines.size
        val margin = height * 0.08f
        val top = when (style.position) {
            SubtitlePosition.Top -> margin
            SubtitlePosition.Center -> (height - blockH) / 2
            SubtitlePosition.Bottom -> height - margin - blockH
        }
        val fm = paint.fontMetrics
        val lastIndex = words.lastIndex
        val sideMargin = width * 0.06f
        var animating = false
        var globalIndex = 0

        lines.forEachIndexed { li, line ->
            val widths = line.map { paint.measureText(it.text) }
            val lineW = widths.sum() + space * (line.size - 1)
            var x = when (style.align) {
                SubtitleAlign.Left -> sideMargin
                SubtitleAlign.Center -> (width - lineW) / 2
                SubtitleAlign.Right -> width - sideMargin - lineW
            }
            val lineTop = top + lineH * li
            val baseline = lineTop + (lineH - (fm.descent - fm.ascent)) / 2 - fm.ascent

            if (style.look == SubtitleLook.Box) {
                val pad = textSize * 0.28f
                boxPaint.color = style.boxColor
                boxPaint.alpha = (Color.alpha(style.boxColor) * style.opacity).toInt()
                rect.set(x - pad, lineTop - pad * 0.15f, x + lineW + pad, lineTop + lineH + pad * 0.15f)
                canvas.drawRoundRect(rect, pad * 0.5f, pad * 0.5f, boxPaint)
            }

            line.forEachIndexed { wi, word ->
                val isLast = globalIndex == lastIndex
                val t = ((now - word.bornAt) / durationOf(style.animation, word.text).toFloat()).coerceIn(0f, 1f)
                if (t < 1f) animating = true
                drawWord(canvas, word.text, x, baseline, widths[wi], textSize, style, t, isLast)
                x += widths[wi] + space
                globalIndex++
            }
        }
        return animating
    }

    private fun drawWord(
        canvas: Canvas, text: String, x: Float, baseline: Float, wordW: Float,
        textSize: Float, style: SubtitleStyle, t: Float, isLast: Boolean,
    ) {
        var scale = 1f
        var rotation = 0f
        var dy = 0f
        var alpha = 1f
        var shown = text
        when (style.animation) {
            SubtitleAnimation.ComicSlam -> {
                // 1.8 -> 0.9 -> 1.0 dengan sedikit rotasi, ±250 ms
                scale = if (t < 0.6f) lerp(1.8f, 0.9f, easeIn(t / 0.6f)) else lerp(0.9f, 1f, (t - 0.6f) / 0.4f)
                rotation = (1f - t) * -9f
                alpha = min(1f, t * 4f)
            }
            SubtitleAnimation.Pop -> {
                scale = if (t < 0.6f) lerp(0.4f, 1.15f, t / 0.6f) else lerp(1.15f, 1f, (t - 0.6f) / 0.4f)
                alpha = min(1f, t * 3f)
            }
            SubtitleAnimation.FadeUp -> {
                dy = (1f - easeOut(t)) * textSize * 0.45f
                alpha = t
            }
            SubtitleAnimation.Typewriter -> {
                val n = (text.length * t).toInt().coerceIn(if (t > 0f) 1 else 0, text.length)
                shown = text.substring(0, n)
            }
            SubtitleAnimation.Bounce -> {
                dy = -abs(sin(t * PI * 2.5).toFloat()) * exp(-4f * t) * textSize * 0.6f
                alpha = min(1f, t * 5f)
            }
            SubtitleAnimation.None -> Unit
        }
        if (shown.isEmpty()) return

        val opacity = (alpha * style.opacity).coerceIn(0f, 1f)
        val cx = x + wordW / 2
        canvas.save()
        canvas.translate(cx, baseline + dy)
        if (rotation != 0f) canvas.rotate(rotation)
        if (scale != 1f) canvas.scale(scale, scale)

        val fill = if (isLast && style.look != SubtitleLook.Karaoke) style.accentColor else style.textColor

        if (style.look == SubtitleLook.Karaoke && isLast) {
            val fm = paint.fontMetrics
            val pad = textSize * 0.14f
            boxPaint.color = style.accentColor
            boxPaint.alpha = (255 * opacity).toInt()
            rect.set(-wordW / 2 - pad, fm.ascent * 0.92f, wordW / 2 + pad, fm.descent * 0.9f)
            canvas.drawRoundRect(rect, pad, pad, boxPaint)
        }

        val left = -wordW / 2
        val strokeW = style.strokeWidth * textSize
        if (strokeW > 0f || style.hollow) {
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = if (style.hollow) maxOf(strokeW, textSize * 0.05f) else strokeW
            paint.color = if (style.hollow) fill else style.strokeColor
            paint.alpha = (Color.alpha(paint.color) * opacity).toInt()
            canvas.drawText(shown, left, 0f, paint)
        }
        if (!style.hollow) {
            paint.style = Paint.Style.FILL
            paint.color = if (style.look == SubtitleLook.Karaoke && isLast) style.strokeColor else fill
            paint.alpha = (Color.alpha(paint.color) * opacity).toInt()
            canvas.drawText(shown, left, 0f, paint)
        }
        canvas.restore()
    }

    private fun durationOf(anim: SubtitleAnimation, text: String): Long = when (anim) {
        SubtitleAnimation.ComicSlam -> 250
        SubtitleAnimation.Pop -> 220
        SubtitleAnimation.FadeUp -> 260
        SubtitleAnimation.Typewriter -> (45L * text.length).coerceIn(90, 600)
        SubtitleAnimation.Bounce -> 480
        SubtitleAnimation.None -> 1
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)
    private fun easeIn(t: Float) = (1 - cos(t * PI / 2)).toFloat()
}
