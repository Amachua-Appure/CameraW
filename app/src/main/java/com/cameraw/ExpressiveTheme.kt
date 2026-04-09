package com.cameraw

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFC0CB),
    onPrimary = Color(0xFF4A0E2A),
    primaryContainer = Color(0xFF8B3A62),
    onPrimaryContainer = Color(0xFFFFE4E1),

    secondary = Color(0xFF5A7B7E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF415456),
    onSecondaryContainer = Color(0xFFBDE9EC),

    error = Color(0xFFC01547),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF8A0A3C),
    onErrorContainer = Color(0xFFFFDAD9),

    background = Color(0xFF0F1111),
    onBackground = Color(0xFFF5F5F5),

    surface = Color(0xFF1A2020),
    onSurface = Color(0xFFE3E3E3),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0D6B7D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F0FF),
    onPrimaryContainer = Color(0xFF00202B),

    secondary = Color(0xFF4A6B6E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDF1F7),
    onSecondaryContainer = Color(0xFF031F22),

    error = Color(0xFFB71E47),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0F),

    background = Color(0xFFFCFCFC),
    onBackground = Color(0xFF1F1F1F),

    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF1F1F1F),
)

@Composable
fun CameraWTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RecorderTypography,
        content = content
    )
}