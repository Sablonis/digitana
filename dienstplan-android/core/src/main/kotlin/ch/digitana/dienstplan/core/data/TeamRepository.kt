package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crdt.EntryValidator
import ch.digitana.dienstplan.core.crypto.InviteCode
import ch.digitana.dienstplan.core.crypto.SecureRandomBytes
import ch.digitana.dienstplan.core.crypto.TeamKeys
import ch.digitana.dienstplan.core.crypto.TeamSecret
import ch.digitana.dienstplan.core.util.Clock
import ch.digitana.dienstplan.core.util.Hex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Mitgliedschaft dieses Geräts in einem Team. */
class Team(
    val secret: TeamSecret,
    /** Zufällige Geräte-ID (16 Hex-Zeichen), Teil jedes Eintrags. */
    val deviceId: String,
    val joinedAtMillis: Long,
) {
    val inviteCode: String get() = InviteCode.encode(secret)

    /** Abgeleitete Schlüssel – teuer genug, um sie einmal pro Team zu berechnen. */
    val keys: TeamKeys by lazy { TeamKeys.derive(secret) }

    override fun equals(other: Any?): Boolean =
        other is Team && secret == other.secret && deviceId == other.deviceId

    override fun hashCode(): Int = 31 * secret.hashCode() + deviceId.hashCode()

    override fun toString(): String = "Team(device=$deviceId)"
}

interface TeamStore {
    fun load(): Team?
    fun save(team: Team)
    fun clear()
}

object TeamCodec {
    private const val VERSION = 1
    private val json = Json { isLenient = false }

    fun encode(team: Team): ByteArray {
        val secret = team.secret.bytes()
        try {
            return buildJsonObject {
                put("v", VERSION)
                put("secret", Hex.encode(secret))
                put("device", team.deviceId)
                put("joined", team.joinedAtMillis)
            }.toString().toByteArray(Charsets.UTF_8)
        } finally {
            secret.fill(0)
        }
    }

    fun decode(bytes: ByteArray): Team {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("Teamformat ungültig")
        fun text(name: String) = (root[name] as? JsonPrimitive)?.content ?: throw IllegalArgumentException("$name fehlt")
        require(text("v") == VERSION.toString()) { "Unbekannte Teamversion" }
        val secretHex = text("secret")
        require(Hex.isLowerHex(secretHex, TeamSecret.SIZE * 2)) { "Geheimnis ungültig" }
        val device = text("device")
        require(EntryValidator.isValidDeviceId(device)) { "Geräte-ID ungültig" }
        val joined = text("joined").toLongOrNull() ?: 0L
        val secretBytes = Hex.decode(secretHex)
        try {
            return Team(TeamSecret(secretBytes), device, joined)
        } finally {
            secretBytes.fill(0)
        }
    }
}

/** Verwaltet das aktuelle Team: neu anlegen, beitreten, Schlüssel wechseln, verlassen. */
class TeamRepository(
    private val store: TeamStore,
    private val clock: Clock = Clock.System,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val _team = MutableStateFlow<Team?>(null)
    val team: StateFlow<Team?> = _team.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun load() {
        val team = withContext(ioDispatcher) { store.load() }
        _team.value = team
        _loaded.value = true
    }

    /** „Neues Team“: zufälliges 32-Byte-Geheimnis. */
    suspend fun create(): Team = replace(Team(TeamSecret.generate(), newDeviceId(), clock.nowMillis()))

    /** „Beitreten“ mit dem Geheimnis aus einem Einladungscode. */
    suspend fun join(secret: TeamSecret): Team = replace(Team(secret, newDeviceId(), clock.nowMillis()))

    /**
     * „Neuen Code erstellen“: neues Geheimnis, gleiche Geräte-ID. Wer den alten Code hat,
     * sieht ab jetzt keine Änderungen mehr – der neue Code muss an alle verbleibenden
     * Mitglieder gehen.
     */
    suspend fun rotate(): Team {
        val current = _team.value ?: throw IllegalStateException("Kein Team aktiv")
        return replace(Team(TeamSecret.generate(), current.deviceId, clock.nowMillis()))
    }

    suspend fun leave() {
        mutex.withLock {
            withContext(ioDispatcher) { store.clear() }
            _team.value = null
        }
    }

    private suspend fun replace(team: Team): Team = mutex.withLock {
        withContext(ioDispatcher) { store.save(team) }
        _team.value = team
        team
    }

    private fun newDeviceId(): String = Hex.encode(SecureRandomBytes.next(8))
}
