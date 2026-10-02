package ch.digitana.dienstplan.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Leere Ansicht mit kleiner Illustration, kurzer Erklärung und dem nächsten sinnvollen Schritt
 * (z. B. „Noch keine Dienste – Rhythmus anwenden?“).
 */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Illustration(icon)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = action.second) { Text(action.first) }
        }
        if (secondary != null) {
            TextButton(onClick = secondary.second) { Text(secondary.first) }
        }
    }
}

/** Symbol in einem farbigen Kreis mit zwei kleinen Begleitkreisen. */
@Composable
private fun Illustration(@DrawableRes icon: Int) {
    val primary = MaterialTheme.colorScheme.primaryContainer
    val accent = MaterialTheme.colorScheme.tertiaryContainer
    val dot = MaterialTheme.colorScheme.secondaryContainer
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(primary, radius = r * 0.78f, center = center)
            drawCircle(accent, radius = r * 0.2f, center = Offset(center.x + r * 0.78f, center.y - r * 0.62f))
            drawCircle(dot, radius = r * 0.12f, center = Offset(center.x - r * 0.86f, center.y + r * 0.55f))
        }
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(44.dp),
        )
    }
}
