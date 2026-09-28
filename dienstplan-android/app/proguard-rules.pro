# R8-Regeln für den Release-Build.
# OkHttp, Okio, Tink (Protobuf) und kotlinx.serialization bringen eigene Regeln mit.
# Die App verwendet keine @Serializable-Klassen, nur JsonElement.

# secp256k1-kmp lädt die JNI-Implementierung per Reflection (Class.forName), und der native
# Code wirft Exceptions über ihren Klassennamen. Das ganze Paket bleibt deshalb unverändert.
-keep class fr.acinq.secp256k1.** { *; }

# Tink referenziert Annotationen, die zur Laufzeit nicht gebraucht werden.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Zusätzliche Absicherung: Logcat-Aufrufe unterhalb von WARN werden im Release entfernt.
# (Die App loggt ohnehin nur in Debug-Builds und nie Geheimnisse.)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Stacktraces im lokalen Absturzbericht lesbar halten (Zeilennummern, keine Quelldateinamen).
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile
