package com.yuklive.app.feature.settings

import android.os.Environment
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yuklive.app.BuildConfig
import com.yuklive.app.R
import com.yuklive.app.core.settings.Resolution
import com.yuklive.app.core.settings.SettingsRepository
import com.yuklive.app.core.settings.StreamOrientation
import com.yuklive.app.core.settings.StreamSettings
import com.yuklive.app.core.settings.VideoCodecOption
import com.yuklive.app.stream.StreamEngine
import com.yuklive.app.core.device.EncoderSupport
import com.yuklive.app.ui.components.PillButton
import com.yuklive.app.ui.components.PillVariant
import com.yuklive.app.ui.theme.SharpShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import com.yuklive.app.ui.components.HairlineDivider
import com.yuklive.app.ui.components.LabeledSlider
import com.yuklive.app.ui.components.ListRow
import com.yuklive.app.ui.components.RowGroup
import com.yuklive.app.ui.components.ScreenHeader
import com.yuklive.app.ui.components.ScrollPills
import com.yuklive.app.ui.components.SectionHeader
import com.yuklive.app.ui.components.SegmentedPills
import com.yuklive.app.ui.components.ToggleRow
import com.yuklive.app.ui.components.YukLabel
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    engine: StreamEngine,
) : ViewModel() {
    val settings: StateFlow<StreamSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreamSettings())
    val live: StateFlow<Boolean> = engine.state.map { it.isActive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun update(transform: (StreamSettings) -> StreamSettings) {
        viewModelScope.launch { repo.update(transform) }
    }
}

private val LanguageTags = listOf("id", "en")

