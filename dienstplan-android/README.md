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

**Planen**

- **Vier Reiter:** Woche, Monat, Ich und Team. Eigenes Farbschema (Indigo mit Koralle), hell,
  dunkel oder wie das System, auf Wunsch mit den Farben des Hintergrundbilds (Material You).
  Schrift Inter (in der App enthalten), Zahlen mit fester Breite.
- **Woche:** Wischen wechselt die Woche, „KW“ oder „Heute“ springt zurück. Die eigene Zeile
  steht oben und bleibt beim Scrollen sichtbar; heute, Wochenenden und Feiertage sind
  hinterlegt. Tipp auf ein Feld: Schicht, Wunsch, Notiz, Abgeben oder Tauschen; langes Drücken
  leert das Feld. Tipp auf einen Tag: Besetzung, wer fehlt, Wünsche, Feiertag, Notiz und
  „Ich übernehme“ für offene Schichten. Stunden pro Person, Besetzung pro Tag (Ist/Soll),
  „Woche kopieren“, „Rhythmus anwenden“, Rasterdichte (mit Zeiten oder nur Kürzel).
- **Schnell eintragen:** Schicht wählen, dann Felder antippen oder mit dem Finger über eine
  Zeile fahren; jedes neue Feld gibt einen kurzen haptischen Tick, der letzte Strich lässt sich
  rückgängig machen.
- **Plan vorschlagen:** füllt leere Felder der Woche bis zur Soll-Besetzung und beachtet
  Wünsche, Abwesenheiten, Ruhezeit, höchstens sechs Tage am Stück und eine faire Verteilung.
  Vorschau mit Lücken; übernehmen, verwerfen oder danach rückgängig machen.
- **Monat:** alle Personen über den ganzen Monat, Stunden, Saldo, Besetzung und Wunsch-Bilanz.
- **Ruhezeit und Soll-Besetzung:** Warnung bei weniger als 11 Stunden Ruhe (einstellbar oder
  aus), rot umrandet und mit Symbol; Soll pro Schicht und Wochentag, Tage darunter rot.
- **Feiertage nach Kanton:** offline berechnet für alle 26 Kantone, sichtbar im Raster, beim
  Tag, unter „Ich“ und angerechnet beim Soll. Feiertage einzelner Gemeinden fehlen.
- **Pensum, Soll und Saldo:** Wochenstunden bei vollem Pensum und Pensum pro Person ergeben
  Soll und Saldo pro Monat und seit Jahresbeginn. Die **Auswertung** zeigt pro Person Stunden,
  Saldo, Wochenend-, Nacht- und Feiertagsdienste sowie erfüllte Wünsche – für eine faire
  Verteilung, nicht als Lohnabrechnung.
- **Schichtarten** (Kürzel, Name, Zeiten, Pause, Art, angerechnete Stunden, Farbe) und
  **Rhythmen** über 1–8 Wochen, anwendbar auf bis zu 52 Wochen.
- **Rückgängig für jede Änderung** über die Meldung am unteren Rand.

**Mitmachen – alle mit gleichen Rechten**

- **Ich (Meine Dienste):** eigene Dienste der nächsten 8 Wochen, Stunden, Saldo, nächster
  Dienst, Feiertage, **Wunschkalender** (Wunschfrei, Ferienwunsch, nicht verfügbar,
  Wunscharbeitstag, Wunschschicht) und Export als .ics.
- **Gleiche Rechte:** Im offenen Plan tragen alle alles ein. Wünsche gehören der Person: Hat
  sie auf ihrem Gerät „Das bin ich“ gewählt, ändern sie nur ihre Geräte und Admins.
- **Planungsrunde:** Admins setzen pro Monat eine **Wunschfrist**; ein Hinweis im Plan und eine
  Erinnerung zwei Tage vorher (für alle ohne Wünsche) sorgen dafür, dass niemand sie verpasst.
  Sperrt ein Admin danach den Plan, gilt er als **veröffentlicht** und alle erhalten eine
  Benachrichtigung.
- **Offene Dienste:** Fehlt jemand gegenüber dem Soll, genügt „Ich übernehme“ (im Plan, beim
  Tag und unter „Ich“).
- **Tauschen und Abgeben:** Einen Dienst zur Abgabe anbieten (alle können ihn übernehmen) oder
  einer bestimmten Person einen Tausch vorschlagen; sie nimmt an oder lehnt ab. Ist der Tag
  gesperrt, bestätigt ein Admin die Übernahme oder den Tausch. Alles Offene steht unter „Ich“
  in „Tausch und Abgabe“.
