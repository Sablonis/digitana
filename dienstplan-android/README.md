# Dienstplan – Android-App

Schichtplan für kleine Teams, der **ohne eigenen Server** zwischen den Handys synchronisiert:
in Echtzeit, konfliktfrei (CRDT) und **Ende-zu-Ende-verschlüsselt mit MLS** (RFC 9420, nach
dem Marmot-Protokoll) über öffentliche Nostr-Relays. Jedes Gerät hat eigene Schlüssel;
Admins fügen Geräte hinzu oder entfernen sie. Die App funktioniert auch offline; Änderungen
werden später gesendet.

- Paket: `ch.digitana.dienstplan` (Debug-Build: `ch.digitana.dienstplan.debug`)
- Kotlin, Jetpack Compose (Material 3), Coroutines
- minSdk 26 (Android 8.0), compileSdk/targetSdk 37 (Android 17), Edge-to-Edge
- Oberfläche auf Deutsch

## Funktionen

- **Vier Reiter:** Woche, Monat, Ich und Team. Eigenes Farbschema (Indigo mit Koralle),
  hell und dunkel, Avatare mit Initialen, farbige Schicht-Kärtchen, neues App-Icon.
- **Woche:** Wischen wechselt die Woche, ein Tipp auf „KW“ oder „Heute“ springt zurück.
  Oben die Übersicht für heute (eigener Dienst, Besetzung je Schicht). Tipp auf ein Feld
  öffnet die Auswahl: Schicht, Wunsch, Notiz zum Dienst; langes Drücken leert das Feld.
  Tipp auf einen Tag zeigt, wer welche Schicht hat, wer fehlt, Wünsche (erfüllt oder nicht)
  und die Notiz zum Tag. Stunden pro Person, Besetzung pro Tag (Ist/Soll), „Woche kopieren“ und
  „Rhythmus anwenden“ mit „Rückgängig“, „Schnell eintragen“ (Schicht wählen, Felder antippen).
- **Personen:** unten in der Woche hinzufügen (Name max. 40 Zeichen); Tipp auf einen Namen
  zum Umbenennen, Löschen oder „Das bin ich“.
- **Monat:** alle Personen über den ganzen Monat, Tage waagrecht scrollbar, Stunden pro
  Monat, Besetzung pro Tag und eine Wunsch-Bilanz pro Person (erfüllt, nicht erfüllt, offen).
- **Ich (Meine Dienste):** die eigenen Dienste der nächsten 8 Wochen, Stunden der Woche und
  des Monats, nächster Dienst, Notizen und ein **Wunschkalender**: Wunsch wählen, Tage
  antippen. Wünsche: Wunschfrei, Ferienwunsch, nicht verfügbar, Wunscharbeitstag und
  Wunschschicht (eine bestimmte Schichtart). Export in den Kalender als .ics.
- **Gleiche Rechte:** Im offenen Plan tragen alle alles ein. Wünsche gehören der Person: Hat
  sie auf ihrem Gerät „Das bin ich“ gewählt, ändern sie nur ihre Geräte und Admins.
- **Ruhezeit und Soll-Besetzung:** Die App warnt, wenn zwischen zwei Diensten einer Person
  weniger als 11 Stunden Ruhe liegen (einstellbar oder abschaltbar), markiert solche Felder rot
  und zeigt beim Eintragen, welche Schicht zu knapp wäre. Pro Schicht und Wochentag lässt sich
  ein Soll festlegen; Tage darunter erscheinen rot, über dem Plan steht eine Zusammenfassung.
- **Plan sperren (Admins):** im Team-Reiter oder über den Hinweis im Plan – bis Ende dieser
  oder nächster Woche, Ende dieses oder nächsten Monats oder der ganze Plan; jederzeit wieder
  öffnen. Gesperrt ändern nur Admins Schichten, Schichtarten, Ruhezeit und Soll sowie
  Personen; Wünsche und Notizen bleiben für alle offen. Gesperrte Tage tragen ein Schloss.
- **Schichtarten:** eigene Arten mit Kürzel, Name, Zeiten, Pause, Art (Arbeit, frei,
  abwesend), angerechneten Stunden und Farbe; archivieren, Standardarten zurücksetzen.
- **Rhythmen:** Abfolgen über 1–8 Wochen „malen“ und auf Personen und bis zu 52 Wochen
  anwenden – nur leere Felder füllen oder überschreiben.
- **Teilen:** Woche oder Monat als PDF oder Bild (mit Hinweis, dass die Datei nicht
  verschlüsselt ist).
- **Widget „Meine Dienste“** für den Startbildschirm mit den nächsten fünf Diensten.
- **Team:** „Neues Team gründen“ (dieses Gerät wird Admin) oder „Einem Team beitreten“: Das
  Gerät zeigt einen Beitrittscode, ein Admin fügt es damit hinzu, und das Gerät bestätigt
  die Einladung (Teamname, Fingerabdruck des einladenden Geräts). Geräteliste mit Namen,
  Fingerabdrücken und zugehöriger Person, Admin-Rechte, Sperre des Plans, „Team verlassen“. Ein entferntes Gerät zeigt den Plan nur
  noch lesend an.
