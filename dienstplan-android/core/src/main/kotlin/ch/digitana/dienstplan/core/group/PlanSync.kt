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

    /**
     * Buckets, aus denen eine neue Sperre Einträge entfernt hat. Der vorherige Wert eines
     * Feldes liegt noch bei anderen Geräten; ein Abgleich holt ihn zurück.
     */
    val purged: Flow<Set<String>>

    /**
     * Führt geprüfte Einträge eines anderen Geräts ein. Einträge, die unter der aktuellen
     * Sperre nicht gelten, werden verworfen und gezählt.
     */
    suspend fun mergeRemote(bucket: String, entries: Map<String, Entry>): RemoteMerge

    /** Eigene Änderungen, die noch kein Relay bestätigt hat. */
    suspend fun pendingEntries(): Map<String, Entry>

    /** Ein Relay hat diese Einträge bestätigt (Schlüssel → Zeitstempel). */
    suspend fun markSent(sent: Map<String, Long>)

    /** Zeitstempel für eine neue Sperre: jünger als alles, was dieses Gerät kennt. */
    suspend fun lockTimestamp(): Long
}

/** Ergebnis von [PlanSync.mergeRemote]: [changed] = Stand geändert, [rejected] = wegen der Sperre verworfen. */
data class RemoteMerge(val changed: Boolean, val rejected: Int = 0)
