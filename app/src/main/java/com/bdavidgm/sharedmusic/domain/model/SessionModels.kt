package com.bdavidgm.sharedmusic.domain.model

/** Estado del ciclo de vida de la sesión, independiente del rol. */
enum class SessionPhase {
    IDLE,
    STARTING,
    CONNECTING,
    READY,
    TRANSFERRING,
    PLAYING,
    PAUSED,
    ERROR
}

/** Metadatos de la pista que se está compartiendo. */
data class TrackInfo(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long = 0L
)

/** Representa un dispositivo conectado aguas abajo (un cliente directo). */
data class Peer(
    val id: String,
    val address: String
)

/** Estado observable completo de la sesión, expuesto por el repositorio. */
data class SessionState(
    val mode: NodeMode? = null,
    val phase: SessionPhase = SessionPhase.IDLE,
    val localAddress: String? = null,
    val listenPort: Int = DEFAULT_PORT,
    val upstreamHost: String? = null,
    val downstreamPeers: List<Peer> = emptyList(),
    val track: TrackInfo? = null,
    val transferProgress: Float = 0f,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val clockOffsetMs: Long = 0L,
    val message: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        const val DEFAULT_PORT = 8765
    }
}
