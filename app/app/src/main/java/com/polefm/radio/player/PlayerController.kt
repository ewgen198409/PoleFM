@file:OptIn(UnstableApi::class)

package com.polefm.radio.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.polefm.radio.data.Api
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class PlayerSnapshot(
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val audioSessionId: Int = 0,
    val position: Long = 0L,
    val duration: Long = 0L,
    val buffered: Long = 0L,
    val connected: Boolean = false
)

class PlayerController(context: Context) {
    private val appContext = context.applicationContext
    private var controller: MediaController? = null

    val state = MutableStateFlow(PlayerSnapshot())
    val errors = MutableSharedFlow<PlaybackException>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun connect() {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(playerListener)
                publish()
            } catch (e: Exception) {
                state.update { it.copy(connected = false) }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
        override fun onAudioSessionIdChanged(audioSessionId: Int) = publish()
        override fun onPlayerError(error: PlaybackException) {
            errors.tryEmit(error)
        }
    }

    private fun publish() {
        val c = controller ?: return
        state.update {
            it.copy(
                isPlaying = c.isPlaying,
                playbackState = c.playbackState,
                duration = if (c.duration == C.TIME_UNSET) 0L else c.duration,
                buffered = c.bufferedPosition,
                connected = true
            )
        }
    }

    fun pollPosition() {
        val c = controller ?: return
        val pos = c.currentPosition
        val sessionId = appContext.getSharedPreferences("polefm_tv", Context.MODE_PRIVATE)
            .getInt("audio_session_id", 0)
        state.update { it.copy(position = pos, buffered = c.bufferedPosition, audioSessionId = sessionId) }
    }

    private fun mediaItem(url: String, title: String): MediaItem = MediaItem.Builder()
        .setUri(url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist("PoleFM")
                .build()
        )
        .build()

    fun playRadio(url: String, title: String) {
        val c = controller ?: return
        c.setMediaItems(listOf(mediaItem(url, title)), 0, 0L)
        c.prepare()
        c.play()
    }

    /** item pairs: url to display title. Playlist gives automatic "next track" like the web player. */
    fun playMusicPlaylist(items: List<Pair<String, String>>, startIndex: Int) {
        val c = controller ?: return
        val mediaItems = items.map { mediaItem(it.first, it.second) }
        c.setMediaItems(mediaItems, startIndex.coerceIn(0, mediaItems.lastIndex.coerceAtLeast(0)), 0L)
        c.prepare()
        c.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun resume() {
        controller?.play()
    }

    fun stop() {
        controller?.stop()
        controller?.clearMediaItems()
        state.update { it.copy(position = 0L, duration = 0L, buffered = 0L, isPlaying = false) }
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    fun setVolume(value: Int) {
        controller?.volume = (value.coerceIn(0, 100) / 100f)
    }

    fun release() {
        controller?.release()
        controller = null
    }
}
