package com.bdavidgm.sharedmusic.network.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mensajes de control intercambiados entre nodos. Se serializan a JSON y viajan
 * dentro de tramas de tipo [Frame.TYPE_CONTROL].
 *
 * Las marcas de tiempo de los comandos temporizados ([Play], [Seek]) se expresan
 * SIEMPRE en el reloj del emisor directo (upstream inmediato). Cada nodo las
 * traduce a su reloj local restando su offset medido y, si retransmite, las
 * vuelve a sellar con su propio reloj local.
 */
@Serializable
sealed interface ControlMessage {

    /** Saludo inicial que envía quien se conecta. */
    @Serializable
    @SerialName("hello")
    data class Hello(val role: String, val nodeId: String) : ControlMessage

    /** Petición de sincronización de reloj. [t0] es el reloj local del que pregunta. */
    @Serializable
    @SerialName("time_req")
    data class TimeRequest(val t0: Long) : ControlMessage

    /** Respuesta de sincronización. [t0] eco del original, [t1] reloj del upstream. */
    @Serializable
    @SerialName("time_resp")
    data class TimeResponse(val t0: Long, val t1: Long) : ControlMessage

    /** Metadatos de la pista, enviados antes de los chunks de audio. */
    @Serializable
    @SerialName("track_meta")
    data class TrackMeta(
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val durationMs: Long
    ) : ControlMessage

    /** Indica que ya se enviaron todos los chunks de la pista. */
    @Serializable
    @SerialName("track_complete")
    data object TrackComplete : ControlMessage

    /** Reproducir desde [positionMs] en el instante absoluto [startAtEpochMs] (reloj del emisor). */
    @Serializable
    @SerialName("play")
    data class Play(val startAtEpochMs: Long, val positionMs: Long) : ControlMessage

    /** Pausar en [positionMs]. */
    @Serializable
    @SerialName("pause")
    data class Pause(val positionMs: Long) : ControlMessage

    /** Detener la reproducción y liberar la pista. */
    @Serializable
    @SerialName("stop")
    data object Stop : ControlMessage

    /** Saltar a [positionMs], aplicado en el instante [atEpochMs] (reloj del emisor). */
    @Serializable
    @SerialName("seek")
    data class Seek(val positionMs: Long, val atEpochMs: Long) : ControlMessage

    /** Latido para mantener viva la conexión. */
    @Serializable
    @SerialName("heartbeat")
    data object Heartbeat : ControlMessage
}
