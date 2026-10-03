package ch.digitana.dienstplan.ui.components

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import ch.digitana.dienstplan.util.findActivity

/**
 * Setzt FLAG_SECURE, solange der Aufrufer sichtbar ist: keine Screenshots, keine
 * Bildschirmaufnahme und keine Vorschau in der App-Übersicht (Einladungscode-Bildschirme).
 */
@Composable
fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
