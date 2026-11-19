package com.polefm.radio.data

import kotlinx.serialization.Serializable

@Serializable
data class Station(val name: String, val url: String)

@Serializable
data class MusicItem(val type: String, val name: String, val size: Long = 0)

@Serializable
data class MusicListResponse(
    val ok: Boolean = false,
    val path: String = "",
    val items: List<MusicItem> = emptyList(),
    val error: String = ""
)

@Serializable
data class NowPlayingResponse(val ok: Boolean = false, val title: String = "")

@Serializable
data class SaveResponse(val ok: Boolean = false, val error: String = "")

sealed interface SaveResult {
    data object Ok : SaveResult
    data object InvalidPin : SaveResult
    data object PinRequired : SaveResult
    data object PinNotConfigured : SaveResult
    data class Error(val detail: String) : SaveResult
}
