package ch.digitana.dienstplan.core.crdt

/** Die 26 Kantone; das Team wählt einen für die Feiertage (`c|canton`). */
enum class Canton(val label: String) {
    AG("Aargau"),
    AI("Appenzell Innerrhoden"),
    AR("Appenzell Ausserrhoden"),
    BE("Bern"),
    BL("Basel-Landschaft"),
    BS("Basel-Stadt"),
    FR("Freiburg"),
    GE("Genf"),
    GL("Glarus"),
    GR("Graubünden"),
    JU("Jura"),
    LU("Luzern"),
    NE("Neuenburg"),
    NW("Nidwalden"),
    OW("Obwalden"),
    SG("St. Gallen"),
    SH("Schaffhausen"),
    SO("Solothurn"),
    SZ("Schwyz"),
    TG("Thurgau"),
    TI("Tessin"),
    UR("Uri"),
    VD("Waadt"),
    VS("Wallis"),
    ZG("Zug"),
    ZH("Zürich");

    /** Kürzel, z. B. „ZH“. */
    val code: String get() = name

    companion object {
        /** null für alles ausser einem der 26 Kürzel (genau zwei Grossbuchstaben). */
        fun fromCode(code: String): Canton? = if (code.length == 2) entries.firstOrNull { it.name == code } else null
    }
}
