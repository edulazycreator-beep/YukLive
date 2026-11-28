package com.yuklive.app.stream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Process
import android.os.SystemClock
import com.yuklive.app.overlay.OverlayFilterRender
import com.yuklive.app.overlay.OverlayRenderer
import com.yuklive.app.overlay.OverlayRepository
import com.yuklive.app.subtitle.SubtitleEngine
import com.yuklive.app.subtitle.SubtitleRenderer
import java.util.concurrent.ArrayBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Compositor: menggambar overlay scene aktif + subtitle live + efek transisi (fade)
 * ke bitmap transparan ~30 fps, lalu menyerahkannya ke [OverlayFilterRender] di
 * pipeline OpenGL. Frame hanya digambar ulang kalau ada perubahan/animasi.
 */
@Singleton
class StreamCompositor @Inject constructor(
    private val overlayRepo: OverlayRepository,
    private val overlayRenderer: OverlayRenderer,
    private val subtitleEngine: SubtitleEngine,
    private val subtitleRenderer: SubtitleRenderer,
) {
    private val pool = ArrayBlockingQueue<Bitmap>(POOL_SIZE)
    val filter = OverlayFilterRender(
        onRelease = { bmp -> if (bmp.width == width && bmp.height == height) pool.offer(bmp) },
        onNeedFrame = { forceRedraw = true },
    )

    @Volatile private var width = 1280
    @Volatile private var height = 720
    @Volatile var overlayEnabled = true
    @Volatile var liveStartedAt: Long? = null

    // Transisi: 0 = video terlihat, 1 = layar hitam penuh.
    @Volatile private var fadeFrom = 0f
    @Volatile private var fadeTo = 0f
    @Volatile private var fadeStart = 0L
    @Volatile private var fadeDuration = 1L

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var forceRedraw = true

    private val blackPaint = Paint().apply { color = Color.BLACK }

    @Synchronized
    fun setOutputSize(outputWidth: Int, outputHeight: Int) {
        // Overlay maks 1080p; GPU men-scale ke resolusi encoder (mis. 4K).
        val scale = min(1f, 1920f / max(outputWidth, outputHeight))
        val w = (outputWidth * scale).toInt().coerceAtLeast(2)
        val h = (outputHeight * scale).toInt().coerceAtLeast(2)
        if (w == width && h == height && pool.isNotEmpty()) return
        width = w
        height = h
        pool.clear()
        repeat(POOL_SIZE) { pool.offer(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)) }
        forceRedraw = true
    }

    @Synchronized
    fun start() {
        if (running) return
        if (pool.isEmpty()) setOutputSize(width, height)
        running = true
        forceRedraw = true
        thread = Thread({ loop() }, "YukLive-Compositor").apply { start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    fun invalidate() {
        forceRedraw = true
    }

    /** Fade ke hitam (target 1f) atau kembali ke video (target 0f). */
    fun fade(target: Float, durationMs: Long) {
        fadeFrom = currentFade(SystemClock.elapsedRealtime())
        fadeTo = target.coerceIn(0f, 1f)
        fadeStart = SystemClock.elapsedRealtime()
        fadeDuration = max(1L, durationMs)
        forceRedraw = true
    }

    private fun currentFade(now: Long): Float {
        val t = ((now - fadeStart) / fadeDuration.toFloat()).coerceIn(0f, 1f)
        return fadeFrom + (fadeTo - fadeFrom) * t
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        var lastVersion = -1L
        var lastDocHash = 0
        var wasAnimating = true
        var lastSecond = -1L
        var lastFade = -1f

        while (running) {
            val frameStart = SystemClock.elapsedRealtime()
            val now = frameStart
            val doc = overlayRepo.document.value
            val layers = if (overlayEnabled) doc.activeScene.layers else emptyList()
            val subtitle = subtitleEngine.frame()
            val fade = currentFade(now)
            val sceneSubtitle = doc.activeScene.showSubtitle
            val docHash = layers.hashCode() + (if (overlayEnabled) 1 else 0) + (if (sceneSubtitle) 2 else 0)
            val second = now / 1000

            val dirty = forceRedraw || wasAnimating || docHash != lastDocHash ||
                subtitle.version != lastVersion || fade != lastFade || second != lastSecond

            if (dirty) {
                val bmp = pool.poll()
                if (bmp != null) {
                    forceRedraw = false
                    bmp.eraseColor(Color.TRANSPARENT)
                    val canvas = Canvas(bmp)
                    val w = bmp.width.toFloat()
                    val h = bmp.height.toFloat()
                    var animating = overlayRenderer.draw(canvas, w, h, layers, liveStartedAt, now)
                    if (subtitle.visible && sceneSubtitle) {
                        animating = subtitleRenderer.draw(canvas, w, h, subtitle, now) || animating
                    }
                    if (fade > 0f) {
                        blackPaint.alpha = (fade * 255).toInt()
                        canvas.drawRect(0f, 0f, w, h, blackPaint)
                    }
                    animating = animating || (fade != fadeTo)
                    filter.submit(bmp)
                    wasAnimating = animating
                    lastVersion = subtitle.version
                    lastDocHash = docHash
                    lastSecond = second
                    lastFade = fade
                }
            }

            val elapsed = SystemClock.elapsedRealtime() - frameStart
            val sleep = FRAME_MS - elapsed
            if (sleep > 0) Thread.sleep(sleep)
        }
    }

    private companion object {
        const val POOL_SIZE = 3
        const val FRAME_MS = 33L
    }
}
