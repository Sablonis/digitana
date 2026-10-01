package ch.digitana.dienstplan.core.nostr

import ch.digitana.dienstplan.core.util.Hex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest

/** Ein Nostr-Event nach NIP-01. */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("pubkey", pubkey)
        put("created_at", createdAt)
        put("kind", kind)
        putJsonArray("tags") {
            for (tag in tags) addJsonArray { for (value in tag) add(value) }
        }
        put("content", content)
        put("sig", sig)
    }

}

/**
 * Event-ID und Parsing exakt nach NIP-01. Signieren und Prüfen übernimmt die MLS-Bibliothek
 * (rust-nostr), die auch die Gruppen-Events verarbeitet.
 */
object Nip01 {

    /**
     * Serialisierung für die Event-ID: `[0,<pubkey>,<created_at>,<kind>,<tags>,<content>]`,
     * UTF-8, ohne Leerraum. In Zeichenketten werden genau die sieben von NIP-01 genannten
     * Zeichen maskiert; alle anderen Zeichen bleiben unverändert.
     */
    fun serializeForId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String {
        val sb = StringBuilder(128 + content.length + tags.sumOf { t -> t.sumOf { it.length + 3 } })
        sb.append("[0,")
        appendString(sb, pubkey)
        sb.append(',').append(createdAt)
        sb.append(',').append(kind)
        sb.append(",[")
        tags.forEachIndexed { i, tag ->
            if (i > 0) sb.append(',')
            sb.append('[')
            tag.forEachIndexed { j, value ->
                if (j > 0) sb.append(',')
                appendString(sb, value)
            }
            sb.append(']')
        }
        sb.append("],")
        appendString(sb, content)
        sb.append(']')
        return sb.toString()
    }

    fun computeId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(serializeForId(pubkey, createdAt, kind, tags, content).toByteArray(Charsets.UTF_8))

    /** true, wenn die ID zum Inhalt passt (Format und Neuberechnung, ohne Signatur). */
    fun hasValidId(event: NostrEvent): Boolean {
        if (!Hex.isLowerHex(event.id, 64) || !Hex.isLowerHex(event.pubkey, 64)) return false
        val id = computeId(event.pubkey, event.createdAt, event.kind, event.tags, event.content)
        return MessageDigest.isEqual(id, Hex.decode(event.id))
    }

    /** Streng typisiertes Parsing. Unbekannte zusätzliche Felder werden ignoriert. */
    fun parseEvent(element: JsonElement): NostrEvent? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id") ?: return null
        val pubkey = obj.string("pubkey") ?: return null
        val createdAt = obj.integer("created_at") ?: return null
        val kindLong = obj.integer("kind") ?: return null
        if (kindLong < 0 || kindLong > 65535) return null
        val tagsArray = obj["tags"] as? JsonArray ?: return null
        val tags = ArrayList<List<String>>(tagsArray.size)
        for (tag in tagsArray) {
            val values = tag as? JsonArray ?: return null
            val strings = ArrayList<String>(values.size)
            for (value in values) strings += value.stringOrNull() ?: return null
            tags += strings
        }
        val content = obj.string("content") ?: return null
        val sig = obj.string("sig") ?: return null
        return NostrEvent(id, pubkey, createdAt, kindLong.toInt(), tags, content, sig)
    }

    internal fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '\n' -> sb.append("\\n")
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private fun JsonObject.string(name: String): String? = this[name]?.stringOrNull()

    /** Nur echte JSON-Ganzzahlen (keine Strings, keine Dezimal- oder Exponentialschreibweise). */
    private fun JsonObject.integer(name: String): Long? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val text = primitive.content
        if (text.isEmpty() || text.length > 18 || !text.all { it in '0'..'9' }) return null
        return text.toLong()
    }
}

internal fun JsonElement.stringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}
