# Sync-Protokoll „DP3“ (MLS über Nostr)

Ein Team ist eine MLS-Gruppe (RFC 9420) nach dem Marmot-Protokoll: Jedes Gerät hat einen
eigenen Schlüssel, Admins fügen Geräte hinzu oder entfernen sie, und jede Änderung der
Gruppe führt zu neuen Gruppenschlüsseln. Die Kryptografie stammt vollständig aus dem
Marmot Development Kit (MDK), OpenMLS und rust-nostr (Ordner `mls/`); die App liefert
Events an diese Bibliothek und spricht selbst mit den Relays.

## Schlüssel pro Gerät

| Schlüssel | Herkunft | Zweck |
|---|---|---|
| Identitätsschlüssel (secp256k1, 32 Byte) | zufällig beim ersten Gründen oder Beitreten | signiert KeyPackages, ist die Identität des Geräts in der Gruppe, Absender von Löschanfragen (NIP-09) und Relay-Anmeldungen (NIP-42) |
| Datenbankschlüssel (32 Byte) | zufällig | verschlüsselt die SQLCipher-Datenbank mit dem MLS-Zustand |
| MLS-Gruppenschlüssel | MLS (Epochen) | verschlüsseln Gruppen-Events; ändern sich bei jedem Commit |

Identitäts- und Datenbankschlüssel liegen nur im verschlüsselten Dateispeicher der App
(Keystore, Datei `device.bin`). Nach „Team verlassen“ werden sie gelöscht; ein neues Team
bekommt neue Schlüssel, Teams sind so nicht miteinander verknüpfbar.

**Geräte-ID** in Planeinträgen: die ersten 16 Hex-Zeichen des öffentlichen Schlüssels.

## Beitreten

1. Das neue Gerät erzeugt Schlüssel und ein KeyPackage (Kind 30443, signiert, als
   „last resort“ markiert, also mehrfach verwendbar) und veröffentlicht es auf allen Relays. Es zeigt seinen
   **Beitrittscode**: `DP3-` + Base64url(öffentlicher Schlüssel (32 Byte) + erste 4 Byte von
   SHA-256(Schlüssel)), 48 Zeichen. Der Code ist kein Geheimnis.
2. Ein Admin gibt den Code ein. Die App lädt die KeyPackages dieses Schlüssels von den
   Relays (`kinds: [30443], authors: [<schlüssel>]`), erzeugt einen Commit (Gerät
   hinzufügen) und veröffentlicht ihn (Kind 445). Nach der ersten Bestätigung eines Relays
   und einer kurzen Wartezeit (siehe „Commits“) übernimmt das Admin-Gerät den Commit und
   schickt die Einladung (MLS-Welcome, Kind 444) als Gift Wrap (NIP-59, Kind 1059) an das
   neue Gerät. Danach sendet es den ganzen Plan, weil das neue Gerät ältere Nachrichten
   nicht lesen kann.
3. Das neue Gerät abonniert Gift Wraps an sich (`#p`) und zeigt die Einladung mit Teamname,
   Fingerabdruck des einladenden Geräts und Anzahl Geräte. Erst nach Bestätigung tritt es
   bei. Liegt schon ein Plan auf dem Gerät, wählt die Nutzerin oder der Nutzer, ob er
   übernommen wird. Danach lässt das Gerät sein verbrauchtes KeyPackage löschen (Kind 5)
   und erneuert seinen MLS-Schlüssel (Self-Update).

## Gruppen-Events (Kind 445)

Alle Nachrichten der Gruppe sind Kind-445-Events mit `h`-Tag (Nostr-Gruppen-ID). Sie sind
doppelt verschlüsselt (MLS und eine äussere ChaCha20-Poly1305-Schicht mit einem Schlüssel
aus dem MLS-Exporter) und mit einem Wegwerfschlüssel signiert. Relays sehen weder Absender
noch Inhalt noch Art (Commit oder Nachricht).

