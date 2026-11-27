package com.yuklive.app.feature.studio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yuklive.app.R
import com.yuklive.app.ui.components.PillButton
import com.yuklive.app.ui.components.PillVariant
import com.yuklive.app.ui.components.YukCard
import com.yuklive.app.ui.components.YukLabel
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType

private val Required = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

private fun requested(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        (Required + Manifest.permission.POST_NOTIFICATIONS).toTypedArray()
    } else Required.toTypedArray()

private fun Context.hasRequired(): Boolean =
    Required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

/** Menampilkan [content] hanya setelah izin kamera & mikrofon diberikan, dengan penjelasan. */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasRequired()) }
    var askedOnce by remember { mutableStateOf(false) }

    // Cek ulang saat kembali dari halaman Pengaturan Android.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = context.hasRequired() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        granted = context.hasRequired()
    }

    if (granted) {
        content()
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.Center,
    ) {
        YukLabel(stringResource(R.string.perm_label))
        Spacer(Modifier.height(Dimens.S1))
        Text(stringResource(R.string.perm_title), style = YukType.Headline)
        Spacer(Modifier.height(Dimens.S2))
        YukCard {
            Text(stringResource(R.string.perm_camera), style = YukType.Body)
            Spacer(Modifier.height(Dimens.S1))
            Text(stringResource(R.string.perm_mic), style = YukType.Body)
            Spacer(Modifier.height(Dimens.S1))
            Text(stringResource(R.string.perm_notif), style = YukType.Body, color = YukColors.TextSecondary)
        }
        Spacer(Modifier.height(Dimens.S3))
        PillButton(
            text = stringResource(R.string.perm_allow),
            onClick = { launcher.launch(requested()) },
            modifier = Modifier.fillMaxWidth(),
            height = 56.dp,
        )
        if (askedOnce) {
            Spacer(Modifier.height(Dimens.S1))
            Text(stringResource(R.string.perm_denied_hint), style = YukType.BodySmall, color = YukColors.TextSecondary)
            Spacer(Modifier.height(Dimens.S1))
            PillButton(
                text = stringResource(R.string.perm_open_settings),
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                variant = PillVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