- **Plan sperren (Admins):** bis Ende dieser oder nächster Woche, dieses oder nächsten Monats
  oder ganz; jederzeit wieder öffnen. Gesperrt ändern nur Admins Schichten, Schichtarten,
  Regeln, Pensum und Personen; Wünsche, Notizen, Angebote und Tauschvorschläge bleiben offen.

**Überblick und Hinweise**

- **Was ist neu?** Ein Punkt markiert Änderungen anderer, die hier noch niemand angesehen hat
  (14 Tage), eine Wolke eigene Änderungen, die noch kein Relay bestätigt hat. Die **Aktivität**
  listet, wer was eingetragen hat, mit Sprung in die Woche.
- **Erinnerungen** vor dem eigenen Dienst (am Vorabend um 19 Uhr, 2 oder 1 Stunde vorher) und
  vor Wunschfristen – geplant auf dem Gerät, ohne Server.
- **Benachrichtigungen** bei Änderungen an den eigenen Diensten, bei Tauschvorschlägen,
  Antworten, abgegebenen Diensten, Bestätigungen für Admins und veröffentlichten Plänen. Dafür
  gleicht die App etwa alle 15 Minuten im Hintergrund ab (WorkManager, nur mit Netz).
- **Gerätekalender (auf Wunsch):** eigene Dienste in einem lokalen Kalender „Dienstplan“, der
  sich selbst nachführt und mit keinem Konto abgeglichen wird.
- **Teilen** von Woche oder Monat als PDF oder Bild, **Widget** „Meine Dienste“.

**Team und Sicherheit**

- **Gründen oder beitreten:** Das neue Gerät zeigt seinen Beitrittscode als **QR-Code** und als
  Text; ein Admin scannt ihn unter „Team“ → „Gerät hinzufügen“ (Kamera nur während des
  Scannens) oder fügt ihn ein. Danach fragt die App gleich „Wer bist du?“.
- **Personen und Geräte:** Der Team-Reiter zeigt die Personen mit ihren Geräten darunter,
  Geräte ohne Person und Personen ohne Gerät, Fingerabdrücke, Admin-Rechte und „Team
  verlassen“. Ein entferntes Gerät zeigt den Plan nur noch lesend an.
- **MLS im Hintergrund:** Schlüsselerneuerung nach dem Beitritt und alle 7 Tage, Abgleich
  verlorener Nachrichten, Auflösung gleichzeitiger Gruppenänderungen (`docs/PROTOKOLL.md`).
- Statusanzeige („Live“) und Diagnose pro Relay.

**Bedienung**

- **Einführung:** kurze Tipps beim ersten Öffnen (Wischen, Felder, Schnell eintragen) und
  Leerzustände mit dem nächsten Schritt.
- **Bewegung:** Seitenübergänge mit vorausschauender Zurück-Geste (Predictive Back), sanfte
  Farbwechsel, federnde Rückmeldung beim Antippen.
- **Barrierefreiheit:** Tippflächen ab 48 dp (ausser den Feldern im Wochenraster), grosse
  Schrift lässt die Zeilen mitwachsen, TalkBack liest Felder vollständig vor und bietet
  Aktionen an (z. B. „Tag öffnen“), Ruhezeit-Warnung als Symbol und nicht nur als Farbe.
- **Tablet und Querformat:** Navigationsleiste am Rand ab 600 dp, ab 840 dp Raster und Details
  nebeneinander; im Querformat zeigt das Raster die Zeiten.