Ein Gerät abonniert `kinds: [445], #h: [<gruppe>], since: <stand − 3 h>` und blättert mit
`until` zurück, bis eine Seite weniger als 20 Events bringt. Erst danach gehen alle
gesammelten Events gemeinsam an die MLS-Schicht, die sie nach `created_at` und ID sortiert.
Events einer noch unbekannten Epoche (der zugehörige Commit fehlt noch) werden
zurückgestellt und nach jedem übernommenen Commit erneut versucht. Der gespeicherte Stand
(`cursor`) springt nie über ein zurückgestelltes Event hinweg. Ältere Events als die
Einladung bzw. die Gründung werden gar nicht erst abgefragt.

## Anwendungsnachrichten (innerhalb von MLS, Kind 30078)

JSON, Version 3. Absender und Mitgliedschaft prüft MLS.

**Stand** (`t = "s"`): Einträge eines oder mehrerer Buckets, jeweils mit dem Digest, den der
ganze Bucket beim Absender danach hat (erste 16 Hex-Zeichen von SHA-256 über alle Einträge):

```json
{"v":3,"t":"s","p":[{"b":"2026-W39","h":"0123456789abcdef","e":[["z|<id>|2026-09-21","F",1790000000000,"<gerät>"]]}]}
```

Ein Teil kann eine Änderung (nur die geänderten Einträge), einen ganzen Bucket oder – mit
leerer Liste – eine Bitte um den Stand der anderen sein. Höchstens 200 Einträge pro
Nachricht (≈ 15 KB, unter den üblichen Grenzen der Relays); grosse Buckets werden geteilt.

**Übersicht** (`t = "g"`): Digests aller Buckets im Fenster ±52 Wochen plus `team`:

```json
{"v":3,"t":"g","from":"2025-W40","to":"2027-W40","d":{"team":"0123456789abcdef","2026-W39":"fedcba9876543210"}}
```

**Austritt** (`t = "x"`): `{"v":3,"t":"x"}` – der Absender bittet die Admins, ihn zu
entfernen.

## Abgleich

- **Eigene Änderungen** bleiben „ausstehend“ (auch über einen Neustart), bis ein Relay die
  Nachricht bestätigt hat (`OK`). Sie gehen gebündelt nach 0,5 s raus, sobald das Gerät mit
  mindestens einem Relay abgeglichen ist.
- **Vergleich:** Nach dem Zusammenführen eines fremden Stands vergleicht das Gerät seinen
  Digest mit `h`. Weicht er ab, schickt es nach einer zufälligen Wartezeit (0,5–4 s) seinen
  ganzen Bucket – ausser ein anderes Gerät hat inzwischen genau diesen Stand geschickt
  (dann wäre die eigene Antwort doppelt). Teile mit verworfenen Einträgen lösen keinen
  Vergleich aus (sonst könnten sich Geräte mit unterschiedlichen Prüfregeln endlos
  antworten). Pro Bucket höchstens eine Antwort in 10 s.
- **Übersicht:** Alle 6 Stunden (und nach einem Rücksprung) schickt ein Gerät eine
  Übersicht. Wer für einen Bucket einen anderen Digest hat, antwortet mit seinem Stand; wer
  den Bucket nicht hat, antwortet mit einer leeren Bitte. So werden auch Nachrichten
  nachgeholt, die Relays verloren haben.
- **Tempo:** höchstens 8 eigene Events pro Relay und Sekunde (hält auch die Reihenfolge der
  MLS-Nachrichten eines Geräts nahe an der Zeitreihenfolge). `rate-limited:` pausiert das
  Relay 10 s.

## Commits

Hinzufügen, Entfernen, Admin-Rechte und Schlüsselerneuerung sind MLS-Commits. Ablauf:

