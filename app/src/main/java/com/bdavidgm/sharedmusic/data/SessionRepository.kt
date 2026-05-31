package com.bdavidgm.sharedmusic.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.bdavidgm.sharedmusic.audio.AudioPlayerController
import com.bdavidgm.sharedmusic.audio.TrackBuffer
import com.bdavidgm.sharedmusic.domain.model.NodeMode
import com.bdavidgm.sharedmusic.domain.model.Peer
import com.bdavidgm.sharedmusic.domain.model.SessionPhase
import com.bdavidgm.sharedmusic.domain.model.SessionState
import com.bdavidgm.sharedmusic.domain.model.TrackInfo
import com.bdavidgm.sharedmusic.network.MusicClient
import com.bdavidgm.sharedmusic.network.MusicServer
import com.bdavidgm.sharedmusic.network.NetworkUtils
import com.bdavidgm.sharedmusic.network.protocol.ControlMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Punto único de orquestación de la sesión. Conoce los tres roles y compone la
 * red ([MusicServer]/[MusicClient]) con el audio ([AudioPlayerController]).
 *
 * El repetidor se construye como cliente + servidor: todo lo que llega del
 * upstream se reproduce localmente y se retransmite aguas abajo, re-sellando los
 * comandos temporizados con el reloj local de este nodo.
 */