- **Sicherheit im Hintergrund:** Schlüsselerneuerung nach dem Beitritt und alle 7 Tage,
  Abgleich verlorener Nachrichten über Digests, gleichzeitige Gruppenänderungen werden
  erkannt und aufgelöst (Details in `docs/PROTOKOLL.md`).
- **Benachrichtigungen:** Wer unter „Ich“ oder „Team“ gewählt hat, wer man im Plan ist, wird
  benachrichtigt, wenn jemand die eigenen künftigen Dienste ändert. Dafür gleicht die App
  etwa alle 15 Minuten im Hintergrund ab (WorkManager, nur mit Netz).
- Statusanzeige („Live“) und Diagnose pro Relay.

## Aufbau

```
dienstplan-android/
├── core/   reines Kotlin/JVM – ohne Android-SDK baubar und testbar
│   ├── group/    Team als MLS-Gruppe: Geräteschlüssel, Beitrittscode, Sync-Engine
│   │             (Relays, Nachholen, Versand mit Bestätigung, Abgleich, Commits)
│   ├── crdt/     Einträge, LWW-Map, hybride Uhr, Schlüssel, Buckets, Eingabeprüfung
│   ├── nostr/    Events nach NIP-01, Relay-Nachrichten
│   ├── sync/     Relay-Verbindungen (OkHttp), TLS-Client, Status, Backoff
│   ├── crypto/   AES-256-GCM (Tink) für lokale Dateien
│   ├── data/     Plan-Repository, Einstellungen, verschlüsselter Dateispeicher
│   └── plan/     Woche, Monat, „Meine Dienste“, Rhythmen, Kalender-Export (.ics), Beschriftungen
├── mls/    Rust: MLS-Gruppenverschlüsselung (Marmot/MDK, OpenMLS) mit Kotlin-Anbindung (UniFFI)
├── app/    Android: Keystore, Sync-Steuerung, ViewModels, Compose-Oberfläche, Export, Widget
└── docs/   PROTOKOLL.md, SICHERHEIT.md, RELEASE.md, INSTALLATION.md
```

Schichten: **UI** (Compose) → **ViewModel** → **Repository** (CRDT-Speicher, Team) →
**Sync-Engine** → **Krypto** (MLS-Bibliothek). Jede Schicht ist einzeln testbar; die gesamte
Logik liegt in `:core` und `mls/`.

## Voraussetzungen

- Android Studio mit Unterstützung für AGP 9.4 (aktuelle stabile Version)
- **JDK 21** als Gradle-JDK (Android Studio: *Settings → Build, Execution, Deployment →
  Build Tools → Gradle → Gradle JDK*, das mitgelieferte JBR 21 genügt)
