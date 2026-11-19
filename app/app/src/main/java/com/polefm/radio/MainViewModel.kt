package com.polefm.radio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.polefm.radio.data.Api
import com.polefm.radio.data.MusicItem
import com.polefm.radio.data.SaveResult
import com.polefm.radio.data.SettingsStore
import com.polefm.radio.data.Station
import com.polefm.radio.data.TvFeedPublisher
import com.polefm.radio.player.AudioLevels
import com.polefm.radio.player.EqPreset
import com.polefm.radio.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlayStatus { STOPPED, BUFFERING, PLAYING, PAUSED }

sealed interface DialogState {
    data object Pin : DialogState
    data class StationEditor(val index: Int, val station: Station) : DialogState
    data class ConfirmDelete(val station: Station, val index: Int) : DialogState
    data class Message(val title: String, val text: String) : DialogState
}

data class UiState(
    val tab: Int = 0,
    val stations: List<Station> = emptyList(),
    val currentStation: Int = -1,
    val recent: List<Station> = emptyList(),
    val musicPath: String = "",
    val musicItems: List<MusicItem> = emptyList(),
    val currentMusic: String = "",
    val icyTitle: String = "",
    val status: PlayStatus = PlayStatus.STOPPED,
    val volume: Int = 60,
    val eqPreset: EqPreset = EqPreset.OFF,
    val loading: Boolean = false,
    val dialog: DialogState? = null,
    val toast: String? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val feedPublisher = TvFeedPublisher(app)
    val player = PlayerController(app)

    private val savedPreset = settings.eqPreset

    private val _ui = MutableStateFlow(
        UiState(
            volume = settings.volume,
            eqPreset = savedPreset,
            stations = settings.stations ?: emptyList(),
            recent = settings.recentStations
        )
    )
    val ui = _ui.asStateFlow()

    private var pinAction: (suspend (String) -> Unit)? = null
    private var retryCount = 0
    private var reconnectJob: Job? = null
    private var volumeApplied = false
    private val musicStack = ArrayDeque<String>()

    init {
        AudioLevels.preset = savedPreset
        player.connect()
        viewModelScope.launch { refreshStations() }
        startPlayerStateObserver()
        startPositionPolling()
        startIcyPolling()
        startErrorObserver()
    }

    private fun startPlayerStateObserver() {
        viewModelScope.launch {
            player.state.collect { ps ->
                if (ps.connected && !volumeApplied) {
                    volumeApplied = true
                    player.setVolume(_ui.value.volume)
                }
                val s = _ui.value
                if (s.currentStation >= 0 || s.currentMusic.isNotEmpty()) {
                    val newStatus = when {
                        ps.playbackState == Player.STATE_BUFFERING -> PlayStatus.BUFFERING
                        ps.isPlaying -> PlayStatus.PLAYING
                        ps.playbackState == Player.STATE_IDLE -> PlayStatus.STOPPED
                        else -> PlayStatus.PAUSED
                    }
                    if (newStatus != s.status) _ui.update { it.copy(status = newStatus) }
                }
                if (ps.playbackState == Player.STATE_ENDED && _ui.value.currentMusic.isNotEmpty()) {
                    stopPlayback()
                }
            }
        }
    }

    private fun startPositionPolling() {
        viewModelScope.launch {
            while (true) {
                delay(500L)
                player.pollPosition()
            }
        }
    }

    private fun startIcyPolling() {
        viewModelScope.launch {
            while (true) {
                delay(12_000L)
                val s = _ui.value
                if (s.tab == 0 && s.currentStation >= 0 && s.status == PlayStatus.PLAYING) {
                    val url = s.stations.getOrNull(s.currentStation)?.url ?: continue
                    val title = runCatching { Api.nowPlaying(url) }.getOrDefault("")
                    _ui.update { it.copy(icyTitle = title) }
                }
            }
        }
    }

    private fun startErrorObserver() {
        viewModelScope.launch {
            player.errors.collect { scheduleReconnect() }
        }
    }

    suspend fun refreshStations() {
        _ui.update { it.copy(loading = true) }
        val loaded = runCatching { Api.loadStations() }.getOrNull()
        val list = if (!loaded.isNullOrEmpty()) loaded
        else settings.stations?.takeIf { it.isNotEmpty() } ?: defaultStations()
        settings.stations = list
        _ui.update { it.copy(stations = list, loading = false) }
    }

    private fun defaultStations() = listOf(
        Station("Русское Радио", "http://rusradio.hostingradio.ru/rusradio96.aacp"),
        Station("Европа Плюс", "http://ep128.hostingradio.ru:8030/ep128"),
        Station("Радио Рекорд", "http://record128.hostingradio.ru:8030/record128")
    )

    fun switchTab(tab: Int) {
        _ui.update { it.copy(tab = tab) }
        if (tab == 1 && _ui.value.musicItems.isEmpty()) {
            loadMusicDir("")
        }
    }

    fun playStation(index: Int) {
        val station = _ui.value.stations.getOrNull(index) ?: return
        retryCount = 0
        reconnectJob?.cancel()
        val previous = _ui.value.stations.getOrNull(_ui.value.currentStation)?.url
        viewModelScope.launch {
            previous?.let { Api.clearNowPlaying(it) }
            Api.clearNowPlaying(station.url)
        }
        _ui.update {
            it.copy(
                tab = 0,
                currentStation = index,
                currentMusic = "",
                icyTitle = "",
                status = PlayStatus.BUFFERING
            )
        }
        player.playRadio(station.url, station.name)

        val updatedRecent = (listOf(station) + _ui.value.recent.filter { it.url != station.url })
            .take(MAX_RECENT_STATIONS)
        settings.recentStations = updatedRecent
        _ui.update { it.copy(recent = updatedRecent) }
        viewModelScope.launch(Dispatchers.IO) { feedPublisher.publish(updatedRecent) }
    }

    fun playStationByUrl(url: String) {
        val index = _ui.value.stations.indexOfFirst { it.url == url }
        if (index >= 0) {
            playStation(index)
        } else {
            viewModelScope.launch {
                refreshStations()
                val refreshed = _ui.value.stations.indexOfFirst { it.url == url }
                if (refreshed >= 0) playStation(refreshed)
            }
        }
    }

    private fun startRadioAgain(index: Int) {
        val station = _ui.value.stations.getOrNull(index) ?: return
        _ui.update { it.copy(currentStation = index, status = PlayStatus.BUFFERING) }
        player.playRadio(station.url, station.name)
    }

    private fun scheduleReconnect() {
        val s = _ui.value
        if (s.currentStation < 0 || s.tab != 0 || retryCount >= MAX_RETRIES) {
            stopPlayback()
            return
        }
        reconnectJob?.cancel()
        retryCount++
        _ui.update { it.copy(status = PlayStatus.BUFFERING) }
        reconnectJob = viewModelScope.launch {
            delay(minOf(5_000L, 1_200L * retryCount))
            startRadioAgain(_ui.value.currentStation)
        }
    }

    fun loadMusicDir(path: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val result = runCatching { Api.musicList(path) }.getOrNull()
            if (result?.ok == true) {
                _ui.update { it.copy(musicPath = path, musicItems = result.items, loading = false) }
            } else {
                _ui.update { it.copy(loading = false, toast = "Ошибка загрузки списка") }
            }
        }
    }

    fun openMusicDir(name: String) {
        val path = _ui.value.musicPath.let { if (it.isEmpty()) name else "$it/$name" }
        musicStack.addLast(_ui.value.musicPath)
        loadMusicDir(path)
    }

    fun musicUp() {
        val parent = musicStack.removeLastOrNull() ?: return
        loadMusicDir(parent)
    }

    fun playMusic(name: String) {
        val s = _ui.value
        val path = s.musicPath.let { if (it.isEmpty()) name else "$it/$name" }
        val files = s.musicItems.filter { it.type == "file" }
        val startIndex = files.indexOfFirst { it.name == name }.coerceAtLeast(0)
        val playlist = files.map { item ->
            val full = s.musicPath.let { if (it.isEmpty()) item.name else "$it/${item.name}" }
            Api.musicStreamUrl(full) to item.name
        }
        if (playlist.isEmpty()) return
        reconnectJob?.cancel()
        _ui.update {
            it.copy(
                tab = 1,
                currentMusic = path,
                currentStation = -1,
                icyTitle = "",
                status = PlayStatus.BUFFERING
            )
        }
        player.playMusicPlaylist(playlist, startIndex)
    }

    fun togglePlayPause() {
        val s = _ui.value
        if (s.status == PlayStatus.PLAYING) player.pause()
        else if (s.status == PlayStatus.PAUSED) player.resume()
        else if (s.currentStation >= 0) playStation(s.currentStation)
    }

    fun stopPlayback() {
        reconnectJob?.cancel()
        retryCount = 0
        player.stop()
        _ui.update {
            it.copy(
                status = PlayStatus.STOPPED,
                currentStation = -1,
                currentMusic = "",
                icyTitle = ""
            )
        }
    }

    fun seekTo(ms: Long) {
        if (_ui.value.currentMusic.isNotEmpty()) player.seekTo(ms)
    }

    fun setVolume(value: Int) {
        val v = value.coerceIn(0, 100)
        settings.volume = v
        player.setVolume(v)
        _ui.update { it.copy(volume = v) }
    }

    fun setEqPreset(preset: EqPreset) {
        settings.eqPreset = preset
        AudioLevels.preset = preset
        _ui.update { it.copy(eqPreset = preset) }
    }

    fun showStationEditor(index: Int, station: Station) {
        _ui.update { it.copy(dialog = DialogState.StationEditor(index, station)) }
    }

    fun requestPin(action: suspend (String) -> Unit) {
        pinAction = action
        _ui.update { it.copy(dialog = DialogState.Pin) }
    }

    fun confirmPin(pin: String) {
        val action = pinAction ?: return
        pinAction = null
        _ui.update { it.copy(dialog = null) }
        viewModelScope.launch { action(pin) }
    }

    fun requestDelete(index: Int) {
        val station = _ui.value.stations.getOrNull(index) ?: return
        _ui.update { it.copy(dialog = DialogState.ConfirmDelete(station, index)) }
    }

    fun confirmDelete() {
        val d = _ui.value.dialog as? DialogState.ConfirmDelete ?: return
        _ui.update { it.copy(dialog = null) }
        requestPin { pin -> deleteStation(d.index, pin) }
    }

    fun dismissDialog() {
        pinAction = null
        _ui.update { it.copy(dialog = null) }
    }

    fun showMessage(title: String, text: String) {
        _ui.update { it.copy(dialog = DialogState.Message(title, text)) }
    }

    fun consumeToast() {
        _ui.update { it.copy(toast = null) }
    }

    suspend fun saveStation(name: String, url: String, pin: String, index: Int = -1) {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        if (trimmedName.isEmpty() || !trimmedUrl.startsWith("http")) {
            showMessage("Ошибка", "Укажите название и URL, начинающийся с http")
            return
        }
        val current = _ui.value.stations
        val updated = if (index in current.indices) {
            current.mapIndexed { i, s -> if (i == index) Station(trimmedName, trimmedUrl) else s }
        } else {
            current + Station(trimmedName, trimmedUrl)
        }
        when (val result = Api.saveStations(pin, updated)) {
            SaveResult.Ok -> {
                settings.stations = updated
                _ui.update { it.copy(stations = updated) }
            }
            SaveResult.InvalidPin -> showMessage("Не удалось сохранить", "Неверный PIN-код — изменения не сохранены")
            SaveResult.PinRequired -> showMessage("Не удалось сохранить", "Не получен PIN-код — изменения не сохранены")
            SaveResult.PinNotConfigured -> showMessage("Не удалось сохранить", "PIN-код не настроен на сервере")
            is SaveResult.Error -> showMessage("Не удалось сохранить", "Ошибка сервера: ${result.detail}")
        }
    }

    private suspend fun deleteStation(index: Int, pin: String) {
        val current = _ui.value.stations
        if (index !in current.indices) return
        val updated = current.filterIndexed { i, _ -> i != index }
        when (val result = Api.saveStations(pin, updated)) {
            SaveResult.Ok -> {
                settings.stations = updated
                val newIndex = when {
                    _ui.value.currentStation == index -> -1
                    _ui.value.currentStation > index -> _ui.value.currentStation - 1
                    else -> _ui.value.currentStation
                }
                if (_ui.value.currentStation == index) player.stop()
                _ui.update { it.copy(stations = updated, currentStation = newIndex) }
            }
            SaveResult.InvalidPin -> showMessage("Не удалось удалить", "Неверный PIN-код — изменения не сохранены")
            SaveResult.PinRequired -> showMessage("Не удалось удалить", "Не получен PIN-код")
            SaveResult.PinNotConfigured -> showMessage("Не удалось удалить", "PIN-код не настроен на сервере")
            is SaveResult.Error -> showMessage("Не удалось удалить", "Ошибка сервера: ${result.detail}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }

    companion object {
        private const val MAX_RETRIES = 4
        private const val MAX_RECENT_STATIONS = 8
    }
}
