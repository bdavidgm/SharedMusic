package com.bdavidgm.sharedmusic.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = SharedMusicPalette.Teal700,
    onPrimary = Color.White,
    primaryContainer = SharedMusicPalette.Teal100,
    onPrimaryContainer = SharedMusicPalette.Teal900,
    secondary = SharedMusicPalette.Indigo600,
    onSecondary = Color.White,
    secondaryContainer = SharedMusicPalette.Indigo100,
    onSecondaryContainer = SharedMusicPalette.Indigo900,
    tertiary = SharedMusicPalette.Amber700,
    onTertiary = Color.White,
    tertiaryContainer = SharedMusicPalette.Amber100,
    onTertiaryContainer = SharedMusicPalette.Amber900,
    background = SharedMusicPalette.NeutralLightBg,
    onBackground = SharedMusicPalette.Teal900,
    surface = SharedMusicPalette.NeutralLightSurface,
    onSurface = SharedMusicPalette.Teal900,
    surfaceVariant = SharedMusicPalette.Teal50,
    onSurfaceVariant = SharedMusicPalette.Teal800,
    outline = SharedMusicPalette.NeutralLightOutline,
    outlineVariant = SharedMusicPalette.Teal100,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF003731),
    primaryContainer = SharedMusicPalette.Teal800,
    onPrimaryContainer = Color(0xFFA7F3EC),
    secondary = Color(0xFFC7D2FE),
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF3730A3),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Color(0xFFFFB77C),
    onTertiary = Color(0xFF431407),
    tertiaryContainer = Color(0xFF7C2D12),
    onTertiaryContainer = Color(0xFFFFEDD5),
    background = SharedMusicPalette.NeutralDarkBg,
    onBackground = Color(0xFFE6FFFA),
    surface = SharedMusicPalette.NeutralDarkSurface,
    onSurface = Color(0xFFE6FFFA),
    surfaceVariant = SharedMusicPalette.NeutralDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF94D3CE),
    outline = Color(0xFF5C7A76),
    outlineVariant = Color(0xFF2A4542),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun SharedMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Desactivado por defecto para usar la paleta armónica de la app. Actívalo para color dinámico del sistema (Android 12+). */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
