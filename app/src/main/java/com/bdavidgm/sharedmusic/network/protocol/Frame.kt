package com.bdavidgm.sharedmusic.network.protocol

import kotlinx.serialization.json.Json

/**
 * Una trama leída de la conexión: o bien un mensaje de control, o bien un bloque
 * de bytes de audio.
 */
sealed interface Frame {
    data class Control(val message: ControlMessage) : Frame
    data class Chunk(val bytes: ByteArray) : Frame {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Chunk && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    companion object {
        const val TYPE_CONTROL: Byte = 1
        const val TYPE_CHUNK: Byte = 2

        val json: Json = Json {
            classDiscriminator = "t"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
