package ch.digitana.dienstplan.screenshots

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import ch.digitana.dienstplan.ui.theme.DienstplanTheme
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf

/** Bildschirmgrössen für Robolectric (Breite, Höhe, Dichte). */
internal const val PHONE = "w393dp-h851dp-xxhdpi"
internal const val SMALL_PHONE = "w360dp-h740dp-xhdpi"
internal const val TABLET = "w1280dp-h800dp-land-xhdpi"

/**
 * Meldet ComponentActivity bei Robolectric an. So braucht es kein ui-test-manifest im
 * Debug-Build (das würde eine exportierte Activity in die verteilte Debug-APK bringen).
 */
class RegisterComponentActivity : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            base.evaluate()
        }
    }
}

/** Inhalt im Thema der App, wahlweise dunkel und mit grösserer Schrift. */
@Composable
internal fun Themed(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        DienstplanTheme(darkTheme = dark, dynamicColor = false) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}
