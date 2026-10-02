package ch.digitana.dienstplan.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.digitana.dienstplan.R

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

/**
 * Inter (SIL Open Font License 1.1), in der App mitgeliefert statt heruntergeladen: Es geht
 * keine Anfrage an einen Schriftendienst. Untermenge für lateinische Schrift (Deutsch,
 * Französisch, Italienisch) in vier Schnitten.
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val BaseTypography = Typography()

private fun TextStyle.inter(weight: FontWeight? = null, tracking: Double? = null): TextStyle = copy(
    fontFamily = Inter,
    fontWeight = weight ?: fontWeight,
    letterSpacing = tracking?.sp ?: letterSpacing,
)

/** Inter mit kräftigeren Überschriften; grosse Grade etwas enger gesetzt. */
private val AppTypography = Typography(
    displayLarge = BaseTypography.displayLarge.inter(tracking = -0.5),
    displayMedium = BaseTypography.displayMedium.inter(tracking = -0.4),
    displaySmall = BaseTypography.displaySmall.inter(tracking = -0.3),
    headlineLarge = BaseTypography.headlineLarge.inter(FontWeight.SemiBold, -0.4),
    headlineMedium = BaseTypography.headlineMedium.inter(FontWeight.SemiBold, -0.3),
    headlineSmall = BaseTypography.headlineSmall.inter(FontWeight.SemiBold, -0.2),
    titleLarge = BaseTypography.titleLarge.inter(FontWeight.SemiBold, -0.1),
    titleMedium = BaseTypography.titleMedium.inter(FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.inter(FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge.inter(),
    bodyMedium = BaseTypography.bodyMedium.inter(),
    bodySmall = BaseTypography.bodySmall.inter(),
    labelLarge = BaseTypography.labelLarge.inter(FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.inter(FontWeight.Medium),
    labelSmall = BaseTypography.labelSmall.inter(FontWeight.Medium).copy(fontSize = 11.sp, letterSpacing = 0.3.sp),
)

/** Ziffern mit fester Breite: Zeiten, Stunden und Zahlen stehen in Spalten bündig. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Hell, dunkel oder wie das System. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Dunkel darstellen? */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Systemfarben (Material You) gibt es ab Android 12. */
val dynamicColorAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Farbschema der App. Mit [dynamicColor] übernehmen Leisten, Knöpfe und Flächen die Farben
 * des Hintergrundbilds (Android 12+). Die Schichtfarben bleiben immer gleich: Sie tragen
 * Bedeutung und sind auf Kontrast geprüft.
 */
@Composable
fun DienstplanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalShiftPalette provides if (darkTheme) DarkShiftPalette else LightShiftPalette) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
