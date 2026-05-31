package com.bdavidgm.sharedmusic.audio

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Acumula en disco los bytes de la pista recibidos por red para poder
 * reproducirla con ExoPlayer una vez completada (o al alcanzar el instante de
 * inicio). Mantiene un único archivo temporal por sesión.
 */
class TrackBuffer(private val context: Context) {

    private var output: BufferedOutputStream? = null
    private var file: File? = null

    @Volatile
    var bytesWritten: Long = 0L
        private set

    @Synchronized
    fun begin(extension: String): File {
        reset()
        val target = File(context.cacheDir, "shared_track_${System.currentTimeMillis()}.$extension")
        output = BufferedOutputStream(FileOutputStream(target))
        file = target
        bytesWritten = 0L
        return target
    }

    @Synchronized
    fun append(bytes: ByteArray) {
        output?.let {
            it.write(bytes)
            bytesWritten += bytes.size
        }
    }

    @Synchronized
    fun finish(): File? {
        runCatching { output?.flush() }
        runCatching { output?.close() }
        output = null
        return file
    }

    @Synchronized
    fun reset() {
        runCatching { output?.close() }
        output = null
        file?.let { runCatching { it.delete() } }
        file = null
        bytesWritten = 0L
    }

    companion object {
        fun extensionForMime(mime: String): String = when {
            mime.contains("mpeg") -> "mp3"
            mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
            mime.contains("ogg") -> "ogg"
            mime.contains("wav") -> "wav"
            mime.contains("flac") -> "flac"
            else -> "audio"
        }
    }
}
