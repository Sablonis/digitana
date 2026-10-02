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

Hinzufügen, Entfernen, Admin-Rechte, die Sperre des Plans und Schlüsselerneuerung sind
MLS-Commits. Ablauf:

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

## Sperre des Plans

Admins können den Plan sperren und wieder öffnen. Die Sperre steht in der **Beschreibung der
MLS-Gruppe** (Marmot-Erweiterung `NostrGroupData`). Diese ändert nur ein Commit, und MDK
nimmt solche Commits nur von Admin-Geräten an (beim Erzeugen und beim Empfang). Format:

```json
{"v":1,"lock":{"seg":[{"until":"2026-10-31","since":1790000000000},{"since":1790500000000}],
 "admins":["0123456789abcdef"],"by":"0123456789abcdef"}}
```

- Gesperrt sind alle Tage bis `until` des letzten Abschnitts; fehlt es, der ganze Plan.
  Leere Beschreibung oder fehlendes `lock` = offen.
- **Abschnitte:** Jede Erweiterung hängt einen Abschnitt an, gültig ab ihrem Zeitstempel
  `since` (hybride Uhr des sperrenden Admins). Schon gesperrte Tage behalten ihren Zeitpunkt,
  bei einer Verkürzung fallen Tage wieder heraus. Höchstens 12 Abschnitte; darüber werden die
  ältesten zusammengelegt (mit dem jüngeren Zeitpunkt).
- `admins`: Geräte-IDs, deren Einträge trotz Sperre gelten – alle Admins seit Beginn der
  Sperre. Wird während der Sperre ein Gerät zum Admin, kommt es im selben Commit dazu.
- Unlesbares (anderes Format, `v` ≠ 1, mehr als 4096 Byte, ungültige Daten) gilt als offen.
  Ein Gerät, das eine Sperre nicht versteht, löscht deshalb nie Einträge.

**Was geschützt ist:** Schichten (`z|…`) an gesperrten Tagen, Schichtarten (`s|…`),
Planungsregeln (`c|…`, `b|…`) und das Löschen von Personen (`m|…` mit leerem Wert), solange
eine Sperre besteht. Wünsche, Notizen,
Rhythmen, Gerätenamen, Zuordnungen und neue Personen bleiben für alle offen.

**Regel für Einträge** (auf jedem Gerät gleich, für empfangene und gespeicherte): Ein
geschützter Eintrag gilt, wenn seine Geräte-ID ein aktuelles Admin-Gerät oder in `admins`
ist, oder wenn sein Zeitstempel ≤ `since` des Abschnitts ist, der den Tag sperrt (für
Schichtarten, Regeln und Personen: des ältesten Abschnitts). Sonst wird er beim Empfang verworfen
(Diagnose „Wegen Sperre“); ein Teil mit solchen Einträgen löst keinen Vergleich aus.

**Wechsel der Sperre:** Ändert sich die Sperre oder die Admin-Liste, entfernt jedes Gerät die
Einträge, die nicht mehr gelten, ganz aus der LWW-Map (kein Tombstone). Danach bittet es mit
einer Reparatur um den Stand der anderen: Wer den Eintrag nie übernommen hat – mindestens
das sperrende Admin-Gerät –, schickt den vorherigen, gültigen Wert zurück. Eigene verworfene
Änderungen meldet die App. Damit eigene Einträge nach dem Sperren nicht als „älter“ gelten,
zieht jedes Gerät seine Uhr auf den jüngsten Abschnitt nach.

Lokal schreibt die App geschützte Einträge nur als Admin; Sammeländerungen (Woche kopieren,
Rhythmus) laufen ganz oder gar nicht.

## Prüfung beim Empfang

- Relay-Nachrichten: höchstens 2 MB und 8 Ebenen JSON, strikter Parser.
- Gruppen-Events: Kind 445, genau ein `h`-Tag mit der eigenen Gruppe, `created_at` > 0 und
  höchstens 1 h in der Zukunft, Inhalt ≤ 2 MB. ID, Signatur, Entschlüsselung, Epoche und
  Mitgliedschaft prüft die MLS-Schicht; was sie nicht lesen kann, wird still verworfen.
- Anwendungsnachrichten: Version, Typ, höchstens 5000 Einträge, Bucket-Namen, Digests
  (16 Hex), keine doppelten Schlüssel, JSON-Tiefe ≤ 5. Strukturfehler verwerfen die ganze
  Nachricht. Einzelne Einträge werden einzeln geprüft: Schlüssel per Regex, echtes Datum
  2000–2100, gültige Schichtart-IDs, Formate der Schichtarten und Rhythmen (siehe oben),
  Namen max. 60 und Notizen max. 200 Zeichen ohne Steuer-, Bidi- oder unsichtbare Zeichen,
  nur die Wunsch-Codes (siehe unten), Zeitstempel > 0 und ≤ jetzt + 24 h, Geräte-ID,
  richtiger Bucket. Danach gilt die Regel der Sperre (siehe oben).
- Gift Wraps: nur an den eigenen Schlüssel; Einladungen nur während eines Beitritts.

## Datenmodell (LWW-Map)

Jeder Eintrag hat Wert, Zeitstempel der hybriden Uhr und Geräte-ID. Bei Konflikten gewinnt
der neuere Zeitstempel, dann die grössere Geräte-ID, zuletzt der Wert. Leerer Wert =
gelöscht bzw. leer.

