package ch.digitana.dienstplan.core.group

import ch.digitana.dienstplan.core.crdt.PlanAccess
import ch.digitana.dienstplan.core.crdt.PlanLock
import ch.digitana.dienstplan.core.crdt.PlanLockCodec
import ch.digitana.dienstplan.core.data.SecureFileStore
import ch.digitana.dienstplan.core.util.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Ein Team (eine MLS-Gruppe) aus Sicht dieses Geräts. */
data class Team(
    val groupId: String,
    val nostrGroupId: String,
    val name: String,
    val epoch: Long,
    /** Öffentliche Schlüssel aller Geräte, sortiert. */
    val members: List<String>,
    /** Öffentliche Schlüssel der Admin-Geräte, sortiert. */
    val admins: List<String>,
    /** Beschreibung der MLS-Gruppe; enthält die Sperre des Plans (nur Admins ändern sie). */
    val description: String = "",
) {
    /** Sperre des Plans; null = offen. */
    val planLock: PlanLock? by lazy(LazyThreadSafetyMode.PUBLICATION) { PlanLockCodec.decode(description) }

    /** Geräte-IDs (wie in den Planeinträgen) der Admins. */
    val adminDevices: Set<String> get() = admins.mapTo(HashSet()) { DeviceKeys.deviceIdOf(it) }

    /** Wer am Plan was ändern darf. */
    val planAccess: PlanAccess get() = PlanAccess(planLock, adminDevices)
}

/** Eine eingegangene, noch nicht beantwortete Einladung. */
data class Invite(
    val id: String,
    val teamName: String,
    /** Gerät, das eingeladen hat (öffentlicher Schlüssel). */
    val inviter: String,
    val memberCount: Int,
    /** Sekunden seit 1970. */
    val createdAt: Long,
)

sealed interface TeamState {
    /** Lokale Daten werden noch geladen. */
    data object Loading : TeamState

    /** Kein Team auf diesem Gerät. */
    data object None : TeamState

    /**
     * Beitritt läuft: Das Gerät zeigt seinen [code], ein Admin fügt es hinzu. [published]:
     * Das KeyPackage liegt auf mindestens einem Relay (erst dann kann der Admin es finden).
     */
    data class Joining(
        val publicKey: String,
        val code: String,
        val published: Boolean,
        val invites: List<Invite>,
    ) : TeamState

    data class Member(val team: Team, val me: String) : TeamState {
        val isAdmin: Boolean get() = me in team.admins
    }

    /**
     * Rechte am Plan in diesem Zustand: Nur Mitglieder kennen eine Sperre. Ein entferntes
     * Gerät schreibt nicht mehr und bekommt nichts mehr, also gibt es dort nichts zu prüfen.
     */
    val planAccess: PlanAccess get() = (this as? Member)?.team?.planAccess ?: PlanAccess.OPEN

    /** Dieses Gerät wurde aus dem Team entfernt; der Plan bleibt lesbar, bis es gelöscht wird. */
    data class Removed(val teamName: String) : TeamState
}

enum class GroupMode { JOINING, MEMBER, REMOVED }

