# Sicherheit: Entscheidungen und Grenzen

## Was geschützt wird – und vor wem

| Schutzziel | Gegen | Wie |
|---|---|---|
| Vertraulichkeit von Namen und Schichten | Relays, Netzwerk-Beobachter, Dritte, entfernte Geräte | MLS-Gruppenverschlüsselung (RFC 9420, Marmot) mit neuen Schlüsseln bei jeder Gruppenänderung |
| Nur berechtigte Geräte lesen und schreiben | Dritte, Relays | Admins fügen Geräte einzeln hinzu und entfernen sie; MLS authentisiert jede Nachricht |
| Forward Secrecy, Erholung nach einer Kompromittierung | Spätere Schlüsseldiebstähle | Epochen, Schlüsselerneuerung nach dem Beitritt und alle 7 Tage |
| Konvergenz trotz Konflikten und Ausfällen | Gleichzeitige Änderungen, Netzabbrüche | LWW-CRDT mit hybrider Uhr, Digests und Übersichten, mehrere Relays |
| Schutz der Daten auf dem Gerät | Diebstahl ausgeschalteter Geräte, Backups | Android Keystore, verschlüsselte Dateien, SQLCipher, kein Backup |

Nicht geschützt: ein kompromittiertes oder entsperrt entwendetes Gerät, böswillige
Mitglieder, Metadaten (siehe „Grenzen“).

## Die wichtigsten Entscheidungen

**1. MLS statt gemeinsamem Geheimnis.** Bis zur Version mit Protokoll DP2 teilte ein Team
einen einzigen Schlüssel: Wer den Code hatte, war drin, und Entfernen ging nur über einen
neuen Code für alle. Jetzt ist ein Team eine MLS-Gruppe (Marmot über Nostr): Jedes Gerät hat
einen eigenen Schlüssel, Admins fügen Geräte hinzu oder entfernen sie, und jede Änderung der
Gruppe führt zu neuen Gruppenschlüsseln. Ein entferntes Gerät kann danach nichts Neues mehr
lesen.

**2. Keine eigene Kryptografie.** Alles Kryptografische kommt aus geprüften Bibliotheken:
MLS aus OpenMLS über das Marmot Development Kit (MDK), Nostr-Signaturen, NIP-44 und NIP-59 aus
rust-nostr, die verschlüsselte Datenbank aus SQLCipher, AES-256-GCM für lokale Dateien aus
Google Tink, der lokale Hauptschlüssel aus dem Android Keystore. Eigener Code (Rust-Schicht
`mls/`, Kotlin) verbindet nur die Bausteine und ist mit Abläufen über echte Datenbanken und
lokale TLS-Relays getestet (Einladen, Entfernen, Austritt, Wettläufe, Neustarts, fremde und
kaputte Eingaben). Die BIP-340-Testvektoren laufen weiterhin gegen libsecp256k1, das in den
Tests die Signaturen des Test-Relays unabhängig prüft.

**3. Beitritt in zwei Schritten.** Ein neues Gerät zeigt einen Beitrittscode: seinen
öffentlichen Schlüssel mit Prüfsumme. Der Code ist kein Geheimnis. Zugang bekommt das
Gerät erst, wenn ein Admin es damit hinzufügt **und** das Gerät die Einladung bestätigt.
Beide Seiten sehen Fingerabdrücke (die ersten 64 Bit des Schlüssels) und können sie
vergleichen. Wichtig bleibt: Der Admin muss sicher sein, dass der Code wirklich vom
richtigen Gerät stammt (persönlich oder über einen bekannten Kanal).

**4. Relays sehen keine Absender.** Gruppen-Events sind doppelt verschlüsselt (MLS und eine
äussere ChaCha20-Poly1305-Schicht) und mit einem Wegwerfschlüssel signiert. Ein Relay sieht
nur die Gruppen-ID im `h`-Tag, nicht, welches Gerät schreibt oder ob es ein Commit oder eine
Planänderung ist. Einladungen kommen als Gift Wrap (NIP-59) mit zufälligem Absender und
zurückdatiertem Zeitstempel.

**5. Forward Secrecy und Erholung.** Jeder Commit führt zu einer neuen Epoche mit neuen
Schlüsseln. Jedes Gerät erneuert seinen eigenen Schlüssel nach dem Beitritt und danach
spätestens alle 7 Tage. Wer später einen Gerätezustand stiehlt, kann ältere Nachrichten
jenseits der letzten fünf Epochen nicht entschlüsseln; nach der nächsten Erneuerung ist er
auch von neuen ausgeschlossen.

**6. Commits erst nach Bestätigung, mit Wartezeit.** Ein eigener Commit wird erst
übernommen, wenn ein Relay ihn bestätigt hat, und erst nach 2 Sekunden Wartezeit. Kommt
in dieser Zeit ein konkurrierender Commit derselben Epoche, der nach MIP-03 Vorrang hat,
verwirft das Gerät seinen eigenen und versucht es erneut. Die verwendete MDK-Version löst
solche Wettläufe sonst nur für fremde Commits auf (ein Rust-Test hält dieses Verhalten
fest). Wird die App mitten in einem Commit beendet, entscheidet der nächste Start anhand
der Relays, ob er übernommen oder verworfen wird. Den automatischen Austrittsvorschlag von
MLS nutzt die App aus demselben Grund nicht (siehe `PROTOKOLL.md`).

**7. CRDT statt Sperren.** Jeder Eintrag trägt Wert, Zeitstempel (hybride Uhr) und
Geräte-ID; bei Konflikten gewinnt der neuere Zeitstempel, dann die grössere Geräte-ID,
zuletzt der Wert. Damit ist das Zusammenführen kommutativ, assoziativ und idempotent, auch
bei fehlerhaften Daten. Zeitstempel über 24 h in der Zukunft werden abgelehnt.

**8. Strenge Eingabeprüfung.** Relays sind nicht vertrauenswürdig, und auch ein Mitglied
kann fehlerhafte Daten senden. Vor dem Parsen: Nachrichtengrösse (2 MB) und JSON-Tiefe.
Danach: Event-Format und Gruppe (genau ein passender `h`-Tag), Zeitstempel (> 0, höchstens
1 h in der Zukunft für Gruppen-Events), in der Nachricht Version, Typ, Bucket-Namen,
Digests, höchstens 5000 Einträge, keine doppelten Schlüssel, und pro Eintrag: Schlüssel per
Regex, echtes Datum 2000–2100, erlaubte Kürzel, Namen (max. 60 Zeichen, keine Steuer-,
Bidi- oder unsichtbaren Zeichen, keine halben Surrogate), Zeitstempel > 0 und ≤ jetzt + 24 h,
Geräte-ID, richtiger Bucket. Strukturfehler verwerfen die ganze Nachricht, einzelne
ungültige Einträge nur sich selbst. Was sich nicht entschlüsseln lässt, wird still
verworfen. ID und Signatur jedes Events prüft rust-nostr nach NIP-01.

**9. TLS ohne Hintertüren.** Nur `wss://`, nur TLS 1.3/1.2, Zertifikats- und
Hostnamenprüfung durch Plattform bzw. OkHttp, **nur vorinstallierte System-CAs** (keine vom
Nutzer oder per MDM installierten CAs), `usesCleartextTraffic=false` plus Network Security
Config, keine Weiterleitungen, Certificate Transparency ab Android 16. **Bewusst kein
Certificate Pinning:** Öffentliche Relays tauschen ihre Zertifikate regelmässig; Pinning
würde den Sync bei jedem Wechsel brechen. Bei einem Relay anmelden (NIP-42) tut sich ein
Gerät nur, wenn das Relay es verlangt.

**10. Umschlagverfahren für lokale Daten.** Ein nicht exportierbarer AES-256-Schlüssel im
Android Keystore (TEE, wenn vorhanden StrongBox) verschlüsselt einen zufälligen
Datenschlüssel. Damit sind Geräteschlüssel, Teamzustand, Plan und Einstellungen mit
AES-256-GCM verschlüsselt; der Dateiname ist Teil der AAD, geschrieben wird atomar. Der
MLS-Zustand liegt in einer SQLCipher-Datenbank, deren zufälliger Schlüssel ebenfalls nur
so verschlüsselt gespeichert ist. Alles liegt in `noBackupFilesDir`. Auf eine Entsperrung pro
Nutzung (Biometrie) wird zugunsten der Bedienbarkeit verzichtet.

**11. Kein Backup, keine Geräteübertragung.** `allowBackup=false`, `dataExtractionRules`
(Cloud-Backup und Gerätetransfer ab Android 12) und `fullBackupContent` (bis Android 11)
schliessen alles aus. Ein neues Handy wird wie ein neues Gerät hinzugefügt.

**12. Team-Bildschirme.** Teamseite, Beitritt und Einladung sind mit `FLAG_SECURE` gegen
Screenshots und die App-Übersicht geschützt. Der Beitrittscode steht nur gekürzt auf dem
Bildschirm (zum Vergleichen genügt der Fingerabdruck), das Eingabefeld nutzt eine
Passwort-Tastatur (keine Vorschläge, kein Lernen).

**13. Keine Geheimnisse in Logs, lokaler Absturzbericht.** Die App loggt nur in
Debug-Builds und nie Schlüssel, Klartext oder Namen; R8 entfernt `Log.v/d/i` im Release
zusätzlich. Abstürze werden lokal ohne sensible Daten gespeichert (Codes, Hex- und
Base64-Folgen entfernt, Meldungen von JSON-Parsern weggelassen). Beim nächsten Start
entscheidet die Nutzerin oder der Nutzer, ob der Bericht kopiert wird. Nichts wird
automatisch gesendet.

**14. Festgelegte Abhängigkeiten.** MDK und OpenMLS auf festen Commits, rust-nostr,
SQLCipher (mit eigenem OpenSSL gebaut), UniFFI/JNA, Tink, OkHttp, kotlinx, AndroidX.
`Cargo.lock` liegt im Repository, die Rust-Version ist in `rust-toolchain.toml` festgelegt,
Kotlin-Abhängigkeiten im Version Catalog, die Gradle-Distribution per SHA-256; die CI prüft
das Wrapper-JAR.

**15. Benachrichtigungen ohne Server.** Ob sich eigene Dienste geändert haben, berechnet
die App lokal nach einem Abgleich im Hintergrund (WorkManager, etwa alle 15 Minuten, nur mit
Netz). Kein Push-Dienst erfährt etwas. Auf dem gesperrten Bildschirm steht nur „Dein
Dienstplan hat sich geändert“, ohne Namen, Daten oder Schichten. Wer man im Plan ist,
speichert jedes Gerät verschlüsselt für sich; es wird nicht synchronisiert.

**16. Neue Eintragsarten mit denselben Regeln.** Schichtarten, Rhythmen, Notizen und Wünsche
sind gewöhnliche Einträge der LWW-Map und laufen durch dieselbe Prüfung: feste Formate statt
JSON (`v1|…` mit genau der erwarteten Zahl Felder), Kürzel nur A–Z/0–9 (1–3 Zeichen), Namen
höchstens 30 und Notizen höchstens 200 Zeichen ohne Steuer-, Bidi- oder unsichtbare Zeichen,
Zeiten `HH:MM`, Zahlen ohne führende Nullen und mit Obergrenzen, Farben nur aus der festen
Palette, Wünsche nur aus festen Codes (`WF`, `FW`, `NV`, `WA`, `WA:<art-id>`), Rhythmen 1–8
ganze Wochen, Ruhezeit 0–960 Minuten, Soll genau sieben Zahlen 0–99. Felder im Plan verweisen nur
auf gültige Schichtart-IDs; ob die Art schon bekannt ist, entscheidet die Anzeige („?“).

**17. Exporte verlassen die Verschlüsselung.** Woche oder Monat als PDF oder Bild und die
eigenen Dienste als Kalenderdatei entstehen nur auf Wunsch. Vor dem Teilen weist die App
darauf hin, dass die Datei nicht verschlüsselt ist. Die Dateien liegen im Cache-Ordner (nicht
im Backup), werden über einen FileProvider nur für diesen Ordner und nur mit befristetem
Leserecht weitergegeben und beim nächsten Start gelöscht. Die Kalenderdatei enthält keine
Mitglieds-IDs; die festen Termin-IDs sind daraus per SHA-256 abgeleitet.

**18. Widget nur mit eigener Auswahl.** Das Widget „Meine Dienste“ zeigt die nächsten fünf
Dienste der Person, die auf diesem Gerät unter „Ich“ gewählt ist, und die Stunden der Woche.
Es liest den Plan aus dem verschlüsselten Speicher der App und erscheint nur, wenn jemand es
selbst auf den Startbildschirm legt.

**19. Die Sperre des Plans liegt in MLS, nicht im Plan.** Wer sperren und öffnen darf, muss
fälschungssicher sein. Deshalb steht die Sperre in der Beschreibung der MLS-Gruppe: Sie
ändert sich nur durch einen Commit, und MDK nimmt Commits, die Gruppendaten ändern, nur von
Admin-Geräten an – beim Erzeugen wie beim Empfang. Ein Mitglied kann die Sperre also weder
setzen noch aufheben, auch nicht mit einer veränderten App. Unlesbare Sperren gelten als
offen, damit ein Formatfehler nie dazu führt, dass Geräte Einträge löschen.

**20. Durchsetzung der Sperre auf jedem Gerät nach derselben Regel.** Welche Einträge trotz
Sperre gelten, entscheidet jedes Gerät allein aus Eintrag und Sperre (Geräte-ID in der
Admin-Liste oder Zeitstempel vor dem Abschnitt). Weil die Regel deterministisch ist,
kommen alle Geräte zum selben Stand, auch wenn Nachrichten in anderer Reihenfolge ankommen.
Gilt ein Eintrag nach einer neuen Sperre nicht mehr, entfernt ihn jedes Gerät; der Abgleich
holt den vorherigen Wert vom sperrenden Admin zurück. Die Abschnitte mit eigenem Zeitpunkt
verhindern, dass ein Verlängern der Sperre Änderungen verwirft, die gemacht wurden, als der
Bereich noch offen war.

**21. Gleiche Rechte beim Eintragen.** Im offenen Plan dürfen alle Mitglieder alles, auch
Schichten. Die Sperre schränkt nur Schichten im gesperrten Bereich, Schichtarten,
Planungsregeln und das Löschen von Personen ein; Wünsche und Notizen bleiben für alle offen, damit niemand vom
Planen ausgeschlossen ist. Wünsche gehören der Person: Hat sie mit „Das bin ich“ ein eigenes
Gerät zugeordnet (Eintrag `u|<geräte-id>`), ändern ihre Wünsche nur dieses Gerät und Admins.
So kann niemand die Wünsche einer Kollegin überschreiben, und Personen ohne Handy können
trotzdem vertreten werden. Die Monatsansicht zeigt allen, wie viele Wünsche pro Person
erfüllt sind – Fairness wird sichtbar statt behauptet.

**22. Ruhezeit und Soll sind Hinweise, keine Sperren.** Zu kurze Ruhe zwischen zwei Diensten
und Schichten unter dem Soll markiert die App rot und zählt sie über dem Plan. Eintragen bleibt
möglich: Ausnahmen (Notfall, Tausch) entscheidet das Team, nicht die App. Während einer Sperre
ändern nur Admins diese Regeln, damit niemand Warnungen im fertigen Plan wegschaltet.

## Grenzen

- **Admins entscheiden, wer dazukommt.** Wer ein Admin-Gerät bedienen kann, kann Geräte
  hinzufügen und entfernen. Ein Admin, der einen falschen Code einträgt, holt ein fremdes
  Gerät ins Team – Fingerabdrücke vergleichen hilft.
- **Entfernte Geräte behalten, was sie schon gesehen haben.** Nach dem Entfernen lesen sie
  nichts Neues mehr; der bisherige Plan bleibt auf ihnen gespeichert.
- **Relays sehen Metadaten:** IP-Adressen, Zeitpunkte, Paketgrössen und die Gruppen-ID, die
  alle Events eines Teams verknüpft. Beim Beitritt sehen sie den öffentlichen Schlüssel des
  neuen Geräts (KeyPackage, Gift Wrap). Wer IP-Adressen verbergen will, braucht zusätzlich
  VPN oder Tor.
- **Böswillige Mitglieder** können Einträge überschreiben oder löschen und mit Zeitstempeln
  bis 24 h in der Zukunft Konflikte gewinnen. MLS weist nach, welches Gerät eine Nachricht
  geschickt hat; die Geräte-ID einzelner Einträge ist dagegen nicht authentisiert, weil
  Geräte auch die Einträge anderer weitergeben. Das gilt auch für Wünsche, Notizen und
  Schichtarten.
