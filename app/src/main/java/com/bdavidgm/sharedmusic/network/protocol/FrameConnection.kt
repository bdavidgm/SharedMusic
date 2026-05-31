package com.bdavidgm.sharedmusic.network.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * Envuelve un [Socket] y serializa/deserializa [Frame]s con un encuadre simple:
 *
 *     [4 bytes longitud big-endian][1 byte tipo][payload...]
 *
 * donde la longitud cuenta el byte de tipo más el payload. Las escrituras están
 * sincronizadas para poder retransmitir desde varios hilos sin entrelazar tramas.
 */
class FrameConnection(private val socket: Socket) : Closeable {

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val writeLock = Any()

    val remoteAddress: String
        get() = (socket.inetAddress?.hostAddress ?: "?") + ":" + socket.port

    fun writeControl(message: ControlMessage) {
        val payload = Frame.json.encodeToString(ControlMessage.serializer(), message)
            .toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            output.writeInt(payload.size + 1)
            output.writeByte(Frame.TYPE_CONTROL.toInt())
            output.write(payload)
            output.flush()
        }
    }

    fun writeChunk(bytes: ByteArray, offset: Int, length: Int) {
        synchronized(writeLock) {
            output.writeInt(length + 1)
            output.writeByte(Frame.TYPE_CHUNK.toInt())
            output.write(bytes, offset, length)
            output.flush()
        }
    }

    /** Reenvía una trama ya recibida tal cual (usado por el repetidor). */
    fun forward(frame: Frame) {
        when (frame) {
            is Frame.Control -> writeControl(frame.message)
            is Frame.Chunk -> writeChunk(frame.bytes, 0, frame.bytes.size)
        }
    }

    /** Lectura bloqueante de la siguiente trama. Lanza EOFException al cerrarse. */
    fun readFrame(): Frame {
        val length = input.readInt()
        require(length >= 1) { "Trama inválida con longitud $length" }
        val type = input.readByte()
        val payload = ByteArray(length - 1)
        input.readFully(payload)
        return when (type) {
            Frame.TYPE_CONTROL -> {
                val message = Frame.json.decodeFromString(
                    ControlMessage.serializer(),
                    String(payload, Charsets.UTF_8)
                )
                Frame.Control(message)
            }

            Frame.TYPE_CHUNK -> Frame.Chunk(payload)
            else -> throw IllegalStateException("Tipo de trama desconocido: $type")
        }
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }
}
