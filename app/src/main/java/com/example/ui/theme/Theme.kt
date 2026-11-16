package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val AbotalalLightColorScheme = lightColorScheme(
    primary = RoyalBluePrimary,
    onPrimary = PureWhite,
    primaryContainer = IceBlueContainer,
    onPrimaryContainer = DeepNavy,
    secondary = ElectricBlue,
    onSecondary = PureWhite,
    secondaryContainer = SurfaceVariantLight,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = CrimsonRed,
    onTertiary = PureWhite,
    tertiaryContainer = SoftRedContainer,
    onTertiaryContainer = OnRedContainer,
    error = AlertRed,
    onError = PureWhite,
    errorContainer = SoftRedContainer,
    onErrorContainer = OnRedContainer,
    background = CoolWhiteSurface,
    onBackground = TextPrimaryLight,
    surface = PureWhite,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = OutlineLight
)

private val AbotalalDarkColorScheme = darkColorScheme(
    primary = BrightSkyBlue,
    onPrimary = PureWhite,
    primaryContainer = ElectricBlue,
    onPrimaryContainer = PureWhite,
    secondary = IceBlueContainer,
    onSecondary = DeepNavy,
    secondaryContainer = DarkSurfaceElevated,
    onSecondaryContainer = DarkTextPrimary,
    tertiary = AlertRed,
    onTertiary = PureWhite,
    tertiaryContainer = CrimsonRed,
    onTertiaryContainer = PureWhite,
    error = AlertRed,
    onError = PureWhite,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkOutline
)

val AbotalalShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AbotalalHostingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    forceRtl: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) AbotalalDarkColorScheme else AbotalalLightColorScheme

    val layoutDirection = if (forceRtl) LayoutDirection.Rtl else LocalLayoutDirection.current

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AbotalalTypography,
            shapes = AbotalalShapes,
            content = content
        )
    }
}
