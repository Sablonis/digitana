package ch.digitana.dienstplan.screenshots

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots der wichtigsten Ansichten (hell, dunkel, grosse Schrift, Tablet). Die Bilder landen
 * in app/build/outputs/roborazzi und werden von der CI als Artefakt hochgeladen; sie dienen dem
 * Durchsehen, nicht dem pixelgenauen Vergleich.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = PHONE)
class ScreenshotTest {

    @get:Rule(order = 0)
    val registerActivity = RegisterComponentActivity()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun weekLight() = capture("woche_hell") { SampleWeek() }

    @Test
    fun weekDark() = capture("woche_dunkel", dark = true) { SampleWeek() }

    @Test
    fun weekLargeFont() = capture("woche_grosse_schrift", fontScale = 1.6f) { SampleWeek() }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun weekSmallPhone() = capture("woche_kleines_handy") { SampleWeek() }

    @Test
    @Config(qualifiers = TABLET)
    fun weekTablet() = capture("woche_tablet") { SampleWeek() }

    @Test
    fun hintsLight() = capture("hinweise_hell") { SampleHints() }

    @Test
    fun hintsDark() = capture("hinweise_dunkel", dark = true) { SampleHints() }

    @Test
    fun settingsLargeFont() = capture("einstellungen_grosse_schrift", fontScale = 1.6f) { SampleSettings() }

    private fun capture(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent { Themed(dark = dark, fontScale = fontScale, content = content) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
