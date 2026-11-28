package com.yuklive.app.video

import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Input "Color bar": pola uji ala SMPTE yang digambar dengan Canvas ke SurfaceTexture.
 * Jadi sumber default scene baru, dan tidak butuh izin kamera.
 */
@Singleton
class ColorBarSource @Inject constructor() : VideoSource() {

    private var barW = 1920
    private var barH = 1080
    private var surface: Surface? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var running = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        // Selalu digambar landscape; pipeline yang menyesuaikan ke orientasi stream.
        barW = maxOf(width, height)
        barH = minOf(width, height)
        return true
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        surfaceTexture.setDefaultBufferSize(barW, barH)
        surface = Surface(surfaceTexture)
        val t = HandlerThread("ColorBar").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        running = true
        handler?.post(drawLoop)
    }

    private val drawLoop: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            draw()
            // Kirim ulang frame tiap 500 ms agar encoder tetap menerima gambar.
            handler?.postDelayed(this, 500)
        }
    }

    private fun draw() {
        val s = surface ?: return
        val canvas = runCatching { s.lockCanvas(null) }.getOrNull() ?: return
        try {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            canvas.drawColor(Color.BLACK)
            // Baris atas: 7 bar 75%
            val top = intArrayOf(0xFFBFBFBF.toInt(), 0xFFBFBF00.toInt(), 0xFF00BFBF.toInt(), 0xFF00BF00.toInt(),
                0xFFBF00BF.toInt(), 0xFFBF0000.toInt(), 0xFF0000BF.toInt())
            val bw = w / 7f
            val topH = h * 0.67f
            top.forEachIndexed { i, c ->
                paint.color = c
                canvas.drawRect(i * bw, 0f, (i + 1) * bw, topH, paint)
            }
            // Baris tengah: bar kebalikan
            val mid = intArrayOf(0xFF0000BF.toInt(), 0xFF131313.toInt(), 0xFFBF00BF.toInt(), 0xFF131313.toInt(),
                0xFF00BFBF.toInt(), 0xFF131313.toInt(), 0xFFBFBFBF.toInt())
            val midH = h * 0.08f
            mid.forEachIndexed { i, c ->
                paint.color = c
                canvas.drawRect(i * bw, topH, (i + 1) * bw, topH + midH, paint)
            }
            // Baris bawah: -I, putih, +Q, hitam, pluge
            val y0 = topH + midH
            val q = w / 7f * 5f / 4f
            val bottom = intArrayOf(0xFF00214C.toInt(), 0xFFFFFFFF.toInt(), 0xFF32006A.toInt(), 0xFF131313.toInt())
            bottom.forEachIndexed { i, c ->
                paint.color = c
                canvas.drawRect(i * q, y0, (i + 1) * q, h, paint)
            }
            val px = w / 7f * 5f
            val pw = w / 7f / 3f
            intArrayOf(0xFF090909.toInt(), 0xFF131313.toInt(), 0xFF1D1D1D.toInt()).forEachIndexed { i, c ->
                paint.color = c
                canvas.drawRect(px + i * pw, y0, px + (i + 1) * pw, h, paint)
            }
            paint.color = 0xFF131313.toInt()
            canvas.drawRect(w / 7f * 6f, y0, w, h, paint)
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    override fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        runCatching { surface?.release() }
        surface = null
    }

    override fun release() = stop()

    override fun isRunning(): Boolean = running

    override fun getOrientationConfig() = OrientationConfig(forced = OrientationForced.LANDSCAPE)
}