| Schlüssel | Bucket | Wert |
|---|---|---|
| `m\|<id>` | `team` | Name einer Person (max. 60 Zeichen) |
| `d\|<geräte-id>` | `team` | Name eines Geräts in der Geräteliste |
| `u\|<geräte-id>` | `team` | Person, der das Gerät gehört („Das bin ich“): ihre ID |
| `c\|rest` | `team` | Mindestruhezeit zwischen zwei Diensten in Minuten (0–960, `0` = keine Warnung, leer = 11 h) |
| `b\|<art-id>` | `team` | Soll-Besetzung pro Wochentag, Montag zuerst: `3,3,3,3,3,2,2` (je 0–99) |
| `s\|<art-id>` | `team` | Schichtart: `v1\|<kürzel>\|<name>\|<beginn>\|<ende>\|<pause>\|<art>\|<farbe>\|<anrechnung>\|<flags>` |
| `r\|<rhythmus-id>` | `team` | Rhythmus: `v1\|<name>\|<art-id>,<art-id>,…` (leere Stelle = Tag frei lassen) |
| `z\|<id>\|<JJJJ-MM-TT>` | Woche | ID einer Schichtart |
| `n\|<JJJJ-MM-TT>` | Woche | Notiz zum Tag (max. 200 Zeichen) |
| `n\|<id>\|<JJJJ-MM-TT>` | Woche | Notiz zum Dienst einer Person (max. 200 Zeichen) |
| `w\|<id>\|<JJJJ-MM-TT>` | Woche | Wunsch: `WF` (Wunschfrei), `FW` (Ferienwunsch), `NV` (nicht verfügbar), `WA` (Wunscharbeitstag), `WA:<art-id>` (Wunschschicht) |

Buckets: `team` und je eine ISO-Woche (`2026-W40`). `<id>` und `<geräte-id>` sind 16
Hex-Zeichen, Rhythmus-IDs 8 Hex-Zeichen.

**Schichtarten.** Die IDs `F`, `S`, `N`, `X` und `U` sind die Standardarten Früh (6–14 Uhr),
Spät (14–22 Uhr), Nacht (22–6 Uhr), Frei und Urlaub; ohne Eintrag `s|…` gelten ihre
Standardwerte, ein leerer Eintrag setzt sie zurück. Eigene Arten haben eine zufällige ID aus
8 Hex-Zeichen. Felder im Plan verweisen auf die ID, nicht auf das Kürzel; Kürzel, Name,
Zeiten und Farbe lassen sich deshalb ändern, ohne Einträge umzuschreiben. Archivierte Arten
(Flag `a`) bleiben sichtbar, werden aber nicht mehr angeboten.

- Kürzel 1–3 Zeichen A–Z/0–9, Name 1–30 Zeichen ohne `|`
- Beginn und Ende `HH:MM` oder beide leer (ganztägig); Ende ≤ Beginn = über Mitternacht
- Pause 0–480 Minuten, Anrechnung 0–1440 Minuten (für Arten ohne Zeiten)
- Art `W` (Arbeit, zählt zur Besetzung), `F` (frei) oder `A` (abwesend); Farbe 0–11

Stunden: Dauer minus Pause, ohne Zeiten die Anrechnung.

**Wünsche.** Ein Wunsch gilt als erfüllt, wenn die eingetragene Schicht passt: bei `WF`,
`FW` und `NV` keine Arbeitsschicht, bei `WA` eine Arbeitsschicht, bei `WA:<art-id>` genau
diese Art. Ohne Eintrag ist er offen. Wünsche einer Person ändern ihre eigenen Geräte
(`u|…`) und Admins; hat eine Person kein Gerät, alle. Diese Regel prüft die App beim
Eintragen, nicht beim Empfang (siehe SICHERHEIT.md).

**Ruhezeit und Soll.** Die App warnt, wenn zwischen zwei Arbeitsdiensten einer Person
weniger Ruhe liegt als `c|rest` (Standard 11 h nach Art. 15a ArG). Es zählen nur
Arbeitsschichten mit Zeiten; ein Dienst über Mitternacht endet am Folgetag, gerechnet wird in
Ortszeit. Weil ein Dienst höchstens 24 h dauert und die Grenze höchstens 16 h beträgt, genügen
die zwei Tage davor. `b|<art-id>` legt fest, wie viele Personen eine Arbeitsschicht pro
Wochentag braucht; die Besetzungszeile zeigt Ist/Soll. Beides sind nur Hinweise: Die App
verhindert keine Einträge.

**Kompatibilität.** Die neuen Schlüssel ergänzen DP3. Geräte mit der ersten DP3-Version
verwerfen sie (und Felder mit eigenen Schichtarten) als ungültig. Weil Teile mit verworfenen
Einträgen keinen Vergleich auslösen, entsteht dabei kein Hin und Her; alle Geräte sollten aber
dieselbe Version nutzen. Das gilt auch für `WA`, `WA:<art-id>`, `u|…`, `c|…` und `b|…`. Ältere Versionen
kennen die Sperre nicht: Sie lassen Änderungen zu, die neuere Geräte verwerfen.

## Relays

Standard: `wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.primal.net`. Verlangt ein
Relay eine Anmeldung (NIP-42, `auth-required:`), meldet sich das Gerät mit seinem
Identitätsschlüssel an und abonniert erneut – nur dann, nicht vorsorglich.
