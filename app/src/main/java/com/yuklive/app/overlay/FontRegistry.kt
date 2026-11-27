package com.yuklive.app.overlay

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class FontOption(val key: String, val label: String, val custom: Boolean = false)

/**
 * Font untuk teks yang "dibakar" ke stream (overlay & subtitle).
 * Bawaan: Host Grotesk, Poppins, Montserrat, Bebas Neue, Anton (OFL, di assets/fonts).
 * Font .ttf/.otf pengguna disalin ke filesDir/fonts.
 */
@Singleton
class FontRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cache = ConcurrentHashMap<String, Typeface>()
    private val customDir = File(context.filesDir, "fonts").apply { mkdirs() }

    private val _options = MutableStateFlow(builtIn + listCustom())
    val options: StateFlow<List<FontOption>> = _options.asStateFlow()

    fun typeface(key: String, weight: Int): Typeface {
        val w = weight.coerceIn(100, 900)
        return cache.getOrPut("$key@$w") { load(key, w) }
    }

    private fun load(key: String, weight: Int): Typeface = runCatching {
        when {
            key == KEY_POPPINS -> {
                val file = when {
                    weight <= 500 -> "Poppins-Regular.ttf"
                    weight <= 750 -> "Poppins-Bold.ttf"
                    else -> "Poppins-Black.ttf"
                }
                Typeface.createFromAsset(context.assets, "fonts/$file")
            }
            key == KEY_MONTSERRAT -> variable("fonts/Montserrat-Variable.ttf", weight)
            key == KEY_HOST -> variable("fonts/HostGrotesk-Variable.ttf", weight.coerceIn(300, 800))
            key == KEY_BEBAS -> Typeface.createFromAsset(context.assets, "fonts/BebasNeue-Regular.ttf")
            key == KEY_ANTON -> Typeface.createFromAsset(context.assets, "fonts/Anton-Regular.ttf")
            key.startsWith(CUSTOM_PREFIX) -> {
                val f = File(customDir, key.removePrefix(CUSTOM_PREFIX))
                Typeface.Builder(f).setFontVariationSettings("'wght' $weight").build()
                    ?: Typeface.createFromFile(f)
            }
            else -> Typeface.DEFAULT_BOLD
        }
    }.getOrDefault(Typeface.DEFAULT_BOLD)

    private fun variable(path: String, weight: Int): Typeface =
        Typeface.Builder(context.assets, path)
            .setFontVariationSettings("'wght' $weight")
            .build()

    /** Impor font dari pemilih file. Mengembalikan key font baru, atau null kalau gagal. */
    fun importFont(uri: Uri): String? = runCatching {
        val name = displayName(uri) ?: "font_${System.currentTimeMillis()}.ttf"
        val ext = name.substringAfterLast('.', "").lowercase()
        require(ext == "ttf" || ext == "otf")
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(customDir, safe)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        // Validasi: font harus bisa dibaca.
        Typeface.createFromFile(target)
        _options.value = builtIn + listCustom()
        CUSTOM_PREFIX + safe
    }.getOrNull()

    private fun listCustom(): List<FontOption> =
        customDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.map {
            FontOption(CUSTOM_PREFIX + it.name, it.nameWithoutExtension, custom = true)
        }.orEmpty()

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    companion object {
        const val KEY_HOST = "hostgrotesk"
        const val KEY_POPPINS = "poppins"
        const val KEY_MONTSERRAT = "montserrat"
        const val KEY_BEBAS = "bebasneue"
        const val KEY_ANTON = "anton"
        const val CUSTOM_PREFIX = "custom:"

        val builtIn = listOf(
            FontOption(KEY_POPPINS, "Poppins"),
            FontOption(KEY_MONTSERRAT, "Montserrat"),
            FontOption(KEY_BEBAS, "Bebas Neue"),
            FontOption(KEY_ANTON, "Anton"),
            FontOption(KEY_HOST, "Host Grotesk"),
        )
    }
}
