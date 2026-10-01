# Sicherheit: Entscheidungen und Grenzen

## Was geschützt wird – und vor wem

| Schutzziel | Gegen | Wie |
|---|---|---|
| Vertraulichkeit von Namen und Schichten | Relays, Netzwerk-Beobachter, Dritte | Ende-zu-Ende-Verschlüsselung (AES-256-GCM), d-Tags als HMAC |
| Integrität, nur Team-Mitglieder ändern | Relays, Dritte | GCM-Authentisierung, BIP-340-Signatur, strenge Eingabeprüfung |
| Konvergenz trotz Konflikten und Ausfällen | Gleichzeitige Änderungen, Netzabbrüche | LWW-CRDT mit hybrider Uhr, Anti-Entropie, mehrere Relays |
| Schutz der Daten auf dem Gerät | Diebstahl ausgeschalteter Geräte, Backups | Android Keystore, verschlüsselte Dateien, kein Backup |

Nicht geschützt: ein kompromittiertes oder entsperrt entwendetes Gerät, Mitglieder mit dem
Code, Metadaten (siehe „Grenzen“).

## Die wichtigsten Entscheidungen

**1. Ein Team-Geheimnis statt Konten.** Kein Server, keine Registrierung: Wer den
Einladungscode hat, gehört zum Team. Das ist einfach und robust, bedeutet aber: Mitglieder
lassen sich nur durch einen Schlüsselwechsel entfernen, und niemand kann kryptografisch
nachweisen, welches Mitglied eine Änderung gemacht hat.

**2. Keine eigene Kryptografie.** Alle Primitive kommen aus geprüften Bibliotheken:
libsecp256k1 (über ACINQ secp256k1-kmp) für BIP-340-Schnorr, Google Tink für HKDF-SHA256 und
AES-256-GCM, die Java-Plattform für HMAC-SHA256 und SHA-256, der Android Keystore für den
lokalen Hauptschlüssel. Eigener Code setzt diese Bausteine nur zusammen und ist mit
offiziellen Testvektoren (BIP-340, RFC 5869) sowie Manipulationstests abgesichert.

**3. Schlüsselableitung mit Domänentrennung.** Aus dem zufälligen 32-Byte-Geheimnis werden
per HKDF-SHA256 (festes Salt, je ein eigenes `info`) drei unabhängige Schlüssel abgeleitet:
Nostr-Signaturschlüssel, AES-Schlüssel, HMAC-Schlüssel. Wird einer bekannt, verrät das
nichts über die anderen.

**4. AES-256-GCM mit Kontextbindung.** Jedes Event wird mit einer zufälligen 96-Bit-Nonce
verschlüsselt. Die Zusatzdaten `DP2|<d-Tag>` binden das Paket an seinen Bucket; der
Bucket-Name im Klartext muss zusätzlich zum d-Tag passen. Ein Relay kann Pakete also nicht
zwischen Wochen verschieben. GCM authentisiert: Pakete, die sich nicht entschlüsseln lassen,
stammen nicht aus dem Team und werden still verworfen. Nonce-Kollisionen sind bei den zu
erwartenden Mengen (einige tausend Events pro Schlüssel, Grenze 2³² bei zufälligen Nonces)
vernachlässigbar.

**5. Signaturen nach NIP-01.** Event-ID (SHA-256 über die exakte NIP-01-Serialisierung) und
BIP-340-Signatur werden bei jedem empfangenen Event neu berechnet und geprüft. Weil Relays
die Signatur ebenfalls prüfen, kann niemand ohne Team-Schlüssel die adressierbaren Events
des Teams überschreiben. Jede eigene Signatur wird nach dem Erzeugen gegengeprüft (Schutz vor
Rechenfehlern, die den Schlüssel verraten könnten).

**6. Verschleierte Metadaten.** Der d-Tag ist ein HMAC des Bucket-Namens. Relays sehen also
keine Kalenderwochen, sondern zufällig wirkende Kennungen. Die Anzahl der Buckets und der
Zeitpunkt von Änderungen bleiben sichtbar.