- Android SDK Platform 37 (Android Studio lädt fehlende Pakete beim Sync nach)
- **Rust** über [rustup](https://rustup.rs): Version und Android-Ziele stehen in
  `mls/rust-toolchain.toml` und werden beim ersten Build automatisch installiert.
- **cargo-ndk**: `cargo install cargo-ndk --version 4.1.2 --locked`
- **Android NDK** (Android Studio: *SDK Manager → SDK Tools → NDK (Side by side)*), Pfad in
  der Umgebungsvariable `ANDROID_NDK_HOME`
- `perl` und `make` (für das mitgebaute OpenSSL von SQLCipher); unter Windows z. B. über WSL

Verwendete Versionen: AGP 9.4.1, Kotlin 2.4.20, Gradle 9.7.1, Compose BOM 2026.09.00,
OkHttp 5.5.0, Tink 1.23.0, JNA 5.19.1 (siehe `gradle/libs.versions.toml`); Rust 1.94.1,
MDK (Marmot), OpenMLS, rust-nostr 0.44, UniFFI 0.32, SQLCipher (siehe `mls/Cargo.toml`).

## Bauen

### Android Studio

1. In Android Studio **File → Open** und den Ordner `dienstplan-android/` wählen
   (nicht das übergeordnete Repository).
2. Gradle-JDK auf 21 stellen (siehe oben) und Gradle synchronisieren.
3. Gerät anschliessen oder Emulator starten, Konfiguration **app** ausführen.

> Hinweis: Über „Run“ installierte APKs sind als *testOnly* markiert und lassen sich nicht
> per Datei-Manager installieren. Zum Weitergeben immer eine APK aus Gradle verwenden
> (siehe unten bzw. `docs/INSTALLATION.md`).

### Kommandozeile

```bash
cd dienstplan-android

./gradlew :core:test                    # Unit- und Integrationstests (JVM, ohne Internet)
(cd mls && cargo test)                  # Rust-Tests der MLS-Schicht
./gradlew :app:assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug             # auf angeschlossenes Gerät installieren
./gradlew :app:lintDebug                # Android-Lint
./gradlew :app:assembleRelease          # Release mit R8 (signiert, wenn konfiguriert)
```

### Automatischer Build (GitHub Actions)

Der Workflow `.github/workflows/dienstplan-android.yml` (im Wurzelverzeichnis des
Repositorys) läuft bei jedem Push, der `dienstplan-android/` betrifft: Kern-Tests,
Debug-APK, Lint und Release-Build mit R8 (unsigniert, ohne Keystore). Die Debug-APK
liegt danach als Artefakt `dienstplan-debug-apk` beim jeweiligen Lauf.

Signierte Release-APK (Signaturschemas v1, v2, v3): **[docs/RELEASE.md](docs/RELEASE.md)**.
Installation per Sideloading und Hilfe bei „App nicht installiert“:
**[docs/INSTALLATION.md](docs/INSTALLATION.md)**.

## Tests

| Befehl | Inhalt |
|---|---|
| `(cd mls && cargo test)` | MLS-Abläufe mit echten SQLCipher-Datenbanken: Einladen und Beitreten, Nachrichten in beide Richtungen, entferntes Gerät liest nichts mehr, Admin-Rechte, Teambeschreibung (Sperre) nur durch Admins, zu früh oder in falscher Reihenfolge eintreffende Events, offene Einladung nach Neustart, Wettlauf zweier Commits, Signieren nur für Kind 5/22242, falscher Datenbankschlüssel, fremde und kaputte Eingaben |
| `./gradlew :core:test` | **Drei Geräte über drei lokale Nostr-Relays mit echtem TLS und echter MLS-Verschlüsselung**: Gründen, Beitreten per Code, ganzer Plan für neue Geräte, gleichzeitige Änderungen mit Konflikt, Entfernen, Austritt und Admin-Übergabe, Offline-Änderungen nach Neustart, Reparatur verlorener Nachrichten, Relays mit Anmeldung (NIP-42), unterbrochene Commits, **Sperre des Plans** (nur Admins sperren, Mitglieder tragen nur noch Wünsche ein, Verstösse verschwinden auf allen Geräten, Öffnen gibt frei). Dazu Format und Regeln der Sperre, Wunschrechte, Wunscharbeitstage und Wunschschichten mit Erfüllungsstatus, Rückgängig, Schichtarten, Notizen, Wünsche und Rhythmen (Formate, kaputte Werte, Überschreiben von Standardarten), Monatsansicht, „Meine Dienste“, Anwenden von Rhythmen, Kalender-Export nach RFC 5545 (UTC, Zeilenumbruch), Nachrichtenformat und Eingabeprüfung mit bösartigen Daten, Beitrittscode, CRDT-Merge (kommutativ, assoziativ, idempotent), NIP-01-ID, BIP-340-Testvektoren (offizielle CSV), manipulierte AES-GCM-Pakete, Kotlin-Anbindung der Rust-Bibliothek, TLS-Negativtests lokal (selbstsigniert, falscher Host, abgelaufen, fremde CA, Klartext) |
| `./gradlew :core:networkTest` | **Drei Geräte über die echten Relays** (damus, nos.lol, primal) mit MLS: Gründen, Beitreten per Code, gleichzeitige Änderungen mit Konflikt; **TLS-Negativtests gegen expired/wrong.host/self-signed/untrusted-root.badssl.com** sowie TLS 1.0/1.1. Braucht eine direkte Internetverbindung (kein TLS-aufbrechender Proxy). Andere Relays: `-Pdienstplan.relays=wss://a,wss://b,wss://c` |
| `./gradlew :app:connectedDebugAndroidTest` | Auf Gerät/Emulator: Android-Keystore (Rundlauf, Manipulation, gelöschter Schlüssel, StrongBox-Rückfall), verschlüsselte Dateien, TLS-Negativtests mit dem Android-Trust-Store und der Network Security Config |

Die BIP-340-Vektoren 15–18 (Nachrichten ≠ 32 Byte) werden übersprungen: secp256k1-kmp
(nur noch in den Tests, zum unabhängigen Prüfen der Signaturen) signiert nur 32-Byte-
Nachrichten, und Nostr signiert ausschliesslich 32-Byte-Event-IDs.

Fehlersuche in den Integrationstests: `./gradlew :core:test -Pdienstplan.testlog=true` gibt
die Meldungen der Sync-Engine aus (ohne Inhalte).

## Weitere Dokumente

- [docs/PROTOKOLL.md](docs/PROTOKOLL.md) – Beitritt, Gruppen-Events, Nachrichtenformat, Abgleich, Commits
- [docs/SICHERHEIT.md](docs/SICHERHEIT.md) – Sicherheitsentscheidungen und ihre Grenzen
- [docs/RELEASE.md](docs/RELEASE.md) – signierte Release-APK
- [docs/INSTALLATION.md](docs/INSTALLATION.md) – Sideloading, Samsung Auto Blocker, Play Protect, Fehlersuche
