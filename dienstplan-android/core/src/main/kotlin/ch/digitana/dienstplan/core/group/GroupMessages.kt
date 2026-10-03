package ch.digitana.dienstplan.core.group

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.EntryValidator
import ch.digitana.dienstplan.core.crdt.Limits
import ch.digitana.dienstplan.core.crdt.LwwMap
import ch.digitana.dienstplan.core.crdt.WeekId
import ch.digitana.dienstplan.core.util.Hex
import ch.digitana.dienstplan.core.util.JsonGuards
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Inhalt der MLS-Anwendungsnachrichten (Protokoll DP3). Verschlüsselung, Absender und
 * Mitgliedschaft prüft MLS; hier geht es nur um den Klartext.
 *
 * Stand (`t = "s"`): Einträge eines oder mehrerer Buckets, jeweils mit dem Digest, den der
 * Bucket beim Absender danach hat:
 * `{"v":3,"t":"s","p":[{"b":"2026-W39","h":"<16 hex>","e":[["<schlüssel>","<wert>",<ms>,"<gerät>"],…]},…]}`
 * Ein Teil kann eine Änderung (nur die geänderten Einträge), einen ganzen Bucket oder – mit
 * leerer Liste – eine Bitte um den Stand anderer Geräte sein. Empfänger führen zusammen und
 * vergleichen danach ihren Digest mit `h`.
 *
 * Übersicht (`t = "g"`): Digests aller Buckets im Fenster `from`…`to` (plus `team`):
 * `{"v":3,"t":"g","from":"2025-W40","to":"2027-W40","d":{"team":"<16 hex>",…}}`
 *
 * Austritt (`t = "x"`): `{"v":3,"t":"x"}` – der Absender bittet die Admins, ihn zu entfernen.
 *
 * Beim Dekodieren gilt wie beim Speicherformat: Strukturfehler verwerfen die ganze Nachricht,
 * inhaltlich ungültige Einträge nur den Eintrag (gezählt).
 */
object GroupMessages {
    const val VERSION = 3
    /** Kind der Nachricht innerhalb von MLS (NIP-78: anwendungsspezifische Daten). */
    const val KIND = 30078
    /** Länge der übertragenen Digests (Hex-Zeichen, 64 Bit). */
    const val DIGEST_LENGTH = 16
    /** So viele Einträge packt der Absender höchstens in eine Nachricht (≈ 15 KB). */
    const val MAX_ENTRIES_PER_MESSAGE = 200
    /** Wochen vor und nach der aktuellen, die eine Übersicht abdeckt. */
    const val DIGEST_WINDOW_WEEKS = 52L

    private const val MAX_JSON_DEPTH = 5
    private const val MAX_PARTS = 1000
    private const val MAX_DIGESTS = 300

