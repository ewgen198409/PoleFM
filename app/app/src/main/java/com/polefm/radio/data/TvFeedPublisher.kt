package com.polefm.radio.data

import android.content.Context
import android.content.SharedPreferences
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.PreviewChannel
import com.polefm.radio.R
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import java.net.URLEncoder

/** Publishes recently played stations into the Android TV home screen channel. */
class TvFeedPublisher(context: Context) {
    private val app = context.applicationContext
    private val helper = PreviewChannelHelper(app)
    private val prefs: SharedPreferences = app.getSharedPreferences("polefm_tv", Context.MODE_PRIVATE)

    fun publish(stations: List<Station>) {
        try {
            val channelId = ensureChannel()
            val previous = prefs.getString(KEY_PROGRAM_IDS, "").orEmpty()
                .split(',').mapNotNull { it.toLongOrNull() }
            previous.forEach { id -> runCatching { helper.deletePreviewProgram(id) } }

            val poster = Uri.parse("android.resource://${app.packageName}/${R.drawable.banner}")
            val ids = mutableListOf<Long>()
            stations.take(MAX_PROGRAMS).forEachIndexed { index, station ->
                val deepLink = Uri.parse(
                    "polefm://station?url=" + URLEncoder.encode(station.url, "UTF-8")
                )
                val program = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setTitle(station.name)
                    .setDescription(station.url)
                    .setIntentUri(deepLink)
                    .setType(TvContract.PreviewPrograms.TYPE_STATION)
                    .setPosterArtUri(poster)
                    .setLogoUri(poster)
                    .setWeight(MAX_PROGRAMS - index)
                    .setLive(true)
                    .setBrowsable(true)
                    .build()
                val id = helper.publishPreviewProgram(program)
                if (id != -1L) ids.add(id)
            }
            prefs.edit().putString(KEY_PROGRAM_IDS, ids.joinToString(",")).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Could not publish TV channel: ${e.message}")
        }
    }

    private fun ensureChannel(): Long {
        val saved = prefs.getLong(KEY_CHANNEL_ID, -1L)
        if (saved != -1L && runCatching { helper.getPreviewChannel(saved) }.getOrNull() != null) {
            return saved
        }
        val poster = Uri.parse("android.resource://${app.packageName}/${R.drawable.banner}")
        val channel = PreviewChannel.Builder()
            .setDisplayName("PoleFM — недавно прослушанные")
            .setDescription("Станции, которые вы недавно слушали")
            .setAppLinkIntentUri(Uri.parse("polefm://recent"))
            .setLogo(poster)
            .build()
        val id = helper.publishDefaultChannel(channel)
        if (id != -1L) prefs.edit().putLong(KEY_CHANNEL_ID, id).apply()
        return id
    }

    private companion object {
        const val TAG = "PoleFM.TvFeed"
        const val KEY_CHANNEL_ID = "feed_channel_id"
        const val KEY_PROGRAM_IDS = "feed_program_ids"
        const val MAX_PROGRAMS = 8
    }
}
