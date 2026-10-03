package ch.digitana.dienstplan.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.core.crdt.ShiftTypes

/**
 * Farbe einer Schichtart: Hintergrund, Text darauf (≥ 7:1) und eine kräftige Variante für
 * Punkte, Zahlen und Rahmen auf der normalen Oberfläche (≥ 6:1).
 */
@Immutable
data class ShiftColor(val container: Color, val content: Color, val strong: Color)

/** Die zwölf Farben, aus denen Schichtarten wählen (Index = [ShiftType.color]). */
@Immutable
class ShiftPalette(val colors: List<ShiftColor>, val unknown: ShiftColor) {
    init {
        require(colors.size == ShiftTypes.COLOR_COUNT)
    }

    operator fun get(index: Int): ShiftColor = colors.getOrElse(index) { unknown }

    /** Farbe einer Schichtart; unbekannte Arten (noch nicht abgeglichen) sind grau. */
    fun of(type: ShiftType?): ShiftColor = type?.let { get(it.color) } ?: unknown

    /** Feste Farbe pro Person für Avatare, aus der ID abgeleitet. */
    fun forId(id: String): ShiftColor = colors[Math.floorMod(id.hashCode(), colors.size)]
}

/** Namen der Farben für die Auswahl und für Screenreader. */
val ShiftColorNames = listOf(
    "Blau", "Orange", "Violett", "Grau", "Grün", "Rot", "Petrol", "Pink", "Gelb", "Indigo", "Braun", "Türkis",
)

val LightShiftPalette = ShiftPalette(
    colors = listOf(
        ShiftColor(Color(0xFFD3E4FF), Color(0xFF001C38), Color(0xFF0060A8)), // Blau
        ShiftColor(Color(0xFFFFDCC2), Color(0xFF2E1500), Color(0xFF8F4E00)), // Orange
        ShiftColor(Color(0xFFFDD6FF), Color(0xFF340042), Color(0xFF942CB0)), // Violett
        ShiftColor(Color(0xFFDEE3E5), Color(0xFF171D1E), Color(0xFF595F61)), // Grau
        ShiftColor(Color(0xFF98F994), Color(0xFF002204), Color(0xFF006E1C)), // Grün
        ShiftColor(Color(0xFFFFDAD6), Color(0xFF410002), Color(0xFFBB171C)), // Rot
        ShiftColor(Color(0xFF8DF5E4), Color(0xFF00201C), Color(0xFF006B5F)), // Petrol
        ShiftColor(Color(0xFFFFD9DE), Color(0xFF3F0016), Color(0xFFBC004F)), // Pink
        ShiftColor(Color(0xFFFFDEAC), Color(0xFF281900), Color(0xFF7E5700)), // Gelb
        ShiftColor(Color(0xFFDEE0FF), Color(0xFF000E5E), Color(0xFF4555B7)), // Indigo
        ShiftColor(Color(0xFFFFDBCE), Color(0xFF2E150B), Color(0xFF7A5649)), // Braun
        ShiftColor(Color(0xFF9EEFFF), Color(0xFF001F24), Color(0xFF006876)), // Türkis
    ),
    unknown = ShiftColor(Color(0xFFE4E1E7), Color(0xFF454651), Color(0xFF767682)),
)

val DarkShiftPalette = ShiftPalette(
    colors = listOf(
        ShiftColor(Color(0xFF004881), Color(0xFFD3E4FF), Color(0xFFA2C9FF)), // Blau
        ShiftColor(Color(0xFF6D3A00), Color(0xFFFFDCC2), Color(0xFFFFB77B)), // Orange
        ShiftColor(Color(0xFF790096), Color(0xFFFDD6FF), Color(0xFFF3AEFF)), // Violett
        ShiftColor(Color(0xFF424849), Color(0xFFDEE3E5), Color(0xFFC2C7C9)), // Grau
        ShiftColor(Color(0xFF005313), Color(0xFF98F994), Color(0xFF7DDC7A)), // Grün
        ShiftColor(Color(0xFF93000D), Color(0xFFFFDAD6), Color(0xFFFFB4AC)), // Rot
        ShiftColor(Color(0xFF005048), Color(0xFF8DF5E4), Color(0xFF70D8C8)), // Petrol
        ShiftColor(Color(0xFF90003B), Color(0xFFFFD9DE), Color(0xFFFFB2BF)), // Pink
        ShiftColor(Color(0xFF604100), Color(0xFFFFDEAC), Color(0xFFFFBA38)), // Gelb
        ShiftColor(Color(0xFF2C3C9E), Color(0xFFDEE0FF), Color(0xFFBBC3FF)), // Indigo
        ShiftColor(Color(0xFF5F3F33), Color(0xFFFFDBCE), Color(0xFFEABCAC)), // Braun
        ShiftColor(Color(0xFF004E59), Color(0xFF9EEFFF), Color(0xFF55D7ED)), // Türkis
    ),
    unknown = ShiftColor(Color(0xFF353439), Color(0xFFC6C5D3), Color(0xFF908F9C)),
)

val LocalShiftPalette = staticCompositionLocalOf { LightShiftPalette }
