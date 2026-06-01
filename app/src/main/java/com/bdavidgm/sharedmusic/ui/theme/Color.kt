package com.bdavidgm.sharedmusic.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta basada en armonía cromática:
 * - **Primario (teal ~175°)** y **secundario (índigo ~245°)**: análogos en el círculo cromático,
 *   vecinos en el arco frío → buena continuidad visual.
 * - **Terciario (ámbar/coral ~25°)**: acento cálido frente al teal (~180° de diferencia con el
 *   frío) para CTAs secundarios, pistas y detalles sin chocar con el primario.
 */
object SharedMusicPalette {
    // --- Primario (teal) ---
    val Teal900 = Color(0xFF0A3D39)
    val Teal800 = Color(0xFF115E59)
    val Teal700 = Color(0xFF0F766E)
    val Teal100 = Color(0xFFCCFBF1)
    val Teal50 = Color(0xFFF0FDFA)

    // --- Secundario (índigo / azul violeta, análogo al teal) ---
    val Indigo900 = Color(0xFF1E1B4B)
    val Indigo700 = Color(0xFF4338CA)
    val Indigo600 = Color(0xFF4F46E5)
    val Indigo100 = Color(0xFFE0E7FF)
    val Indigo50 = Color(0xFFEEF2FF)

    // --- Terciario (ámbar anaranjado, acento cálido) ---
    val Amber900 = Color(0xFF431407)
    val Amber800 = Color(0xFF9A3412)
    val Amber700 = Color(0xFFC2410C)
    val Amber100 = Color(0xFFFFEDD5)
    val Amber50 = Color(0xFFFFF7ED)

    // --- Neutros de superficie ---
    val NeutralLightBg = Color(0xFFF0FDFA)
    val NeutralLightSurface = Color(0xFFFFFFFF)
    val NeutralLightOutline = Color(0xFF5F7A77)

    val NeutralDarkBg = Color(0xFF0A1614)
    val NeutralDarkSurface = Color(0xFF101E1C)
    val NeutralDarkSurfaceVariant = Color(0xFF1A2E2C)
}
