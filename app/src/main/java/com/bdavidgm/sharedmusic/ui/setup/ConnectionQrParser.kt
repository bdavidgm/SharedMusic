package com.bdavidgm.sharedmusic.ui.setup

/**
 * Interpreta el texto del QR de conexión del servidor (`host:puerto`),
 * el mismo formato que genera la pantalla de sesión del servidor.
 */
internal fun parseConnectionQrPayload(raw: String): Pair<String, Int>? {
    val text = raw.trim().lineSequence().firstOrNull()?.trim().orEmpty()
    if (text.isEmpty()) return null

    if (text.startsWith("[") && text.contains("]:")) {
        val close = text.indexOf(']')
        if (close <= 1) return null
        val host = text.substring(1, close).trim()
        if (host.isEmpty()) return null
        val port = text.substring(close + 2).trim().toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }

    val colon = text.lastIndexOf(':')
    if (colon <= 0 || colon >= text.lastIndex) return null
    val host = text.substring(0, colon).trim()
    if (host.isEmpty()) return null
    val port = text.substring(colon + 1).trim().toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return host to port
}