**7. CRDT statt Sperren.** Jeder Eintrag trägt Wert, Zeitstempel (hybride Uhr) und
Geräte-ID; bei Konflikten gewinnt der neuere Zeitstempel, dann die grössere Geräte-ID, zuletzt
der Wert. Damit ist das Zusammenführen kommutativ, assoziativ und idempotent, auch bei
fehlerhaften Daten. Zeitstempel über 24 h in der Zukunft werden abgelehnt, damit eine falsch
gestellte Uhr Konflikte nicht beliebig lange „gewinnt“.

**8. Strenge Eingabeprüfung.** Relays sind nicht vertrauenswürdig, und auch ein Mitglied
kann fehlerhafte Daten senden. Geprüft werden: Nachrichtengrösse (2 MB) und JSON-Tiefe vor dem
Parsen, JSON-Typen, Event-Format, genau ein d-Tag, Schlüssel per Regex, echtes Datum
2000–2100, erlaubte Kürzel, Namen (max. 60 Zeichen, keine Steuer-, Bidi- oder unsichtbaren
Zeichen, keine halben Surrogate), Zeitstempel > 0 und ≤ jetzt + 24 h, Geräte-ID, höchstens
5000 Einträge, jeder Eintrag im richtigen Bucket. Strukturfehler verwerfen das ganze Event,
einzelne ungültige Einträge nur sich selbst. Die Regeln benutzen feste Zeichenlisten statt
Unicode-Kategorien der Laufzeit, damit alle Android-Versionen gleich entscheiden – sonst
könnten Geräte dauerhaft auseinanderlaufen.