- **Die Sperre schützt vor Versehen, nicht vor Absicht.** Sperren und öffnen können nur
  Admins (MLS). Die Durchsetzung bei den Einträgen beruht aber auf Geräte-ID und Zeitstempel,
  die ein Mitglied mit einer veränderten App fälschen kann: als Admin-Gerät ausgeben oder
  auf einen Zeitpunkt vor der Sperre zurückdatieren. Das liesse sich nur mit einzeln
  signierten Einträgen und einer vertrauenswürdigen Zeitquelle ausschliessen, also mit einem
  Server. Mit der offiziellen App halten sich alle an die Sperre.
- **Wunschrechte prüft nur die App.** Dass Wünsche einer Person nur von ihren Geräten und von
  Admins kommen, prüft die App beim Eintragen, nicht beim Empfang. Die Zuordnung „Das bin
  ich“ setzt jedes Gerät selbst; zwei Geräte können sich derselben Person zuordnen. Die
  Geräteliste im Team zeigt deshalb, welches Gerät zu wem gehört.
- **Wechsel der Sperre:** Änderungen, die beim Sperren noch unterwegs waren, können
  verworfen werden, besonders bei falsch gehenden Uhren. Die App meldet eigene verworfene
  Änderungen.
- **Widget und Exporte:** Wer den entsperrten Startbildschirm sieht, sieht die Dienste im
  Widget. Geteilte PDFs, Bilder und Kalenderdateien sind unverschlüsselt und unterliegen
  danach den Regeln der Empfänger-App.
- **Ältere App-Versionen** im selben Team verwerfen Schichtarten, Notizen, Wünsche,
  Rhythmen und Felder mit eigenen Schichtarten, ebenso Wunscharbeitstage und Zuordnungen von
  Geräten. Die Sperre kennen sie nicht und lassen Änderungen zu, die neuere Geräte verwerfen.
  Alle Geräte sollten dieselbe Version nutzen.
- **Uhren:** Falsch gehende Uhren beeinflussen, welche Änderung bei gleichzeitigen
  Konflikten gewinnt.
- **Ruhezeit in Ortszeit:** Die Prüfung rechnet ohne Zeitumstellung; in der Nacht der
  Umstellung liegt sie um eine Stunde daneben. Sie ersetzt keine rechtliche Prüfung (z. B.
  Ausnahmen nach ArGV 2 im Gesundheitswesen oder die wöchentliche Ruhezeit).
- **Verfügbarkeit:** Öffentliche Relays können Events ablehnen, löschen oder nur begrenzt
  aufbewahren. Drei Relays und die Übersichten mildern das. Fehlt einem Gerät aber ein
  Commit endgültig oder war es länger als 45 Tage offline (Grenze von MDK), kann es neue
  Nachrichten nicht mehr lesen und muss neu hinzugefügt werden.
- **Gleichzeitige Gruppenänderungen:** Trifft ein konkurrierender, früherer Commit erst nach
  der Wartezeit von 2 Sekunden ein, verliert das Gerät mit dem späteren Commit den
  Anschluss. Die App erkennt das und zeigt es an; das Gerät muss dann neu hinzugefügt
  werden. Wird ein Gerät eingeladen, während gleichzeitig ein früherer Commit unterwegs ist,
  kann die Einladung ins Leere gehen (das Gerät erscheint nicht in der Geräteliste und wird
  einfach erneut hinzugefügt).
- **Fünf vergangene Epochen** bleiben lesbar, damit verspätete Nachrichten ankommen; so weit
  reicht die Forward Secrecy also nicht zurück.
- **Das Gerät selbst:** Wer das entsperrte Gerät in der Hand hat, sieht den Plan.
  Root-Zugriff oder Schadsoftware können Schlüssel aus dem Arbeitsspeicher lesen.
- **Grosse Nachrichten:** OkHttp liest eine WebSocket-Nachricht vollständig ein, bevor die
  2-MB-Grenze greift; ein böswilliges Relay kann so kurzzeitig Speicher belegen.
- **Abgleich bei geschlossener App nur etwa alle 15 Minuten.** Android kann ihn im
  Energiesparmodus verschieben. Echte Push-Nachrichten bräuchten einen eigenen Server.
  Änderungen bleiben lokal gespeichert und gehen beim nächsten Abgleich raus.
- **„Team verlassen“** bittet die Admins, das Gerät zu entfernen, und löscht dann alles
  Lokale. Ohne Verbindung geht die Bitte nicht raus; dann sollte ein Admin das Gerät von
  Hand entfernen. Die verschlüsselten Events auf den Relays bleiben bestehen.
