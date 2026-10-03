package ch.digitana.dienstplan.screenshots

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.digitana.dienstplan.ui.plan.DENSE_TARGET_TAG
import com.github.takahirom.roborazzi.AccessibilityCheckAfterTestStrategy
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.RoborazziRule
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils.matchesElements
import com.google.android.apps.common.testing.accessibility.framework.matcher.ElementMatchers.withTestTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Barrierefreiheit mit dem Accessibility Test Framework von Google (über Roborazzi): Beschriftung,
 * Kontrast, Grösse der Tippflächen u. a. Fehler lassen den Test scheitern, Warnungen stehen im
 * Log. Ausgenommen sind nur die Felder und Tage im Wochenraster (siehe [DENSE_TARGET_TAG]).
 * Zusätzlich muss jedes antippbare Element einen Text oder eine Beschreibung haben.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = PHONE)
class AccessibilityTest {

    /** Ganz aussen, damit auch die Prüfung nach dem Test (Roborazzi-Regel) abgefangen wird. */
    @Suppress("DEPRECATION")
    @get:Rule(order = -1)
    val thrown: ExpectedException = ExpectedException.none()

    @get:Rule(order = 0)
    val registerActivity = RegisterComponentActivity()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule(order = 2)
    val roborazzi = RoborazziRule(
        composeRule = compose,
        captureRoot = compose.onRoot(),
        options = RoborazziRule.Options(
            roborazziAccessibilityOptions = RoborazziATFAccessibilityCheckOptions(
                checker = RoborazziATFAccessibilityChecker(
                    preset = AccessibilityCheckPreset.LATEST,
                    suppressions = matchesElements(withTestTag(DENSE_TARGET_TAG)),
                ),
                failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Error,
            ),
            accessibilityCheckStrategy = AccessibilityCheckAfterTestStrategy(),
        ),
    )

    /** Gegenprobe: Ohne Beschriftung muss die Prüfung anschlagen – sonst liefe sie gar nicht. */
    @Test
    fun checksDetectMissingLabel() {
        thrown.expectMessage("SpeakableTextPresentCheck")
        compose.setContent {
            Themed {
                Box(Modifier.size(56.dp).background(Color.DarkGray).clickable {})
            }
        }
    }

    @Test
    fun weekLight() = check { SampleWeek() }

    @Test
    fun weekDark() = check(dark = true) { SampleWeek() }

    @Test
    fun weekLargeFont() = check(fontScale = 1.6f) { SampleWeek() }

    @Test
    fun hintsLight() = check { SampleHints() }

    @Test
    fun hintsDark() = check(dark = true) { SampleHints() }

    @Test
    fun settings() = check { SampleSettings() }

    @Test
    fun settingsDark() = check(dark = true) { SampleSettings() }

    private fun check(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent { Themed(dark = dark, fontScale = fontScale, content = content) }
        compose.waitForIdle()
        val clickable = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)
        val unlabeled = compose.onAllNodes(clickable, useUnmergedTree = false).fetchSemanticsNodes().filter { node ->
            val config = node.config
            config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() &&
                config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() &&
                config.getOrNull(SemanticsProperties.EditableText) == null
        }
        assertTrue("Antippbare Elemente ohne Beschriftung: ${unlabeled.map { it.config }}", unlabeled.isEmpty())
    }
}
