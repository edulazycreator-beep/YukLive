package com.yuklive.app.feature.sources

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuklive.app.R
import com.yuklive.app.audio.AudioChannel
import com.yuklive.app.core.device.NetworkUtils
import com.yuklive.app.feature.studio.channelLabel
import com.yuklive.app.ui.components.HairlineDivider
import com.yuklive.app.ui.components.LabeledSlider
import com.yuklive.app.ui.components.ListRow
import com.yuklive.app.ui.components.PillButton
import com.yuklive.app.ui.components.PillToggle
import com.yuklive.app.ui.components.PillVariant
import com.yuklive.app.ui.components.RowGroup
import com.yuklive.app.ui.components.ScreenHeader
import com.yuklive.app.ui.components.ScrollPills
import com.yuklive.app.ui.components.SectionHeader
import com.yuklive.app.ui.components.StatusBanner
import com.yuklive.app.ui.components.ToggleRow
import com.yuklive.app.ui.components.VuMeter
import com.yuklive.app.ui.components.YukCard
import com.yuklive.app.ui.components.YukLabel
import com.yuklive.app.ui.components.YukTextField
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.PillShape
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType
import com.yuklive.app.video.LensKind
import com.yuklive.app.video.UvcStatus
import com.yuklive.app.video.network.NetworkInputConfig
import com.yuklive.app.video.network.NetworkInputType
import com.yuklive.app.video.network.NetworkStatus
import kotlin.math.roundToInt

@Composable
fun SourcesScreen(onBack: () -> Unit, viewModel: SourcesViewModel = hiltViewModel()) {
    val cam by viewModel.cameraState.collectAsStateWithLifecycle()
    val uvc by viewModel.uvcState.collectAsStateWithLifecycle()
    val net by viewModel.networkState.collectAsStateWithLifecycle()
    val mixer by viewModel.mixerState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.screen_sources), onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            // ---------------- Kamera HP
            SectionHeader(stringResource(R.string.sources_camera))
            RowGroup {
                if (cam.lenses.isEmpty()) {
                    ListRow(title = stringResource(R.string.sources_no_camera), enabled = false)
                }
                cam.lenses.forEachIndexed { i, lens ->
                    if (i > 0) HairlineDivider()
                    ListRow(
                        title = stringResource(
                            when (lens.kind) {
                                LensKind.Back -> R.string.lens_back
                                LensKind.UltraWide -> R.string.lens_ultrawide
                                LensKind.Tele -> R.string.lens_tele
                                LensKind.Front -> R.string.lens_front
                            },
                        ),
                        subtitle = if (lens.kind != LensKind.Front) String.format(java.util.Locale.US, "%.1fx", lens.zoomFactor) else null,
                        icon = Icons.Outlined.CameraAlt,
                        onClick = { viewModel.selectLens(lens) },
                        trailing = { PillToggle(lens == cam.selected, { viewModel.selectLens(lens) }) },
                    )
                }
            }

            // ---------------- Capture card USB
            SectionHeader(stringResource(R.string.sources_usb))
            when (uvc.status) {
                UvcStatus.NoOtg -> StatusBanner(stringResource(R.string.usb_no_otg), YukColors.Live)
                UvcStatus.Idle -> StatusBanner(stringResource(R.string.usb_idle), YukColors.TextSecondary)
                UvcStatus.WaitingPermission -> StatusBanner(stringResource(R.string.usb_waiting_permission, uvc.deviceName.orEmpty()), YukColors.Warning)
                UvcStatus.Opening -> StatusBanner(stringResource(R.string.usb_opening), YukColors.Warning)
                UvcStatus.Ready -> StatusBanner(stringResource(R.string.usb_ready, uvc.deviceName.orEmpty()), YukColors.Success)
                UvcStatus.Error -> StatusBanner(stringResource(R.string.usb_error), YukColors.Live)
            }
            if (uvc.formats.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.S1))
                RowGroup {
                    uvc.formats.take(12).forEachIndexed { i, f ->
                        if (i > 0) HairlineDivider()
                        ListRow(
                            title = f.label,
                            icon = Icons.Outlined.Usb,
                            onClick = { viewModel.selectUvcFormat(f) },
                            trailing = { PillToggle(f == uvc.selected, { viewModel.selectUvcFormat(f) }) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(Dimens.S1))
            RowGroup {
                ToggleRow(
                    stringResource(R.string.usb_audio),
                    mixer.usbAudioEnabled,
                    viewModel::setUsbAudio,
                    subtitle = stringResource(R.string.usb_audio_hint),
                )
            }

            // ---------------- Wi-Fi
            SectionHeader(stringResource(R.string.sources_wifi))
            NetworkInputSection(
                config = net.config,
                status = net.status,
                bitrate = net.bitrateKbps,
                bufferMs = net.bufferMs,
                size = if (net.videoWidth > 0) "${net.videoWidth}×${net.videoHeight}" else null,
                attempt = net.attempt,
                localIp = viewModel.localIp,
                onSave = viewModel::saveNetworkConfig,
                onStartTest = viewModel::startNetworkTest,
                onStopTest = viewModel::stopNetworkTest,
            )

            // ---------------- Audio
            SectionHeader(stringResource(R.string.sources_audio_mixer))
            Text(stringResource(R.string.mixer_mic_device), style = YukType.Body)
            Spacer(Modifier.height(6.dp))
            val devices = listOf(0 to stringResource(R.string.mixer_default_mic)) + mixer.inputDevices.map { it.id to it.name }
            ScrollPills(
                devices.map { it.second },
                devices.indexOfFirst { it.first == mixer.micDeviceId }.coerceAtLeast(0),
                { viewModel.selectMic(devices[it].first) },
            )
            if (mixer.micError) {
                Spacer(Modifier.height(Dimens.S1))
                StatusBanner(stringResource(R.string.mixer_mic_error), YukColors.Live)
            }
            Spacer(Modifier.height(Dimens.S1))
            RowGroup {
                AudioChannel.entries.forEachIndexed { i, ch ->
                    if (i > 0) HairlineDivider()
                    val cs = mixer.channels.getValue(ch)
                    Column(Modifier.padding(Dimens.S2)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(channelLabel(ch)), style = YukType.Body, modifier = Modifier.weight(1f))
                            YukLabel(
                                stringResource(if (cs.active) R.string.mixer_active else R.string.mixer_inactive),
                                color = if (cs.active) YukColors.Success else YukColors.TextDisabled,
                            )
                            Spacer(Modifier.width(Dimens.S1))
                            PillToggle(!cs.muted, { on -> viewModel.setMuted(ch, !on) })
                        }
                        Spacer(Modifier.height(Dimens.S1))
                        VuMeter(cs.level)
                        LabeledSlider(
                            label = stringResource(R.string.mixer_level),
                            valueText = "${(cs.gain * 100).roundToInt()}%",
                            value = cs.gain,
                            onValueChange = { viewModel.setGain(ch, it) },
                            valueRange = 0f..2f,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Dimens.S4))
        }
    }
}

@Composable
private fun NetworkInputSection(
    config: NetworkInputConfig,
    status: NetworkStatus,
    bitrate: Int,
    bufferMs: Long,
    size: String?,
    attempt: Int,
    localIp: String?,
    onSave: (NetworkInputConfig) -> Unit,
    onStartTest: (android.view.Surface?) -> Unit,
    onStopTest: () -> Unit,
) {
    var type by rememberSaveable { mutableStateOf(config.type) }
    var url by rememberSaveable { mutableStateOf(config.url) }
    var port by rememberSaveable { mutableStateOf(config.port.toString()) }
    var latency by rememberSaveable { mutableStateOf(config.latencyMs.toString()) }
    var pass by rememberSaveable { mutableStateOf(config.passphrase) }
    var testing by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }

    val draft = NetworkInputConfig(
        type = type,
        url = url.trim(),
        port = port.toIntOrNull() ?: 0,
        latencyMs = latency.toIntOrNull() ?: 120,
        passphrase = pass,
    )

    val typeLabels = listOf(
        stringResource(R.string.net_type_srt_listener),
        stringResource(R.string.net_type_srt_caller),
        stringResource(R.string.net_type_rtsp),
        stringResource(R.string.net_type_rtmp),
        stringResource(R.string.net_type_flv),
    )
    val types = listOf(
        NetworkInputType.SrtListener, NetworkInputType.SrtCaller, NetworkInputType.Rtsp,
        NetworkInputType.Rtmp, NetworkInputType.HttpFlv,
    )
    ScrollPills(typeLabels, types.indexOf(type), { type = types[it] })
    Spacer(Modifier.height(Dimens.S2))

    if (type == NetworkInputType.SrtListener) {
        Text(stringResource(R.string.net_listener_hint), style = YukType.BodySmall, color = YukColors.TextSecondary)
        Spacer(Modifier.height(Dimens.S1))
        YukTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = stringResource(R.string.net_port),
            keyboardType = KeyboardType.Number,
            error = if (showErrors && !draft.isValid()) stringResource(R.string.net_port_invalid) else null)
        val ip = localIp
        if (ip != null && draft.isValid()) {
            val senderUrl = "srt://$ip:${draft.port}"
            Spacer(Modifier.height(Dimens.S2))
            YukCard {
                YukLabel(stringResource(R.string.net_send_to))
                Spacer(Modifier.height(4.dp))
                Text(senderUrl, style = YukType.TitleSmall)
                Spacer(Modifier.height(Dimens.S2))
                val qr = remember(senderUrl) { NetworkUtils.qrBitmap(senderUrl)?.asImageBitmap() }
                if (qr != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(qr, contentDescription = senderUrl, modifier = Modifier.size(180.dp).background(Color.White))
                    }
                }
            }
        } else if (ip == null) {
            Spacer(Modifier.height(Dimens.S1))
            StatusBanner(stringResource(R.string.net_no_wifi), YukColors.Warning)
        }
    } else {
        val hint = when (type) {
            NetworkInputType.Rtsp -> "rtsp://192.168.1.10:554/stream"
            NetworkInputType.SrtCaller -> "srt://192.168.1.10:9000"
            NetworkInputType.Rtmp -> "rtmp://192.168.1.10/live/stream"
            else -> "http://192.168.1.10:8080/live.flv"
        }
        YukTextField(url, { url = it }, label = stringResource(R.string.net_url), placeholder = hint,
            keyboardType = KeyboardType.Uri,
            error = if (showErrors && !draft.isValid()) stringResource(R.string.net_url_invalid) else null)
    }
    if (type == NetworkInputType.SrtCaller || type == NetworkInputType.SrtListener) {
        Spacer(Modifier.height(Dimens.S1))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.S1)) {
            YukTextField(latency, { latency = it.filter(Char::isDigit).take(4) }, label = stringResource(R.string.net_latency),
                keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
            YukTextField(pass, { pass = it }, label = stringResource(R.string.net_passphrase), password = true,
                modifier = Modifier.weight(1f))
        }
    }

    Spacer(Modifier.height(Dimens.S2))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.S1)) {
        PillButton(
            text = stringResource(R.string.net_save),
            onClick = {
                showErrors = true
                if (draft.isValid()) onSave(draft)
            },
            modifier = Modifier.weight(1f),
        )
        PillButton(
            text = stringResource(if (testing) R.string.net_stop_test else R.string.net_test),
            onClick = {
                showErrors = true
                if (testing) {
                    testing = false
                    onStopTest()
                } else if (draft.isValid()) {
                    onSave(draft)
                    testing = true
                }
            },
            variant = PillVariant.Secondary,
            modifier = Modifier.weight(1f),
        )
    }

    if (testing) {
        Spacer(Modifier.height(Dimens.S2))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(YukColors.Black)
                .border(Dimens.Hairline, YukColors.Border),
        ) {
            TestSurface(onStartTest, onStopTest)
        }
    }

    Spacer(Modifier.height(Dimens.S1))
    YukCard {
        val (label, color) = when (status) {
            NetworkStatus.Idle -> stringResource(R.string.net_status_idle) to YukColors.TextSecondary
            NetworkStatus.Connecting -> stringResource(R.string.net_status_connecting) to YukColors.Warning
            NetworkStatus.WaitingSender -> stringResource(R.string.net_status_waiting) to YukColors.Warning
            NetworkStatus.Playing -> stringResource(R.string.net_status_playing) to YukColors.Success
            NetworkStatus.Reconnecting -> stringResource(R.string.net_status_reconnecting, attempt) to YukColors.Warning
            NetworkStatus.Error -> stringResource(R.string.net_status_error) to YukColors.Live
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, PillShape))
            Spacer(Modifier.width(Dimens.S1))
            Text(label, style = YukType.Body, color = color)
        }
        Spacer(Modifier.height(Dimens.S1))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.S2)) {
            Stat(stringResource(R.string.net_stat_bitrate), stringResource(R.string.studio_bitrate_value, bitrate))
            Stat(stringResource(R.string.net_stat_latency), "$bufferMs ms")
            Stat(stringResource(R.string.net_stat_size), size ?: "—")
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        YukLabel(label)
        Text(value, style = YukType.Mono, color = YukColors.TextPrimary)
    }
}

@Composable
private fun TestSurface(onStart: (android.view.Surface?) -> Unit, onStop: () -> Unit) {
    var view by remember { mutableStateOf<SurfaceView?>(null) }
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also { sv ->
                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = onStart(holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) = onStop()
                })
                view = sv
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(Unit) { onDispose { onStop() } }
}
