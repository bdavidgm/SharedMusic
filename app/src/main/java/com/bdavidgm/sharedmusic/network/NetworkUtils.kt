package com.bdavidgm.sharedmusic.network

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Nombres exactos de interfaz frecuentes para hotspot / tether (orden de prioridad).
     * No todos existen en cada dispositivo; se prueba en orden hasta encontrar IPv4.
     */
    private val PREFERRED_AP_INTERFACE_NAMES: List<String> = listOf(
        "wlan0",
        "wlan1",
        "wlan2",
        "wlan3",
        "ap0",
        "softap0",
        "swlan0"
    )

    private fun ipv4ForNetworkInterface(ni: NetworkInterface): String? {
        if (!ni.isUp || ni.isLoopback) return null
        val addrs = ni.inetAddresses.asSequence()
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .toList()
        if (addrs.isEmpty()) return null
        return addrs.firstOrNull { it.startsWith("192.168.43.") } ?: addrs.first()
    }

    private fun ipv4ForInterfaceByName(interfaceName: String): String? = runCatching {
        val ni = NetworkInterface.getByName(interfaceName) ?: return@runCatching null
        ipv4ForNetworkInterface(ni)
    }.getOrNull()

    /**
     * IPv4 en la primera interfaz de [PREFERRED_AP_INTERFACE_NAMES] que esté activa con dirección.
     */
    fun ipv4OnPreferredTetherInterfaces(): String? =
        PREFERRED_AP_INTERFACE_NAMES.firstNotNullOfOrNull { ipv4ForInterfaceByName(it) }

    /** Interfaces `ap_br_*` (p. ej. `ap_br_wlan2`) usadas en algunas ROM. */
    private fun ipv4OnApBridgeInterfaces(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && it.name.startsWith("ap_br_", ignoreCase = true) }
            .mapNotNull { ipv4ForNetworkInterface(it) }
            .firstOrNull()
    }.getOrNull()

    /**
     * Nombres de todas las interfaces activas (up, no loopback) en este instante.
     * Sirve como "foto" base para el algoritmo diferencial al activar la zona WiFi.
     */
    fun activeInterfaceNames(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .map { it.name }
            .toSet()
    }.getOrElse { emptySet() }

    /**
     * Algoritmo diferencial: lee las interfaces activas actuales y devuelve la IPv4
     * de la primera interfaz cuyo nombre **no estaba** en [baseline] (es decir, la
     * que apareció al activarse la zona WiFi). Si hay varias, prioriza `192.168.43.x`.
     */
    fun ipv4OnNewInterfaceVsBaseline(baseline: Set<String>): String? = runCatching {
        val newInterfaces = NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && it.name !in baseline }
            .toList()
        val ipv4s = newInterfaces.mapNotNull { ipv4ForNetworkInterface(it) }
        ipv4s.firstOrNull { it.startsWith("192.168.43.") } ?: ipv4s.firstOrNull()
    }.getOrNull()

    /**
     * Heurística para saber si la zona WiFi (hotspot) ya parece activa: existe una
     * interfaz con nombre típico de AP (`ap0`, `softap*`, `swlan*`, `ap_br_*`) o una
     * dirección de la subred clásica de tether `192.168.43.x`.
     */
    fun isLikelyHotspotActive(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { ni ->
            if (!ni.isUp || ni.isLoopback) return@any false
            val n = ni.name.lowercase()
            val apName = n == "ap0" ||
                n.startsWith("softap") ||
                n.startsWith("swlan") ||
                n.startsWith("ap_br_")
            val has43 = ni.inetAddresses.asSequence()
                .filterIsInstance<Inet4Address>()
                .any { it.hostAddress?.startsWith("192.168.43.") == true }
            apName || has43
        }
    }.getOrElse { false }

    /**
     * Devuelve la mejor IPv4 local para mostrar a clientes (hotspot / LAN).
     * Prioriza interfaces típicas de tether ([PREFERRED_AP_INTERFACE_NAMES], `ap_br_*`),
     * luego el resto de heurísticas (softap en nombre, 192.168.43.x, privadas).
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

        fun candidateOnPreferredName(c: Candidate): Boolean =
            c.iface in PREFERRED_AP_INTERFACE_NAMES ||
                c.iface.startsWith("ap_br_", ignoreCase = true)

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

        val preferredExact = ipv4OnPreferredTetherInterfaces()
        val preferredBridge = ipv4OnApBridgeInterfaces()

        if (candidates.isEmpty()) {
            return@runCatching preferredExact ?: preferredBridge
        }

        preferredExact
            ?: preferredBridge
            ?: candidates.firstOrNull { candidateOnPreferredName(it) && it.host.startsWith("192.168.43.") }?.host
            ?: candidates.firstOrNull { candidateOnPreferredName(it) }?.host
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
