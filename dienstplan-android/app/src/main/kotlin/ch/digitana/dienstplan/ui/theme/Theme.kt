package ch.digitana.dienstplan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Eigene Farben statt Systemfarben: Indigo als Hauptfarbe, Koralle als Akzent. Berechnet mit den
// Material-Tonpaletten (Töne 40/90/10 hell, 80/30/90 dunkel); alle Text-Hintergrund-Paare ≥ 4.5:1.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3648E7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF000965),
    inversePrimary = Color(0xFFBDC2FF),
    secondary = Color(0xFF615A76),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7DEFF),
    onSecondaryContainer = Color(0xFF1D1830),
    tertiary = Color(0xFF9F4124),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBD1),
    onTertiaryContainer = Color(0xFF3A0A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FE),
    onBackground = Color(0xFF1B1B20),
    surface = Color(0xFFFBF8FE),
    onSurface = Color(0xFF1B1B20),
    surfaceVariant = Color(0xFFE3E1EF),
    onSurfaceVariant = Color(0xFF454651),
    surfaceTint = Color(0xFF3648E7),
    inverseSurface = Color(0xFF303035),
    inverseOnSurface = Color(0xFFF3EFF6),
    outline = Color(0xFF767682),
    outlineVariant = Color(0xFFC6C5D3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FE),
    surfaceDim = Color(0xFFDCD9DF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2F9),
    surfaceContainer = Color(0xFFF0EDF3),
    surfaceContainerHigh = Color(0xFFEAE7ED),
    surfaceContainerHighest = Color(0xFFE4E1E7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDC2FF),
    onPrimary = Color(0xFF00149F),
    primaryContainer = Color(0xFF1228D0),
    onPrimaryContainer = Color(0xFFDFE0FF),
    inversePrimary = Color(0xFF3648E7),
    secondary = Color(0xFFCBC2E2),
    onSecondary = Color(0xFF322D46),
    secondaryContainer = Color(0xFF49435E),
    onSecondaryContainer = Color(0xFFE7DEFF),
    tertiary = Color(0xFFFFB59F),
    onTertiary = Color(0xFF5F1600),
    tertiaryContainer = Color(0xFF7F2A0F),
    onTertiaryContainer = Color(0xFFFFDBD1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131317),
    onBackground = Color(0xFFE4E1E7),
    surface = Color(0xFF131317),
    onSurface = Color(0xFFE4E1E7),
    surfaceVariant = Color(0xFF454651),
    onSurfaceVariant = Color(0xFFC6C5D3),
    surfaceTint = Color(0xFFBDC2FF),
    inverseSurface = Color(0xFFE4E1E7),
    inverseOnSurface = Color(0xFF303035),
    outline = Color(0xFF908F9C),
    outlineVariant = Color(0xFF454651),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF39393D),
    surfaceDim = Color(0xFF131317),
    surfaceContainerLowest = Color(0xFF0E0E12),
    surfaceContainerLow = Color(0xFF1B1B20),
    surfaceContainer = Color(0xFF1F1F24),
    surfaceContainerHigh = Color(0xFF2A292E),
    surfaceContainerHighest = Color(0xFF353439),
)

private val BaseTypography = Typography()

/** Systemschrift mit kräftigeren Überschriften und Beschriftungen. */
private val AppTypography = BaseTypography.copy(
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = BaseTypography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.3.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun DienstplanTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalShiftPalette provides if (darkTheme) DarkShiftPalette else LightShiftPalette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