@Composable
fun SettingsScreen(
    onOpenSources: () -> Unit,
    onOpenSubtitle: () -> Unit,
    onOpenDestinations: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val locked = live

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.screen_settings))
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            if (locked) {
                com.yuklive.app.ui.components.StatusBanner(stringResource(R.string.settings_locked_live), YukColors.Warning)
            }

            // ---------------- Video
            SettingsSection(stringResource(R.string.settings_section_video))
            SettingsCard {
                val resolutions = EncoderSupport.supportedResolutions()
                Field(stringResource(R.string.settings_resolution)) {
                    ScrollPills(resolutions.map { it.label }, resolutions.indexOf(s.resolution).coerceAtLeast(0), { i ->
                        val r = resolutions[i]
                        viewModel.update { it.copy(resolution = r, videoBitrateKbps = StreamSettings.recommendedBitrate(r, it.fps)) }
                    }, enabled = !locked)
                }
                FieldDivider()
                Field(stringResource(R.string.settings_orientation)) {
                    ScrollPills(
                        listOf(stringResource(R.string.orientation_landscape), stringResource(R.string.orientation_portrait)),
                        s.orientation.ordinal, { i -> viewModel.update { it.copy(orientation = StreamOrientation.entries[i]) } },
                        enabled = !locked,
                    )
                }
                FieldDivider()
                Field(stringResource(R.string.settings_fps)) {
                    ScrollPills(StreamSettings.FpsOptions.map { "$it fps" }, StreamSettings.FpsOptions.indexOf(s.fps).coerceAtLeast(0), { i ->
                        val f = StreamSettings.FpsOptions[i]
                        viewModel.update { it.copy(fps = f, videoBitrateKbps = StreamSettings.recommendedBitrate(it.resolution, f)) }
                    }, enabled = !locked)
                }
                FieldDivider()
                Field(stringResource(R.string.settings_codec)) {
                    ScrollPills(VideoCodecOption.entries.map { it.label }, s.codec.ordinal, { i ->
                        viewModel.update { it.copy(codec = VideoCodecOption.entries[i]) }
                    }, enabled = !locked)
                }
                FieldDivider()
                Field(stringResource(R.string.settings_keyframe)) {
                    ScrollPills(StreamSettings.KeyframeOptions.map { "$it s" }, StreamSettings.KeyframeOptions.indexOf(s.keyframeSec).coerceAtLeast(0), { i ->
                        viewModel.update { it.copy(keyframeSec = StreamSettings.KeyframeOptions[i]) }
                    }, enabled = !locked)
                }
            }
            Spacer(Modifier.height(Dimens.S2))
            SettingsCard {
                var bitrate by remember(s.videoBitrateKbps) { mutableFloatStateOf(s.videoBitrateKbps.toFloat()) }
                val recommended = StreamSettings.recommendedBitrate(s.resolution, s.fps)
                Column(Modifier.padding(horizontal = Dimens.S2, vertical = 12.dp)) {
                    LabeledSlider(
                        stringResource(R.string.settings_video_bitrate),
                        stringResource(R.string.studio_bitrate_value, bitrate.roundToInt()),
                        bitrate,
                        { bitrate = (it / 100f).roundToInt() * 100f },
                        valueRange = StreamSettings.MinVideoBitrate.toFloat()..StreamSettings.MaxVideoBitrate.toFloat(),
                    )
                    Text(
                        stringResource(
                            R.string.settings_bitrate_hint,
                            recommended,
                            EncoderSupport.dataPerHour(bitrate.roundToInt(), s.audioBitrateKbps),
                        ),
                        style = YukType.BodySmall, color = YukColors.TextSecondary,
                    )
                    if (bitrate.roundToInt() != recommended) {
                        Spacer(Modifier.height(6.dp))
                        PillButton(stringResource(R.string.settings_use_recommended), onClick = { bitrate = recommended.toFloat() },
                            variant = PillVariant.Secondary, height = 32.dp)
                    }
                }
                androidx.compose.runtime.LaunchedEffect(bitrate) {
                    kotlinx.coroutines.delay(400)
                    if (bitrate.roundToInt() != s.videoBitrateKbps) viewModel.update { it.copy(videoBitrateKbps = bitrate.roundToInt()) }
                }
                FieldDivider()
                ToggleRow(stringResource(R.string.quick_adaptive), s.adaptiveBitrate, { on -> viewModel.update { it.copy(adaptiveBitrate = on) } },
                    subtitle = stringResource(R.string.quick_adaptive_hint))
            }

            // ---------------- Audio
            SettingsSection(stringResource(R.string.settings_section_audio))
            SettingsCard {
                Field(stringResource(R.string.settings_audio_bitrate)) {
                    ScrollPills(StreamSettings.AudioBitrateOptions.map { "$it kbps" },
                        StreamSettings.AudioBitrateOptions.indexOf(s.audioBitrateKbps).coerceAtLeast(0), { i ->
                            viewModel.update { it.copy(audioBitrateKbps = StreamSettings.AudioBitrateOptions[i]) }
                        }, enabled = !locked)
                }
                FieldDivider()
                Field(stringResource(R.string.settings_sample_rate)) {
                    ScrollPills(StreamSettings.SampleRateOptions.map { "${it / 1000.0} kHz" },
                        StreamSettings.SampleRateOptions.indexOf(s.sampleRate).coerceAtLeast(0), { i ->
                            viewModel.update { it.copy(sampleRate = StreamSettings.SampleRateOptions[i]) }
                        }, enabled = !locked)
                }
                FieldDivider()
                ToggleRow(stringResource(R.string.settings_stereo), s.stereo, { on -> viewModel.update { it.copy(stereo = on) } }, enabled = !locked)
            }

            // ---------------- Penyimpanan
            SettingsSection(stringResource(R.string.settings_section_storage))
            RowGroup {
                ToggleRow(stringResource(R.string.quick_record), s.recordLocally, { on -> viewModel.update { it.copy(recordLocally = on) } },
                    subtitle = stringResource(R.string.quick_record_hint))
                HairlineDivider()
                ListRow(
                    title = stringResource(R.string.settings_recording_folder),
                    value = "${Environment.DIRECTORY_MOVIES}/YukLive",
                    icon = Icons.Outlined.Folder,
                )
            }

            // ---------------- Bahasa
            SettingsSection(stringResource(R.string.settings_section_language))
            LanguagePicker()

            // ---------------- Kelola
            SettingsSection(stringResource(R.string.settings_section_manage))
            RowGroup {
                ListRow(stringResource(R.string.screen_sources), icon = Icons.Outlined.VideoSettings, showChevron = true, onClick = onOpenSources)
                HairlineDivider()
                ListRow(stringResource(R.string.screen_subtitle), icon = Icons.Outlined.Subtitles, showChevron = true, onClick = onOpenSubtitle)
                HairlineDivider()
                ListRow(stringResource(R.string.screen_destinations), icon = Icons.Outlined.Cast, showChevron = true, onClick = onOpenDestinations)
            }

            // ---------------- Tentang
            SettingsSection(stringResource(R.string.settings_section_about))
            RowGroup {
                ListRow(
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.settings_about_desc),
                    value = BuildConfig.VERSION_NAME,
                    icon = Icons.Outlined.Info,
                )
                HairlineDivider()
                ListRow(title = stringResource(R.string.settings_licenses), subtitle = stringResource(R.string.settings_licenses_desc))
            }
            Spacer(Modifier.height(Dimens.S4))
        }
    }
}

@Composable
private fun LanguagePicker() {
    val initial = remember {
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (current.startsWith("en")) 1 else 0
    }
    var selected by remember { mutableIntStateOf(initial) }
    SegmentedPills(
        options = listOf(stringResource(R.string.language_indonesian), stringResource(R.string.language_english)),
        selectedIndex = selected,
        onSelect = { index ->
            if (index != selected) {
                selected = index
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(LanguageTags[index]))
            }
        },
    )
}

/** Judul bagian pengaturan: lebih besar dari label isian supaya hierarkinya jelas. */
@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        style = YukType.TitleSmall,
        color = YukColors.TextPrimary,
        modifier = Modifier.padding(top = Dimens.S4, bottom = Dimens.S1),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(YukColors.Surface)
            .border(Dimens.Hairline, YukColors.Border, SharpShape),
        content = content,
    )
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.S2, vertical = 12.dp)) {
        YukLabel(label)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun FieldDivider() = HairlineDivider()
