@file:OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)

package com.polefm.radio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.polefm.radio.DialogState
import com.polefm.radio.MainViewModel
import com.polefm.radio.PlayStatus
import com.polefm.radio.UiState
import com.polefm.radio.data.MusicItem
import com.polefm.radio.data.Station
import com.polefm.radio.player.AudioLevels
import com.polefm.radio.player.EqPreset
import com.polefm.radio.player.PlayerSnapshot
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

private val CardShape = RoundedCornerShape(16.dp)
private const val EQ_BANDS = AudioLevels.BAND_COUNT

@Composable
private fun glassColors(): ClickableSurfaceColors = ClickableSurfaceDefaults.colors(
    containerColor = TvColors.Glass,
    focusedContainerColor = TvColors.GlassStrong,
    pressedContainerColor = TvColors.GlassStrong
)

@Composable
private fun glassBorder(): ClickableSurfaceBorder = ClickableSurfaceDefaults.border(
    focusedBorder = Border(
        border = BorderStroke(2.dp, TvColors.Accent),
        shape = CardShape
    )
)

@Composable
private fun glassGlow(): ClickableSurfaceGlow = ClickableSurfaceDefaults.glow(
    focusedGlow = Glow(elevationColor = TvColors.Accent.copy(alpha = 0.45f), elevation = 14.dp)
)

@Composable
private fun glassScale(): ClickableSurfaceScale = ClickableSurfaceDefaults.scale(
    focusedScale = 1.035f,
    pressedScale = 0.985f
)

@Composable
fun AppRoot(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val ps by vm.player.state.collectAsState()

    BackHandler(enabled = ui.dialog != null) { vm.dismissDialog() }
    BackHandler(enabled = ui.dialog == null && ui.tab == 1 && ui.musicPath.isNotEmpty()) {
        vm.musicUp()
    }
    BackHandler(enabled = ui.dialog == null && ui.tab == 1 && ui.musicPath.isEmpty()) {
        vm.switchTab(0)
    }

    Box(modifier = Modifier.fillMaxSize().background(TvColors.Background)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF17283B), Color(0xFF0D131C), TvColors.Background),
                        radius = 900f
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 44.dp, vertical = 30.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            LeftPanel(ui = ui, ps = ps, vm = vm, modifier = Modifier.weight(0.40f))
            RightPanel(ui = ui, vm = vm, modifier = Modifier.weight(0.60f))
        }

        if (ui.toast != null) {
            Toast(text = ui.toast!!)
            LaunchedEffect(ui.toast) {
                delay(2600L)
                vm.consumeToast()
            }
        }

        when (val dialog = ui.dialog) {
            is DialogState.Pin -> PinDialog(vm = vm)
            is DialogState.StationEditor -> StationEditorDialog(editor = dialog, vm = vm)
            is DialogState.ConfirmDelete -> ConfirmDeleteDialog(station = dialog.station, vm = vm)
            is DialogState.Message -> MessageDialog(title = dialog.title, text = dialog.text, vm = vm)
            null -> Unit
        }
    }
}

