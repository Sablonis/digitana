package ch.digitana.dienstplan.core.sync

import ch.digitana.dienstplan.core.crdt.Buckets
import ch.digitana.dienstplan.core.crdt.Entry
import ch.digitana.dienstplan.core.crdt.EntryValidator
import ch.digitana.dienstplan.core.crdt.Limits
import ch.digitana.dienstplan.core.crdt.LwwMap
import ch.digitana.dienstplan.core.crypto.PacketCipher
import ch.digitana.dienstplan.core.util.JsonGuards
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Inhalt eines Bucket-Events.
 *
 * Klartext (JSON): `{"v":2,"b":"<bucket>","e":[["<schlüssel>","<wert>",<ms>,"<gerät>"],…]}`
 * Chiffrat: AES-256-GCM, AAD = `DP2|<d-Tag>`, übertragen als Base64 im `content`.
 *
 * Beim Dekodieren gilt:
 *  - Strukturfehler (kein JSON, falsche Typen, doppelte Schlüssel, > 5000 Einträge,
 *    Bucket passt nicht zum d-Tag) → das ganze Event wird verworfen.
 *  - Inhaltlich ungültige Einträge (Schlüsselformat, Datum, Kürzel, Name, Zeitstempel,
 *    Geräte-ID, falscher Bucket) → nur dieser Eintrag wird verworfen und gezählt.
 *    So kann ein einzelner fehlerhafter Eintrag nicht den ganzen Bucket blockieren.
 */
object BucketCodec {
    const val FORMAT_VERSION = 2
    private const val MAX_JSON_DEPTH = 4

    private val json = Json {
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    fun associatedData(dTag: String): ByteArray = "DP2|$dTag".toByteArray(Charsets.US_ASCII)

    fun encodePlaintext(bucket: String, map: LwwMap): ByteArray {
        val root = buildJsonObject {
            put("v", FORMAT_VERSION)
            put("b", bucket)
            putJsonArray("e") {
                for (key in map.entries.keys.sorted()) {
                    val entry = map.entries.getValue(key)
                    addJsonArray {
                        add(key)
                        add(entry.value)
                        add(entry.timestamp)
                        add(entry.device)
                    }
                }
            }
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun encrypt(cipher: PacketCipher, dTag: String, bucket: String, map: LwwMap): String =
        Base64.getEncoder().encodeToString(cipher.encrypt(encodePlaintext(bucket, map), associatedData(dTag)))

    enum class RejectReason { UNDECRYPTABLE, MALFORMED, UNSUPPORTED_VERSION, BUCKET_MISMATCH, TOO_MANY_ENTRIES, DUPLICATE_KEY }

    sealed interface Result {
        data class Ok(val bucket: String, val entries: Map<String, Entry>, val dropped: Int) : Result
        data class Rejected(val reason: RejectReason) : Result
    }

    /**
     * @param dTagOf berechnet den erwarteten d-Tag eines Bucket-Namens (HMAC).
     */
    fun decrypt(
        cipher: PacketCipher,
        dTag: String,
        content: String,
        nowMillis: Long,
        dTagOf: (String) -> String,
    ): Result {
        val raw = try {
            Base64.getDecoder().decode(content)
        } catch (e: IllegalArgumentException) {
            return Result.Rejected(RejectReason.MALFORMED)
        }
        // GCM prüft das Tag: Nur wer den Team-Schlüssel hat, erzeugt ein gültiges Paket.
        val plaintext = cipher.decrypt(raw, associatedData(dTag))
            ?: return Result.Rejected(RejectReason.UNDECRYPTABLE)
        return decodePlaintext(plaintext, dTag, nowMillis, dTagOf)
    }

    fun decodePlaintext(
        plaintext: ByteArray,
        dTag: String,
        nowMillis: Long,
        dTagOf: (String) -> String,
    ): Result {
        val text = decodeUtf8Strict(plaintext) ?: return Result.Rejected(RejectReason.MALFORMED)
        if (JsonGuards.exceedsDepth(text, MAX_JSON_DEPTH)) return Result.Rejected(RejectReason.MALFORMED)
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return Result.Rejected(RejectReason.MALFORMED)

        val version = (root["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
            ?: return Result.Rejected(RejectReason.MALFORMED)
        if (version != FORMAT_VERSION.toString()) return Result.Rejected(RejectReason.UNSUPPORTED_VERSION)

        val bucket = (root["b"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Result.Rejected(RejectReason.MALFORMED)
        if (!Buckets.isValid(bucket) || dTagOf(bucket) != dTag) {
            return Result.Rejected(RejectReason.BUCKET_MISMATCH)
        }

        val items = root["e"] as? JsonArray ?: return Result.Rejected(RejectReason.MALFORMED)
        if (items.size > Limits.MAX_ENTRIES_PER_EVENT) return Result.Rejected(RejectReason.TOO_MANY_ENTRIES)

        val seen = HashSet<String>(items.size * 2)
        val entries = HashMap<String, Entry>(items.size * 2)
        var dropped = 0
        for (item in items) {
            val fields = item as? JsonArray ?: return Result.Rejected(RejectReason.MALFORMED)
            if (fields.size != 4) return Result.Rejected(RejectReason.MALFORMED)
            val key = fields[0].asString() ?: return Result.Rejected(RejectReason.MALFORMED)
            val value = fields[1].asString() ?: return Result.Rejected(RejectReason.MALFORMED)
            val timestamp = fields[2].asInteger() ?: return Result.Rejected(RejectReason.MALFORMED)
            val device = fields[3].asString() ?: return Result.Rejected(RejectReason.MALFORMED)
            if (!seen.add(key)) return Result.Rejected(RejectReason.DUPLICATE_KEY)
            val entry = Entry(value, timestamp, device)
            if (EntryValidator.check(bucket, key, entry, nowMillis) != null) {
                dropped++
                continue
            }
            entries[key] = entry
        }
        return Result.Ok(bucket, entries, dropped)
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun kotlinx.serialization.json.JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Ganzzahl-Literal mit 1–16 Ziffern; negative Werte, Dezimalpunkt, Exponent → ungültig. */
    private fun kotlinx.serialization.json.JsonElement.asInteger(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val text = primitive.content
        if (text.isEmpty() || text.length > 16 || !text.all { it in '0'..'9' }) return null
        return text.toLong()
    }
}
