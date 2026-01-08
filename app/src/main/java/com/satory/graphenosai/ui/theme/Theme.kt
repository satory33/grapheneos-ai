package com.satory.graphenosai.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// Material 3 Expressive Dark Color Scheme
private val DarkColorScheme = darkColorScheme(
    primary = AIDarkPrimary,
    onPrimary = AIDarkSurface,
    primaryContainer = Purple40,
    onPrimaryContainer = Purple80,
    secondary = AIDarkSecondary,
    onSecondary = AIDarkSurface,
    secondaryContainer = PurpleGrey40,
    onSecondaryContainer = PurpleGrey80,
    tertiary = AIDarkTertiary,
    onTertiary = AIDarkSurface,
    tertiaryContainer = Pink40,
    onTertiaryContainer = Pink80,
    background = AIDarkSurface,
    onBackground = AIDarkOnSurface,
    surface = AIDarkSurface,
    onSurface = AIDarkOnSurface,
    surfaceVariant = AIDarkSurfaceVariant,
    onSurfaceVariant = AIDarkOnSurface.copy(alpha = 0.8f),
    error = AIDarkError,
    onError = AIDarkSurface
)

// Material 3 Expressive Light Color Scheme
private val LightColorScheme = lightColorScheme(
    primary = AILightPrimary,
    onPrimary = AILightSurface,
    primaryContainer = Purple80,
    onPrimaryContainer = Purple40,
    secondary = AILightSecondary,
    onSecondary = AILightSurface,
    secondaryContainer = PurpleGrey80,
    onSecondaryContainer = PurpleGrey40,
    tertiary = AILightTertiary,
    onTertiary = AILightSurface,
    tertiaryContainer = Pink80,
    onTertiaryContainer = Pink40,
    background = AILightSurface,
    onBackground = AILightOnSurface,
    surface = AILightSurface,
    onSurface = AILightOnSurface,
    surfaceVariant = AILightSurfaceVariant,
    onSurfaceVariant = AILightOnSurface.copy(alpha = 0.8f),
    error = AILightError,
    onError = AILightSurface
)

// Material 3 Expressive Shapes - more rounded for AI assistant feel
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun AiintegratedintoandroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
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
        shapes = ExpressiveShapes,
        content = content
    )
}