@Composable
private fun LeftPanel(ui: UiState, ps: PlayerSnapshot, vm: MainViewModel, modifier: Modifier) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    Column(modifier = modifier.fillMaxHeight()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(TvColors.AccentDeep, TvColors.Accent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("📻", fontSize = 26.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("PoleFM", style = MaterialTheme.typography.headlineSmall, color = TvColors.Text)
                Text(
                    "радио и музыка",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextFaint
                )
            }
            Spacer(Modifier.weight(1f))
            StatusDot(ui.status)
        }

        Spacer(Modifier.height(22.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            colors = SurfaceDefaults.colors(containerColor = TvColors.Glass)
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(
                    "СЕЙЧАС ИГРАЕТ",
                    style = MaterialTheme.typography.labelSmall,
                    color = TvColors.TextFaint,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(10.dp))
                val title = when {
                    ui.currentStation >= 0 -> ui.stations.getOrNull(ui.currentStation)?.name.orEmpty()
                    ui.currentMusic.isNotEmpty() -> ui.currentMusic.substringAfterLast('/')
                    else -> "Нет воспроизведения"
                }
                Text(
                    text = title.ifEmpty { "Нет воспроизведения" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = TvColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee()
                )
                val subtitle = when {
                    ui.currentStation >= 0 -> ui.stations.getOrNull(ui.currentStation)?.url.orEmpty()
                    ui.currentMusic.isNotEmpty() -> ui.currentMusic
                    else -> "Выберите станцию или файл"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee()
                )

                if (ui.icyTitle.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "♪  ${ui.icyTitle}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvColors.Accent,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                }

                Spacer(Modifier.height(12.dp))
                Equalizer(
                    playing = ui.status == PlayStatus.PLAYING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                )

                if (ui.currentMusic.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    ProgressBar(ui = ui, ps = ps, vm = vm)
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { vm.togglePlayPause() },
                        modifier = Modifier
                            .height(30.dp)
                            .focusRequester(playFocus),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Text(if (ui.status == PlayStatus.PLAYING) "⏸ Пауза" else "▶ Играть")
                    }
                    OutlinedButton(
                        onClick = { vm.stopPlayback() },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Text("⏹ Стоп")
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        VolumeBar(
            volume = ui.volume,
            eqPreset = ui.eqPreset,
            onVolumeChange = { vm.setVolume(it) },
            onPresetChange = { vm.setEqPreset(it) }
        )
    }
}

@Composable
private fun StatusDot(status: PlayStatus) {
    val color = when (status) {
        PlayStatus.PLAYING -> TvColors.Green
        PlayStatus.BUFFERING -> TvColors.Yellow
        PlayStatus.PAUSED -> TvColors.Yellow
        PlayStatus.STOPPED -> TvColors.TextFaint
    }
    val label = when (status) {
        PlayStatus.PLAYING -> "играет"
        PlayStatus.BUFFERING -> "буферизация"
        PlayStatus.PAUSED -> "пауза"
        PlayStatus.STOPPED -> "остановлено"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun RightPanel(ui: UiState, vm: MainViewModel, modifier: Modifier) {
    Column(modifier = modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(
                0 to "📡  Онлайн радио",
                1 to "🎵  Моя музыка"
            ).forEach { (index, label) ->
                val selected = ui.tab == index
                Surface(
                    onClick = { vm.switchTab(index) },
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp),
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) {
                            TvColors.AccentDeep.copy(alpha = 0.22f)
                        } else {
                            TvColors.Glass
                        },
                        focusedContainerColor = TvColors.GlassStrong,
                        pressedContainerColor = TvColors.GlassStrong
                    ),
                    scale = glassScale(),
                    border = if (selected) {
                        ClickableSurfaceDefaults.border(
                            border = Border(
                                BorderStroke(1.dp, TvColors.Accent.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp)
                            ),
                            focusedBorder = Border(
                                BorderStroke(2.dp, TvColors.Accent),
                                shape = RoundedCornerShape(12.dp)
                            )
                        )
                    } else {
                        glassBorder()
                    },
                    glow = glassGlow()
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) TvColors.Accent else TvColors.TextDim
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        when (ui.tab) {
            0 -> RadioTab(ui = ui, vm = vm)
            1 -> MusicTab(ui = ui, vm = vm)
        }
    }
}

@Composable
private fun RadioTab(ui: UiState, vm: MainViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "РАДИОСТАНЦИИ",
            style = MaterialTheme.typography.labelSmall,
            color = TvColors.TextFaint,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.width(10.dp))
        Text("${ui.stations.size}", color = TvColors.TextFaint, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = { vm.showStationEditor(-1, Station("", "")) },
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
        ) { Text("+ Добавить") }
    }

    Spacer(Modifier.height(12.dp))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(ui.stations, key = { i, s -> "${s.url}-$i" }) { index, station ->
            StationCard(
                station = station,
                index = index,
                active = ui.currentStation == index,
                status = ui.status,
                onPlay = { vm.playStation(index) },
                onEdit = { vm.showStationEditor(index, station) },
                onDelete = { vm.requestDelete(index) }
            )
        }
        item {
            Spacer(Modifier.height(70.dp))
        }
    }
}

@Composable
private fun StationCard(
    station: Station,
    index: Int,
    active: Boolean,
    status: PlayStatus,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onPlay,
        onLongClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) TvColors.AccentDeep.copy(alpha = 0.22f) else TvColors.Glass,
            focusedContainerColor = TvColors.GlassStrong,
            pressedContainerColor = TvColors.GlassStrong
        ),
        scale = glassScale(),
        border = if (active) ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, TvColors.Accent), shape = CardShape),
            border = Border(BorderStroke(1.dp, TvColors.Accent.copy(alpha = 0.5f)), shape = CardShape)
        ) else glassBorder(),
        glow = glassGlow()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                String.format("%02d", index + 1),
                style = MaterialTheme.typography.labelMedium,
                color = TvColors.TextFaint
            )
            Spacer(Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) TvColors.Accent else TvColors.GlassStrong),
                contentAlignment = Alignment.Center
            ) {
                Text(if (active && status == PlayStatus.PLAYING) "♪" else "▶", color = if (active) Color.Black else TvColors.Text)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    station.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (active) TvColors.Accent else TvColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    station.url,
                    style = MaterialTheme.typography.labelMedium,
                    color = TvColors.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee()
                )
            }
            Spacer(Modifier.width(12.dp))
            SmallAction(text = "✎", onClick = onEdit, tint = TvColors.TextDim)
            Spacer(Modifier.width(6.dp))
            SmallAction(text = "✕", onClick = onDelete, tint = TvColors.Red)
        }
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit, tint: Color) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(TvColors.Glass)
            .border(1.dp, TvColors.Stroke, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = tint, fontSize = 18.sp)
    }
}

