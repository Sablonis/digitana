---
title: "Offene Entscheide und blockierende Fragen"
status: draft
decision_status: open
owner: Raphael Bollag
created: 2026-07-23
updated: 2026-07-23
project: digitana
source_basis:
  - repository
  - auftragsprompt
assumptions: []
open_questions: []
---

# Offene Entscheide und blockierende Fragen

Deliverable 12. Getrennt nach **blockierend** (verhindert den nächsten
Umsetzungsschritt) und **nicht blockierend** (mit reversibler Arbeitsannahme
weiterführbar).

## Blockierend

### B1 – Verhältnis zum bestehenden Code `[OFFENE ENTSCHEIDUNG]`

Das Repository enthält heute eine Infomaniak-/Soziokratie-Plattform, nicht
digitana. Bevor Domänencode entsteht, muss entschieden sein:

- **A (empfohlen):** Neuaufbau als Teilhabe-Plattform, technisches Gerüst
  behalten, Circle-/Infomaniak-Teile archivieren/entfernen.
- **B:** Auf bestehenden Modellen aufbauen (widerspricht mehreren Nicht-Zielen).
- **C:** Zwei getrennte Projekte (dann Zielrepository/-verzeichnis angeben).

Details und Empfehlung: `decisions/ADR-000-relationship-to-existing-code.md`.
**Arbeitsannahme bis zum Entscheid:** A – es wird jedoch **kein** bestehender
Code gelöscht, nur Dokumentation erstellt.

## Nicht blockierend (mit Arbeitsannahme)

| ID | Frage | Arbeitsannahme `[ARBEITSANNAHME]` | Später zu entscheiden |
|---|---|---|---|
| N1 | Login-Verfahren | Passwortloser E-Mail-Link **und** klassischer Login parallel testen | nach Nutzbarkeitstest (ADR-002) |
| N2 | Hosting Pilot | Schweizer VPS; Raspberry Pi nur Dev/Staging ohne Personendaten | ADR-003 |
| N3 | Lizenz | offen; Vorschlag AGPL-3.0 (Gemeinnützigkeit, Copyleft) | vor erster Veröffentlichung |
| N4 | UI-Interaktivität | Server-Rendering + progressive Enhancement; HTMX nur bei Bedarf | pro Feature |
| N5 | Sprache/Locale | de-CH, Europe/Zurich, «ss» statt «ß» | keiner |
| N6 | Datei-Uploads | im MVP deaktiviert | separater DS-/Sicherheitsentscheid |
| N7 | KI-Funktionen | keine im MVP | spätere, eng begrenzte Option |

## Zu bestätigende Planungswerte `[HISTORISCHE PLANUNG]`

Nicht als Zusagen verwenden, bis von Raphael bestätigt: Pilotstandort Riehen,
Pilotbudget ~CHF 40'000, zehn Teilnehmende, zwölf Monate Pilotdauer,
Vereinsgründung, konkrete Partner-/Förderorganisationen.

## Fragen an die Projektleitung

1. **B1 entscheiden:** Variante A, B oder C?
2. Sollen die historischen Planungswerte (Budget, Standort, Zeitrahmen) als
   Annahmen weitergeführt oder aktualisiert werden?
3. Gibt es weitere Projektunterlagen (Obsidian-Vault, PortfolioAktuell.txt), die
   ins Repository übernommen werden sollen?
