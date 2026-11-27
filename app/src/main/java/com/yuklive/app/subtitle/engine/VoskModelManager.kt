package com.yuklive.app.subtitle.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class VoskModelInfo(val name: String, val label: String, val url: String, val sizeMb: Int)

data class VoskDownloadState(
    val downloading: String? = null,
    val progress: Float = 0f,
    val installed: List<String> = emptyList(),
    val error: Boolean = false,
)

/**
 * Mengunduh & memasang model Vosk ke filesDir/vosk/<nama>.
 * Catatan: daftar model resmi Vosk saat ini belum memuat model Bahasa Indonesia;
 * pengguna bisa memasukkan URL .zip model sendiri (mis. model hasil latih komunitas).
 */
@Singleton
class VoskModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root = File(context.filesDir, "vosk").apply { mkdirs() }

    private val _state = MutableStateFlow(VoskDownloadState(installed = listInstalled()))
    val state: StateFlow<VoskDownloadState> = _state.asStateFlow()

    fun modelDir(name: String): File = File(root, name)

    fun isInstalled(name: String?): Boolean = name != null && File(modelDir(name), "am").exists() ||
        name != null && File(modelDir(name), "conf").exists()

    suspend fun download(url: String): String? = withContext(Dispatchers.IO) {
        val name = url.substringAfterLast('/').removeSuffix(".zip").ifBlank { "model_${System.currentTimeMillis()}" }
        _state.update { it.copy(downloading = name, progress = 0f, error = false) }
        val zip = File(context.cacheDir, "$name.zip")
        val result = runCatching {
            var conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            // Ikuti redirect lintas protokol (http -> https) secara manual.
            var redirects = 0
            while (conn.responseCode in 300..399 && redirects < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(URL(url), next).openConnection() as HttpURLConnection
                redirects++
            }
            require(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _state.update { it.copy(progress = done.toFloat() / total * 0.9f) }
                    }
                }
            }
            val target = modelDir(name)
            target.deleteRecursively()
            unzipStripRoot(zip, target)
            _state.update { it.copy(progress = 1f) }
            name
        }
        zip.delete()
        _state.update {
            it.copy(downloading = null, error = result.isFailure, installed = listInstalled())
        }
        result.getOrNull()
    }

    fun delete(name: String) {
        modelDir(name).deleteRecursively()
        _state.update { it.copy(installed = listInstalled()) }
    }

    private fun listInstalled(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()

    /** Ekstrak zip; folder teratas di dalam zip dibuang supaya struktur model langsung di [target]. */
    private fun unzipStripRoot(zip: File, target: File) {
        target.mkdirs()
        val canonicalTarget = target.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val parts = entry.name.split('/').filter { it.isNotEmpty() }
                val rel = if (parts.size > 1) parts.drop(1).joinToString("/") else parts.joinToString("/")
                if (rel.isNotEmpty()) {
                    val out = File(target, rel)
                    require(out.canonicalPath.startsWith(canonicalTarget)) { "zip tidak aman" }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        val Catalog = listOf(
            VoskModelInfo(
                "vosk-model-small-en-us-0.15", "English (US) — small",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40,
            ),
            VoskModelInfo(
                "vosk-model-small-en-in-0.4", "English (India) — small",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip", 36,
            ),
        )
    }
}
