package ch.digitana.dienstplan.core.data

import ch.digitana.dienstplan.core.crdt.PlanKeys
import ch.digitana.dienstplan.core.crdt.ShiftTypes
import ch.digitana.dienstplan.core.plan.ReminderMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
    /** Eigene Dienste (IDs der Schichtarten), wie sie zuletzt in der App zu sehen waren; null = noch keine Grundlage. */
    val seenShifts: Map<LocalDate, String>? = null,
    /** Stand, über den zuletzt benachrichtigt wurde; null = keine offene Benachrichtigung. */
    val notifiedShifts: Map<LocalDate, String>? = null,
    /** Erinnerung an eigene Dienste. */
    val reminderMode: ReminderMode = ReminderMode.OFF,
    /** Zwei Tage vor einer Wunschfrist erinnern, falls noch keine Wünsche eingetragen sind. */
    val deadlineReminders: Boolean = true,
    /** Benachrichtigen bei Tauschvorschlägen, Antworten und abgegebenen Diensten. */
    val tradeAlerts: Boolean = true,
    /** Benachrichtigen, wenn ein Admin den Plan sperrt (veröffentlicht). */
    val publishAlerts: Boolean = true,
    /** Tausch-Ereignisse, über die schon benachrichtigt wurde (siehe `TradeEvent.id`). */
    val notifiedTrades: Set<String> = emptySet(),
    /** Zuletzt bekannte Sperre (Format der Teambeschreibung), um neue Veröffentlichungen zu erkennen; null = noch keine Grundlage. */
    val knownLock: String? = null,
    /** Eigene Dienste in einen lokalen Kalender des Geräts schreiben. */
    val calendarSync: Boolean = false,
    /** Gesehene Tipps (Bits, siehe App). */
    val tipsSeen: Int = 0,
    /** „Wer bist du?“ wurde schon gefragt (auch wenn übersprungen). */
    val askedWho: Boolean = false,
) {
    init {
        require(myMemberId == null || PlanKeys.isValidId(myMemberId)) { "Ungültige Mitglieds-ID" }
        require(notifiedTrades.size <= MAX_NOTIFIED_TRADES && notifiedTrades.all { it.length <= MAX_TRADE_ID_LENGTH }) { "Zu viele Ereignisse" }
        require(knownLock == null || knownLock.length <= MAX_LOCK_LENGTH) { "Sperre zu lang" }
    }

    companion object {
        const val MAX_NOTIFIED_TRADES = 500
        const val MAX_TRADE_ID_LENGTH = 200
        const val MAX_LOCK_LENGTH = 4096
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
        put("reminder", settings.reminderMode.name.lowercase())
        put("deadlines", settings.deadlineReminders)
        put("trades", settings.tradeAlerts)
        put("publish", settings.publishAlerts)
        putJsonArray("tradeIds") { settings.notifiedTrades.sorted().forEach { add(it) } }
        settings.knownLock?.let { put("lock", it) }
        put("calendar", settings.calendarSync)
        put("tips", settings.tipsSeen)
        put("askedWho", settings.askedWho)
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): DeviceSettings {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("Einstellungen ungültig")
        require((root["v"] as? JsonPrimitive)?.content == VERSION.toString()) { "Unbekannte Einstellungsversion" }
        val me = root["me"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("me ungültig") }
        val defaults = DeviceSettings()
        val reminder = root["reminder"]?.let { element ->
            val name = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("reminder ungültig")
            ReminderMode.entries.firstOrNull { it.name.lowercase() == name } ?: ReminderMode.OFF
        } ?: defaults.reminderMode
        val tradeIds = (root["tradeIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.filter { it.length <= DeviceSettings.MAX_TRADE_ID_LENGTH }?.take(DeviceSettings.MAX_NOTIFIED_TRADES)?.toSet()
            ?: emptySet()
        val lock = (root["lock"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= DeviceSettings.MAX_LOCK_LENGTH }
        return DeviceSettings(
            myMemberId = me,
            notifyOnChanges = flag(root, "notify", false),
            seenShifts = shifts(root["seen"]),
            notifiedShifts = shifts(root["notified"]),
            reminderMode = reminder,
            deadlineReminders = flag(root, "deadlines", defaults.deadlineReminders),
            tradeAlerts = flag(root, "trades", defaults.tradeAlerts),
            publishAlerts = flag(root, "publish", defaults.publishAlerts),
            notifiedTrades = tradeIds,
            knownLock = lock,
            calendarSync = flag(root, "calendar", defaults.calendarSync),
            tipsSeen = (root["tips"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull() ?: 0,
            askedWho = flag(root, "askedWho", false),
        )
    }

    private fun flag(root: JsonObject, name: String, default: Boolean): Boolean =
        root[name]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: throw IllegalArgumentException("$name ungültig") } ?: default

    private fun JsonObjectBuilder.putShifts(name: String, shifts: Map<LocalDate, String>) {
        putJsonObject(name) {
            for ((date, typeId) in shifts.toSortedMap()) put(date.toString(), typeId)
        }
    }

    private fun shifts(element: JsonElement?): Map<LocalDate, String>? {
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
            val typeId = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { ShiftTypes.isValidId(it) } ?: throw IllegalArgumentException("Schichtart ungültig")
            date to typeId
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
