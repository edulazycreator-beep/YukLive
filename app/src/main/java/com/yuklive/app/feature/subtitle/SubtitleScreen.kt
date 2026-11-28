package com.yuklive.app.feature.subtitle

import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuklive.app.R
import com.yuklive.app.subtitle.RecognizerStatus
import com.yuklive.app.subtitle.SpeechEngineType
import com.yuklive.app.subtitle.SubtitleAlign
import com.yuklive.app.subtitle.SubtitleAnimation
import com.yuklive.app.subtitle.SubtitleConfig
import com.yuklive.app.subtitle.SubtitleLook
import com.yuklive.app.subtitle.SubtitlePosition
import com.yuklive.app.subtitle.engine.VoskModelManager
import com.yuklive.app.ui.components.ColorSwatches
import com.yuklive.app.ui.components.HairlineDivider
import com.yuklive.app.ui.components.LabeledSlider
import com.yuklive.app.ui.components.ListRow
import com.yuklive.app.ui.components.PillButton
import com.yuklive.app.ui.components.PillVariant
import com.yuklive.app.ui.components.RowGroup
import com.yuklive.app.ui.components.ScreenHeader
import com.yuklive.app.ui.components.ScrollPills
import com.yuklive.app.ui.components.SectionHeader
import com.yuklive.app.ui.components.StatusBanner
import com.yuklive.app.ui.components.ToggleRow
import com.yuklive.app.ui.components.YukLabel
import com.yuklive.app.ui.components.YukTextField
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType
import kotlin.math.roundToInt

