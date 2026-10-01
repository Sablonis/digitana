package ch.digitana.dienstplan.core.group

import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.PlanState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Sicht der [GroupSyncEngine] auf den lokalen Plan. */
interface PlanSync {
    val state: StateFlow<PlanState>

    /** Meldet lokale Änderungen (Eingaben der Nutzerin oder des Nutzers). */
    val localChanges: Flow<Any>

    /** Führt geprüfte Einträge eines anderen Geräts ein. */
    suspend fun mergeRemote(bucket: String, entries: Map<String, Entry>): Boolean

    /** Eigene Änderungen, die noch kein Relay bestätigt hat. */
    suspend fun pendingEntries(): Map<String, Entry>

    /** Ein Relay hat diese Einträge bestätigt (Schlüssel → Zeitstempel). */
    suspend fun markSent(sent: Map<String, Long>)
}
