package com.bdavidgm.sharedmusic.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Envuelve un [ExoPlayer] y centraliza la reproducción con arranque/seek
 * programados a un instante absoluto del reloj LOCAL, lo que permite que todos
 * los nodos empiecen a la vez tras sincronizar relojes.
 *
 * Todo el acceso al reproductor se realiza en el hilo principal, como exige
 * ExoPlayer.
 */
class AudioPlayerController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pendingStart: Runnable? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var positionMs: Long = 0L
        private set

    private val ticker = object : Runnable {
        override fun run() {
            player?.let {
                positionMs = it.currentPosition
                isPlaying = it.isPlaying
            }
            mainHandler.postDelayed(this, 250)
        }
    }

    fun prepare(file: File, onError: (Throwable) -> Unit = {}) = onMain {
        runCatching {
            val exo = player ?: ExoPlayer.Builder(context).build().also { player = it }
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exo.playWhenReady = false
            exo.prepare()
            mainHandler.removeCallbacks(ticker)
            mainHandler.post(ticker)
        }.onFailure(onError)
    }

    /**
     * Programa el arranque en [localStartEpochMs] (reloj local) desde
     * [positionMs]. Si el instante ya pasó, arranca de inmediato compensando el
     * tiempo transcurrido.
     */
    fun scheduleStart(localStartEpochMs: Long, positionMs: Long) = onMain {
        val exo = player ?: return@onMain
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        val now = System.currentTimeMillis()
        val delay = localStartEpochMs - now
        if (delay <= 0) {
            exo.seekTo((positionMs - delay).coerceAtLeast(0))
            exo.play()
        } else {
            exo.seekTo(positionMs)
            val start = Runnable { player?.play() }
            pendingStart = start
            mainHandler.postDelayed(start, delay)
        }
    }

    fun scheduleSeek(localAtEpochMs: Long, positionMs: Long) = onMain {
        val exo = player ?: return@onMain
        val delay = localAtEpochMs - System.currentTimeMillis()
        if (delay <= 0) {
            exo.seekTo((positionMs - delay).coerceAtLeast(0))
        } else {
            mainHandler.postDelayed({ player?.seekTo(positionMs) }, delay)
        }
    }

    fun pause(positionMs: Long? = null) = onMain {
        val exo = player ?: return@onMain
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        exo.pause()
        positionMs?.let { exo.seekTo(it) }
    }

    fun resume() = onMain { player?.play() }

    fun stop() = onMain {
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        isPlaying = false
        positionMs = 0L
    }

    fun release() = onMain {
        mainHandler.removeCallbacks(ticker)
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        player?.release()
        player = null
        isPlaying = false
        positionMs = 0L
    }

    fun durationMs(): Long = player?.duration?.coerceAtLeast(0L) ?: 0L

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }
}
