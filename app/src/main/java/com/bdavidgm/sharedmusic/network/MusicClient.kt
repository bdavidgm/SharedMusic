package com.bdavidgm.sharedmusic.network

import com.bdavidgm.sharedmusic.network.protocol.ControlMessage
import com.bdavidgm.sharedmusic.network.protocol.Frame
import com.bdavidgm.sharedmusic.network.protocol.FrameConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Se conecta a un upstream (servidor o repetidor), sincroniza su reloj y entrega
 * las tramas recibidas a través de [Listener].
 *
 * Los instantes de los comandos temporizados se traducen al reloj local antes de
 * entregarlos: `localEpoch = upstreamEpoch - offset`.
 */
class MusicClient(
    private val host: String,
    private val port: Int,
    private val nodeId: String,
    private val role: String,
    private val scope: CoroutineScope,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected(clockOffsetMs: Long)
        fun onTrackMeta(meta: ControlMessage.TrackMeta)
        fun onChunk(bytes: ByteArray)
        fun onTrackComplete()
        fun onPlay(localStartEpochMs: Long, positionMs: Long)
        fun onPause(positionMs: Long)
        fun onStop()
        fun onSeek(localAtEpochMs: Long, positionMs: Long)
        fun onDisconnected()
        fun onError(throwable: Throwable)
    }

    private var connection: FrameConnection? = null
    private var readJob: Job? = null

    @Volatile
    private var offsetMs: Long = 0L

    fun connect(connectTimeoutMs: Int = 8000) {
        readJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                val conn = FrameConnection(socket)
                connection = conn

                conn.writeControl(ControlMessage.Hello(role = role, nodeId = nodeId))
                offsetMs = TimeSynchronizer.measureOffset(conn)
                listener.onConnected(offsetMs)

                while (isActive) {
                    dispatch(conn.readFrame())
                }
            } catch (t: Throwable) {
                if (isActive) listener.onError(t)
            } finally {
                listener.onDisconnected()
                close()
            }
        }
    }

    private fun dispatch(frame: Frame) {
        when (frame) {
            is Frame.Chunk -> listener.onChunk(frame.bytes)
            is Frame.Control -> when (val msg = frame.message) {
                is ControlMessage.TrackMeta -> listener.onTrackMeta(msg)
                is ControlMessage.TrackComplete -> listener.onTrackComplete()
                is ControlMessage.Play ->
                    listener.onPlay(msg.startAtEpochMs - offsetMs, msg.positionMs)

                is ControlMessage.Pause -> listener.onPause(msg.positionMs)
                is ControlMessage.Stop -> listener.onStop()
                is ControlMessage.Seek ->
                    listener.onSeek(msg.atEpochMs - offsetMs, msg.positionMs)

                is ControlMessage.Heartbeat,
                is ControlMessage.Hello,
                is ControlMessage.TimeRequest,
                is ControlMessage.TimeResponse -> Unit
            }
        }
    }

    fun close() {
        readJob?.cancel()
        runCatching { connection?.close() }
        connection = null
    }
}
