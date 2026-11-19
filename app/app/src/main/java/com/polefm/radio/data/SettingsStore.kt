package com.polefm.radio.data

import android.content.Context
import kotlinx.serialization.encodeToString

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("polefm_tv", Context.MODE_PRIVATE)

    var volume: Int
        get() = prefs.getInt("volume", 60).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("volume", value).apply()

    var stations: List<Station>?
        get() = runCatching {
            prefs.getString("stations", null)?.let { Api.json.decodeFromString<List<Station>>(it) }
        }.getOrNull()
        set(value) = prefs.edit().putString(
            "stations", value?.let { Api.json.encodeToString(it) }
        ).apply()

    var eqPreset: com.polefm.radio.player.EqPreset
        get() = com.polefm.radio.player.EqPreset.fromName(prefs.getString("eq_preset", null))
        set(value) = prefs.edit().putString("eq_preset", value.name).apply()

    var recentStations: List<Station>
        get() = runCatching {
            prefs.getString("recent_stations", null)?.let {
                Api.json.decodeFromString<List<Station>>(it)
            }
        }.getOrNull() ?: emptyList()
        set(value) = prefs.edit().putString(
            "recent_stations", Api.json.encodeToString(value.take(MAX_RECENT))
        ).apply()

    private companion object {
        const val MAX_RECENT = 8
    }
}
