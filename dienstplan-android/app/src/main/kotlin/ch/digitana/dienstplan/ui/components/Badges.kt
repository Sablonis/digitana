package ch.digitana.dienstplan.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.digitana.dienstplan.core.crdt.ShiftType
import ch.digitana.dienstplan.ui.theme.LocalShiftPalette

/** Runder Avatar mit Initialen, Farbe fest pro Person. */
@Composable
fun MemberAvatar(name: String, id: String, modifier: Modifier = Modifier, size: Dp = 32.dp, highlighted: Boolean = false) {
    val color = LocalShiftPalette.current.forId(id)
    val ring = if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier
    Box(
        modifier = modifier
            .size(size)
            .then(ring)
            .clip(CircleShape)
            .background(color.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = Format.initials(name),
            color = color.content,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.38f).sp,
            maxLines = 1,
        )
    }
}

/**
 * Farbiges Kärtchen mit dem Kürzel einer Schicht. [typeId] ohne bekannte [type] zeigt „?“
 * (eingetragen, aber die Schichtart ist noch nicht abgeglichen).
 */
@Composable
fun ShiftBadge(type: ShiftType?, typeId: String?, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    if (typeId == null) return
    val color = LocalShiftPalette.current.of(type)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(color.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = type?.code ?: "?",
            color = color.content,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * if ((type?.code?.length ?: 1) > 2) 0.3f else 0.4f).sp,
            maxLines = 1,
        )
    }
}

/** Kleiner Punkt als Hinweis (Wunsch, Notiz). */
@Composable
fun IndicatorDot(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}
