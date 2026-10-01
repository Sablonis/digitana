package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.Shift
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Einstellungen dieses Geräts. Sie werden nicht synchronisiert: Jedes Gerät wählt selbst,
 * welche Person im Plan es benutzt und ob es bei Änderungen benachrichtigt.
 */
data class DeviceSettings(
    /** Mitglied, das dieses Gerät benutzt („Das bin ich“), oder null. */
    val myMemberId: String? = null,
    /** Benachrichtigen, wenn sich die eigenen künftigen Dienste ändern. */
    val notifyOnChanges: Boolean = false,
    /** Eigene Dienste, wie sie zuletzt in der App zu sehen waren; null = noch keine Grundlage. */
    val seenShifts: Map<LocalDate, Shift>? = null,
    /** Stand, über den zuletzt benachrichtigt wurde; null = keine offene Benachrichtigung. */
    val notifiedShifts: Map<LocalDate, Shift>? = null,
) {
    init {
        require(myMemberId == null || PlanKeys.isValidId(myMemberId)) { "Ungültige Mitglieds-ID" }
    }
}

object DeviceSettingsCodec {
    private const val VERSION = 1
    private const val MAX_SHIFTS = 5000
    private val json = Json { isLenient = false }

    fun encode(settings: DeviceSettings): ByteArray = buildJsonObject {
        put("v", VERSION)
        settings.myMemberId?.let { put("me", it) }
        put("notify", settings.notifyOnChanges)
        settings.seenShifts?.let { putShifts("seen", it) }
        settings.notifiedShifts?.let { putShifts("notified", it) }
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): DeviceSettings {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("Einstellungen ungültig")
        require((root["v"] as? JsonPrimitive)?.content == VERSION.toString()) { "Unbekannte Einstellungsversion" }
        val me = root["me"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("me ungültig") }
        val notify = root["notify"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: throw IllegalArgumentException("notify ungültig") } ?: false
        return DeviceSettings(me, notify, shifts(root["seen"]), shifts(root["notified"]))
    }

    private fun JsonObjectBuilder.putShifts(name: String, shifts: Map<LocalDate, Shift>) {
        putJsonObject(name) {
            for ((date, shift) in shifts.toSortedMap()) put(date.toString(), shift.code)
        }
    }

    private fun shifts(element: JsonElement?): Map<LocalDate, Shift>? {
        if (element == null) return null
        val obj = element as? JsonObject ?: throw IllegalArgumentException("Dienste ungültig")
        require(obj.size <= MAX_SHIFTS) { "Zu viele Dienste" }
        return obj.entries.associate { (key, value) ->
            val date = try {
                LocalDate.parse(key)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("Datum ungültig", e)
            }
            require(PlanKeys.isValidDate(date)) { "Datum ausserhalb 2000–2100" }
            val code = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            val shift = code?.let { Shift.fromCode(it) } ?: throw IllegalArgumentException("Kürzel ungültig")
            date to shift
        }
    }
}

interface SettingsStore {
    fun load(): DeviceSettings?
    fun save(settings: DeviceSettings)
    fun clear()
}

class EncryptedSettingsStore(private val files: SecureFileStore) : SettingsStore {
    /** Unlesbare Einstellungen sind kein Grund, Team und Plan zu verwerfen: dann Standardwerte. */
    override fun load(): DeviceSettings? {
        val bytes = files.read(FILE) ?: return null
        return try {
            DeviceSettingsCodec.decode(bytes)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun save(settings: DeviceSettings) = files.write(FILE, DeviceSettingsCodec.encode(settings))

    override fun clear() = files.delete(FILE)

    private companion object {
        const val FILE = "settings.bin"
    }
}

class SettingsRepository(
    private val store: SettingsStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val _settings = MutableStateFlow(DeviceSettings())
    val settings: StateFlow<DeviceSettings> = _settings.asStateFlow()

    suspend fun load() {
        _settings.value = withContext(ioDispatcher) { store.load() } ?: DeviceSettings()
    }

    /** Ändert die Einstellungen atomar und speichert sie nur, wenn sich etwas geändert hat. */
    suspend fun update(transform: (DeviceSettings) -> DeviceSettings): DeviceSettings = mutex.withLock {
        val before = _settings.value
        val after = transform(before)
        if (after != before) {
            withContext(ioDispatcher) { store.save(after) }
            _settings.value = after
        }
        after
    }

    suspend fun clear() = mutex.withLock {
        withContext(ioDispatcher) { store.clear() }
        _settings.value = DeviceSettings()
    }
}