    private val json = Json {
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    /** Gekürzter Digest eines Buckets, wie er übertragen wird. */
    fun digestOf(map: LwwMap): String = map.digest.take(DIGEST_LENGTH)

    val EMPTY_DIGEST: String = digestOf(LwwMap.EMPTY)

    /** Ein zu sendender Teil: Einträge eines Buckets und der Digest des ganzen Buckets beim Absender. */
    data class Part(val bucket: String, val digest: String, val entries: Map<String, Entry>)

    /** Fenster einer Übersicht (inklusive). */
    data class Window(val from: WeekId, val to: WeekId) {
        fun contains(bucket: String): Boolean {
            if (bucket == Buckets.TEAM) return true
            val week = Buckets.parseWeek(bucket) ?: return false
            return week >= from && week <= to
        }

        companion object {
            fun around(week: WeekId): Window = Window(
                WeekId.of(week.monday.minusWeeks(DIGEST_WINDOW_WEEKS)),
                WeekId.of(week.monday.plusWeeks(DIGEST_WINDOW_WEEKS)),
            )
        }
    }

    sealed interface Message {
        data class State(val parts: List<ReceivedPart>) : Message
        data class Overview(val window: Window, val digests: Map<String, String>) : Message
        /** Der Absender verlässt das Team; ein Admin entfernt ihn. */
        data object Leave : Message
    }

    data class ReceivedPart(
        val bucket: String,
        val digest: String,
        val entries: Map<String, Entry>,
        /** Verworfene, ungültige Einträge (der Digest des Absenders ist dann nie erreichbar). */
        val dropped: Int,
    )

    sealed interface Decoded {
        data class Ok(val message: Message) : Decoded
        /** Unbekannte (neuere) Version: still ignorieren. */
        data object Unsupported : Decoded
        data object Malformed : Decoded
    }

    /**
     * Verteilt Teile auf Nachrichten mit höchstens [maxEntries] Einträgen. Grosse Buckets werden
     * in Stücke geteilt (gleicher Digest); leere Teile (Bitten) zählen als ein Eintrag.
     */
    fun pack(parts: List<Part>, maxEntries: Int = MAX_ENTRIES_PER_MESSAGE): List<List<Part>> {
        require(maxEntries > 0)
        val messages = ArrayList<List<Part>>()
        var current = ArrayList<Part>()
        var used = 0
        fun flush() {
            if (current.isEmpty()) return
            messages += current
            current = ArrayList()
            used = 0
        }
        for (part in parts) {
            require(Buckets.isValid(part.bucket) && Hex.isLowerHex(part.digest, DIGEST_LENGTH)) { "Teil ungültig" }
            if (part.entries.isEmpty()) {
                if (used + 1 > maxEntries) flush()
                current += part
                used += 1
                continue
            }
            val keys = part.entries.keys.sorted()
            var index = 0
            while (index < keys.size) {
                if (used >= maxEntries) flush()
                val take = minOf(maxEntries - used, keys.size - index)
                val chunk = keys.subList(index, index + take).associateWith { part.entries.getValue(it) }
                current += Part(part.bucket, part.digest, chunk)
                used += take
                index += take
            }
        }
        flush()
        return messages
    }

    /** Stand-Nachrichten für [parts] (siehe [pack]). */
    fun encodeState(parts: List<Part>, maxEntries: Int = MAX_ENTRIES_PER_MESSAGE): List<String> =
        pack(parts, maxEntries).map { encodeParts(it) }

    /** Eine Stand-Nachricht aus bereits verteilten Teilen. */
    fun encodeParts(parts: List<Part>): String {
        require(parts.isNotEmpty() && parts.size <= MAX_PARTS) { "Anzahl Teile ungültig" }
        return stateJson(parts)
    }

    fun encodeOverview(window: Window, digests: Map<String, String>): String {
        val inWindow = digests.filterKeys { window.contains(it) }
        require(inWindow.size <= MAX_DIGESTS) { "Zu viele Buckets für eine Übersicht" }
        return buildJsonObject {
            put("v", VERSION)
            put("t", "g")
            put("from", window.from.bucketName)
            put("to", window.to.bucketName)
            putJsonObject("d") {
                for ((bucket, digest) in inWindow.toSortedMap()) {
                    require(Hex.isLowerHex(digest, DIGEST_LENGTH)) { "Digest ungültig" }
                    put(bucket, digest)
                }
            }
        }.toString()
    }

    fun encodeLeave(): String = buildJsonObject {
        put("v", VERSION)
        put("t", "x")
    }.toString()

    private fun stateJson(parts: List<Part>): String = buildJsonObject {
        put("v", VERSION)
        put("t", "s")
        putJsonArray("p") {
            for (part in parts) {
                addJsonObject {
                    put("b", part.bucket)
                    put("h", part.digest)
                    putJsonArray("e") {
                        for (key in part.entries.keys.sorted()) {
                            val entry = part.entries.getValue(key)
                            addJsonArray {
                                add(key)
                                add(entry.value)
                                add(entry.timestamp)
                                add(entry.device)
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    fun decode(content: String, nowMillis: Long): Decoded {
        if (JsonGuards.utf8LengthExceeds(content, Limits.MAX_MESSAGE_BYTES)) return Decoded.Malformed
        if (JsonGuards.exceedsDepth(content, MAX_JSON_DEPTH)) return Decoded.Malformed
        val root = try {
            json.parseToJsonElement(content) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return Decoded.Malformed

        val version = (root["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.content ?: return Decoded.Malformed
        if (version != VERSION.toString()) {
            return if (version.all { it in '0'..'9' } && version.length <= 4) Decoded.Unsupported else Decoded.Malformed
        }
        return when (root["t"]?.asString()) {
            "s" -> decodeState(root, nowMillis)
            "g" -> decodeOverview(root)
            "x" -> Decoded.Ok(Message.Leave)
            else -> Decoded.Malformed
        }
    }

    private fun decodeState(root: JsonObject, nowMillis: Long): Decoded {
        val items = root["p"] as? JsonArray ?: return Decoded.Malformed
        if (items.isEmpty() || items.size > MAX_PARTS) return Decoded.Malformed
        var total = 0
        val parts = ArrayList<ReceivedPart>(items.size)
        for (item in items) {
            val obj = item as? JsonObject ?: return Decoded.Malformed
            val bucket = obj["b"]?.asString() ?: return Decoded.Malformed
            if (!Buckets.isValid(bucket)) return Decoded.Malformed
            val digest = obj["h"]?.asString() ?: return Decoded.Malformed
            if (!Hex.isLowerHex(digest, DIGEST_LENGTH)) return Decoded.Malformed
            val list = obj["e"] as? JsonArray ?: return Decoded.Malformed
            total += list.size
            if (total > Limits.MAX_ENTRIES_PER_EVENT) return Decoded.Malformed

            val entries = HashMap<String, Entry>(list.size * 2)
            var dropped = 0
            for (element in list) {
                val fields = element as? JsonArray ?: return Decoded.Malformed
                if (fields.size != 4) return Decoded.Malformed
                val key = fields[0].asString() ?: return Decoded.Malformed
                val value = fields[1].asString() ?: return Decoded.Malformed
                val timestamp = fields[2].asInteger() ?: return Decoded.Malformed
                val device = fields[3].asString() ?: return Decoded.Malformed
                if (entries.containsKey(key)) return Decoded.Malformed
                val entry = Entry(value, timestamp, device)
                if (EntryValidator.check(bucket, key, entry, nowMillis) != null) {
                    dropped++
                    continue
                }
                entries[key] = entry
            }
            parts += ReceivedPart(bucket, digest, entries, dropped)
        }
        return Decoded.Ok(Message.State(parts))
    }

    private fun decodeOverview(root: JsonObject): Decoded {
        val from = root["from"]?.asString()?.let { Buckets.parseWeek(it) } ?: return Decoded.Malformed
        val to = root["to"]?.asString()?.let { Buckets.parseWeek(it) } ?: return Decoded.Malformed
        if (from > to) return Decoded.Malformed
        val window = Window(from, to)
        val map = root["d"] as? JsonObject ?: return Decoded.Malformed
        if (map.size > MAX_DIGESTS) return Decoded.Malformed
        val digests = HashMap<String, String>(map.size * 2)
        for ((bucket, value) in map) {
            if (!Buckets.isValid(bucket) || !window.contains(bucket)) return Decoded.Malformed
            val digest = value.asString() ?: return Decoded.Malformed
            if (!Hex.isLowerHex(digest, DIGEST_LENGTH)) return Decoded.Malformed
            digests[bucket] = digest
        }
        return Decoded.Ok(Message.Overview(window, digests))
    }

    private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Ganzzahl-Literal mit 1–16 Ziffern; negative Werte, Dezimalpunkt, Exponent → ungültig. */
    private fun JsonElement.asInteger(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val text = primitive.content
        if (text.isEmpty() || text.length > 16 || !text.all { it in '0'..'9' }) return null
        return text.toLong()
    }
}
