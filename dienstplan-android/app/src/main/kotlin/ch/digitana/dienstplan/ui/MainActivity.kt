package ch.digitana.dienstplan.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.digitana.dienstplan.DienstplanApp
import ch.digitana.dienstplan.ui.theme.DienstplanTheme
import ch.digitana.dienstplan.ui.theme.dynamicColorAvailable
import ch.digitana.dienstplan.ui.theme.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-Edge: Inhalte liegen hinter Status- und Navigationsleiste; die Abstände
        // setzen Scaffold, TopAppBar und die WindowInsets-Modifier.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as DienstplanApp).container
        setContent {
            val ui by container.uiPreferences.state.collectAsStateWithLifecycle()
            val dark = ui.themeMode.isDark()
            // Symbole der Systemleisten passend zum gewählten Thema, nicht nur zum System.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(DARK_SCRIM) else SystemBarStyle.light(LIGHT_SCRIM, DARK_SCRIM),
                )
            }
            DienstplanTheme(darkTheme = dark, dynamicColor = ui.dynamicColor && dynamicColorAvailable) {
                DienstplanRoot(container)
            }
        }
    }

    private companion object {
        // Wie die Standardwerte von enableEdgeToEdge für die Navigationsleiste mit drei Tasten.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
