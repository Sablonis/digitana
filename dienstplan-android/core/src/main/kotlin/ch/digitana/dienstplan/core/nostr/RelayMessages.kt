package ch.digitana.dienstplan.core.nostr

import ch.digitana.dienstplan.core.crdt.Limits
import ch.digitana.dienstplan.core.util.JsonGuards
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Nachrichten Relay → Client nach NIP-01. */
sealed interface RelayMessage {
    data class Event(val subscriptionId: String, val event: JsonElement) : RelayMessage
    data class EndOfStoredEvents(val subscriptionId: String) : RelayMessage
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage
    data class Notice(val message: String) : RelayMessage
    data class Closed(val subscriptionId: String, val message: String) : RelayMessage
    data class Auth(val challenge: String) : RelayMessage
}

object RelayMessages {
    /** Strikter Parser (keine Kommentare, keine nachgestellten Kommas, keine Sonderzahlen). */
    private val json = Json {
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    /** Gültige Relay-Nachrichten sind höchstens 4 Ebenen tief (["EVENT", id, {…, tags: [[…]]}]). */
    private const val MAX_JSON_DEPTH = 8

    fun parse(text: String): RelayMessage? {
        if (JsonGuards.utf8LengthExceeds(text, Limits.MAX_MESSAGE_BYTES)) return null
        if (JsonGuards.exceedsDepth(text, MAX_JSON_DEPTH)) return null
        val array = try {
            json.parseToJsonElement(text) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return null
        val type = array.getOrNull(0)?.stringOrNull() ?: return null
        return when (type) {
            "EVENT" -> {
                if (array.size != 3) return null
                RelayMessage.Event(array[1].stringOrNull() ?: return null, array[2])
            }
            "EOSE" -> RelayMessage.EndOfStoredEvents(array.getOrNull(1)?.stringOrNull() ?: return null)
            "OK" -> {
                if (array.size < 3) return null
                val eventId = array[1].stringOrNull() ?: return null
                val flag = array[2] as? JsonPrimitive ?: return null
                if (flag.isString) return null
                val accepted = when (flag.content) {
                    "true" -> true
                    "false" -> false
                    else -> return null
                }
                RelayMessage.Ok(eventId, accepted, array.getOrNull(3)?.stringOrNull() ?: "")
            }
            "NOTICE" -> RelayMessage.Notice(array.getOrNull(1)?.stringOrNull() ?: return null)
            "CLOSED" -> RelayMessage.Closed(
                array.getOrNull(1)?.stringOrNull() ?: return null,
                array.getOrNull(2)?.stringOrNull() ?: "",
            )
            "AUTH" -> RelayMessage.Auth(array.getOrNull(1)?.stringOrNull() ?: return null)
            else -> null
        }
    }

    fun req(subscriptionId: String, filter: JsonObject): String =
        buildJsonArray {
            add("REQ")
            add(subscriptionId)
            add(filter)
        }.toString()

    fun close(subscriptionId: String): String =
        buildJsonArray {
            add("CLOSE")
            add(subscriptionId)
        }.toString()

    fun event(event: NostrEvent): String =
        buildJsonArray {
            add("EVENT")
            add(event.toJson())
        }.toString()

    fun filter(kind: Int, author: String, limit: Int, until: Long? = null): JsonObject =
        filter(kinds = listOf(kind), authors = listOf(author), limit = limit, until = until)

    /** Filter nach NIP-01; [tags] wird zu `#<name>` (z. B. `h` → `#h`). */
    fun filter(
        kinds: List<Int>,
        authors: List<String> = emptyList(),
        tags: Map<String, List<String>> = emptyMap(),
        since: Long? = null,
        until: Long? = null,
        limit: Int,
    ): JsonObject =
        buildJsonObject {
            putJsonArray("kinds") { kinds.forEach { add(it) } }
            if (authors.isNotEmpty()) putJsonArray("authors") { authors.forEach { add(it) } }
            for ((name, values) in tags) putJsonArray("#$name") { values.forEach { add(it) } }
            if (since != null) put("since", since)
            if (until != null) put("until", until)
            put("limit", limit)
        }

    /** Antwort auf eine Anmeldeaufforderung (NIP-42). */
    fun auth(event: JsonElement): String =
        buildJsonArray {
            add("AUTH")
            add(event)
        }.toString()

    /** Ein bereits als JSON vorliegendes Event veröffentlichen. */
    fun event(event: JsonElement): String =
        buildJsonArray {
            add("EVENT")
            add(event)
        }.toString()
}
