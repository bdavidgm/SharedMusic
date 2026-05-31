package com.bdavidgm.sharedmusic.network

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Primera IPv4 no loopback de la interfaz **wlan0** (activa), o null si no existe.
     * En muchos dispositivos la zona WiFi (hotspot) expone la IP en esta interfaz.
     * Si hay varias direcciones, se prioriza la subred típica del tether `192.168.43.*`.
     */
    fun ipv4OnWlan0(): String? = runCatching {
        val ni = NetworkInterface.getByName("wlan0") ?: return@runCatching null
        if (!ni.isUp || ni.isLoopback) return@runCatching null
        val addrs = ni.inetAddresses.asSequence()
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .toList()
        if (addrs.isEmpty()) return@runCatching null
        addrs.firstOrNull { it.startsWith("192.168.43.") } ?: addrs.first()
    }.getOrNull()

    /**
     * Devuelve la mejor IPv4 local para mostrar a clientes (hotspot / LAN).
     * Prioriza **wlan0**, luego interfaces típicas de tethering (softap, ap0, swlan…)
     * y la subred 192.168.43.x; evita interfaces de datos móvil.
     */
    fun bestLocalIpv4ForDisplay(): String? = runCatching<String?> {
        data class Candidate(val iface: String, val host: String)

        fun isBlockedInterface(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("rmnet") ||
                n.startsWith("ccmni") ||
                n.startsWith("dummy") ||
                n.startsWith("ifb") ||
                n.startsWith("sit") ||
                n.startsWith("ip6tnl") ||
                n.startsWith("p2p") ||
                n.startsWith("v4-rmnet")
        }

        fun isLikelySoftApInterface(name: String): Boolean {
            val n = name.lowercase()
            return n.contains("softap") ||
                n == "ap0" ||
                n.startsWith("swlan") ||
                n.startsWith("ap_br_")
        }

        fun isPrivateIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts[1].toIntOrNull() ?: return false
            return when (a) {
                10 -> true
                172 -> b in 16..31
                192 -> b == 168
                else -> false
            }
        }

        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !isBlockedInterface(it.name) }
            .flatMap { ni ->
                ni.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .mapNotNull { addr -> addr.hostAddress?.let { Candidate(ni.name, it) } }
            }
            .toList()

        if (candidates.isEmpty()) return@runCatching ipv4OnWlan0()

        ipv4OnWlan0()
            ?: candidates.firstOrNull { it.iface == "wlan0" }?.host
            ?: candidates.firstOrNull { isLikelySoftApInterface(it.iface) && it.host.startsWith("192.168.43.") }?.host
            ?: candidates.firstOrNull { isLikelySoftApInterface(it.iface) }?.host
            ?: candidates.firstOrNull { it.host.startsWith("192.168.43.") }?.host
            ?: candidates.firstOrNull { isPrivateIpv4(it.host) }?.host
            ?: candidates.first().host
    }.getOrNull()

    /**
     * @see bestLocalIpv4ForDisplay
     */
    fun localIpv4Address(): String? = bestLocalIpv4ForDisplay()
}