/** Persistierter Zustand der Teamzugehörigkeit (ohne Geheimnisse; die liegen im MLS-Speicher). */
data class GroupRecord(
    val mode: GroupMode,
    val groupId: String? = null,
    val nostrGroupId: String? = null,
    /** Gruppen-Events bis zu diesem Zeitpunkt verarbeitet (Sekunden seit 1970). */
    val cursor: Long = 0,
    /** Ältere Gruppen-Events sind für dieses Gerät nicht lesbar (Gründung bzw. Einladung). */
    val floor: Long = 0,
    /** Letzte eigene Übersicht (ms seit 1970). */
    val lastOverviewAt: Long = 0,
    /** Beginn des Beitritts (Sekunden); ältere Einladungen sind nicht für dieses KeyPackage. */
    val joinSince: Long = 0,
    /** Eigenes KeyPackage-Event (JSON), bis es nach dem Beitritt wieder gelöscht ist. */
    val keyPackage: String? = null,
    /** Mindestens ein Relay hat das KeyPackage bestätigt. */
    val keyPackagePublished: Boolean = false,
    /**
     * Eigener Commit, der veröffentlicht, aber noch nicht übernommen wurde (Event-ID). Nach
     * einem Abbruch entscheidet der nächste Abruf: Liegt er auf einem Relay, wird er übernommen,
     * sonst verworfen.
     */
    val pendingCommit: String? = null,
) {
    init {
        require(groupId == null || isHexId(groupId)) { "Gruppen-ID ungültig" }
        require(nostrGroupId == null || Hex.isLowerHex(nostrGroupId, 64)) { "Nostr-Gruppen-ID ungültig" }
        require(mode == GroupMode.JOINING || (groupId != null && nostrGroupId != null)) { "Gruppe fehlt" }
        require(cursor >= 0 && floor >= 0 && lastOverviewAt >= 0 && joinSince >= 0) { "Zeitangabe ungültig" }
        require(pendingCommit == null || Hex.isLowerHex(pendingCommit, 64)) { "Commit-ID ungültig" }
    }

    private companion object {
        fun isHexId(id: String) = id.length in 2..128 && id.length % 2 == 0 && id.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

interface GroupRecordStore {
    fun load(): GroupRecord?
    fun save(record: GroupRecord)
    fun clear()
}

object GroupRecordCodec {
    private const val VERSION = 1
    private const val MAX_KEY_PACKAGE = 64 * 1024
    private val json = Json { isLenient = false }

    fun encode(record: GroupRecord): ByteArray = buildJsonObject {
        put("v", VERSION)
        put("mode", record.mode.name.lowercase())
        record.groupId?.let { put("group", it) }
        record.nostrGroupId?.let { put("h", it) }
        put("cursor", record.cursor)
        put("floor", record.floor)
        put("overview", record.lastOverviewAt)
        put("joinSince", record.joinSince)
        record.keyPackage?.let { put("kp", it) }
        put("kpPublished", record.keyPackagePublished)
        record.pendingCommit?.let { put("commit", it) }
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): GroupRecord {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("Teamformat ungültig")
        fun text(name: String): String? = (root[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun number(name: String): Long = (root[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: 0L
        require((root["v"] as? JsonPrimitive)?.content == VERSION.toString()) { "Unbekannte Teamversion" }
        val mode = when (text("mode")) {
            "joining" -> GroupMode.JOINING
            "member" -> GroupMode.MEMBER
            "removed" -> GroupMode.REMOVED
            else -> throw IllegalArgumentException("Modus ungültig")
        }
        val keyPackage = text("kp")
        require(keyPackage == null || keyPackage.length <= MAX_KEY_PACKAGE) { "KeyPackage zu gross" }
        return GroupRecord(
            mode = mode,
            groupId = text("group"),
            nostrGroupId = text("h"),
            cursor = number("cursor"),
            floor = number("floor"),
            lastOverviewAt = number("overview"),
            joinSince = number("joinSince"),
            keyPackage = keyPackage,
            keyPackagePublished = (root["kpPublished"] as? JsonPrimitive)?.booleanOrNull ?: false,
            pendingCommit = text("commit"),
        )
    }
}

/** [GroupRecordStore] auf Basis von [SecureFileStore]. */
class EncryptedGroupRecordStore(private val files: SecureFileStore) : GroupRecordStore {
    override fun load(): GroupRecord? = files.read(FILE)?.let { GroupRecordCodec.decode(it) }
    override fun save(record: GroupRecord) = files.write(FILE, GroupRecordCodec.encode(record))
    override fun clear() = files.delete(FILE)

    private companion object {
        const val FILE = "group.bin"
    }
}
