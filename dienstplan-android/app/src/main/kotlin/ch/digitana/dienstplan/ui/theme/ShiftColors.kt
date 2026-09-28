package ch.digitana.dienstplan.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ch.digitana.dienstplan.core.crdt.Shift

@Immutable
data class ShiftColor(val container: Color, val content: Color)

/**
 * Feste Schichtfarben (F blau, S orange, N violett, X grau, U grün), unabhängig von den
 * dynamischen Systemfarben. Text- und Hintergrundfarbe haben in beiden Modi mindestens
 * 4.5:1 Kontrast.
 */
@Immutable
class ShiftColors(
    private val colors: Map<Shift, ShiftColor>,
    val weekendTint: Color,
    val todayTint: Color,
) {
    operator fun get(shift: Shift): ShiftColor = colors.getValue(shift)
}

val LightShiftColors = ShiftColors(
    colors = mapOf(
        Shift.FRUEH to ShiftColor(Color(0xFFBBDEFB), Color(0xFF0D47A1)),
        Shift.SPAET to ShiftColor(Color(0xFFFFE0B2), Color(0xFF9A3A00)),
        Shift.NACHT to ShiftColor(Color(0xFFE1BEE7), Color(0xFF4A148C)),
        Shift.FREI to ShiftColor(Color(0xFFE0E0E0), Color(0xFF363636)),
        Shift.URLAUB to ShiftColor(Color(0xFFC8E6C9), Color(0xFF1B5E20)),
    ),
    weekendTint = Color(0xFFFFF3E3),
    todayTint = Color(0xFFDDE8FF),
)

val DarkShiftColors = ShiftColors(
    colors = mapOf(
        Shift.FRUEH to ShiftColor(Color(0xFF1565C0), Color(0xFFE3F2FD)),
        Shift.SPAET to ShiftColor(Color(0xFFB84A00), Color(0xFFFFF3E0)),
        Shift.NACHT to ShiftColor(Color(0xFF6A1B9A), Color(0xFFF3E5F5)),
        Shift.FREI to ShiftColor(Color(0xFF5A5A5A), Color(0xFFFAFAFA)),
        Shift.URLAUB to ShiftColor(Color(0xFF2E7D32), Color(0xFFE8F5E9)),
    ),
    weekendTint = Color(0xFF2A221C),
    todayTint = Color(0xFF1C2A44),
)

val LocalShiftColors = staticCompositionLocalOf { LightShiftColors }
