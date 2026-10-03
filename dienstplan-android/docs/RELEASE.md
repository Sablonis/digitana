# Signierte Release-APK (Signaturschemas v1, v2, v3)

Android installiert nur signierte APKs. Updates werden nur akzeptiert, wenn sie mit
**demselben Schlüssel** signiert sind und einen **höheren `versionCode`** haben. Der
Release-Schlüssel ist deshalb der wichtigste Besitz des Projekts: Geht er verloren, lässt
sich die App nicht mehr aktualisieren, nur deinstallieren und neu installieren (dabei gehen
die lokalen Daten verloren; das Team-Geheimnis muss neu per Code beitreten).

## 1. Schlüssel einmalig erzeugen

`keytool` gehört zum JDK (in Android Studio: `<Android Studio>/jbr/bin/keytool`).

```bash
mkdir -p ~/keys
keytool -genkeypair -v \
  -keystore ~/keys/dienstplan-release.jks \
  -storetype PKCS12 \
  -alias dienstplan \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=Dienstplan, O=digitana, C=CH"
```

- Die Keystore-Datei **ausserhalb des Repositorys** ablegen und zusätzlich sicher sichern
  (z. B. verschlüsselter Passwortmanager mit Dateianhang und ein Offline-Backup).
- Passwörter nicht in Skripte oder die Shell-History schreiben; `keytool` fragt sie ab.

## 2. Build konfigurieren

`keystore.properties.example` nach `keystore.properties` kopieren (steht in `.gitignore`)
und ausfüllen:

```properties
storeFile=/home/ich/keys/dienstplan-release.jks
storePassword=…
keyAlias=dienstplan
keyPassword=…
```

Alternativ (z. B. für CI) Umgebungsvariablen setzen:
`DIENSTPLAN_KEYSTORE`, `DIENSTPLAN_KEYSTORE_PASSWORD`, `DIENSTPLAN_KEY_ALIAS`,
`DIENSTPLAN_KEY_PASSWORD`.

In `app/build.gradle.kts` sind alle drei Schemas eingeschaltet:

```kotlin
enableV1Signing = true   // JAR-Signatur – ab minSdk 24 eigentlich unnötig, AGP liesse sie sonst weg
enableV2Signing = true   // APK Signature Scheme v2 (ganze Datei, ab Android 7)
enableV3Signing = true   // v3 mit Schlüsselrotation (ab Android 9)
```

## 3. Bauen

```bash
cd dienstplan-android
./gradlew clean :app:assembleRelease
```

Ergebnis: `app/build/outputs/apk/release/app-release.apk` – mit R8 verkleinert, auf 16 KB
ausgerichtet (für Geräte mit 16-KB-Speicherseiten) und signiert.

Fehlt die Signierkonfiguration, entsteht `app-release-unsigned.apk`. Diese Datei lässt sich
**nicht installieren** („App nicht installiert“).

## 4. Prüfen

`apksigner` liegt im Android SDK unter `build-tools/<Version>/`:

```bash
APKSIGNER="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)/apksigner"
"$APKSIGNER" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Erwartet:

```
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
...
Signer #1 certificate SHA-256 digest: 3f…a9
```

Den **SHA-256-Fingerabdruck des Zertifikats** und die Prüfsumme der Datei
(`sha256sum app-release.apk`) mit der APK weitergeben. So kann das Team prüfen, dass die
Datei unterwegs nicht verändert wurde.

## Alternative: nachträglich von Hand signieren

Falls eine unsignierte APK vorliegt (z. B. aus CI):

```bash
BT="$ANDROID_HOME/build-tools/<Version>"
# 1. Ausrichten VOR dem Signieren (v2/v3 decken die ganze Datei ab; danach darf nichts mehr geändert werden)
"$BT/zipalign" -P 16 -f -v 4 app-release-unsigned.apk app-release-aligned.apk
# 2. Signieren mit allen drei Schemas
"$BT/apksigner" sign \
  --ks ~/keys/dienstplan-release.jks --ks-key-alias dienstplan \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out app-release.apk app-release-aligned.apk
# 3. Prüfen
"$BT/apksigner" verify --verbose --print-certs app-release.apk
```

`-P 16` richtet native Bibliotheken auf 16 KB aus (Android 15+).

## Neue Version veröffentlichen

1. In `app/build.gradle.kts` `versionCode` erhöhen (z. B. 1 → 2) und `versionName` anpassen.
2. Mit **demselben** Keystore bauen und prüfen (siehe oben).
3. Die neue APK über die alte installieren – die Daten bleiben erhalten.