@Composable
private fun MusicTab(ui: UiState, vm: MainViewModel) {
    val listFocus = remember { FocusRequester() }
    LaunchedEffect(ui.musicPath) {
        runCatching { listFocus.requestFocus() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "ФАЙЛОВЫЙ МЕНЕДЖЕР",
            style = MaterialTheme.typography.labelSmall,
            color = TvColors.TextFaint,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.width(10.dp))
        Text("${ui.musicItems.size}", color = TvColors.TextFaint, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        if (ui.musicPath.isNotEmpty()) {
            OutlinedButton(
                onClick = { vm.musicUp() },
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
            ) { Text("⬆ Наверх") }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = if (ui.musicPath.isEmpty()) "📁 Корень" else "📁 ${ui.musicPath.replace("/", " › ")}",
        style = MaterialTheme.typography.bodyMedium,
        color = TvColors.Accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(10.dp))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(ui.musicItems, key = { _, item -> item.name + item.type }) { index, item ->
            MusicRow(
                item = item,
                active = ui.currentMusic.endsWith("/" + item.name),
                modifier = if (index == 0) Modifier.focusRequester(listFocus) else Modifier,
                onClick = {
                    if (item.type == "dir") vm.openMusicDir(item.name) else vm.playMusic(item.name)
                }
            )
        }
        item { Spacer(Modifier.height(70.dp)) }
    }
}

@Composable
private fun MusicRow(item: MusicItem, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = CardShape),
        colors = glassColors(),
        scale = glassScale(),
        border = glassBorder(),
        glow = glassGlow()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (item.type == "dir") "📁" else "🎵", fontSize = 20.sp)
            Spacer(Modifier.width(16.dp))
            Text(
                item.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (active) TvColors.Accent else TvColors.Text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (item.type == "dir") "папка" else formatSize(item.size),
                style = MaterialTheme.typography.labelMedium,
                color = TvColors.TextFaint
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

@Composable
private fun ProgressBar(ui: UiState, ps: PlayerSnapshot, vm: MainViewModel) {
    val duration = ps.duration
    val position = ps.position.coerceAtMost(duration)
    val focus = remember { FocusRequester() }
    val fraction = if (duration > 0) position.toFloat() / duration else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { vm.seekTo((position - 5_000).coerceAtLeast(0)); true }
                    Key.DirectionRight -> { vm.seekTo((position + 5_000).coerceAtMost(duration)); true }
                    else -> false
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(TvColors.GlassStrong)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(TvColors.Accent)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = TvColors.TextDim)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = TvColors.TextDim)
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun digitFromKey(key: Key): String? = when (key.keyCode.toInt()) {
    android.view.KeyEvent.KEYCODE_0, android.view.KeyEvent.KEYCODE_NUMPAD_0 -> "0"
    android.view.KeyEvent.KEYCODE_1, android.view.KeyEvent.KEYCODE_NUMPAD_1 -> "1"
    android.view.KeyEvent.KEYCODE_2, android.view.KeyEvent.KEYCODE_NUMPAD_2 -> "2"
    android.view.KeyEvent.KEYCODE_3, android.view.KeyEvent.KEYCODE_NUMPAD_3 -> "3"
    android.view.KeyEvent.KEYCODE_4, android.view.KeyEvent.KEYCODE_NUMPAD_4 -> "4"
    android.view.KeyEvent.KEYCODE_5, android.view.KeyEvent.KEYCODE_NUMPAD_5 -> "5"
    android.view.KeyEvent.KEYCODE_6, android.view.KeyEvent.KEYCODE_NUMPAD_6 -> "6"
    android.view.KeyEvent.KEYCODE_7, android.view.KeyEvent.KEYCODE_NUMPAD_7 -> "7"
    android.view.KeyEvent.KEYCODE_8, android.view.KeyEvent.KEYCODE_NUMPAD_8 -> "8"
    android.view.KeyEvent.KEYCODE_9, android.view.KeyEvent.KEYCODE_NUMPAD_9 -> "9"
    else -> null
}

