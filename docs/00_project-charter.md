---
title: "Projektzusammenfassung digitana"
status: draft
decision_status: open
owner: Raphael Bollag
created: 2026-07-23
updated: 2026-07-23
project: digitana
source_basis:
  - auftragsprompt
assumptions:
  - "digitana wird als Teilhabe-Plattform neu aufgebaut (siehe ADR-000)"
open_questions:
  - "Verhältnis zum bestehenden Infomaniak-/Circle-Code (ADR-000)"
---

# Projektzusammenfassung digitana

Konsolidierte Zusammenfassung (Deliverable 1). Grundlage ist der
Auftragsprompt. Zahlen und Organisationsangaben aus früheren Unterlagen sind
als `[HISTORISCHE PLANUNG]` gekennzeichnet.

## Problemstellung

Ältere Menschen in der Schweiz sind keine homogene, «technikferne» Gruppe.
Aktuelle Daten (Digital Seniors 2025) zeigen eine hohe, aber ungleich verteilte
Internetnutzung ab 65 Jahren:

- ab 65 Jahren gesamt: **89 %**
- 65–74 Jahre: **97 %**
- 75–84 Jahre: **88 %**
- ab 85 Jahren: **60 %**
- fühlen sich durch die Digitalisierung unter Druck gesetzt: **45 %**
- befürchten gesellschaftlichen Anschlussverlust ohne digitale Technik: **38 %**

Das Problem ist deshalb **nicht** primär fehlender Zugang, sondern ungleiche
Nutzungskompetenzen, Sicherheitsfragen, wechselnde Bedienoberflächen, fehlende
Unterstützung und ungewollte Abhängigkeit von anderen Personen. digitana
bearbeitet vier Ebenen digitaler Teilhabe: **Zugang, Bedienung, Orientierung/
Sicherheit, Nutzungsergebnis**.

> Frühere Aussage «26 % der über 65-Jährigen sind offline» ist überholt und wird
> nicht mehr verwendet. `[VERWORFEN]`

## Zielgruppen

- **Primär:** Personen ab ca. 65 Jahren, die bei konkreten digitalen
  Alltagshandlungen Unterstützung wünschen (Altersgrenze ist Orientierung, kein
  Ausschluss).
- **Sekundär:** Angehörige, Freiwillige, Peer-Begleitpersonen, Fachpersonen der
  Sozialen Arbeit, Alters- und Quartierorganisationen, Bibliotheken, Gemeinden,
  Bildungseinrichtungen, Redaktions- und Projektverantwortliche.

## Ziel und Wirkungslogik

**Übergeordnetes Wirkungsziel:** digitana stärkt die **digitale
Handlungsfähigkeit** älterer Menschen – die Fähigkeit, eigene digitale Anliegen
zu benennen, passende Unterstützung zu finden, Handlungen nachzuvollziehen,
selbst zu entscheiden, Risiken zu erkennen und nach einer Begleitung mehr
Schritte eigenständig auszuführen.

**Wirkungslogik (verkürzt):** Inputs (Fachwissen, Zeit, Lernmaterial,
Mitwirkung der Adressat:innen) → Aktivitäten (Co-Design, Plattform, Begleitung,
Support) → Outputs (Anleitungen, Begleitungen, Termine, gelöste Anfragen) →
kurzfristige Outcomes (Orientierung, Erfolgserlebnisse, Sicherheit) →
mittelfristige Outcomes (mehr selbstständige Handlungen, Selbstwirksamkeit,
weniger ungewollte Abhängigkeit) → Impact (digitale und soziale Teilhabe).

> Keine unbelegten Wirkungsversprechen. Aus einzelnen Plattformnutzungen wird
> **nicht** direkt auf weniger Einsamkeit oder langfristige Autonomie
> geschlossen.

## Hybrides Angebotsmodell (vier Ebenen)

1. **Öffentliche Orientierung** – ohne Konto: Projektinfos, Anleitungen,
   lokale Angebote, Veranstaltungskalender, Sicherheitshinweise, Druckansicht.
2. **Persönliche Nutzung** – optionales Konto: Übersicht, Termine, eigene
   Anfragen, gespeicherte Inhalte, Darstellungseinstellungen, Einwilligungen.
3. **Menschliche Unterstützung** – 1:1-Begleitung, telefonische Orientierung,
   sichere Rückfragen, Video-Hilfe nach Termin, Peer-Lernen, lokale Formate,
   Weitervermittlung.
4. **Fachliche/organisatorische Steuerung** – Supportbearbeitung, Termin- und
   Veranstaltungsverwaltung, redaktioneller Freigabeprozess, Rollen/Rechte,
   Datenschutz, Qualitätssicherung, minimale Evaluation.

## MVP in einem Satz

Eine kleine Zahl **durchgängiger, barrierefreier, datensparsamer Abläufe** –
öffentliche Anleitungen, Terminbuchung, Supportanfragen mit menschlicher
Bearbeitung, zugängliches Konto, freiwillige Rückmeldung. Details in
`02_scope-mvp.md`.

## Ausdrückliche Nicht-Ziele

digitana ist **kein** soziales Netzwerk, kein Ersatz für persönliche
Begleitung, keine medizinische/rechtliche/finanzielle Beratung, kein
E-Banking, keine Fernwartung, keine Fallführungssoftware/Klient:innenakte,
keine Plattform für Gesundheitsdaten, kein Geräte-Shop, keine native App
(MVP), keine KI-zentrierte Anwendung, kein Microservice-/Kubernetes-Projekt.

Unterstützer:innen nehmen **keine** Passwörter/PINs/TANs entgegen, loggen sich
**nicht** stellvertretend ein, führen **keine** Zahlungen aus, steuern **keine**
Geräte ohne transparente Zustimmung fern.

## Fachliche Grundsätze (Soziale Arbeit)

Empowerment · Ressourcenorientierung · Lebensweltorientierung · Partizipation ·
Beziehungsarbeit · soziale Gerechtigkeit/Tripelmandat. Methodischer Rahmen:
**Kooperative Prozessgestaltung (KPG)** mit sieben zirkulären Schritten
(Situationserfassung, Analyse, Diagnose, Zielsetzung, Interventionsplanung,
Interventionsdurchführung, Evaluation) – als Orientierungsrahmen, **nicht** als
Fallführungssystem im MVP.

## Organisation und Status

Projektleitung: **Raphael Bollag**, Student der Sozialen Arbeit (FHNW), Region
Riehen/Basel-Stadt. digitana entstand aus dem übergeordneten Projekt
**IT-Matters**.

Als Planungswerte, **nicht** als bestätigte Zusagen: Pilotbudget ~CHF 40'000,
zehn Teilnehmende, Pilot in Riehen, zwölf Monate Dauer, Vereinsgründung,
einzelne Förderstellen und Partner. `[HISTORISCHE PLANUNG]` – vor Übernahme in
Anträge neu zu prüfen. Der Arbeitsname **ProAge** wird nicht mehr verwendet;
kanonischer Produktname ist **digitana**.
