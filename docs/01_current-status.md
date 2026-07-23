---
title: "Bestandsaufnahme und Widerspruchs-/Statusmatrix"
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
open_questions:
  - "Verhältnis zum bestehenden Code (ADR-000)"
---

# Bestandsaufnahme und Widerspruchs-/Statusmatrix

Deliverable 2. Trennt den geprüften Ist-Zustand des Repositorys von den
historischen und den auftragsseitigen Planungen.

## 1. Ist-Zustand des Codes `[BESTÄTIGTER PROJEKTSTAND]`

Stand: Branch `claude/repo-code-status-jeks17`, 7 Commits, Arbeitsverzeichnis
sauber. Geprüft am 2026-07-23.

### Was vorhanden ist

- **Django 5.2 Projekt** `digitana` mit Apps `core`, `integrations`,
  `frontend`; DRF + drf-spectacular; Admin über django-unfold.
- **`core.User`** – Custom User (UUID-PK), `status`, `is_guest`. Semantik:
  «organisatorisches Mitglied oder Gast».
- **`core.Circle` / `Role` / `RoleAssignment`** – soziokratisches
  Organisationsmodell (S3-Kreise mit Hierarchie, Rollen mit
  «decision_reference» auf Konsent-Entscheide).
- **`core.SystemConfig`** – Singleton für Infomaniak-OAuth-Credentials.
- **App `integrations`** – `IntegrationService`, `IntegrationInstance`,
  `UserIntegrationIdentity`; Adapter für Infomaniak kSuite (kDrive, kChat,
  kMeet, Kalender, Newsletter); OAuth2/API-Key; Infomaniak-SSO (OIDC).
- **App `frontend`** – Dashboard mit Partials (circles, files, services,
  stats), Infomaniak-Setup-Wizard.
- **Infrastruktur** – Dockerfile, docker-compose, GitHub-Actions-CI, Poetry,
  Testsuite (`tests/` für core/integrations/frontend).

### Sicherheits-/Konfigurationsbeobachtungen (Ist)

- `SECRET_KEY` mit unsicherem Default; `DEBUG` per Env; `ALLOWED_HOSTS` leer als
  Default. Für Produktion gemäss Deployment-Checkliste zu härten.
- `credentials`/Tokens in `JSONField` gespeichert; im Code selbst als
  «SECURITY WARNING» markiert (Klartext). Verschlüsselung offen.
- `LANGUAGE_CODE = 'en-us'`, `TIME_ZONE = 'UTC'`. Für CH-Zielgruppe
  anzupassen (de-CH, Europe/Zurich).

### Kernbefund

Das Repository ist heute eine **organisatorische Kollaborations-/
Integrationsplattform** (soziokratische Kreise + Infomaniak-kSuite). Es
entspricht **nicht** dem im Auftrag beschriebenen digitana (Teilhabe-Plattform
für ältere Menschen). Keine der im Auftrag vorgeschlagenen Domänen-Apps
(`accounts`, `privacy`, `content`, `learning`, `events`, `support`,
`feedback`, `audit`) existiert; die vorhandene App `integrations` kommt im
Auftrag nicht vor.

**Wiederverwendbar** als technisches Fundament (Phase 2): Django-5.2-Setup,
Custom User (UUID), PostgreSQL, Docker Compose, CI, DRF, Admin.

**Ausserhalb des Auftrags** (Ablösung als offene Entscheidung, siehe ADR-000):
Circle-/Role-Soziokratie, gesamte Infomaniak-Integration, Integrations-
Dashboard/Wizard.

## 2. Widerspruchs- und Statusmatrix

| # | Punkt | Bisherige/andere Aussage | Aktueller Status | Risiko | Empfehlung | Nötiger Entscheid |
|---|---|---|---|---|---|---|
| 1 | Produktname | «ProAge» in älteren Notizen | `digitana` kanonisch | Verwechslung/Doku | «ProAge» konsequent ersetzen | keiner (verbindlich lt. Auftrag) |
| 2 | Dachprojekt | IT-Matters / digitana teils vermischt | IT-Matters = Träger, digitana = Plattform | gering | Trennung dokumentieren | keiner |
| 3 | Bestehender Code | Infomaniak-/Circle-Plattform im Repo | widerspricht Auftrag | **hoch** | Neuaufbau, Gerüst behalten (ADR-000) | **blockierend** |
| 4 | Pilotstandort | Riehen | Planungswert | mittel | als Annahme führen | offen |
| 5 | Projektstart/Zeitplan | 12 Monate, Start offen | historische Planung | mittel | vor Antrag prüfen | offen |
| 6 | Budget | ~CHF 40'000 | historische Planung | mittel | nicht als Zusage nennen | offen |
| 7 | Vereins-/Rechtsform | Verein geplant | nicht bestätigt | mittel | «in Abklärung» | offen |
| 8 | Partner/Förderer | einzelne genannt | nicht bestätigt | mittel | «Kontakt vorgesehen» | offen |
| 9 | Hosting | Schweizer VPS vs. Raspberry Pi | zwei Planungsstände | **hoch** | CH-VPS für Pilot; Pi nur Dev/Staging | ADR-003 (offen) |
| 10 | Django-Version | 5.x | Code nutzt 5.2 | gering | 5.2 LTS festhalten | keiner (empfohlen) |
| 11 | UI-Ansatz | SPA vs. Templates | zwei Planungsstände | mittel | Server-Rendering + progressive Enhancement | ADR (offen) |
| 12 | API-first | API-first vs. -fähig | Auftrag: API-fähig | gering | Domain-Services, nur nötige Endpunkte | keiner |
| 13 | Microservices/K8s | überdimensionierter Entwurf | Auftrag: Monolith | mittel | modularer Monolith (ADR-001) | ADR-001 |
| 14 | KI | zentrale vs. spätere Option | Auftrag: keine MVP-Voraussetzung | gering | spätere, eng begrenzte Option | offen |
| 15 | Datei-Uploads | teils vorgesehen | Auftrag: MVP deaktiviert | mittel | im MVP aus | offen (separater DS-/Sicherheitsentscheid) |
| 16 | Evaluation | teils ambitioniert | Auftrag: minimal, getrennt | mittel | datensparsam, pseudonym, getrennt | offen |
| 17 | Lizenz | nicht festgelegt | keine Lizenzdatei | mittel | Lizenz wählen (z. B. AGPL/MIT) | offen |

## 3. Zentrale Risiken (Kurzregister)

- **R1 – Domänenbruch:** Bestehender Code ≠ Zielprodukt. Ohne Entscheid
  (ADR-000) blockiert. Wirkung hoch, Eintritt sicher. → ADR-000.
- **R2 – Personendaten/Datenschutz:** Zielgruppe schützenswert; DSG/DSGVO-
  Prüfung ausstehend. → `09_data-protection.md`, DSFA-Screening vor Pilot.
- **R3 – Barrierefreiheit:** WCAG 2.2 AA erst noch zu verankern; aktuelles
  UI (unfold/Dashboard) nicht auf Zielgruppe geprüft. → `08_accessibility.md`.
- **R4 – Credential-Handling:** Tokens im Klartext-JSONField (falls
  Integrationen bestehen bleiben). → Verschlüsselung oder Entfernung.
- **R5 – Scope-Ausweitung:** Feature-Fülle statt einfachster tragfähiger
  Lösung. → strikter MVP-Scope, Definition of Done.
