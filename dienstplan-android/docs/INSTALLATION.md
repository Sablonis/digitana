# Installation per Sideloading

Die App wird nicht über den Play Store verteilt, sondern als APK-Datei. So klappt die
Installation auf Android 8 bis 17.

## Schritt für Schritt

1. **Richtige Datei verwenden:** eine signierte `app-release.apk` (siehe `RELEASE.md`) oder
   zum Testen `app-debug.apk` aus `./gradlew :app:assembleDebug`. Keine
   `app-release-unsigned.apk`, keine `.aab`-Datei, keine APK aus dem „Run“-Knopf von
   Android Studio (siehe Ursachen unten).
2. **Datei aufs Handy bringen**, z. B. per USB, Cloud-Speicher oder Messenger. Wenn möglich
   die SHA-256-Prüfsumme mit der Angabe der Person vergleichen, die gebaut hat.
3. **„Unbekannte Apps installieren“ erlauben** – nicht global, sondern für die App, mit der
   die APK geöffnet wird (Dateien/Files, Chrome, Messenger …):
   - Android 8 und neuer: Beim ersten Öffnen der APK erscheint ein Hinweis → *Einstellungen*
     → *Dieser Quelle vertrauen* bzw. *Aus dieser Quelle zulassen* aktivieren → zurück.
   - Manuell: *Einstellungen → Apps → (Menü) Spezieller App-Zugriff → Unbekannte Apps
     installieren* → die Datei-App wählen → *Aus dieser Quelle zulassen*.
   - Samsung: *Einstellungen → Apps → ⋮ → Spezieller Zugriff → Unbekannte Apps installieren*.
   - Nach der Installation die Erlaubnis wieder entziehen.
4. **Samsung Auto Blocker** (One UI 6 und neuer) blockiert Apps aus anderen Quellen als
   Galaxy Store und Play Store vollständig – oft ohne verständliche Meldung:
   *Einstellungen → Sicherheit und Datenschutz → Auto Blocker* → vorübergehend
   **ausschalten**, installieren, danach wieder einschalten. (Ist der „Maximale Schutz“ aktiv,
   lässt er sich nur dort abschalten.)
5. **Google Play Protect** prüft unbekannte Apps. Bei „Von Play Protect blockiert“ bzw.
   „Unsichere App blockiert“: *Details* → *Trotzdem installieren*. Wenn das nicht angeboten
   wird: *Play Store → Profilbild → Play Protect → Einstellungen (Zahnrad) → „Apps mit Play
   Protect scannen“* vorübergehend ausschalten und nach der Installation wieder einschalten.
   Play Protect kann anbieten, die App zur Prüfung an Google zu senden; das ist optional.
6. Installieren, öffnen, „Neues Team“ oder mit dem Code beitreten.

### Entwickler-Verifizierung von Google (2026/2027)

Google führt eine Pflicht zur Entwickler-Verifizierung für Apps auf zertifizierten
Android-Geräten ein. Stand Ende September 2026: Ab dem 30. September 2026 gilt sie in
Brasilien, Indonesien, Singapur und Thailand – und zunächst nur für Installationen aus
teilnehmenden App-Stores. Direktes Sideloading ist davon **noch nicht** betroffen; die
weltweite Einführung ist für 2027 angekündigt. Danach gibt es drei Wege:

- **Konto zur eingeschränkten Verteilung** (kostenlos, ohne amtlichen Ausweis, für bis zu
  20 Geräte) über die Android Developer Console – für ein kleines Team passend.
- Nutzerinnen und Nutzer richten einmalig den **„Advanced Flow“** ein, der die Installation
  von Apps nicht verifizierter Entwickler erlaubt.
- **`adb install`** bleibt ohne Registrierung möglich.

Aktuellen Stand prüfen: https://developer.android.com/developer-verification

## „App nicht installiert“ – Ursachen und Lösungen

Die Meldung verschweigt fast immer den eigentlichen Grund. Den erfährt man am schnellsten
mit einem Rechner und USB-Debugging:

```bash
adb install -r app-release.apk
# Beispielausgabe: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package ch.digitana.dienstplan signatures do not match newer version]
```

