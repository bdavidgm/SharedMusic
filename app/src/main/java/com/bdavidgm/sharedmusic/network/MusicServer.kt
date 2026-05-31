package com.bdavidgm.sharedmusic.network

import com.bdavidgm.sharedmusic.domain.model.Peer
import com.bdavidgm.sharedmusic.network.protocol.ControlMessage
import com.bdavidgm.sharedmusic.network.protocol.Frame
import com.bdavidgm.sharedmusic.network.protocol.FrameConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Acepta conexiones entrantes y distribuye el stream a todos los clientes
 * conectados. Lo usan tanto el [com.bdavidgm.sharedmusic.domain.model.NodeMode.SERVER]
 * como el [com.bdavidgm.sharedmusic.domain.model.NodeMode.REPEATER].
 *
 * Responde a las peticiones de sincronización de reloj con su propio reloj, de
 * modo que cada cliente queda sincronizado con ESTE nodo (no con el origen).
 */
class MusicServer(
    private val port: Int,
    private val scope: CoroutineScope,
    private val listener: Listener
) {
    interface Listener {
        fun onPeerConnected(peer: Peer)
        fun onPeerDisconnected(peer: Peer)
        fun onError(throwable: Throwable)
    }

    private data class Client(val peer: Peer, val connection: FrameConnection, val job: Job)

    private val clients = ConcurrentHashMap<String, Client>()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val peers: List<Peer> get() = clients.values.map { it.peer }

    fun start() {
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                while (isActive) {
                    val client = socket.accept()
                    handleNewConnection(FrameConnection(client))
                }
            } catch (t: Throwable) {
                if (isActive) listener.onError(t)
            }
        }
    }

    private fun handleNewConnection(connection: FrameConnection) {
        val id = connection.remoteAddress
        val peer = Peer(id = id, address = id)
        val job = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val frame = connection.readFrame()
                    if (frame is Frame.Control && frame.message is ControlMessage.TimeRequest) {
                        connection.writeControl(
                            ControlMessage.TimeResponse(
                                t0 = frame.message.t0,
                                t1 = System.currentTimeMillis()
                            )
                        )
                    }
                    // El resto de tramas entrantes de un cliente se ignoran.
                }
            } catch (_: Throwable) {
                // Desconexión: se limpia abajo.
            } finally {
                clients.remove(id)?.connection?.close()
                listener.onPeerDisconnected(peer)
            }
        }
        clients[id] = Client(peer, connection, job)
        listener.onPeerConnected(peer)
    }

    fun broadcastControl(message: ControlMessage) = forEachClient { it.writeControl(message) }

    fun broadcastChunk(bytes: ByteArray, length: Int) =
        forEachClient { it.writeChunk(bytes, 0, length) }

    fun broadcastFrame(frame: Frame) = forEachClient { it.forward(frame) }

    private inline fun forEachClient(action: (FrameConnection) -> Unit) {
        clients.values.forEach { client ->
            runCatching { action(client.connection) }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        clients.values.forEach { runCatching { it.connection.close() } }
        clients.clear()
    }
}
