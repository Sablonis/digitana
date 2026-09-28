# Sync-Protokoll „DP2“

Technische Beschreibung für Review und spätere Clients. Referenzimplementierung: `core/`.

## Schlüssel

```
geheimnis  = 32 zufällige Bytes (SecureRandom)
salt       = "ch.digitana.dienstplan/DP2/hkdf-salt"
nostrKey   = HKDF-SHA256(geheimnis, salt, "dienstplan/v2/nostr-secp256k1", 32)
             (ungültiger Skalar → info + "/1", "/2", … – Wahrscheinlichkeit ≈ 2^-128)
aesKey     = HKDF-SHA256(geheimnis, salt, "dienstplan/v2/aes-256-gcm", 32)
hmacKey    = HKDF-SHA256(geheimnis, salt, "dienstplan/v2/d-tag-hmac-sha256", 32)
pubkey     = x-only-Public-Key von nostrKey (BIP-340), 64 Hex-Zeichen
```

Einladungscode: `"DP2-" + Base64url_ohne_Padding(geheimnis ‖ SHA-256(geheimnis)[0..4])`
(48 Zeichen nach dem Präfix).

## Datenmodell (LWW-Map)

| Schlüssel | Wert | Bucket |
|---|---|---|
| `m\|<id>` | Name (1–60 Zeichen), `""` = gelöscht | `team` |
| `z\|<id>\|<JJJJ-MM-TT>` | `F`, `S`, `N`, `X`, `U`, `""` = leer | ISO-Woche des Datums, z. B. `2026-W39` |

- `<id>`: 16 Hex-Kleinbuchstaben (64 Bit Zufall). Datum 2000-01-01 bis 2100-12-31.
- Jeder Eintrag: `(wert, zeitstempel_ms, geräte_id)`; Geräte-ID = 16 Hex-Kleinbuchstaben.
- Ordnung: Zeitstempel, dann Geräte-ID (lexikografisch), dann Wert. Merge = Maximum pro Schlüssel.
- Hybride Uhr: lokal `neu = max(jetzt, letzter + 1)`; beim Empfang `letzter = max(letzter, ts)`.

## Event (NIP-01, Kind 30078 nach NIP-78)

```json
{
  "kind": 30078,
  "pubkey": "<team-pubkey>",
  "created_at": <sekunden>,
  "tags": [["d", "<hex(HMAC-SHA256(hmacKey, \"dienstplan/bucket/\" + bucket))>"]],
  "content": "<Base64(nonce12 ‖ AES-256-GCM(aesKey, klartext, aad) ‖ tag16)>",
  "id": "<sha256 der NIP-01-Serialisierung>",
  "sig": "<BIP-340-Signatur über id>"
}
```

- `aad = "DP2|" + d-Tag` (ASCII).
- Klartext (UTF-8-JSON): `{"v":2,"b":"<bucket>","e":[["<schlüssel>","<wert>",<ms>,"<gerät>"], …]}`,
  Einträge nach Schlüssel sortiert. Pro Bucket genau ein adressierbares Event, das den
  **vollständigen** bekannten Stand des Buckets enthält.

## Prüfung beim Empfang

1. Nachricht ≤ 2 MB (UTF-8), JSON-Tiefe begrenzt, strikt geparst.
2. `kind == 30078`, `pubkey == Team-Pubkey`, genau ein Tag `["d", <64 Hex>]`,
   `0 < created_at ≤ jetzt + 24 h`.
3. ID neu berechnen und vergleichen, BIP-340-Signatur prüfen.
4. Entschlüsseln (GCM prüft Echtheit) – schlägt das fehl: still verwerfen.
5. Klartext: `v == 2`, `b` gültiger Bucket und `HMAC(b) == d-Tag`, höchstens 5000 Einträge,
   keine doppelten Schlüssel, korrekte JSON-Typen – sonst ganzes Event verwerfen.
6. Pro Eintrag: Schlüssel-Regex, Datum, Wert, `0 < ts ≤ jetzt + 24 h`, Geräte-ID, Bucket
   passt – sonst nur diesen Eintrag verwerfen.

## Sync-Ablauf pro Relay

1. Verbinden (`wss://`, TLS 1.2+), `REQ` mit `{"kinds":[30078],"authors":[pubkey],"limit":500}`.
2. Gespeicherte Events verarbeiten und zusammenführen. Kommen ≥ 20 Events, wird mit
   `until = ältestes created_at` weitergeblättert, bis keine neuen mehr kommen (einmal auch
   an einer Sekunde vorbei, falls sich viele Events dieselbe Sekunde teilen).
3. **Nach EOSE (Anti-Entropie):** Für jeden lokalen Bucket prüfen, ob das Relay denselben
   Stand hat (Digest der gültigen Einträge seines neuesten Events). Wenn nicht: den
   zusammengeführten Stand senden.
4. **Live:** Neue Events zusammenführen. Fehlen einem Live-Event lokal bekannte Einträge,
   geht der zusammengeführte Stand zurück an das Relay.
5. **Senden:** `created_at = max(jetzt, letztes_eigenes + 1, neuestes_gesehenes + 1)`,
   höchstens ein Event pro Bucket und Sekunde, insgesamt höchstens 50 Events pro Sekunde
   (Team-Bucket und Wochen um heute zuerst).
6. **OK-Antworten:** `true` bzw. `duplicate:` → Relay hat diesen Stand. `false` → erneut mit
   neuerem `created_at`, höchstens 5 Versuche pro Relay, Bucket und Stand; `blocked:`,
   `restricted:`, `pow:`, `invalid:`, `auth-required:` gelten als endgültig,
   `rate-limited:` pausiert das Relay kurz. Ohne OK nach 15 s zählt ein Fehlversuch.
7. Verbindungsabbruch → neu verbinden mit exponentiellem Backoff (1 s … 60 s, Jitter),
   nach Netzwechsel sofort.

Weil jedes Event den ganzen Bucket enthält und Relays pro (kind, pubkey, d-Tag) nur das
neueste behalten, reicht dieser Abgleich, damit alle Geräte und Relays beim selben Stand
ankommen.