(USB-Debugging: *Einstellungen → Über das Telefon → 7× auf „Build-Nummer“ tippen →
Entwickleroptionen → USB-Debugging*.) Zusätzlich hilfreich:
`adb logcat | grep -i -E "PackageManager|PackageInstaller"`.

| Fehlercode (adb) | Ursache | Lösung |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Eine Version mit **gleichem Paketnamen, aber anderem Signaturschlüssel** ist installiert – z. B. Debug-Build von einem anderen PC (jeder PC hat einen eigenen Debug-Schlüssel) oder früherer Release mit anderem Keystore. **Häufigste Ursache.** | Alte App deinstallieren (lokale Daten gehen verloren; danach mit Code neu beitreten) oder immer mit demselben Keystore signieren. Debug- und Release-Build haben hier bewusst verschiedene Paket-IDs (`…dienstplan.debug`) und stören sich nicht. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` trotz Deinstallation | Die App ist noch **für einen anderen Nutzer, das Arbeitsprofil oder den Sicheren Ordner (Samsung)** installiert. | *Einstellungen → Apps → Dienstplan → ⋮ → Für alle Nutzer deinstallieren*, bzw. `adb uninstall ch.digitana.dienstplan`. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Die installierte Version hat einen **höheren `versionCode`**. | Neuere APK verwenden oder vorher deinstallieren. |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | APK **unsigniert** (`app-release-unsigned.apk`) oder **nach dem Signieren verändert** (z. B. erst signiert, dann `zipalign`). | Signiert bauen (`RELEASE.md`); `zipalign` immer vor `apksigner`. Prüfen mit `apksigner verify --verbose`. |
| `INSTALL_FAILED_TEST_ONLY` | APK aus dem **„Run“-Knopf von Android Studio** (enthält `android:testOnly="true"`). | APK mit `./gradlew :app:assembleDebug` bzw. `assembleRelease` bauen; testOnly-APKs gehen nur mit `adb install -t`. |
| `INSTALL_FAILED_OLDER_SDK` | Gerät älter als **Android 8.0** (minSdk 26). | Gerät mit Android 8 oder neuer verwenden. |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Native Bibliothek (secp256k1) fehlt für die Prozessorarchitektur, z. B. bei ABI-Splits. | Die normale APK enthält arm64-v8a, armeabi-v7a, x86 und x86_64 – keine Splits verwenden. |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Zu wenig Speicher. | Speicher freigeben. |
| `INSTALL_PARSE_FAILED_NOT_APK` / „Beim Parsen des Pakets ist ein Problem aufgetreten“ | **Unvollständiger Download**, Datei beschädigt oder ein `.aab` statt `.apk`. | Neu übertragen, Prüfsumme vergleichen, APK statt AAB verwenden. |
| keine Meldung, Installation bricht einfach ab | **Samsung Auto Blocker** oder **Play Protect** blockiert still; oder die Installer-App darf keine unbekannten Apps installieren. | Schritte 3–5 oben. |
| „App wurde nicht installiert, da das Paket mit einem vorhandenen Paket in Konflikt steht“ | Wie `UPDATE_INCOMPATIBLE`. | Siehe erste Zeile. |

**Vermutlich** war bei der früheren Version einer der ersten drei Fälle die Ursache: ein
Update mit anderem Schlüssel (Debug ↔ Release oder anderer PC), eine unsignierte
Release-APK oder eine APK aus dem „Run“-Knopf. Diese Version beugt vor: Debug hat eine
eigene Paket-ID, Release wird immer mit allen drei Signaturschemas signiert, und
`RELEASE.md` beschreibt die Prüfung mit `apksigner`.

### Prüfen, was in der APK steckt

```bash
BT="$ANDROID_HOME/build-tools/<Version>"
"$BT/aapt2" dump badging app-release.apk | grep -E "package:|sdkVersion|native-code"
"$BT/apksigner" verify --verbose --print-certs app-release.apk
adb shell pm list packages | grep dienstplan        # schon installiert?
adb shell dumpsys package ch.digitana.dienstplan | grep -E "versionCode|signatures"
```
