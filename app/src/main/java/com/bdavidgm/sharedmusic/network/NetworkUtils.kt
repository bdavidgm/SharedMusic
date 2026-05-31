package com.bdavidgm.sharedmusic.network

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Devuelve la primera dirección IPv4 no loopback del dispositivo, útil para
     * mostrarla a otros nodos que deban conectarse a este (servidor/repetidor).
     */
    fun localIpv4Address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}
