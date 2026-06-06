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
import com.bdavidgm.sharedmusic.domain.model.PlaylistItem
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * Foto de las interfaces activas tomada al iniciar el nodo cuando la zona WiFi
     * NO estaba activa. Si es null, la zona WiFi ya estaba activa al arrancar y se
     * usa directamente el algoritmo de lista de preferencia.
     */
    private var interfaceBaseline: Set<String>? = null

    private var selectedTrackUri: Uri? = null

    private var playlistMode: Boolean = false
    private var playlistIndex: Int = 0
    private var serverStreamJob: Job? = null

    /** Invalida callbacks de jobs de transferencia cancelados al iniciar otro o detener. */
    private val serverStreamGeneration = AtomicInteger(0)

    private fun prepareStreamReplacement(): Int {
        serverStreamJob?.cancel()
        serverStreamJob = null
        return serverStreamGeneration.incrementAndGet()
    }

    // region Inicio de sesión por rol

    fun startServer(port: Int) {
        reset()
        val requested = port.coerceIn(1, 65535)
        val chosen = NetworkUtils.findAvailableListenPort(requested) ?: run {
            _state.update {
                it.copy(
                    mode = NodeMode.SERVER,
                    phase = SessionPhase.ERROR,
                    listenPort = requested,
                    errorMessage = "No hay ningún puerto TCP libre (1-65535)."
                )
            }
            return
        }
        val portNote =
            if (chosen != requested) " (el $requested estaba ocupado; usando $chosen)"
            else ""
        _state.update {
            it.copy(
                mode = NodeMode.SERVER,
                phase = SessionPhase.READY,
                listenPort = chosen,
                localAddress = NetworkUtils.localIpv4Address(),
                playlist = emptyList(),
                playingFromPlaylist = false,
                message = "Servidor listo en puerto $chosen$portNote. Usa Conexión para la red y Reproducción para la cola."
            )
        }
        server = MusicServer(chosen, scope, serverListener).also { it.start() }
        registerLocalAddressNetworkObserver()
        audio.setOnPlaybackEndedListener {
            if (_state.value.mode == NodeMode.SERVER && playlistMode) {
                onPlaylistTrackPlaybackEnded()
            }
        }
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
        client = MusicClient(
            host = host,
            port = port,
            nodeId = nodeId,
            role = NodeMode.CLIENT.name,
            deviceManufacturer = clientDeviceManufacturer(),
            deviceModel = clientDeviceModel(),
            scope = scope,
            listener = clientListener
        ).also { it.connect() }
    }

    fun startRepeater(upstreamHost: String, upstreamPort: Int, listenPort: Int) {
        reset()
        val requested = listenPort.coerceIn(1, 65535)
        val chosen = NetworkUtils.findAvailableListenPort(requested) ?: run {
            _state.update {
                it.copy(
                    mode = NodeMode.REPEATER,
                    phase = SessionPhase.ERROR,
                    listenPort = requested,
                    upstreamHost = upstreamHost,
                    errorMessage = "No hay ningún puerto TCP libre (1-65535) para escuchar."
                )
            }
            return
        }
        val portNote =
            if (chosen != requested) " Puerto de escucha: $chosen (el $requested estaba ocupado)."
            else " Puerto de escucha: $chosen."
        _state.update {
            it.copy(
                mode = NodeMode.REPEATER,
                phase = SessionPhase.CONNECTING,
                upstreamHost = upstreamHost,
                listenPort = chosen,
                localAddress = NetworkUtils.localIpv4Address(),
                message = "Repetidor: conectando a $upstreamHost:$upstreamPort…$portNote"
            )
        }
        server = MusicServer(chosen, scope, serverListener).also { it.start() }
        client = MusicClient(
            host = upstreamHost,
            port = upstreamPort,
            nodeId = nodeId,
            role = NodeMode.REPEATER.name,
            deviceManufacturer = clientDeviceManufacturer(),
            deviceModel = clientDeviceModel(),
            scope = scope,
            listener = clientListener
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

    fun addPlaylistItems(uris: List<Uri>) {
        if (_state.value.mode != NodeMode.SERVER) return
        if (uris.isEmpty()) return
        val existing = _state.value.playlist.map { it.uriString }.toSet()
        val newItems = uris
            .distinctBy { it.toString() }
            .filter { it.toString() !in existing }
            .map { uri ->
                val info = readTrackInfo(uri)
                PlaylistItem(
                    uriString = uri.toString(),
                    name = info.name,
                    mimeType = info.mimeType,
                    sizeBytes = info.sizeBytes,
                    durationMs = info.durationMs
                )
            }
        if (newItems.isEmpty()) return
        _state.update {
            it.copy(playlist = it.playlist + newItems, errorMessage = null)
        }
    }

    fun addPlaylistFromTree(treeUri: Uri) {
        if (_state.value.mode != NodeMode.SERVER) return
        scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val uris = PlaylistAudioScanner.collectAudioUrisFromTree(context, treeUri)
            if (uris.isEmpty()) {
                _state.update {
                    it.copy(message = "No se encontraron archivos de audio en esa carpeta.")
                }
                return@launch
            }
            addPlaylistItems(uris)
            _state.update {
                it.copy(message = "Se agregaron ${uris.size} pista(s) desde la carpeta.")
            }
        }
    }

    fun removePlaylistItemAt(index: Int) {
        if (_state.value.mode != NodeMode.SERVER) return
        val list = _state.value.playlist
        if (index !in list.indices) return
        val interrupted = playlistMode && index == playlistIndex
        val streamJob = if (interrupted) serverStreamJob else null
        if (interrupted) {
            prepareStreamReplacement()
        }
        val newList = list.toMutableList().apply { removeAt(index) }
        if (playlistMode) {
            when {
                index < playlistIndex -> playlistIndex--
                index == playlistIndex && newList.isNotEmpty() && playlistIndex >= newList.size ->
                    playlistIndex = newList.lastIndex
            }
        }
        val emptyAfter = newList.isEmpty()
        if (interrupted && emptyAfter) {
            playlistMode = false
            playlistIndex = 0
        }
        _state.update {
            it.copy(
                playlist = newList,
                playingFromPlaylist = if (interrupted && emptyAfter) false else it.playingFromPlaylist,
                phase = if (interrupted && emptyAfter) SessionPhase.READY else it.phase,
                isPlaying = if (interrupted && emptyAfter) false else it.isPlaying,
                positionMs = if (interrupted && emptyAfter) 0L else it.positionMs,
                message = if (interrupted && emptyAfter) "Lista actualizada." else it.message
            )
        }
        val resumeNext = interrupted && !emptyAfter && _state.value.playingFromPlaylist
        val nextUri = if (resumeNext) Uri.parse(newList[playlistIndex].uriString) else null
        if (interrupted) {
            scope.launch {
                streamJob?.join()
                audio.stop()
                server?.broadcastControl(ControlMessage.Stop)
                if (nextUri != null) {
                    playlistMode = true
                    delay(200)
                    launchServerStreamFromUri(nextUri)
                }
            }
        }
    }

    /**
     * Inicia o detiene la reproducción desde la cola del servidor.
     * Al detener cancela la transferencia en curso.
     */
    fun togglePlaylistTransport() {
        if (_state.value.mode != NodeMode.SERVER) return
        if (_state.value.playingFromPlaylist || playlistMode) {
            playlistMode = false
            val streamJob = serverStreamJob
            prepareStreamReplacement()
            playlistIndex = 0
            scope.launch {
                streamJob?.join()
                audio.stop()
                server?.broadcastControl(ControlMessage.Stop)
                _state.update {
                    it.copy(
                        isPlaying = false,
                        phase = SessionPhase.READY,
                        positionMs = 0L,
                        playingFromPlaylist = false,
                        message = "Reproducción detenida."
                    )
                }
            }
            return
        }
        val list = _state.value.playlist
        if (list.isEmpty()) {
            _state.update {
                it.copy(errorMessage = "Agrega canciones o carpetas a la lista primero.")
            }
            return
        }
        playlistMode = true
        playlistIndex = 0
        _state.update {
            it.copy(
                playingFromPlaylist = true,
                errorMessage = null,
                phase = SessionPhase.READY,
                message = "Iniciando cola de reproducción…"
            )
        }
        launchServerStreamFromUri(Uri.parse(list[0].uriString))
    }

    private fun onPlaylistTrackPlaybackEnded() {
        scope.launch {
            delay(100)
            if (!playlistMode) return@launch
            playlistIndex++
            val list = _state.value.playlist
            if (playlistIndex >= list.size) {
                playlistMode = false
                playlistIndex = 0
                audio.stop()
                server?.broadcastControl(ControlMessage.Stop)
                _state.update {
                    it.copy(
                        isPlaying = false,
                        phase = SessionPhase.READY,
                        playingFromPlaylist = false,
                        message = "Lista de reproducción finalizada."
                    )
                }
                return@launch
            }
            server?.broadcastControl(ControlMessage.Stop)
            delay(200)
            launchServerStreamFromUri(Uri.parse(list[playlistIndex].uriString))
        }
    }

    private fun launchServerStreamFromUri(uri: Uri) {
        val srv = server ?: return
        val generation = prepareStreamReplacement()
        serverStreamJob = scope.launch {
            val track = readTrackInfo(uri)
            selectedTrackUri = uri
            _state.update {
                it.copy(
                    track = track,
                    transferProgress = 0f,
                    errorMessage = null,
                    message = if (playlistMode) "Transmitiendo: ${track.name}" else "Pista: ${track.name}"
                )
            }
            try {
                _state.update { it.copy(phase = SessionPhase.TRANSFERRING) }
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
                        ensureActive()
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
            } catch (e: CancellationException) {
                trackBuffer.reset()
                audio.stop()
                if (generation == serverStreamGeneration.get()) {
                    _state.update {
                        it.copy(
                            phase = SessionPhase.READY,
                            transferProgress = 0f,
                            isPlaying = false,
                            message = "Transferencia cancelada."
                        )
                    }
                }
                throw e
            } catch (t: Throwable) {
                trackBuffer.reset()
                playlistMode = false
                _state.update {
                    it.copy(
                        phase = SessionPhase.ERROR,
                        errorMessage = t.message ?: "Error al transferir",
                        playingFromPlaylist = false
                    )
                }
            }
        }
    }

    /** Compatibilidad: reproduce la pista seleccionada con [selectTrack] (sin cola). */
    fun startPlayback() {
        val uri = selectedTrackUri ?: run {
            _state.update { it.copy(errorMessage = "Selecciona una canción primero.") }
            return
        }
        if (server == null) return
        playlistMode = false
        _state.update { it.copy(playingFromPlaylist = false) }
        launchServerStreamFromUri(uri)
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
        val streamJob = serverStreamJob
        prepareStreamReplacement()
        playlistMode = false
        scope.launch {
            streamJob?.join()
            audio.stop()
            server?.broadcastControl(ControlMessage.Stop)
            _state.update {
                it.copy(
                    isPlaying = false,
                    phase = SessionPhase.READY,
                    positionMs = 0L,
                    playingFromPlaylist = false
                )
            }
        }
    }

    // endregion

    fun stopSession() {
        reset()
        _state.value = SessionState()
    }

    private fun reset() {
        unregisterLocalAddressNetworkObserver()
        prepareStreamReplacement()
        playlistMode = false
        playlistIndex = 0
        audio.setOnPlaybackEndedListener(null)
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
        // Si la zona WiFi NO está activa al arrancar, guardamos la foto base de
        // interfaces para detectar luego cuál aparece al encenderla (diferencial).
        // Si ya está activa, baseline = null -> se usa la lista de preferencia.
        interfaceBaseline = if (NetworkUtils.isLikelyHotspotActive()) {
            null
        } else {
            NetworkUtils.activeInterfaceNames()
        }
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
        interfaceBaseline = null
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
            val ip = resolveLocalAddress() ?: return@launch
            _state.update { curr ->
                if (curr.localAddress == ip) curr else curr.copy(localAddress = ip)
            }
        }
    }

    /**
     * Elige la IP a mostrar:
     * - Si arrancamos sin zona WiFi (hay [interfaceBaseline]): algoritmo diferencial,
     *   la IP de la interfaz que apareció respecto a la foto base. Si aún no apareció
     *   ninguna nueva, cae a la lista de preferencia.
     * - Si arrancamos con la zona WiFi ya activa (baseline null): lista de preferencia.
     */
    private fun resolveLocalAddress(): String? {
        val baseline = interfaceBaseline
        return if (baseline != null) {
            NetworkUtils.ipv4OnNewInterfaceVsBaseline(baseline)
                ?: NetworkUtils.bestLocalIpv4ForDisplay()
        } else {
            NetworkUtils.bestLocalIpv4ForDisplay()
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
            trackBuffer.reset()
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

    private fun clientDeviceManufacturer(): String =
        Build.MANUFACTURER.orEmpty().trim().take(96)

    private fun clientDeviceModel(): String =
        Build.MODEL.orEmpty().trim().take(96)

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
