package com.bdavidgm.sharedmusic.network

import com.bdavidgm.sharedmusic.network.protocol.ControlMessage
import com.bdavidgm.sharedmusic.network.protocol.Frame
import com.bdavidgm.sharedmusic.network.protocol.FrameConnection

/**
 * Calcula el desfase de reloj contra el upstream con un intercambio tipo SNTP.
 *
 * Para cada muestra: el cliente envía t0 (su reloj), el upstream responde con t1
 * (su reloj) y el cliente anota t2 (su reloj al recibir). Con la muestra de menor
 * RTT, el offset = t1 - (t0 + t2) / 2.
 *
 * `localTime = upstreamTime - offset`.
 */
object TimeSynchronizer {

    fun measureOffset(connection: FrameConnection, samples: Int = 7): Long {
        var bestRtt = Long.MAX_VALUE
        var bestOffset = 0L
        repeat(samples) {
            val t0 = System.currentTimeMillis()
            connection.writeControl(ControlMessage.TimeRequest(t0))
            val response = readTimeResponse(connection) ?: return@repeat
            val t2 = System.currentTimeMillis()
            val rtt = t2 - response.t0
            if (rtt < bestRtt) {
                bestRtt = rtt
                bestOffset = response.t1 - (response.t0 + t2) / 2
            }
        }
        return bestOffset
    }

    private fun readTimeResponse(connection: FrameConnection): ControlMessage.TimeResponse? {
        // Durante el handshake no debería llegar otro tipo de trama, pero por
        // robustez ignoramos cualquier cosa que no sea una respuesta de tiempo.
        repeat(8) {
            val frame = connection.readFrame()
            if (frame is Frame.Control && frame.message is ControlMessage.TimeResponse) {
                return frame.message
            }
        }
        return null
    }
}