@Composable
fun SubtitleScreen(onBack: () -> Unit, viewModel: SubtitleViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val rec by viewModel.recognizer.collectAsStateWithLifecycle()
    val fonts by viewModel.fontOptions.collectAsStateWithLifecycle()
    val vosk by viewModel.voskState.collectAsStateWithLifecycle()
    val transcriptCount by viewModel.transcriptCount.collectAsStateWithLifecycle()
    val style = config.style
    val context = LocalContext.current

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importFont)
    }
    val exportDone: (Boolean) -> Unit = { ok ->
        Toast.makeText(context, if (ok) R.string.subtitle_export_ok else R.string.subtitle_export_fail, Toast.LENGTH_SHORT).show()
    }
    val srtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        uri?.let { viewModel.export(it, srt = true, onDone = exportDone) }
    }
    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { viewModel.export(it, srt = false, onDone = exportDone) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.screen_subtitle), onBack = onBack)
        // Preview menempel di atas supaya efek setiap perubahan langsung terlihat.
        SubtitlePreview(viewModel, Modifier.padding(horizontal = Dimens.ScreenPadding))
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding),
        ) {

            // ---------------- Mesin
            SectionHeader(stringResource(R.string.subtitle_section_engine))
            RowGroup {
                ToggleRow(stringResource(R.string.subtitle_enable), config.enabled, viewModel::setEnabled,
                    subtitle = recognizerText(rec.status, rec.errorCode))
            }
            if (rec.partial.isNotBlank()) {
                Spacer(Modifier.height(Dimens.S1))
                Text(stringResource(R.string.subtitle_heard, rec.partial), style = YukType.BodySmall, color = YukColors.TextSecondary)
            }
            Spacer(Modifier.height(Dimens.S1))
            ScrollPills(
                listOf(stringResource(R.string.engine_android), stringResource(R.string.engine_vosk)),
                config.engine.ordinal,
                { viewModel.setEngine(SpeechEngineType.entries[it]) },
            )
            Spacer(Modifier.height(Dimens.S1))
            if (config.engine == SpeechEngineType.Android) {
                ScrollPills(
                    SubtitleConfig.Languages.map { it.second },
                    SubtitleConfig.Languages.indexOfFirst { it.first == config.language }.coerceAtLeast(0),
                    { viewModel.setLanguage(SubtitleConfig.Languages[it].first) },
                )
                Spacer(Modifier.height(Dimens.S1))
                Text(
                    stringResource(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) R.string.engine_android_hint_stream
                        else R.string.engine_android_hint_mic,
                    ),
                    style = YukType.BodySmall, color = YukColors.TextSecondary,
                )
            } else {
                VoskSection(
                    installed = vosk.installed,
                    selected = config.voskModel,
                    downloading = vosk.downloading,
                    progress = vosk.progress,
                    error = vosk.error,
                    onSelect = viewModel::setVoskModel,
                    onDownload = viewModel::downloadModel,
                    onDelete = viewModel::deleteModel,
                )
            }

            // ---------------- Uji tampilan
            SectionHeader(stringResource(R.string.subtitle_section_test))
            var testText by rememberSaveable { mutableStateOf("") }
            val sample = stringResource(R.string.subtitle_sample)
            YukTextField(testText, { testText = it }, placeholder = sample, label = stringResource(R.string.subtitle_manual_text))
            Spacer(Modifier.height(Dimens.S1))
            PillButton(stringResource(R.string.subtitle_test), onClick = { viewModel.test(testText.ifBlank { sample }) },
                modifier = Modifier.fillMaxWidth())

            // ---------------- Potongan
            SectionHeader(stringResource(R.string.subtitle_section_chunk))
            LabeledSlider(stringResource(R.string.subtitle_words_per_line), "${style.maxWordsPerLine}",
                style.maxWordsPerLine.toFloat(), { v -> viewModel.style { it.copy(maxWordsPerLine = v.roundToInt()) } }, valueRange = 1f..10f)
            LabeledSlider(stringResource(R.string.subtitle_lines), "${style.maxLines}",
                style.maxLines.toFloat(), { v -> viewModel.style { it.copy(maxLines = v.roundToInt()) } }, valueRange = 1f..4f)
            LabeledSlider(stringResource(R.string.subtitle_clear_after), String.format(java.util.Locale.US, "%.1f s", style.clearAfterSec),
                style.clearAfterSec, { v -> viewModel.style { it.copy(clearAfterSec = (v * 10).roundToInt() / 10f) } }, valueRange = 0.5f..5f)

            // ---------------- Animasi & gaya
            SectionHeader(stringResource(R.string.subtitle_section_animation))
            val anims = listOf(R.string.anim_comic_slam, R.string.anim_pop, R.string.anim_fade_up, R.string.anim_typewriter,
                R.string.anim_bounce, R.string.anim_none).map { stringResource(it) }
            ScrollPills(anims, style.animation.ordinal, { i -> viewModel.style { it.copy(animation = SubtitleAnimation.entries[i]) } })
            Spacer(Modifier.height(Dimens.S1))
            val looks = listOf(R.string.look_minimal, R.string.look_box, R.string.look_karaoke).map { stringResource(it) }
            ScrollPills(looks, style.look.ordinal, { i -> viewModel.style { it.copy(look = SubtitleLook.entries[i]) } })

            SectionHeader(stringResource(R.string.subtitle_section_font))
            ScrollPills(fonts.map { it.label }, fonts.indexOfFirst { it.key == style.fontKey }.coerceAtLeast(0),
                { i -> viewModel.style { it.copy(fontKey = fonts[i].key) } })
            Spacer(Modifier.height(Dimens.S1))
            PillButton(stringResource(R.string.subtitle_import_font), onClick = {
                fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
            }, variant = PillVariant.Secondary, height = 36.dp)
            LabeledSlider(stringResource(R.string.prop_size), "${(style.size * 1000).roundToInt()}",
                style.size, { v -> viewModel.style { it.copy(size = v) } }, valueRange = 0.03f..0.16f)
            ScrollPills(listOf("400", "500", "600", "700", "800", "900"),
                listOf(400, 500, 600, 700, 800, 900).indexOf(style.fontWeight).coerceAtLeast(0),
                { i -> viewModel.style { it.copy(fontWeight = 400 + i * 100) } })

            SectionHeader(stringResource(R.string.subtitle_section_color))
            YukLabel(stringResource(R.string.prop_text_color))
            ColorSwatches(style.textColor, { c -> viewModel.style { it.copy(textColor = c) } })
            Spacer(Modifier.height(Dimens.S1))
            YukLabel(stringResource(R.string.subtitle_accent))
            ColorSwatches(style.accentColor, { c -> viewModel.style { it.copy(accentColor = c) } })
            Spacer(Modifier.height(Dimens.S1))
            YukLabel(stringResource(R.string.prop_stroke_color))
            ColorSwatches(style.strokeColor, { c -> viewModel.style { it.copy(strokeColor = c) } })
            LabeledSlider(stringResource(R.string.prop_stroke), "${(style.strokeWidth * 100).roundToInt()}%",
                style.strokeWidth, { v -> viewModel.style { it.copy(strokeWidth = v) } }, valueRange = 0f..0.3f)
            LabeledSlider(stringResource(R.string.prop_opacity), "${(style.opacity * 100).roundToInt()}%",
                style.opacity, { v -> viewModel.style { it.copy(opacity = v) } }, valueRange = 0.1f..1f)
            RowGroup {
                ToggleRow(stringResource(R.string.subtitle_hollow), style.hollow, { on -> viewModel.style { it.copy(hollow = on) } })
            }

            SectionHeader(stringResource(R.string.subtitle_section_position))
            ScrollPills(
                listOf(R.string.pos_top, R.string.pos_center, R.string.pos_bottom).map { stringResource(it) },
                style.position.ordinal, { i -> viewModel.style { it.copy(position = SubtitlePosition.entries[i]) } },
            )
            Spacer(Modifier.height(Dimens.S1))
            ScrollPills(
                listOf(R.string.align_left, R.string.align_center, R.string.align_right).map { stringResource(it) },
                style.align.ordinal, { i -> viewModel.style { it.copy(align = SubtitleAlign.entries[i]) } },
            )

            // ---------------- Preset
            SectionHeader(stringResource(R.string.subtitle_section_presets))
            config.presets.forEachIndexed { i, p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.subtitle_preset_slot, i + 1), style = YukType.Body, modifier = Modifier.weight(1f),
                        color = if (p != null) YukColors.TextPrimary else YukColors.TextDisabled)
                    PillButton(stringResource(R.string.action_load), onClick = { viewModel.loadPreset(i) }, enabled = p != null,
                        variant = PillVariant.Secondary, height = 34.dp)
                    Spacer(Modifier.size(Dimens.S1))
                    PillButton(stringResource(R.string.action_save), onClick = { viewModel.savePreset(i) }, height = 34.dp)
                }
            }

            // ---------------- Kamus koreksi
            SectionHeader(stringResource(R.string.subtitle_section_corrections))
            var wrong by rememberSaveable { mutableStateOf("") }
            var right by rememberSaveable { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.S1)) {
                YukTextField(wrong, { wrong = it }, placeholder = stringResource(R.string.correction_wrong), modifier = Modifier.weight(1f))
                YukTextField(right, { right = it }, placeholder = stringResource(R.string.correction_right), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Dimens.S1))
            PillButton(stringResource(R.string.correction_add), onClick = {
                viewModel.addCorrection(wrong, right)
                wrong = ""
                right = ""
            }, variant = PillVariant.Secondary, height = 36.dp)
            if (config.corrections.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.S1))
                RowGroup {
                    config.corrections.forEachIndexed { i, c ->
                        if (i > 0) HairlineDivider()
                        ListRow(
                            title = "${c.wrong}  →  ${c.right}",
                            trailing = {
                                Box(Modifier.size(36.dp).clickable { viewModel.removeCorrection(i) }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Close, stringResource(R.string.action_delete), tint = YukColors.TextSecondary, modifier = Modifier.size(16.dp))
                                }
                            },
                        )
                    }
                }
            }

            // ---------------- Transkrip
            SectionHeader(stringResource(R.string.subtitle_section_transcript), trailing = stringResource(R.string.subtitle_transcript_count, transcriptCount))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.S1)) {
                PillButton(stringResource(R.string.subtitle_export_srt), onClick = { srtLauncher.launch("YukLive-transkrip.srt") },
                    enabled = transcriptCount > 0, modifier = Modifier.weight(1f))
                PillButton(stringResource(R.string.subtitle_export_txt), onClick = { txtLauncher.launch("YukLive-transkrip.txt") },
                    enabled = transcriptCount > 0, variant = PillVariant.Secondary, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Dimens.S4))
        }
    }
}

