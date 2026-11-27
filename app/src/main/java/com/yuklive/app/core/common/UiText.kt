package com.yuklive.app.core.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Teks untuk UI yang bisa berasal dari resource (ikut bahasa aplikasi)
 * atau teks mentah. Dipakai engine supaya pesan error tidak terkunci ke satu bahasa.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText

    fun resolve(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.toTypedArray())
        is Raw -> text
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Res -> stringResource(id, *args.toTypedArray())
    is UiText.Raw -> text
}

/** Pesan sesaat untuk banner di Studio. */
data class UiMessage(
    val text: UiText,
    val level: Level = Level.Info,
    val id: Long = System.nanoTime(),
) {
    enum class Level { Info, Success, Warning, Error }
}