- **Einstellungen:** Thema, Systemfarben, Rasterdichte, Benachrichtigungen, Erinnerungen,
  Gerätekalender, Tipps, Lizenzen.

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
│   └── plan/     Woche, Monat, „Meine Dienste“, Rhythmen, Feiertage, Soll und Saldo,
│                 Tausch und Abgabe, Plan-Vorschlag, Aktivität, Erinnerungen, Kalender-Export
├── mls/    Rust: MLS-Gruppenverschlüsselung (Marmot/MDK, OpenMLS) mit Kotlin-Anbindung (UniFFI)
├── app/    Android: Keystore, Sync-Steuerung, ViewModels, Compose-Oberfläche, Export, Widget,
│           Erinnerungen und Benachrichtigungen (notify/), Gerätekalender (calendar/),
│           QR-Code und Scanner (CameraX, ZXing); Screenshot- und Barrierefreiheitstests
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
OkHttp 5.5.0, Tink 1.23.0, JNA 5.19.1, CameraX 1.6.2, ZXing 3.5.4, Robolectric 4.17,
Roborazzi 1.76.0 (siehe `gradle/libs.versions.toml`); Rust 1.94.1,
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
./gradlew :app:testDebugUnitTest        # Screenshots und Barrierefreiheit (Robolectric)
./gradlew :app:assembleRelease          # Release mit R8 (signiert, wenn konfiguriert)
```

### Automatischer Build (GitHub Actions)

Der Workflow `.github/workflows/dienstplan-android.yml` (im Wurzelverzeichnis des
Repositorys) läuft bei jedem Push, der `dienstplan-android/` betrifft: Kern-Tests,
Debug-APK, Lint, Release-Build mit R8 (unsigniert, ohne Keystore) sowie Screenshot- und
Barrierefreiheitstests. Die Debug-APK liegt danach als Artefakt `dienstplan-debug-apk` beim
jeweiligen Lauf, die Screenshots als `dienstplan-screenshots`.

Signierte Release-APK (Signaturschemas v1, v2, v3): **[docs/RELEASE.md](docs/RELEASE.md)**.
Installation per Sideloading und Hilfe bei „App nicht installiert“:
**[docs/INSTALLATION.md](docs/INSTALLATION.md)**.

## Tests

| Befehl | Inhalt |
|---|---|
| `(cd mls && cargo test)` | MLS-Abläufe mit echten SQLCipher-Datenbanken: Einladen und Beitreten, Nachrichten in beide Richtungen, entferntes Gerät liest nichts mehr, Admin-Rechte, Teambeschreibung (Sperre) nur durch Admins, zu früh oder in falscher Reihenfolge eintreffende Events, offene Einladung nach Neustart, Wettlauf zweier Commits, Signieren nur für Kind 5/22242, falscher Datenbankschlüssel, fremde und kaputte Eingaben |
| `./gradlew :core:test` | **Drei Geräte über drei lokale Nostr-Relays mit echtem TLS und echter MLS-Verschlüsselung**: Gründen, Beitreten per Code, ganzer Plan für neue Geräte, gleichzeitige Änderungen mit Konflikt, Entfernen, Austritt und Admin-Übergabe, Offline-Änderungen nach Neustart, Reparatur verlorener Nachrichten, Relays mit Anmeldung (NIP-42), unterbrochene Commits, **Sperre des Plans** (nur Admins sperren, Mitglieder tragen nur noch Wünsche ein, Verstösse verschwinden auf allen Geräten, Öffnen gibt frei). Dazu Format und Regeln der Sperre, Wunschrechte, Wunscharbeitstage und Wunschschichten mit Erfüllungsstatus, Rückgängig, Schichtarten, Notizen, Wünsche und Rhythmen (Formate, kaputte Werte, Überschreiben von Standardarten), Monatsansicht, „Meine Dienste“, Anwenden von Rhythmen, Kalender-Export nach RFC 5545 (UTC, Zeilenumbruch), Nachrichtenformat und Eingabeprüfung mit bösartigen Daten, Beitrittscode, CRDT-Merge (kommutativ, assoziativ, idempotent), NIP-01-ID, BIP-340-Testvektoren (offizielle CSV), manipulierte AES-GCM-Pakete, Kotlin-Anbindung der Rust-Bibliothek, TLS-Negativtests lokal (selbstsigniert, falscher Host, abgelaufen, fremde CA, Klartext) |
| `./gradlew :core:networkTest` | **Drei Geräte über die echten Relays** (damus, nos.lol, primal) mit MLS: Gründen, Beitreten per Code, gleichzeitige Änderungen mit Konflikt; **TLS-Negativtests gegen expired/wrong.host/self-signed/untrusted-root.badssl.com** sowie TLS 1.0/1.1. Braucht eine direkte Internetverbindung (kein TLS-aufbrechender Proxy). Andere Relays: `-Pdienstplan.relays=wss://a,wss://b,wss://c` |
| `./gradlew :app:testDebugUnitTest` | **Screenshots und Barrierefreiheit auf der JVM** (Robolectric, Roborazzi): Wochenraster eines Beispielteams hell, dunkel, mit grosser Schrift, auf kleinem Handy und Tablet, Hinweise und Einstellungen; Bilder in `app/build/outputs/roborazzi`. Das Accessibility Test Framework von Google prüft Beschriftung, Kontrast und Tippflächen (Fehler lassen den Test scheitern), zusätzlich braucht jedes antippbare Element einen Text |
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

Zur Leistung: `app/src/main/baseline-prof.txt` ist ein von Hand geschriebenes Baseline Profile
(Start, Wochenraster, Plan-Modell). Android kompiliert diese Teile im Release-Build bei der
Installation vor; Debug-Builds nutzen es nicht. Ein mit Macrobenchmark erzeugtes Profil wäre
genauer, braucht aber ein Gerät oder einen Emulator in der CI.