1. Commit erzeugen; ab jetzt ruht die Verarbeitung fremder Gruppen-Events.
2. Veröffentlichen; ohne Bestätigung eines Relays innerhalb von 30 s wird er verworfen.
3. **2 s warten.** Trifft in dieser Zeit ein anderer Commit derselben Epoche ein, der nach
   MIP-03 Vorrang hat (früheres `created_at`, bei Gleichstand die kleinere ID), wird der
   eigene verworfen und neu versucht. Grund: Die verwendete MDK-Version löst Wettläufe nur
   für fremde Commits auf; einen bereits übernommenen eigenen Commit setzt sie nicht zurück.
4. Übernehmen, danach die zurückgehaltenen Events verarbeiten.

Die Event-ID eines laufenden Commits wird gespeichert. Wird die App mittendrin beendet,
entscheidet der nächste Start anhand der Relays: Liegt der Commit dort (und hat kein
früherer Vorrang), wird er übernommen, sonst verworfen.

**Austritt:** Die App nutzt den MLS-Austrittsvorschlag (SelfRemove) bewusst nicht, weil
dessen automatische Bestätigung in der verwendeten MDK-Version keinen
Wiederherstellungspunkt anlegt. Stattdessen gibt ein Admin-Gerät zuerst seine Rechte ab
(das letzte Admin-Gerät muss vorher ein anderes ernennen), schickt dann die Bitte `t = "x"`
und löscht alles Lokale. Admins entfernen das Gerät gestaffelt nach Rang (0 s, 5 s, …), damit
nicht alle gleichzeitig committen. Wird eine Entfernung durch einen Rücksprung aufgehoben,
wiederholt das Admin-Gerät sie.

**Schlüsselerneuerung:** nach dem Beitritt und danach alle 7 Tage, jeweils erst, wenn eine
Minute lang kein Commit kam (damit sich Commits selten kreuzen).

## Prüfung beim Empfang

- Relay-Nachrichten: höchstens 2 MB und 8 Ebenen JSON, strikter Parser.
- Gruppen-Events: Kind 445, genau ein `h`-Tag mit der eigenen Gruppe, `created_at` > 0 und
  höchstens 1 h in der Zukunft, Inhalt ≤ 2 MB. ID, Signatur, Entschlüsselung, Epoche und
  Mitgliedschaft prüft die MLS-Schicht; was sie nicht lesen kann, wird still verworfen.
- Anwendungsnachrichten: Version, Typ, höchstens 5000 Einträge, Bucket-Namen, Digests
  (16 Hex), keine doppelten Schlüssel, JSON-Tiefe ≤ 5. Strukturfehler verwerfen die ganze
  Nachricht. Einzelne Einträge werden wie bisher geprüft: Schlüssel per Regex, echtes
  Datum 2000–2100, erlaubte Kürzel, Namen max. 60 Zeichen ohne Steuer-, Bidi- oder
  unsichtbare Zeichen, Zeitstempel > 0 und ≤ jetzt + 24 h, Geräte-ID, richtiger Bucket.
- Gift Wraps: nur an den eigenen Schlüssel; Einladungen nur während eines Beitritts.

## Datenmodell (LWW-Map)

Unverändert gegenüber DP2: Schlüssel `m|<id>` (Name), `z|<id>|<JJJJ-MM-TT>` (Kürzel) und neu
`d|<geräte-id>` (Name eines Geräts in der Geräteliste), jeweils mit Wert, Zeitstempel der
hybriden Uhr und Geräte-ID. Buckets: `team` (Personen, Gerätenamen) und je eine ISO-Woche.
Bei Konflikten gewinnt der neuere Zeitstempel, dann die grössere Geräte-ID, zuletzt der Wert.

## Relays

Standard: `wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.primal.net`. Verlangt ein
Relay eine Anmeldung (NIP-42, `auth-required:`), meldet sich das Gerät mit seinem
Identitätsschlüssel an und abonniert erneut – nur dann, nicht vorsorglich.