@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audio: AudioPlayerController,
    private val trackBuffer: TrackBuffer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nodeId = UUID.randomUUID().toString().take(8)

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var server: MusicServer? = null
    private var client: MusicClient? = null

    private var localAddressNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var localAddressRefreshJob: Job? = null
    private var wifiApStateReceiver: BroadcastReceiver? = null

    private var selectedTrackUri: Uri? = null

    // region Inicio de sesión por rol

    fun startServer(port: Int) {
        reset()
        _state.update {
            it.copy(
                mode = NodeMode.SERVER,
                phase = SessionPhase.READY,
                listenPort = port,
                localAddress = NetworkUtils.localIpv4Address(),
                message = "Servidor listo. Comparte tu IP y selecciona una canción."
            )
        }
        server = MusicServer(port, scope, serverListener).also { it.start() }
        registerLocalAddressNetworkObserver()
    }

    fun startClient(host: String, port: Int) {
        reset()
        _state.update {
            it.copy(
                mode = NodeMode.CLIENT,
                phase = SessionPhase.CONNECTING,
                upstreamHost = host,
                listenPort = port,
                message = "Conectando a $host:$port…"
            )
        }
        client = MusicClient(host, port, nodeId, NodeMode.CLIENT.name, scope, clientListener)
            .also { it.connect() }
    }

    fun startRepeater(upstreamHost: String, upstreamPort: Int, listenPort: Int) {
        reset()
        _state.update {
            it.copy(
                mode = NodeMode.REPEATER,
                phase = SessionPhase.CONNECTING,
                upstreamHost = upstreamHost,
                listenPort = listenPort,
                localAddress = NetworkUtils.localIpv4Address(),
                message = "Repetidor: conectando a $upstreamHost:$upstreamPort…"
            )
        }
        server = MusicServer(listenPort, scope, serverListener).also { it.start() }
        client = MusicClient(
            upstreamHost, upstreamPort, nodeId, NodeMode.REPEATER.name, scope, clientListener
        ).also { it.connect() }
        registerLocalAddressNetworkObserver()
    }

    // endregion

    // region Acciones del servidor (origen)

    fun selectTrack(uri: Uri) {
        selectedTrackUri = uri
        val info = readTrackInfo(uri)
        _state.update {
            it.copy(track = info, message = "Pista seleccionada: ${info.name}")
        }
    }

    /** Distribuye la pista a todos los clientes y programa el arranque común. */
    fun startPlayback() {
        val uri = selectedTrackUri ?: run {
            _state.update { it.copy(errorMessage = "Selecciona una canción primero.") }
            return
        }
        val srv = server ?: return
        val track = _state.value.track ?: readTrackInfo(uri)

        scope.launch {
            try {
                _state.update { it.copy(phase = SessionPhase.TRANSFERRING, transferProgress = 0f) }
                val extension = TrackBuffer.extensionForMime(track.mimeType)
                val localFile = trackBuffer.begin(extension)

                srv.broadcastControl(
                    ControlMessage.TrackMeta(
                        name = track.name,
                        mimeType = track.mimeType,
                        sizeBytes = track.sizeBytes,
                        durationMs = track.durationMs
                    )
                )

                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var sent = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        srv.broadcastChunk(buffer, read)
                        trackBuffer.append(buffer.copyOf(read))
                        sent += read
                        val progress =
                            if (track.sizeBytes > 0) sent.toFloat() / track.sizeBytes else 0f
                        _state.update { it.copy(transferProgress = progress.coerceIn(0f, 1f)) }
                    }
                }
                trackBuffer.finish()
                srv.broadcastControl(ControlMessage.TrackComplete)

                audio.prepare(localFile)

                val startAt = System.currentTimeMillis() + START_LEAD_MS
                srv.broadcastControl(ControlMessage.Play(startAtEpochMs = startAt, positionMs = 0))
                audio.scheduleStart(startAt, 0)
                _state.update {
                    it.copy(
                        phase = SessionPhase.PLAYING,
                        isPlaying = true,
                        transferProgress = 1f,
                        message = "Reproduciendo en sincronía."
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(phase = SessionPhase.ERROR, errorMessage = t.message ?: "Error al transferir")
                }
            }
        }
    }

    // endregion

    // region Controles de transporte (servidor; el repetidor los origina su upstream)

    fun pause() {
        val pos = audio.positionMs
        audio.pause(pos)
        server?.broadcastControl(ControlMessage.Pause(pos))
        _state.update { it.copy(isPlaying = false, phase = SessionPhase.PAUSED, positionMs = pos) }
    }

    fun resume() {
        val startAt = System.currentTimeMillis() + RESUME_LEAD_MS
        val pos = audio.positionMs
        audio.scheduleStart(startAt, pos)
        server?.broadcastControl(ControlMessage.Play(startAt, pos))
        _state.update { it.copy(isPlaying = true, phase = SessionPhase.PLAYING) }
    }

    fun seekTo(positionMs: Long) {
        val at = System.currentTimeMillis() + RESUME_LEAD_MS
        audio.scheduleSeek(at, positionMs)
        server?.broadcastControl(ControlMessage.Seek(positionMs, at))
        _state.update { it.copy(positionMs = positionMs) }
    }

    fun stopPlayback() {
        audio.stop()
        server?.broadcastControl(ControlMessage.Stop)
        _state.update {
            it.copy(isPlaying = false, phase = SessionPhase.READY, positionMs = 0L)
        }
    }

    // endregion

    fun stopSession() {
        reset()
        _state.value = SessionState()
    }

    private fun reset() {
        unregisterLocalAddressNetworkObserver()
        client?.close()
        server?.stop()
        client = null
        server = null
        audio.stop()
        trackBuffer.reset()
    }

    /**
     * Actualiza la IP mostrada cuando cambian las redes o el hotspot portable:
     * [ConnectivityManager.registerNetworkCallback] y los broadcasts de estado
     * del hotspot (`android.net.wifi.WIFI_AP_STATE_CHANGED` y la acción API 33+).
     */
    private fun registerLocalAddressNetworkObserver() {
        unregisterLocalAddressNetworkObserver()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleLocalAddressRefresh()

            override fun onLost(network: Network) = scheduleLocalAddressRefresh()

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) = scheduleLocalAddressRefresh()

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties
            ) = scheduleLocalAddressRefresh()
        }
        localAddressNetworkCallback = callback
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        }
        registerWifiApStateReceiver()
        scheduleLocalAddressRefresh()
    }

    private fun unregisterLocalAddressNetworkObserver() {
        localAddressRefreshJob?.cancel()
        localAddressRefreshJob = null
        unregisterWifiApStateReceiver()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        localAddressNetworkCallback?.let {
            runCatching { cm.unregisterNetworkCallback(it) }
        }
        localAddressNetworkCallback = null
    }

    /** Reacciona al hotspot portable del sistema (zona WiFi). */
    private fun registerWifiApStateReceiver() {
        unregisterWifiApStateReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(appContext: Context?, intent: Intent?) {
                scheduleLocalAddressRefresh(delayMs = 900L)
            }
        }
        wifiApStateReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_WIFI_AP_STATE_LEGACY)
            addAction(ACTION_WIFI_AP_STATE_PLATFORM)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
        }
    }

    private fun unregisterWifiApStateReceiver() {
        wifiApStateReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        wifiApStateReceiver = null
    }

    private fun scheduleLocalAddressRefresh(delayMs: Long = 450L) {
        val mode = _state.value.mode
        if (mode != NodeMode.SERVER && mode != NodeMode.REPEATER) return
        localAddressRefreshJob?.cancel()
        localAddressRefreshJob = scope.launch {
            delay(delayMs)
            val ip = NetworkUtils.bestLocalIpv4ForDisplay() ?: return@launch
            _state.update { curr ->
                if (curr.localAddress == ip) curr else curr.copy(localAddress = ip)
            }
        }
    }

    // region Listeners de red

    private val serverListener = object : MusicServer.Listener {
        override fun onPeerConnected(peer: Peer) = updatePeers()
        override fun onPeerDisconnected(peer: Peer) = updatePeers()
        override fun onError(throwable: Throwable) {
            _state.update {
                it.copy(errorMessage = "Error de servidor: ${throwable.message}")
            }
        }

        private fun updatePeers() {
            _state.update { it.copy(downstreamPeers = server?.peers ?: emptyList()) }
        }
    }

    private val clientListener = object : MusicClient.Listener {
        override fun onConnected(clockOffsetMs: Long) {
            _state.update {
                it.copy(
                    phase = SessionPhase.READY,
                    clockOffsetMs = clockOffsetMs,
                    message = "Conectado. Desfase de reloj: ${clockOffsetMs} ms"
                )
            }
        }

        override fun onTrackMeta(meta: ControlMessage.TrackMeta) {
            val extension = TrackBuffer.extensionForMime(meta.mimeType)
            trackBuffer.begin(extension)
            _state.update {
                it.copy(
                    phase = SessionPhase.TRANSFERRING,
                    transferProgress = 0f,
                    track = TrackInfo(meta.name, meta.mimeType, meta.sizeBytes, meta.durationMs)
                )
            }
            server?.broadcastControl(meta)
        }

        override fun onChunk(bytes: ByteArray) {
            trackBuffer.append(bytes)
            server?.broadcastChunk(bytes, bytes.size)
            val size = _state.value.track?.sizeBytes ?: 0L
            if (size > 0) {
                val progress = (trackBuffer.bytesWritten.toFloat() / size).coerceIn(0f, 1f)
                _state.update { it.copy(transferProgress = progress) }
            }
        }

        override fun onTrackComplete() {
            val file = trackBuffer.finish()
            server?.broadcastControl(ControlMessage.TrackComplete)
            file?.let { audio.prepare(it) }
            _state.update { it.copy(transferProgress = 1f, message = "Pista recibida.") }
        }

        override fun onPlay(localStartEpochMs: Long, positionMs: Long) {
            audio.scheduleStart(localStartEpochMs, positionMs)
            server?.broadcastControl(ControlMessage.Play(localStartEpochMs, positionMs))
            _state.update { it.copy(phase = SessionPhase.PLAYING, isPlaying = true) }
        }

        override fun onPause(positionMs: Long) {
            audio.pause(positionMs)
            server?.broadcastControl(ControlMessage.Pause(positionMs))
            _state.update { it.copy(isPlaying = false, phase = SessionPhase.PAUSED, positionMs = positionMs) }
        }

        override fun onStop() {
            audio.stop()
            server?.broadcastControl(ControlMessage.Stop)
            _state.update { it.copy(isPlaying = false, phase = SessionPhase.READY, positionMs = 0L) }
        }

        override fun onSeek(localAtEpochMs: Long, positionMs: Long) {
            audio.scheduleSeek(localAtEpochMs, positionMs)
            server?.broadcastControl(ControlMessage.Seek(positionMs, localAtEpochMs))
            _state.update { it.copy(positionMs = positionMs) }
        }

        override fun onDisconnected() {
            _state.update {
                it.copy(
                    phase = if (it.phase == SessionPhase.ERROR) it.phase else SessionPhase.IDLE,
                    message = "Desconectado del upstream."
                )
            }
        }

        override fun onError(throwable: Throwable) {
            _state.update {
                it.copy(phase = SessionPhase.ERROR, errorMessage = "Conexión fallida: ${throwable.message}")
            }
        }
    }

    // endregion

    private fun readTrackInfo(uri: Uri): TrackInfo {
        var name = "audio"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        }
        val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
        val duration = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
        return TrackInfo(name = name, mimeType = mime, sizeBytes = size, durationMs = duration)
    }

    companion object {
        private const val START_LEAD_MS = 3000L
        private const val RESUME_LEAD_MS = 800L

        /** Valor de [android.net.wifi.WifiManager.WIFI_AP_STATE_CHANGED_ACTION] (API antigua). */
        private const val ACTION_WIFI_AP_STATE_LEGACY = "android.net.wifi.WIFI_AP_STATE_CHANGED"

        /** Valor de la acción de hotspot en API 33+ (nombre estable en la plataforma). */
        private const val ACTION_WIFI_AP_STATE_PLATFORM = "android.net.wifi.action.WIFI_AP_STATE_CHANGED"
    }
}
