package com.bdavidgm.sharedmusic.domain.model

/**
 * Rol que cumple el dispositivo dentro de la sesión.
 *
 * - [SERVER]: origen de la música. Abre un servidor TCP y distribuye la pista.
 * - [CLIENT]: se conecta a un upstream (servidor o repetidor) y reproduce.
 * - [REPEATER]: es cliente de un upstream y, a la vez, servidor para nuevos
 *   clientes; retransmite todo lo que recibe hacia aguas abajo.
 */
enum class NodeMode {
    SERVER,
    CLIENT,
    REPEATER
}