@Composable
private fun VolumeBar(
    volume: Int,
    eqPreset: EqPreset,
    onVolumeChange: (Int) -> Unit,
    onPresetChange: (EqPreset) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) TvColors.GlassStrong else TvColors.Glass)
            .border(1.dp, if (focused) TvColors.Accent else TvColors.Stroke, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onVolumeChange(volume - 5); true }
                    Key.DirectionRight -> { onVolumeChange(volume + 5); true }
                    else -> false
                }
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔊", fontSize = 18.sp)
            Spacer(Modifier.width(12.dp))
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(20) { i ->
                    val filled = i < ((volume / 100f) * 20).toInt()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((7 + i * 0.6f).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when {
                                    !filled -> TvColors.TextFaint.copy(alpha = 0.35f)
                                    i > 17 -> TvColors.Red
                                    i > 14 -> TvColors.Yellow
                                    else -> TvColors.Accent
                                }
                            )
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("$volume%", style = MaterialTheme.typography.labelMedium, color = TvColors.Text)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "◀ ▶ громкость",
            style = MaterialTheme.typography.labelSmall,
            color = TvColors.TextFaint
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ЭКВАЛАЙЗЕР",
                style = MaterialTheme.typography.labelSmall,
                color = TvColors.TextFaint,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.width(10.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val presets = EqPreset.entries
                val presetFocus = remember { List(presets.size) { FocusRequester() } }
                presets.forEachIndexed { index, preset ->
                    val active = preset == eqPreset
                    Surface(
                        onClick = { onPresetChange(preset) },
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .focusRequester(presetFocus[index])
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val step = when (event.key) {
                                    Key.DirectionLeft -> -1
                                    Key.DirectionRight -> 1
                                    else -> return@onPreviewKeyEvent false
                                }
                                val next = (index + step + presets.size) % presets.size
                                onPresetChange(presets[next])
                                runCatching { presetFocus[next].requestFocus() }
                                true
                            },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (active) {
                                TvColors.AccentDeep.copy(alpha = 0.4f)
                            } else {
                                TvColors.Glass
                            },
                            focusedContainerColor = TvColors.GlassStrong,
                            pressedContainerColor = TvColors.GlassStrong
                        ),
                        scale = glassScale(),
                        border = if (active) {
                            ClickableSurfaceDefaults.border(
                                border = Border(
                                    BorderStroke(1.dp, TvColors.Accent),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            )
                        } else {
                            glassBorder()
                        },
                        glow = glassGlow()
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                preset.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                                color = if (active) TvColors.Accent else TvColors.TextDim,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Equalizer(playing: Boolean, modifier: Modifier) {
    val bands = remember { mutableStateOf(FloatArray(EQ_BANDS)) }
    val peaks = remember { mutableStateOf(FloatArray(EQ_BANDS)) }

    // Amplitude comes from the real PCM signal measured by RmsAudioProcessor in the player.
    LaunchedEffect(playing) {
        while (true) {
            val spectrum = AudioLevels.bands
            val previous = bands.value
            val next = FloatArray(EQ_BANDS) { i ->
                val target = if (playing) spectrum[i].coerceIn(0f, 1f) else 0f
                val decay = 0.80f + (i % 5) * 0.025f
                if (target > previous[i]) target else previous[i] * decay + target * (1f - decay)
            }
            bands.value = next
            val currentPeaks = peaks.value
            for (i in 0 until EQ_BANDS) {
                currentPeaks[i] = max(next[i], currentPeaks[i] - 0.018f)
            }
            peaks.value = currentPeaks.copyOf()
            delay(45L)
        }
    }

    val values = bands.value
    val peakValues = peaks.value
    Canvas(modifier = modifier) {
        val gap = 3.dp.toPx()
        val barW = (size.width - gap * (EQ_BANDS - 1)) / EQ_BANDS
        for (i in 0 until EQ_BANDS) {
            val amplitude = if (playing) values[i] else 0f
            val barH = max(if (amplitude > 0.01f) 3.dp.toPx() else 2.dp.toPx(), amplitude * size.height)
            val x = i * (barW + gap)
            drawRoundRect(
                color = if (amplitude > 0.72f) TvColors.Accent else TvColors.AccentDeep,
                topLeft = Offset(x, size.height - barH),
                size = androidx.compose.ui.geometry.Size(barW, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            )
            if (playing && peakValues[i] > 0.02f) {
                val peakY = size.height - (peakValues[i] * size.height) - 2.dp.toPx()
                drawRoundRect(
                    color = TvColors.Accent.copy(alpha = 0.7f),
                    topLeft = Offset(x, peakY),
                    size = androidx.compose.ui.geometry.Size(barW, 2.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun DialogShell(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x9905090D))
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        onDismiss()
                        true
                    } else false
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF1A222E))
                    .border(1.dp, TvColors.Stroke, RoundedCornerShape(22.dp))
                    .padding(28.dp)
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = TvColors.Text)
                Spacer(Modifier.height(18.dp))
                content()
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PinDialog(vm: MainViewModel) {
    var pin by remember { mutableStateOf("") }
    val pinFocus = remember { FocusRequester() }
    var pinFieldFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { pinFocus.requestFocus() } }

    DialogShell("🔒  Введите PIN-код", onDismiss = { vm.dismissDialog() }) {
        Text(
            "Изменение списка станций защищено PIN-кодом.",
            style = MaterialTheme.typography.bodyMedium,
            color = TvColors.TextDim
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(TvColors.Glass)
                .border(
                    1.dp,
                    if (pinFieldFocused) TvColors.Accent else TvColors.Stroke,
                    RoundedCornerShape(12.dp)
                )
                .focusRequester(pinFocus)
                .onFocusChanged { pinFieldFocused = it.isFocused }
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        event.key == Key.Backspace || event.key == Key.Delete -> {
                            pin = pin.dropLast(1); true
                        }
                        event.key == Key.Enter || event.key == Key.DirectionCenter -> {
                            vm.confirmPin(pin); true
                        }
                        digitFromKey(event.key) != null -> {
                            val digit = digitFromKey(event.key)!!
                            if (pin.length < 8) pin += digit
                            true
                        }
                        else -> false
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (pin.isEmpty()) {
                Text("• • • • • •", fontSize = 26.sp, color = TvColors.TextFaint)
            } else {
                Text("• ".repeat(pin.length), fontSize = 26.sp, color = TvColors.Accent)
            }
        }
        Spacer(Modifier.height(18.dp))
        val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0 until 4) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (col in 0 until 3) {
                        val label = digits[row * 3 + col]
                        Button(
                            onClick = {
                                when (label) {
                                    "⌫" -> pin = pin.dropLast(1)
                                    "✓" -> vm.confirmPin(pin)
                                    else -> if (pin.length < 8) pin += label
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp),
                            colors = ButtonDefaults.colors(
                                containerColor = TvColors.Glass,
                                focusedContainerColor = TvColors.Accent
                            )
                        ) {
                            Text(label, fontSize = 20.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { vm.dismissDialog() }, modifier = Modifier.weight(1f)) { Text("Отмена") }
            Button(onClick = { vm.confirmPin(pin) }, modifier = Modifier.weight(1f)) { Text("Подтвердить") }
        }
    }
}

@Composable
private fun StationEditorDialog(editor: DialogState.StationEditor, vm: MainViewModel) {
    var name by remember { mutableStateOf(editor.station.name) }
    var url by remember { mutableStateOf(editor.station.url) }
    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val isNew = editor.index < 0
    DialogShell(
        title = if (isNew) "＋  Добавить станцию" else "✎  Редактировать станцию",
        onDismiss = { vm.dismissDialog() }
    ) {
        Field(
            label = "Название", value = name, placeholder = "Например, Русское Радио",
            focusRequester = nameFocus,
            downFocus = urlFocus
        ) { name = it }
        Spacer(Modifier.height(12.dp))
        Field(
            label = "URL потока", value = url, placeholder = "http://...",
            keyboardType = KeyboardType.Uri,
            focusRequester = urlFocus,
            upFocus = nameFocus,
            downFocus = saveFocus
        ) { url = it }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { vm.dismissDialog() },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelFocus)
            ) { Text("Отмена") }
            Button(
                onClick = { vm.requestPin { pin -> vm.saveStation(name, url, pin, editor.index) } },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(saveFocus)
                    .focusProperties { up = urlFocus }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            runCatching { urlFocus.requestFocus() }; true
                        } else false
                    }
            ) { Text("Сохранить") }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    focusRequester: FocusRequester,
    keyboardType: KeyboardType = KeyboardType.Text,
    upFocus: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    onChange: (String) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelMedium, color = TvColors.TextDim)
    Spacer(Modifier.height(6.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(TvColors.Glass)
            .border(
                1.dp,
                if (focused) TvColors.Accent else TvColors.Stroke,
                RoundedCornerShape(12.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.DirectionDown && downFocus != null -> {
                        runCatching { downFocus.requestFocus() }; true
                    }
                    event.key == Key.DirectionUp && upFocus != null -> {
                        runCatching { upFocus.requestFocus() }; true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = TvColors.TextFaint)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = TvColors.Text, fontSize = 18.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusProperties {
                    if (upFocus != null) up = upFocus
                    if (downFocus != null) down = downFocus
                }
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(station: Station, vm: MainViewModel) {
    DialogShell("🗑  Удалить станцию", onDismiss = { vm.dismissDialog() }) {
        Text(station.name, style = MaterialTheme.typography.titleLarge, color = TvColors.Text)
        Spacer(Modifier.height(4.dp))
        Text(station.url, style = MaterialTheme.typography.bodyMedium, color = TvColors.TextDim)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { vm.dismissDialog() }, modifier = Modifier.weight(1f)) { Text("Отмена") }
            Button(onClick = { vm.confirmDelete() }, modifier = Modifier.weight(1f)) { Text("Удалить") }
        }
    }
}

@Composable
private fun MessageDialog(title: String, text: String, vm: MainViewModel) {
    DialogShell(title, onDismiss = { vm.dismissDialog() }) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TvColors.Text)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { vm.dismissDialog() }, modifier = Modifier.fillMaxWidth()) { Text("OK") }
    }
}

@Composable
private fun Toast(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 36.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = TvColors.Text,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xE6222B38))
                .border(1.dp, TvColors.Stroke, RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}
