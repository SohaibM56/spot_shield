package com.hcrdevelopers.spotshield.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = ShieldBlue,
    onPrimary = ShieldWhite,
    secondary = ShieldBlue,
    tertiary = ShieldGreen,
    background = ShieldBlack,
    onBackground = ShieldWhite,
    surface = ShieldSurface,
    onSurface = ShieldWhite,
    surfaceVariant = ShieldCard,
    onSurfaceVariant = ShieldGray,
    outline = ShieldDivider,
    outlineVariant = ShieldDivider,
    error = ShieldRed,
    onError = ShieldWhite
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun SPoTShieldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default: the app's blue/red/green accents are fixed, so wallpaper-based
    // colors would make surfaces and accents clash on Android 12+.
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
