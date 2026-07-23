---
title: "ADR-000: Verhältnis zum bestehenden Repository-Code"
status: proposed
decision_status: open
owner: Raphael Bollag
created: 2026-07-23
updated: 2026-07-23
project: digitana
source_basis:
  - repository
  - auftragsprompt
---

# ADR-000: Verhältnis zum bestehenden Repository-Code

- **Status:** vorgeschlagen (Entscheid der Projektleitung ausstehend)
- **Kontext-Dokumente:** `01_current-status.md`, `18_open-questions.md`

## Kontext

Das Repository enthält heute eine funktionierende Django-5.2-Anwendung, die
inhaltlich eine **organisatorische Kollaborations-/Integrationsplattform** ist:
soziokratische Kreise (`Circle`/`Role`/`RoleAssignment`), Infomaniak-kSuite-
Integration (kDrive/kChat/kMeet/Kalender/Newsletter), Infomaniak-SSO sowie ein
Integrations-Dashboard mit Setup-Wizard.

Der Auftragsprompt beschreibt digitana dagegen als **sozial-digitales
Unterstützungssystem zur digitalen Teilhabe älterer Menschen** mit den Apps
`accounts`, `privacy`, `content`, `learning`, `events`, `support`, `feedback`,
`audit`. Zwischen beiden besteht ein grundlegender Domänenbruch. Ohne Entscheid
kann kein tragfähiger Domänencode entstehen.

## Optionen

### A – Neuaufbau, technisches Gerüst behalten (empfohlen)

Fundament (Django 5.2, Custom `User` mit UUID, PostgreSQL, Docker Compose, CI,
DRF, Admin) bleibt. Domänen-Apps werden neu gemäss Auftrag aufgebaut. Circle-/
Role-Soziokratie und die gesamte Infomaniak-Integration werden – nach
bestätigtem Entscheid – archiviert bzw. entfernt.

- **Pro:** entspricht dem Auftrag und den Nicht-Zielen; klarste Datenschutz-
  und Rollenkontrolle; geringste Altlast.
- **Contra:** verwirft einen Teil bestehender Arbeit; `User`-Semantik
  («Mitglied/Gast») ist an Teilhabe-Rollen anzupassen.

### B – Auf bestehenden Modellen aufbauen

Circle/Integrations bleiben und werden um Teilhabe-Module erweitert.

- **Pro:** bewahrt bestehende Arbeit maximal.
- **Contra:** widerspricht Nicht-Zielen («kein Integrationsprojekt»); vermischt
  zwei Domänen; erschwert Datenschutz/Rollen; höhere Dauerkomplexität.

### C – Zwei getrennte Projekte

Bestehendes Repo bleibt eigenständig; digitana entsteht separat.

- **Pro:** keine Vermischung.
- **Contra:** erfordert Angabe eines Ziel-Repositorys/-Verzeichnisses; keine
  Wiederverwendung des Gerüsts.

## Empfehlung `[FACHLICHE EMPFEHLUNG]`

**Option A.** Sie deckt den Auftrag am direktesten, minimiert Altlast und
erlaubt saubere Datenschutz-/Rollenarchitektur. Vorgehen bei Bestätigung:

1. Bestehende Infomaniak-/Circle-Historie auf dem Branch belassen (git-Historie
   bleibt Referenz), nichts unwiderruflich löschen.
2. Neue Domänen-Apps additiv anlegen; nicht mehr benötigte Apps in einem
   klar benannten Commit entfernen (reversibel via Historie).
3. `User`-Modell auf Teilhabe-Rollen (Gast/Teilnehmer:in/Unterstützer:in/
   Redaktion/Prüfung/Administration/Evaluation) ausrichten.

## Konsequenz

Bis zur Bestätigung wird **kein** bestehender Code verändert oder gelöscht; es
entsteht nur Dokumentation. Nach Bestätigung von A beginnt Phase 2 (technisches
Fundament) auf dem vorhandenen Gerüst.
