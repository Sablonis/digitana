package ch.digitana.dienstplan.core.crdt

import java.time.LocalDate

/**
 * Angebot „Dienst abgeben“ (`o|<id>|<JJJJ-MM-TT>`): Die Person möchte ihren Dienst [typeId]
 * an diesem Tag abgeben. Ist der Tag gesperrt, meldet sich jemand mit [claimedBy] und ein
 * Admin bestätigt die Übergabe. Format: `<schichtart-id>` oder `<schichtart-id>@<id>`.
 */
data class ShiftOffer(val typeId: String, val claimedBy: String? = null) {
    init {
        require(ShiftTypes.isValidId(typeId)) { "Ungültige Schichtart" }
        require(claimedBy == null || PlanKeys.isValidId(claimedBy)) { "Ungültige ID" }
    }

    fun encode(): String = if (claimedBy == null) typeId else "$typeId@$claimedBy"

    companion object {
        private val VALUE = Regex("([FSNXU]|[0-9a-f]{8})(?:@([0-9a-f]{16}))?")

        fun decode(value: String): ShiftOffer? {
            val match = VALUE.matchEntire(value) ?: return null
            return ShiftOffer(match.groupValues[1], match.groupValues[2].ifEmpty { null })
        }
    }
}

/** Stand eines Tauschvorschlags. */
enum class SwapStatus(val code: String) {
    /** Vorgeschlagen, die andere Person hat noch nicht geantwortet. */
    PROPOSED("P"),

    /** Angenommen, aber ein Tag ist gesperrt: Ein Admin muss ihn ausführen. */
    ACCEPTED("A"),

    /** Ausgeführt: Die Schichten sind getauscht. */
    DONE("X"),

    /** Abgelehnt (von der anderen Person oder einem Admin). */
    DECLINED("D");

    companion object {
        fun fromCode(code: String): SwapStatus? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Tauschvorschlag (`t|<a>|<tag-a>|<b>|<tag-b>`): Person A gibt ihren Dienst [fromType] am
 * ersten Tag an Person B und übernimmt dafür deren Dienst [toType] am zweiten Tag. Ohne
 * [toType] übernimmt B den Dienst, ohne einen zurückzugeben. Die Schichtarten halten fest,
 * worauf sich der Vorschlag bezog; hat sich der Plan seither geändert, gilt er nicht mehr.
 * Format: `<status>:<schichtart-a>:<schichtart-b oder leer>`.
 */
data class SwapRequest(val status: SwapStatus, val fromType: String, val toType: String? = null) {
    init {
        require(ShiftTypes.isValidId(fromType)) { "Ungültige Schichtart" }
        require(toType == null || ShiftTypes.isValidId(toType)) { "Ungültige Schichtart" }
    }

    val isOpen: Boolean get() = status == SwapStatus.PROPOSED || status == SwapStatus.ACCEPTED

    fun encode(): String = "${status.code}:$fromType:${toType.orEmpty()}"

    fun with(status: SwapStatus): SwapRequest = copy(status = status)

    companion object {
        private val VALUE = Regex("([PAXD]):([FSNXU]|[0-9a-f]{8}):((?:[FSNXU]|[0-9a-f]{8})?)")

        fun decode(value: String): SwapRequest? {
            val match = VALUE.matchEntire(value) ?: return null
            val status = SwapStatus.fromCode(match.groupValues[1]) ?: return null
            return SwapRequest(status, match.groupValues[2], match.groupValues[3].ifEmpty { null })
        }
    }
}

/** Ein Angebot im Plan: wer an welchem Tag welchen Dienst abgeben möchte. */
data class OfferEntry(val memberId: String, val date: LocalDate, val offer: ShiftOffer, val entry: Entry) {
    val key: String get() = PlanKeys.offer(memberId, date)
}

/** Ein Tauschvorschlag im Plan. */
data class SwapEntry(val swap: PlanKey.Swap, val request: SwapRequest, val entry: Entry) {
    val key: String get() = PlanKeys.swap(swap.fromMember, swap.fromDate, swap.toMember, swap.toDate)
}