**9. TLS ohne Hintertüren.** Nur `wss://`, nur TLS 1.3/1.2, Zertifikats- und
Hostnamenprüfung durch Plattform bzw. OkHttp, **nur vorinstallierte System-CAs** (keine vom
Nutzer oder per MDM installierten CAs, die TLS-Proxys ermöglichen würden),
`usesCleartextTraffic=false` plus Network Security Config, keine Weiterleitungen,
Certificate Transparency ab Android 16. **Bewusst kein Certificate Pinning:** Öffentliche
Relays tauschen ihre Zertifikate regelmässig (z. B. Let's Encrypt); Pinning würde den Sync
bei jedem Wechsel brechen. Die Vertraulichkeit hängt ohnehin an der
Ende-zu-Ende-Verschlüsselung; TLS schützt vor allem Metadaten wie den abonnierten
Team-Schlüssel.

**10. Umschlagverfahren für lokale Daten.** Ein nicht exportierbarer AES-256-Schlüssel im
Android Keystore (TEE, wenn vorhanden StrongBox) verschlüsselt einen zufälligen
Datenschlüssel. Damit sind Team-Geheimnis und Plan mit AES-256-GCM verschlüsselt; der
Dateiname ist Teil der AAD, geschrieben wird atomar, alles liegt in `noBackupFilesDir`. Das
veraltete Jetpack-Security-Crypto wird nicht verwendet. Auf eine Entsperrung pro Nutzung
(Biometrie) wird zugunsten der Bedienbarkeit verzichtet; die Displaysperre ist die erste
Schutzschicht.

**11. Kein Backup, keine Geräteübertragung.** `allowBackup=false`, `dataExtractionRules`
(Cloud-Backup und Gerätetransfer ab Android 12) und `fullBackupContent` (bis Android 11)
schliessen alles aus. Das Team-Geheimnis verlässt das Gerät nur als Einladungscode.

**12. Umgang mit dem Einladungscode.** 256 Bit Zufall plus 4 Byte Prüfsumme gegen
Tippfehler. Weitergabe über das Android-Teilen-Menü; beim Kopieren wird der Inhalt als
vertraulich markiert (Android 13+ zeigt ihn nicht in der Vorschau). Auf dem Bildschirm steht
er nur gekürzt, die Code-Bildschirme sind mit `FLAG_SECURE` gegen Screenshots und die
App-Übersicht geschützt, und das Eingabefeld nutzt eine Passwort-Tastatur (keine
Vorschläge, kein Lernen).

**13. Keine Geheimnisse in Logs, lokaler Absturzbericht.** Die App loggt nur in
Debug-Builds und nie Schlüssel, Klartext oder Namen; R8 entfernt `Log.v/d/i` im Release
zusätzlich. Abstürze werden lokal ohne sensible Daten gespeichert (Einladungscodes, Hex- und
Base64-Folgen entfernt, Meldungen von JSON-Parsern weggelassen). Beim nächsten Start
entscheidet die Nutzerin oder der Nutzer, ob der Bericht kopiert wird. Nichts wird
automatisch gesendet.

**14. Wenige, bekannte Abhängigkeiten.** secp256k1-kmp (ACINQ), Tink (Google), OkHttp
(Square), kotlinx, AndroidX. Versionen stehen fest im Version Catalog, die
Gradle-Distribution ist per SHA-256 festgelegt, und die CI prüft das Wrapper-JAR.

**15. Benachrichtigungen ohne Server.** Ob sich eigene Dienste geändert haben, berechnet
die App lokal nach einem Abgleich im Hintergrund (WorkManager, etwa alle 15 Minuten, nur mit
Netz). Kein Push-Dienst erfährt etwas. Auf dem gesperrten Bildschirm steht nur „Dein
Dienstplan hat sich geändert“, ohne Namen, Daten oder Schichten. Wer man im Plan ist,
speichert jedes Gerät verschlüsselt für sich; es wird nicht synchronisiert.

## Grenzen

- **Wer den Code hat, hat Zugriff** – lesend und schreibend. Der Code ist der Schlüssel.
- **Entfernte Mitglieder behalten, was sie schon gesehen haben.** Nach „Neuen Code
  erstellen“ sehen sie keine neuen Änderungen mehr. Die alten Events liegen aber weiter
  verschlüsselt auf den Relays und bleiben mit dem alten Code lesbar.
- **Relays sehen Metadaten:** IP-Adressen, Zeitpunkte, Paketgrössen, Anzahl der Buckets
  und den öffentlichen Team-Schlüssel, der alle Events eines Teams verknüpft. Wer
  IP-Adressen verbergen will, braucht zusätzlich VPN oder Tor.
- **Keine Forward Secrecy:** Wer den Code später erlangt, kann alle Events entschlüsseln,
  die Relays noch speichern.
- **Keine Urheberschaft:** Alle Geräte signieren mit demselben Schlüssel; die Geräte-ID ist
  nicht authentisiert. Ein böswilliges Mitglied kann Einträge überschreiben oder löschen
  und mit Zeitstempeln bis 24 h in der Zukunft Konflikte gewinnen.
- **Uhren:** Falsch gehende Uhren beeinflussen, welche Änderung bei gleichzeitigen
  Konflikten gewinnt.
- **Verfügbarkeit:** Öffentliche Relays können Events ablehnen, löschen oder nur begrenzt
  aufbewahren. Drei Relays und die Anti-Entropie mildern das; eine Garantie gibt es nicht.
  Manche Relays begrenzen die Event-Grösse (oft 64 KB) – das reicht für eine Woche mit
  etwa 80 Personen.
- **Das Gerät selbst:** Wer das entsperrte Gerät in der Hand hat, sieht den Plan.
  Root-Zugriff oder Schadsoftware können Schlüssel aus dem Arbeitsspeicher lesen.
- **Grosse Nachrichten:** OkHttp liest eine WebSocket-Nachricht vollständig ein, bevor die
  2-MB-Grenze greift; ein böswilliges Relay kann so kurzzeitig Speicher belegen.
- **Abgleich bei geschlossener App nur etwa alle 15 Minuten.** Android kann ihn im
  Energiesparmodus verschieben. Echte Push-Nachrichten bräuchten einen eigenen Server.
  Änderungen bleiben lokal gespeichert und gehen beim nächsten Abgleich raus.
- **„Team verlassen“** löscht nur die Daten auf diesem Gerät. Die verschlüsselten Events auf
  den Relays bleiben bestehen.
