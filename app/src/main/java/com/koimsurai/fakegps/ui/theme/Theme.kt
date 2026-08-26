package com.koimsurai.fakegps.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColors: ColorScheme = darkColorScheme(
    primary = brandPrimaryDark,
    onPrimary = brandOnPrimaryDark,
    primaryContainer = brandPrimaryContainerDark,
    onPrimaryContainer = brandOnPrimaryContainerDark,
    secondary = brandSecondaryDark,
    onSecondary = brandOnSecondaryDark,
    secondaryContainer = brandSecondaryContainerDark,
    onSecondaryContainer = brandOnSecondaryContainerDark,
    tertiary = brandTertiaryDark,
    onTertiary = brandOnTertiaryDark,
    tertiaryContainer = brandTertiaryContainerDark,
    onTertiaryContainer = brandOnTertiaryContainerDark,
    background = brandBackgroundDark,
    onBackground = brandOnBackgroundDark,
    surface = brandSurfaceDark,
    onSurface = brandOnSurfaceDark,
    surfaceContainerLow = brandSurfaceContainerLowDark,
    surfaceContainer = brandSurfaceContainerDark,
    surfaceContainerHigh = brandSurfaceContainerHighDark,
    surfaceContainerHighest = brandSurfaceContainerHighestDark,
    surfaceVariant = brandSurfaceVariantDark,
    onSurfaceVariant = brandOnSurfaceVariantDark,
    outline = brandOutlineDark,
    outlineVariant = brandOutlineVariantDark,
    error = brandErrorDark,
    onError = brandOnErrorDark,
    errorContainer = brandErrorContainerDark,
    onErrorContainer = brandOnErrorContainerDark,
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = brandPrimaryLight,
    onPrimary = brandOnPrimaryLight,
    primaryContainer = brandPrimaryContainerLight,
    onPrimaryContainer = brandOnPrimaryContainerLight,
    secondary = brandSecondaryLight,
    onSecondary = brandOnSecondaryLight,
    secondaryContainer = brandSecondaryContainerLight,
    onSecondaryContainer = brandOnSecondaryContainerLight,
    tertiary = brandTertiaryLight,
    onTertiary = brandOnTertiaryLight,
    tertiaryContainer = brandTertiaryContainerLight,
    onTertiaryContainer = brandOnTertiaryContainerLight,
    background = brandBackgroundLight,
    onBackground = brandOnBackgroundLight,
    surface = brandSurfaceLight,
    onSurface = brandOnSurfaceLight,
    surfaceContainerLow = brandSurfaceContainerLowLight,
    surfaceContainer = brandSurfaceContainerLight,
    surfaceContainerHigh = brandSurfaceContainerHighLight,
    surfaceContainerHighest = brandSurfaceContainerHighestLight,
    surfaceVariant = brandSurfaceVariantLight,
    onSurfaceVariant = brandOnSurfaceVariantLight,
    outline = brandOutlineLight,
    outlineVariant = brandOutlineVariantLight,
    error = brandErrorLight,
    onError = brandOnErrorLight,
    errorContainer = brandErrorContainerLight,
    onErrorContainer = brandOnErrorContainerLight,
)

// Generously rounded — the single most legible cue of the Expressive direction, and it keeps the
// app's own surfaces reading as distinct objects sitting on top of the busy map.
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

// Dynamic colour is deliberately not used: it sampled washed-out tints from the wallpaper and made
// the app look unstyled. A fixed palette keeps it identical and intentional on every device.
// MaterialExpressiveTheme is still internal in material3 1.4.0, so the Expressive look here comes
// from the palette, shapes and type below.
@Composable
fun FakeGpsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}