@Composable
private fun recognizerText(status: RecognizerStatus, error: String?): String = when (status) {
    RecognizerStatus.Off -> stringResource(R.string.rec_off)
    RecognizerStatus.Starting -> stringResource(R.string.rec_starting)
    RecognizerStatus.Listening -> stringResource(R.string.rec_listening)
    RecognizerStatus.Error -> when (error) {
        "permission" -> stringResource(R.string.rec_err_permission)
        "language" -> stringResource(R.string.rec_err_language)
        "network" -> stringResource(R.string.rec_err_network)
        "model" -> stringResource(R.string.rec_err_model)
        "unavailable" -> stringResource(R.string.rec_err_unavailable)
        else -> stringResource(R.string.rec_err_generic)
    }
}

/** Preview subtitle memakai renderer yang sama dengan stream. */
@Composable
private fun SubtitlePreview(viewModel: SubtitleViewModel, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { now = SystemClock.elapsedRealtime() } }
    val placeholder = stringResource(R.string.subtitle_preview_placeholder).split(" ")
    Box(
        modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(YukColors.Surface)
            .border(Dimens.Hairline, YukColors.Border),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = now
            var frame = viewModel.previewFrame()
            if (frame.words.isEmpty()) {
                // Saat diam: tampilkan contoh samar supaya gaya tetap terlihat.
                frame = frame.copy(
                    words = placeholder.map { com.yuklive.app.subtitle.DisplayWord(it, 0L) },
                    style = frame.style.copy(opacity = frame.style.opacity * 0.35f),
                )
            }
            drawIntoCanvas { c -> viewModel.renderer.draw(c.nativeCanvas, size.width, size.height, frame, t) }
        }
    }
}

