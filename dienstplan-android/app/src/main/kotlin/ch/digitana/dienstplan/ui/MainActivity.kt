package ch.digitana.dienstplan.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ch.digitana.dienstplan.DienstplanApp
import ch.digitana.dienstplan.ui.theme.DienstplanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-Edge: Inhalte liegen hinter Status- und Navigationsleiste; die Abstände
        // setzen Scaffold, TopAppBar und die WindowInsets-Modifier.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as DienstplanApp).container
        setContent {
            DienstplanTheme {
                DienstplanRoot(container)
            }
        }
    }
}
