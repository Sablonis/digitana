package ch.digitana.dienstplan.core.util

/**
 * Minimale Log-Schnittstelle. Der Kern protokolliert nur Betriebsdaten
 * (Relay-URL, Zustände, Zähler) – niemals Geheimnisse, Klartext oder Namen.
 * Die App verdrahtet Logcat nur in Debug-Builds; im Release ist alles still.
 */
interface Logger {
    fun debug(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)

    object None : Logger {
        override fun debug(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, error: Throwable?) = Unit
    }
}
