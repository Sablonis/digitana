package ch.digitana.dienstplan.core.group

import ch.digitana.dienstplan.core.util.Clock
import ch.digitana.dienstplan.core.util.Logger
import ch.digitana.dienstplan.mls.MlsEngine
import ch.digitana.dienstplan.mls.PendingInvite
import ch.digitana.dienstplan.mls.TeamInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Eine Teamaktion ist im aktuellen Zustand nicht möglich. */
class TeamStateException(message: String) : IllegalStateException(message)

/**
 * Teamzugehörigkeit dieses Geräts: Geräteschlüssel, MLS-Zustand (SQLCipher) und der kleine
 * persistierte [GroupRecord]. Alle Aufrufe der MLS-Bibliothek laufen nacheinander auf einem
 * eigenen Thread ([mls]); Zustandswechsel sind zusätzlich mit einer Sperre geschützt.
 *
 * Was Relays braucht (Einladen, Entfernen, Austreten, Schlüssel erneuern), erledigt die
 * [GroupSyncEngine]; hier geschieht nur Lokales.
 */
class TeamRepository(
    private val keyStore: DeviceKeyStore,
    private val recordStore: GroupRecordStore,
    private val databaseDir: File,
    /** Relays, die im Team und in KeyPackages eingetragen werden. */
    private val relays: List<String>,
    private val clock: Clock = Clock.System,
    private val logger: Logger = Logger.None,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

    private val mlsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mls").apply { isDaemon = true }
    }
    private val mlsDispatcher = mlsExecutor.asCoroutineDispatcher()
    private val mutex = Mutex()

    @Volatile private var keys: DeviceKeys? = null
    @Volatile private var engine: MlsEngine? = null

    private val _record = MutableStateFlow<GroupRecord?>(null)
    val record: StateFlow<GroupRecord?> = _record.asStateFlow()

    private val _state = MutableStateFlow<TeamState>(TeamState.Loading)
    val state: StateFlow<TeamState> = _state.asStateFlow()

    /** Öffentlicher Schlüssel dieses Geräts, sobald es einen hat. */
    val publicKey: String? get() = keys?.publicKey

    /** Geräte-ID für Planeinträge, sobald das Gerät Schlüssel hat. */
    val deviceId: String? get() = keys?.deviceId

    /**
     * Lädt Schlüssel und Zustand und öffnet die MLS-Datenbank.
     * @throws Exception wenn die Daten nicht lesbar sind (die App setzt dann zurück).
     */
    suspend fun load() = mutex.withLock {
        val loadedKeys = withContext(ioDispatcher) { keyStore.load() }
        val loadedRecord = withContext(ioDispatcher) { recordStore.load() }
        keys = loadedKeys
        if (loadedKeys != null) openEngine(loadedKeys)
        // Ohne Schlüssel ist ein gespeicherter Zustand wertlos.
        _record.value = if (loadedKeys != null) loadedRecord else null
        publishState()
    }

    /** Führt [block] auf dem MLS-Thread aus. */
    suspend fun <T> mls(block: (MlsEngine) -> T): T = withContext(mlsDispatcher) {
        block(engine ?: throw TeamStateException("Kein MLS-Zustand geöffnet"))
    }

    /** „Neues Team“: Dieses Gerät ist einziges Mitglied und Admin. */
    suspend fun createTeam(name: String): Team = mutex.withLock {
        if (_record.value != null) throw TeamStateException("Gerät ist bereits in einem Team")
        ensureKeys()
        val info = mls { it.createTeam(name, relays) }
        val start = nowSeconds() - CURSOR_START_MARGIN_SECS
        saveRecord(GroupRecord(GroupMode.MEMBER, info.groupId, info.nostrGroupId, cursor = start, floor = start))
        publishState()
        info.toTeam()
    }

    /** Beitritt beginnen: Schlüssel und KeyPackage erzeugen; die Engine veröffentlicht es. */
    suspend fun startJoining(): TeamState.Joining = mutex.withLock {
        val current = _record.value
        if (current != null && current.mode != GroupMode.JOINING) throw TeamStateException("Gerät ist bereits in einem Team")
        if (current == null) {
            ensureKeys()
            val keyPackage = mls { it.keyPackageEvent(relays) }
            saveRecord(GroupRecord(GroupMode.JOINING, joinSince = nowSeconds(), keyPackage = keyPackage))
        }
        publishState()
        _state.value as TeamState.Joining
    }

    /** Prüft einen Gift Wrap; ist es eine Einladung, erscheint sie im Zustand. */
    suspend fun receiveInvite(giftWrap: String): Invite? = mutex.withLock {
        if (_record.value?.mode != GroupMode.JOINING) return@withLock null
        val pending = mls { it.receiveInvite(giftWrap) } ?: return@withLock null
        publishState()
        pending.toInvite()
    }

    /** Einladung annehmen. Danach holt die Engine den Plan und erneuert den eigenen Schlüssel. */
    suspend fun acceptInvite(inviteId: String): Team = mutex.withLock {
        val current = _record.value?.takeIf { it.mode == GroupMode.JOINING }
            ?: throw TeamStateException("Kein Beitritt aktiv")
        val pending = mls { it.pendingInvites() }
        val invite = pending.firstOrNull { it.inviteId == inviteId } ?: throw TeamStateException("Einladung nicht gefunden")
        val info = mls { engine ->
            val accepted = engine.acceptInvite(inviteId)
            for (other in pending) if (other.inviteId != inviteId) runCatching { engine.declineInvite(other.inviteId) }
            accepted
        }
        // Gruppen-Events vor der Einladung sind für dieses Gerät nicht lesbar.
        val cursor = (invite.createdAt.toLong() - CURSOR_START_MARGIN_SECS).coerceAtLeast(0)
        saveRecord(
            GroupRecord(
                GroupMode.MEMBER,
                info.groupId,
                info.nostrGroupId,
                cursor = cursor,
                floor = cursor,
                keyPackage = current.keyPackage,
                keyPackagePublished = current.keyPackagePublished,
            ),
        )
        publishState()
        info.toTeam()
    }

    suspend fun declineInvite(inviteId: String) = mutex.withLock {
        mls { it.declineInvite(inviteId) }
        publishState()
    }

    /** Liest den Teamzustand neu (nach einem Commit) und erkennt, ob dieses Gerät entfernt wurde. */
    suspend fun refresh() = mutex.withLock {
        val current = _record.value
        if (current?.mode == GroupMode.MEMBER) {
            val info = mls { it.team(current.groupId!!) }
            if (info == null || !info.active) {
                logger.debug(TAG, "Gerät ist nicht mehr Mitglied des Teams")
                saveRecord(current.copy(mode = GroupMode.REMOVED))
            }
        }
        publishState()
    }

    /** Ändert den gespeicherten Zustand (Cursor, Zeitpunkte, KeyPackage). */
    suspend fun updateRecord(transform: (GroupRecord) -> GroupRecord) = mutex.withLock {
        val current = _record.value ?: return@withLock
        val updated = transform(current)
        if (updated == current) return@withLock
        require(updated.mode == current.mode && updated.groupId == current.groupId) { "Modus nur über Teamaktionen ändern" }
        saveRecord(updated)
        if (updated.keyPackagePublished != current.keyPackagePublished) publishState()
    }

    /** Löscht Schlüssel, MLS-Zustand und Teamzugehörigkeit von diesem Gerät. */
    suspend fun reset() = mutex.withLock {
        withContext(mlsDispatcher) {
            engine?.close()
            engine = null
        }
        withContext(ioDispatcher) {
            databaseDir.deleteRecursively()
            recordStore.clear()
            keyStore.clear()
        }
        keys = null
        _record.value = null
        _state.value = TeamState.None
    }

    override fun close() {
        mlsExecutor.submit { engine?.close() }
        mlsExecutor.shutdown()
    }

    private suspend fun ensureKeys(): DeviceKeys {
        keys?.let { return it }
        val fresh = DeviceKeys.generate()
        withContext(ioDispatcher) {
            // Reste eines früheren Teams (z. B. nach einem Absturz beim Zurücksetzen) entfernen.
            databaseDir.deleteRecursively()
            keyStore.save(fresh)
        }
        keys = fresh
        openEngine(fresh)
        return fresh
    }

    private suspend fun openEngine(deviceKeys: DeviceKeys) {
        withContext(mlsDispatcher) {
            engine?.close()
            databaseDir.mkdirs()
            val databaseKey = deviceKeys.databaseKey()
            val identity = deviceKeys.identity()
            try {
                engine = MlsEngine.open(File(databaseDir, DATABASE_FILE).path, databaseKey, identity)
            } finally {
                databaseKey.fill(0)
                identity.fill(0)
            }
        }
    }

    private suspend fun saveRecord(record: GroupRecord) {
        withContext(ioDispatcher) { recordStore.save(record) }
        _record.value = record
    }

    private suspend fun publishState() {
        val record = _record.value
        val deviceKeys = keys
        _state.value = when {
            record == null || deviceKeys == null -> TeamState.None
            record.mode == GroupMode.JOINING -> TeamState.Joining(
                publicKey = deviceKeys.publicKey,
                code = JoinCode.encode(deviceKeys.publicKey),
                published = record.keyPackagePublished,
                invites = mls { it.pendingInvites() }.map { it.toInvite() }.sortedByDescending { it.createdAt },
            )
            record.mode == GroupMode.MEMBER -> {
                val info = mls { it.team(record.groupId!!) }
                if (info != null && info.active) TeamState.Member(info.toTeam(), deviceKeys.publicKey) else TeamState.Removed(info?.name ?: "")
            }
            else -> TeamState.Removed(mls { it.team(record.groupId!!) }?.name ?: "")
        }
    }

    private fun nowSeconds(): Long = clock.nowMillis() / 1000

    companion object {
        private const val TAG = "TeamRepository"
        private const val DATABASE_FILE = "mls.db"
        /** Spielraum für Uhrabweichungen beim ersten Abruf der Gruppen-Events. */
        private const val CURSOR_START_MARGIN_SECS = 120L

        internal fun TeamInfo.toTeam() = Team(
            groupId = groupId,
            nostrGroupId = nostrGroupId,
            name = name,
            epoch = epoch.toLong(),
            members = members.sorted(),
            admins = admins.sorted(),
            description = description,
        )

        internal fun PendingInvite.toInvite() = Invite(
            id = inviteId,
            teamName = groupName,
            inviter = inviter,
            memberCount = memberCount.toInt(),
            createdAt = createdAt.toLong(),
        )
    }
}
