package com.yuklive.app.overlay

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.util.concurrent.atomic.AtomicReference

/**
 * Filter OpenGL di pipeline RootEncoder yang menempelkan satu bitmap transparan
 * (semua overlay + subtitle + efek transisi) di atas video, menutupi seluruh frame.
 * Karena berada di dalam pipeline, hasilnya ikut ter-encode ke stream & rekaman,
 * bukan hanya tampil di layar.
 *
 * Bitmap baru diunggah dengan glTexSubImage2D (tanpa alokasi ulang tekstur) lalu
 * dikembalikan ke pool milik compositor lewat [onRelease].
 */
class OverlayFilterRender(
    private val onRelease: (Bitmap) -> Unit,
    /** Dipanggil saat filter belum punya gambar (mis. setelah pipeline GL dibuat ulang). */
    private val onNeedFrame: () -> Unit = {},
) : BaseObjectFilterRender() {

    private val pending = AtomicReference<Bitmap?>(null)
    private var textureId = intArrayOf(-1)
    private var texWidth = 0
    private var texHeight = 0

    /** Dipanggil dari thread compositor. Bitmap tidak boleh diubah sampai dikembalikan. */
    fun submit(bitmap: Bitmap) {
        pending.getAndSet(bitmap)?.let(onRelease)
    }

    override fun drawFilter() {
        super.drawFilter() // menyiapkan shader + tekstur video; unit aktif = TEXTURE1
        pending.getAndSet(null)?.let { bmp ->
            if (textureId[0] == -1 || bmp.width != texWidth || bmp.height != texHeight) {
                if (textureId[0] != -1) GLES20.glDeleteTextures(1, textureId, 0)
                GlUtil.createTextures(1, textureId, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                texWidth = bmp.width
                texHeight = bmp.height
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
            }
            onRelease(bmp)
        }
        if (textureId[0] == -1) onNeedFrame()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
        GLES20.glUniform1f(uAlphaHandle, if (textureId[0] == -1) 0f else 1f)
    }

    override fun release() {
        super.release()
        if (textureId[0] != -1) GLES20.glDeleteTextures(1, textureId, 0)
        textureId = intArrayOf(-1)
        texWidth = 0
        texHeight = 0
        pending.getAndSet(null)?.let(onRelease)
    }
}
