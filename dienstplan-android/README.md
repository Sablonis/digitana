# Dienstplan – Android-App

Schichtplan für kleine Teams, der **ohne eigenen Server** zwischen den Handys synchronisiert:
in Echtzeit, konfliktfrei (CRDT) und **Ende-zu-Ende-verschlüsselt** über öffentliche
Nostr-Relays. Die App funktioniert auch offline; Änderungen werden später gesendet.

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
- Team: „Neues Team“, „Beitreten“ per Code, „Neuen Code erstellen“ (Schlüsselwechsel),
  „Team verlassen“. Beim Beitreten mit vorhandenen Daten fragt die App, ob diese
  übernommen oder verworfen werden.
- Statusanzeige („Live · 3/3 Relays“) und Diagnose pro Relay.

## Aufbau

```
dienstplan-android/
├── core/   reines Kotlin/JVM – ohne Android-SDK baubar und testbar
│   ├── crypto/   HKDF, AES-256-GCM (Tink), BIP-340-Schnorr (secp256k1-kmp), Einladungscode
│   ├── crdt/     Einträge, LWW-Map, hybride Uhr, Schlüssel, Buckets, Eingabeprüfung
│   ├── nostr/    Events nach NIP-01, Relay-Nachrichten
│   ├── sync/     Sync-Engine (Anti-Entropie, OK-Auswertung, Backoff), TLS-Client
│   ├── data/     Plan- und Team-Repository, verschlüsselter Dateispeicher
│   └── plan/     Wochenmodell (Stunden, Besetzung), deutsche Beschriftungen
├── app/    Android: Keystore, Sync-Steuerung, ViewModels, Compose-Oberfläche
└── docs/   PROTOKOLL.md, SICHERHEIT.md, RELEASE.md, INSTALLATION.md
```

Schichten: **UI** (Compose) → **ViewModel** → **Repository** (CRDT-Speicher) → **Sync-Engine**
→ **Krypto**. Jede Schicht ist einzeln testbar; die gesamte Logik liegt in `:core`.

## Voraussetzungen

- Android Studio mit Unterstützung für AGP 9.4 (aktuelle stabile Version)
- **JDK 21** als Gradle-JDK (Android Studio: *Settings → Build, Execution, Deployment →
  Build Tools → Gradle → Gradle JDK*, das mitgelieferte JBR 21 genügt)
- Android SDK Platform 37 (Android Studio lädt fehlende Pakete beim Sync nach)

Verwendete Versionen: AGP 9.4.1, Kotlin 2.4.20, Gradle 9.7.1, Compose BOM 2026.09.00,
OkHttp 5.5.0, secp256k1-kmp 0.24.0, Tink 1.23.0 (siehe `gradle/libs.versions.toml`).

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
./gradlew :app:assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug             # auf angeschlossenes Gerät installieren
./gradlew :app:lintDebug                # Android-Lint
./gradlew :app:assembleRelease          # Release mit R8 (signiert, wenn konfiguriert)
```

Signierte Release-APK (Signaturschemas v1, v2, v3): **[docs/RELEASE.md](docs/RELEASE.md)**.
Installation per Sideloading und Hilfe bei „App nicht installiert“:
**[docs/INSTALLATION.md](docs/INSTALLATION.md)**.

## Tests

| Befehl | Inhalt |
|---|---|
| `./gradlew :core:test` | BIP-340-Testvektoren (offizielle CSV), HKDF-Testfälle aus RFC 5869, manipulierte und fremde AES-GCM-Pakete, CRDT-Merge (kommutativ, assoziativ, idempotent), Eingabeprüfung mit bösartigen Daten, NIP-01-ID/Signatur, Sync-Regeln (Wiederholung abgelehnter Events, max. 5 Versuche, 1 Event/Bucket/Sekunde, Anti-Entropie, Live-Events), **drei simulierte Geräte über drei lokale Nostr-Relays mit echtem TLS** (gleichzeitige Änderungen, Konflikt, später Beitritt, Relay-Ausfall, Datenverlust, Paging), TLS-Negativtests lokal (selbstsigniert, falscher Host, abgelaufen, fremde CA, Klartext) |
| `./gradlew :core:networkTest` | **Drei Geräte über die echten Relays** (damus, nos.lol, primal) mit gleichzeitigen Änderungen, Konflikt und spätem Beitritt; **TLS-Negativtests gegen expired/wrong.host/self-signed/untrusted-root.badssl.com** sowie TLS 1.0/1.1. Braucht eine direkte Internetverbindung (kein TLS-aufbrechender Proxy). Andere Relays: `-Pdienstplan.relays=wss://a,wss://b,wss://c` |
| `./gradlew :app:connectedDebugAndroidTest` | Auf Gerät/Emulator: Android-Keystore (Rundlauf, Manipulation, gelöschter Schlüssel, StrongBox-Rückfall), verschlüsselte Dateien, TLS-Negativtests mit dem Android-Trust-Store und der Network Security Config |

Die BIP-340-Vektoren 15–18 (Nachrichten ≠ 32 Byte) werden übersprungen: secp256k1-kmp
signiert nur 32-Byte-Nachrichten, und Nostr signiert ausschliesslich 32-Byte-Event-IDs.

## Weitere Dokumente

- [docs/PROTOKOLL.md](docs/PROTOKOLL.md) – Datenmodell, Schlüsselableitung, Event-Format, Sync-Regeln
- [docs/SICHERHEIT.md](docs/SICHERHEIT.md) – Sicherheitsentscheidungen und ihre Grenzen
- [docs/RELEASE.md](docs/RELEASE.md) – signierte Release-APK
- [docs/INSTALLATION.md](docs/INSTALLATION.md) – Sideloading, Samsung Auto Blocker, Play Protect, Fehlersuche
