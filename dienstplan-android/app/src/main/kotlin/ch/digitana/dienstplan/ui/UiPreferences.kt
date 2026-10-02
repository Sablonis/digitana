package ch.digitana.dienstplan.ui

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import ch.digitana.dienstplan.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Wie dicht das Wochenraster ist. */
enum class GridDensity {
    /** Kürzel und Zeiten (Standard). */
    COMFORTABLE,

    /** Nur Kürzel, mehr Luft. */
    COMPACT,
}

/** Darstellung auf diesem Gerät. */
data class UiPrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val density: GridDensity = GridDensity.COMFORTABLE,
)

/**
 * Darstellung (Thema, Systemfarben, Rasterdichte) in den SharedPreferences: nichts davon ist
 * vertraulich, und so steht sie schon beim ersten Bild bereit (der verschlüsselte Speicher
 * wird erst im Hintergrund geladen). Wie alle App-Daten ist die Datei vom Backup ausgenommen.
 */
class UiPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<UiPrefs> = _state.asStateFlow()

    init {
        applyNightMode(_state.value.themeMode)
    }

    fun setThemeMode(mode: ThemeMode) = update(_state.value.copy(themeMode = mode))

    fun setDynamicColor(enabled: Boolean) = update(_state.value.copy(dynamicColor = enabled))

    fun setDensity(density: GridDensity) = update(_state.value.copy(density = density))

    private fun update(prefs: UiPrefs) {
        if (prefs == _state.value) return
        this.prefs.edit()
            .putString(KEY_THEME, prefs.themeMode.name)
            .putBoolean(KEY_DYNAMIC, prefs.dynamicColor)
            .putString(KEY_DENSITY, prefs.density.name)
            .apply()
        val modeChanged = prefs.themeMode != _state.value.themeMode
        _state.value = prefs
        if (modeChanged) applyNightMode(prefs.themeMode)
    }

    private fun read(): UiPrefs = UiPrefs(
        themeMode = ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM,
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, false),
        density = GridDensity.entries.firstOrNull { it.name == prefs.getString(KEY_DENSITY, null) } ?: GridDensity.COMFORTABLE,
    )

    /**
     * Ab Android 12 kennt auch das System die Wahl: Startbildschirm und Fensterhintergrund
     * erscheinen dann gleich in der richtigen Helligkeit.
     */
    private fun applyNightMode(mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = appContext.getSystemService(UiModeManager::class.java) ?: return
        val night = when (mode) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        runCatching { manager.setApplicationNightMode(night) }
    }

    private companion object {
        const val FILE = "darstellung"
        const val KEY_THEME = "thema"
        const val KEY_DYNAMIC = "systemfarben"
        const val KEY_DENSITY = "dichte"
    }
}
