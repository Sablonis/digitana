package ch.digitana.dienstplan.core.sync

enum class RelayConnectionState {
    /** Verbindungsaufbau läuft. */
    CONNECTING,
    /** Verbunden, gespeicherte Events werden geladen. */
    SYNCING,
    /** Verbunden, abgeglichen, Live-Abo aktiv. */
    LIVE,
    /** Getrennt, nächster Versuch nach Backoff. */
    DISCONNECTED,
}

data class RelayDiagnostics(
    val url: String,
    val state: RelayConnectionState,
    /** Letzter Fehler (eigener Text oder bereinigte Relay-Meldung). */
    val lastError: String?,
    /** Letzter erfolgreicher Abgleich (EOSE oder angenommenes Event), ms seit 1970. */
    val lastSyncAt: Long?,
    val lastNotice: String?,
    val eventsReceived: Int,
    val eventsAccepted: Int,
    val eventsRejected: Int,
    /** Events mit gültiger Signatur, die sich nicht entschlüsseln liessen (still verworfen). */
    val undecryptable: Int,
    /** Formal ungültige Nachrichten oder Events. */
    val invalid: Int,
    /** Einzelne verworfene Einträge (ungültig laut Eingabeprüfung). */
    val droppedEntries: Int,
)

data class SyncStatus(
    val liveRelays: Int,
    val totalRelays: Int,
    val connectingRelays: Int,
    /** Buckets, die mindestens einem abgeglichenen Relay noch fehlen. */
    val pendingBuckets: Int,
    val running: Boolean,
) {
    val isLive: Boolean get() = liveRelays > 0

    companion object {
        fun stopped(totalRelays: Int) = SyncStatus(0, totalRelays, 0, 0, running = false)
    }
}
