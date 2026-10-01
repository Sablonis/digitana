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

- Mitarbeitende anlegen, umbenennen, löschen (Name max. 40 Zeichen) – Tipp auf den Namen.
- Wochenansicht Mo–So mit Kalenderwoche und Datum, vor/zurück blättern, Tipp auf die
  Kalenderwoche springt zu heute. Heute hervorgehoben, Wochenende eingefärbt.
- Schichten **F** Früh (8 h, blau), **S** Spät (8 h, orange), **N** Nacht (8 h, violett),
  **X** Frei (0 h, grau), **U** Urlaub (0 h, grün). Tipp wechselt der Reihe nach
  (leer → F → S → N → X → U → leer), langes Drücken leert das Feld.
- Stunden pro Person und Woche, Besetzung pro Tag (Anzahl F/S/N).
- „Woche kopieren“: Die nächste Woche wird identisch mit der angezeigten (auch leere
  Felder). Stehen dort schon Einträge, fragt die App vorher nach.
- Team: „Neues Team gründen“ (dieses Gerät wird Admin) oder „Einem Team beitreten“: Das
  Gerät zeigt einen Beitrittscode, ein Admin fügt es damit hinzu, und das Gerät bestätigt
  die Einladung (Teamname, Fingerabdruck des einladenden Geräts). Liegt schon ein Plan auf
  dem Gerät, fragt die App, ob er übernommen wird.
- Geräteliste unter „Team und Geräte“ mit Namen und Fingerabdrücken. Admins fügen Geräte
  hinzu, entfernen sie und vergeben Admin-Rechte. „Team verlassen“ bittet die Admins um
  Entfernung und löscht dann alles Lokale. Ein entferntes Gerät zeigt den Plan nur noch
  lesend an.
- Sicherheit im Hintergrund: Schlüsselerneuerung nach dem Beitritt und alle 7 Tage,
  Abgleich verlorener Nachrichten über Digests, gleichzeitige Gruppenänderungen werden
  erkannt und aufgelöst (Details in `docs/PROTOKOLL.md`).
- Statusanzeige („Live · 3/3 Relays“) und Diagnose pro Relay.
- Benachrichtigungen: Wer unter „Team und Geräte“ wählt, wer man im Plan ist, wird
  benachrichtigt, wenn jemand die eigenen künftigen Dienste ändert. Dafür gleicht die App
  etwa alle 15 Minuten im Hintergrund ab (WorkManager, nur mit Netz). Die eigene Zeile
  ist im Plan hervorgehoben.

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
│   └── plan/     Wochenmodell (Stunden, Besetzung), deutsche Beschriftungen
├── mls/    Rust: MLS-Gruppenverschlüsselung (Marmot/MDK, OpenMLS) mit Kotlin-Anbindung (UniFFI)
├── app/    Android: Keystore, Sync-Steuerung, ViewModels, Compose-Oberfläche
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
| `(cd mls && cargo test)` | MLS-Abläufe mit echten SQLCipher-Datenbanken: Einladen und Beitreten, Nachrichten in beide Richtungen, entferntes Gerät liest nichts mehr, Admin-Rechte, zu früh oder in falscher Reihenfolge eintreffende Events, offene Einladung nach Neustart, Wettlauf zweier Commits, Signieren nur für Kind 5/22242, falscher Datenbankschlüssel, fremde und kaputte Eingaben |
| `./gradlew :core:test` | **Drei Geräte über drei lokale Nostr-Relays mit echtem TLS und echter MLS-Verschlüsselung**: Gründen, Beitreten per Code, ganzer Plan für neue Geräte, gleichzeitige Änderungen mit Konflikt, Entfernen, Austritt und Admin-Übergabe, Offline-Änderungen nach Neustart, Reparatur verlorener Nachrichten, Relays mit Anmeldung (NIP-42), unterbrochene Commits. Dazu Nachrichtenformat und Eingabeprüfung mit bösartigen Daten, Beitrittscode, CRDT-Merge (kommutativ, assoziativ, idempotent), NIP-01-ID, BIP-340-Testvektoren (offizielle CSV), manipulierte AES-GCM-Pakete, Kotlin-Anbindung der Rust-Bibliothek, TLS-Negativtests lokal (selbstsigniert, falscher Host, abgelaufen, fremde CA, Klartext) |
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
