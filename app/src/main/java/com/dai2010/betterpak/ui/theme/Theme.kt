package com.dai2010.betterpak.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.dai2010.betterpak.data.AppSettings
import com.dai2010.betterpak.data.ThemeMode

@Composable
fun BetterPakTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val customSeed = parseColor(settings.customSeedHex)
    val colorScheme = when {
        customSeed != null -> customColorScheme(customSeed, darkTheme)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

private fun parseColor(value: String): Color? {
    if (value.isBlank()) return null
    return runCatching {
        Color(android.graphics.Color.parseColor(value.trim().let { if (it.startsWith("#")) it else "#$it" }))
    }.getOrNull()
}

private fun customColorScheme(seed: Color, darkTheme: Boolean) = if (darkTheme) {
    darkColorScheme(
        primary = seed,
        onPrimary = if (seed.luminance() > 0.5f) Color.Black else Color.White,
    )
} else {
    lightColorScheme(
        primary = seed,
        onPrimary = if (seed.luminance() > 0.5f) Color.Black else Color.White,
    )
}
