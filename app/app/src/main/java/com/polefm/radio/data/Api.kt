package com.polefm.radio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Api {
    const val BASE = "https://polefm.mooo.com/"

    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun loadStations(): List<Station> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BASE + "stations.json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            json.decodeFromString<List<Station>>(body)
        }
    }

    suspend fun saveStations(pin: String, stations: List<Station>): SaveResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(stations).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(BASE + "save_stations.sh")
            .header("X-Pin", pin)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@use SaveResult.Error("HTTP ${response.code}")
            val data = runCatching { json.decodeFromString<SaveResponse>(text) }.getOrNull()
            when {
                data?.ok == true -> SaveResult.Ok
                data?.error == "invalid_pin" -> SaveResult.InvalidPin
                data?.error == "pin_required" -> SaveResult.PinRequired
                data?.error == "pin_not_configured" -> SaveResult.PinNotConfigured
                else -> SaveResult.Error(data?.error ?: "unknown")
            }
        }
    }

    suspend fun nowPlaying(url: String): String = withContext(Dispatchers.IO) {
        val quoted = json.encodeToString(url)
        val payload = """{"url":$quoted}""".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(BASE + "nowplaying.php").post(payload).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use ""
            val text = response.body?.string().orEmpty()
            runCatching { json.decodeFromString<NowPlayingResponse>(text).title }.getOrDefault("")
        }.trim()
    }

    suspend fun clearNowPlaying(url: String) = withContext(Dispatchers.IO) {
        val quoted = json.encodeToString(url)
        val payload = """{"action":"clear","url":$quoted}""".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(BASE + "nowplaying.php").post(payload).build()
        runCatching { client.newCall(request).execute().use { it.close() } }
    }

    suspend fun musicList(path: String): MusicListResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BASE + "music_list.sh?path=" + URLEncoder.encode(path, "UTF-8"))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            json.decodeFromString<MusicListResponse>(response.body?.string().orEmpty())
        }
    }

    fun musicStreamUrl(path: String): String =
        BASE + "music_stream.sh?path=" + URLEncoder.encode(path, "UTF-8")
}
