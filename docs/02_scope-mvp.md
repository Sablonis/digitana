---
title: "Empfohlener MVP-Scope"
status: draft
decision_status: open
owner: Raphael Bollag
created: 2026-07-23
updated: 2026-07-23
project: digitana
source_basis:
  - auftragsprompt
assumptions:
  - "Neuaufbau als Teilhabe-Plattform (ADR-000)"
open_questions:
  - "Login-Verfahren (N1)"
  - "Datei-Uploads (N6)"
---

# Empfohlener MVP-Scope

Deliverable 3. MoSCoW-Priorisierung. Ziel: eine kleine Zahl **durchgängiger**,
barrierefreier, datensparsamer Abläufe – nicht ein breiter Funktionskatalog.

## Must (MVP)

| # | Funktion | Kern-Abnahmekriterium |
|---|---|---|
| M1 | Öffentliche Projekt-/Hilfeseite | Ohne Konto; klar, was digitana leistet und was nicht; sichtbare Kontaktmöglichkeit |
| M2 | Wissensbereich (Artikel/Anleitungen) | Kategorien + Suche; Prüfdatum/Version sichtbar; druckfreundlich; Kernanleitung in ≤3 Interaktionen erreichbar |
| M3 | Termin-/Veranstaltungsfunktion | Anzeigen, buchen, bestätigen, stornieren, E-Mail-Erinnerung, iCal-Export; kein Doppeltermin; Storno so leicht wie Buchung |
| M4 | Konto + zugängliches Onboarding | Einfache Registrierung, versionierte Einwilligung, zugängliche Passwort-Wiederherstellung; öffentliche Inhalte bleiben kontenlos |
| M5 | Supportanfragen | Anliegen erfassen; Sicherheitshinweis **vor** dem Textfeld; Status sichtbar; nachträglich ergänz-/zurückziehbar; kein Kompetenz-Score |
| M6 | Unterstützer:innen-Arbeitsbereich | Nur zugewiesene Anfragen; Status/Antwort/Anleitung/Termin; Weitervermittlung dokumentierbar; keine Zugangsdaten |
| M7 | Persönliches Dashboard | Nur relevante Infos (nächster Termin, offene Anfrage, letzte Anleitung); gut sichtbare Hilfeoption |
| M8 | Redaktion/Administration | Artikel-Versionierung, Veranstaltungen, Rollen, Einwilligungsversionen, Aufbewahrung/Löschung, Audit-Log |
| M9 | Feedback | Freiwillige Kurzrückmeldung nach Anleitung/Begleitung/Veranstaltung |

**Abgrenzungen im Must:** Datei-Uploads deaktiviert (N6); keine bidirektionale
CalDAV-Synchronisation; keine automatisierten Personen-Bewertungen.

## Should

Darstellungspräferenzen (Schrift/Kontrast); Favoriten; freiwillige Lernmodule +
Fortschritt; Browser-Spracherkennung als optionale Eingabehilfe; Audio-/
Videoversion + Untertitel/Transkripte; Vorbereitung Vor-Ort-Termin; anonyme
Rückmeldung ohne Konto; CSV-Export aggregierter Wirkungsdaten.

## Could

Vertretungs-/Begleitrolle für Angehörige (begrenzt); Peer-Matching;
Mehrsprachigkeit; SMS-Erinnerungen; Jitsi-Integration; Passkeys.

## Won't (MVP) `[SPÄTERE OPTION]` / `[VERWORFEN]`

Mobile App; offene öffentliche API mit Personendaten; Partnerportal;
KI-gestützte Lernhilfe; externe Identitätsdienste; Microservices/Kubernetes;
soziales Netzwerk; Fallführung/Klient:innenakte; Zahlungen; Fernwartung.

## Abhängigkeiten

- M4/M5/M6 setzen **Rollen/Rechte** (accounts) und **privacy** (Einwilligung)
  voraus.
- M3 setzt E-Mail-Versand voraus (Termin-Bestätigung/Erinnerung).
- Alle personenbezogenen Musts setzen die **Datenschutz-Vorprüfung**
  (`09_data-protection.md`) und **audit** voraus.
- M2/M8 setzen den **redaktionellen Workflow** voraus.

## Risiken des Scope

- Scope-Creep Richtung Integrationsplattform → strikt an MoSCoW halten.
- «Should» wirkt attraktiv, ist aber nicht MVP-kritisch → erst nach erster
  vertikaler Scheibe.
- Datenschutz-/Accessibility-Aufwand wird häufig unterschätzt → in jeder
  Definition of Done verankert.

## Erste vertikale Produktscheibe (Phase 3)

**«Öffentliche Anleitung finden → Termin buchen → Bestätigung erhalten →
Rückmeldung geben»** – deckt M2, M3 und M9 durchgängig ab und ist ohne Konto
bzw. mit minimalem Konto realisierbar. Diese Scheibe wird zuerst gebaut,
barrierefrei, getestet und produktionsnah, bevor Support/persönlicher Bereich
(Phase 4) folgen.
