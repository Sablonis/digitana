# R8-Regeln für den Release-Build.
# OkHttp, Okio, Tink (Protobuf) und kotlinx.serialization bringen eigene Regeln mit.
# Die App verwendet keine @Serializable-Klassen, nur JsonElement.

# JNA und die UniFFI-Bindings der MLS-Bibliothek: JNA greift per Reflection auf Klassen,
# Felder und Methoden zu (Structure, Callback, Library-Schnittstellen).
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class ch.digitana.dienstplan.mls.** { *; }
-dontwarn java.awt.**

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