@Composable
private fun VoskSection(
    installed: List<String>,
    selected: String?,
    downloading: String?,
    progress: Float,
    error: Boolean,
    onSelect: (String?) -> Unit,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Text(stringResource(R.string.engine_vosk_hint), style = YukType.BodySmall, color = YukColors.TextSecondary)
    Spacer(Modifier.height(Dimens.S1))
    if (installed.isNotEmpty()) {
        RowGroup {
            installed.forEachIndexed { i, name ->
                if (i > 0) HairlineDivider()
                ListRow(
                    title = name,
                    onClick = { onSelect(name) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (name == selected) YukLabel(stringResource(R.string.vosk_active), color = YukColors.Success)
                            Box(Modifier.size(36.dp).clickable { onDelete(name) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.action_delete), tint = YukColors.TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(Dimens.S1))
    }
    if (downloading != null) {
        StatusBanner(stringResource(R.string.vosk_downloading, downloading, (progress * 100).roundToInt()), YukColors.Warning)
        Spacer(Modifier.height(Dimens.S1))
    } else if (error) {
        StatusBanner(stringResource(R.string.vosk_download_failed), YukColors.Live)
        Spacer(Modifier.height(Dimens.S1))
    }
    VoskModelManager.Catalog.filter { it.name !in installed }.forEach { m ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.label, style = YukType.Body)
                Text("${m.sizeMb} MB", style = YukType.BodySmall, color = YukColors.TextSecondary)
            }
            PillButton(stringResource(R.string.action_download), onClick = { onDownload(m.url) }, enabled = downloading == null, height = 34.dp)
        }
    }
    Spacer(Modifier.height(Dimens.S1))
    var customUrl by rememberSaveable { mutableStateOf("") }
    YukTextField(customUrl, { customUrl = it }, label = stringResource(R.string.vosk_custom_url), placeholder = "https://…/model.zip",
        keyboardType = KeyboardType.Uri)
    Spacer(Modifier.height(Dimens.S1))
    PillButton(stringResource(R.string.action_download), onClick = { if (customUrl.startsWith("http")) onDownload(customUrl.trim()) },
        enabled = downloading == null && customUrl.startsWith("http"), variant = PillVariant.Secondary, height = 36.dp)
}
