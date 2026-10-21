package com.drivealert.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object BrandColors {
    val Navy = Color(0xFF0A1F44)
    val NavyDeep = Color(0xFF061430)
    val ElectricBlue = Color(0xFF1F6BFF)
    val ElectricBlueLight = Color(0xFF8DB4FF)
    val CardTint = Color(0xFFF3F6FC)
    val Amber = Color(0xFFF2A100)
    val WarningRed = Color(0xFFD93025)
    val Success = Color(0xFF1E8E3E)
}

/** Status colours. Red/amber are reserved for genuine warnings. */
@Immutable
data class StatusColors(
    val good: Color,
    val onGood: Color,
    val warn: Color,
    val onWarn: Color,
    val bad: Color,
    val onBad: Color,
    val header: Color,
    val onHeader: Color,
    val onHeaderMuted: Color,
)

private val LightStatus = StatusColors(
    good = Color(0xFFE3F4E8), onGood = Color(0xFF0D652D),
    warn = Color(0xFFFFF1D6), onWarn = Color(0xFF7A4F00),
    bad = Color(0xFFFCE4E2), onBad = Color(0xFFA50E0E),
    header = BrandColors.Navy, onHeader = Color.White, onHeaderMuted = Color(0xFFB9C6E4),
)

private val DarkStatus = StatusColors(
    good = Color(0xFF173B24), onGood = Color(0xFF9BE0B0),
    warn = Color(0xFF3D2E0A), onWarn = Color(0xFFFFD27A),
    bad = Color(0xFF4A1512), onBad = Color(0xFFFFB4AB),
    header = BrandColors.NavyDeep, onHeader = Color.White, onHeaderMuted = Color(0xFF9FB0D6),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val LightScheme: ColorScheme = lightColorScheme(
    primary = BrandColors.ElectricBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = BrandColors.Navy,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE3F3),
    onSecondaryContainer = Color(0xFF111C33),
    tertiary = BrandColors.Amber,
    onTertiary = Color(0xFF2B1B00),
    background = BrandColors.CardTint,
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE7ECF5),
    onSurfaceVariant = Color(0xFF475069),
    surfaceContainer = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFE),
    surfaceContainerHigh = Color(0xFFEFF3FA),
    outline = Color(0xFF8A93A8),
    outlineVariant = Color(0xFFD5DBE8),
    error = BrandColors.WarningRed,
    onError = Color.White,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = BrandColors.ElectricBlueLight,
    onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF0E47B8),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFB8C7E8),
    onSecondary = Color(0xFF1B2A48),
    secondaryContainer = Color(0xFF2A3A5C),
    onSecondaryContainer = Color(0xFFDDE3F3),
    tertiary = Color(0xFFFFC54D),
    onTertiary = Color(0xFF3F2C00),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE4E8F2),
    surface = Color(0xFF121A2B),
    onSurface = Color(0xFFE4E8F2),
    surfaceVariant = Color(0xFF1F2940),
    onSurfaceVariant = Color(0xFFBAC3D8),
    surfaceContainer = Color(0xFF151E31),
    surfaceContainerLow = Color(0xFF111A2B),
    surfaceContainerHigh = Color(0xFF1C263B),
    outline = Color(0xFF8590A8),
    outlineVariant = Color(0xFF2E3850),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    )
}

val DisplayNumberStyle = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun DriveAlertTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // Dynamic colour is intentionally off: the brand palette keeps warning colours meaningful.
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
