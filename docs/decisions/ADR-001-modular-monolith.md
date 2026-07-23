---
title: "ADR-001: Modularer Django-Monolith für den MVP"
status: proposed
decision_status: open
owner: Raphael Bollag
created: 2026-07-23
updated: 2026-07-23
project: digitana
source_basis:
  - auftragsprompt
---

# ADR-001: Modularer Django-Monolith für den MVP

- **Status:** vorgeschlagen
- **Bezug:** Auftragsprompt Abschnitt 20 (Technische Zielarchitektur)

## Kontext

Frühere Unterlagen enthalten zwei Architekturstände: einen realistischen
modularen Django-Monolithen und einen erheblich überdimensionierten Entwurf mit
Raspberry-Pi-Produktivbetrieb, Kubernetes, Argo CD, Atlantis, HSM,
Microservices und mehreren Observability-Systemen. Für einen Pilot mit einer
primären Projektleitung und noch nicht validierter Nutzung ist der zweite
Entwurf unverhältnismässig.

## Entscheidung `[FACHLICHE EMPFEHLUNG]`

Für den MVP wird ein **modularer Django-Monolith** verwendet:

- **Django 5.2 LTS** (erweiterter Support bis April 2028), von 5.2 unterstützte
  Python-Version.
- **PostgreSQL**, Django Templates, semantisches HTML, möglichst wenig
  JavaScript, progressive Erweiterung.
- **DRF** nur für klar begründete API-Anwendungsfälle; Geschäftslogik in
  Domain-Services, nicht ausschliesslich in Views/Serializern.
- **Docker Compose** für Entwicklung und Deployment; Reverse Proxy (Caddy oder
  nginx); produktiver WSGI/ASGI-Server.
- Getrennte Umgebungen (Dev/Test/Prod), keine Geheimnisse im Repository, CI,
  verschlüsselte Back-ups, grundlegendes Monitoring.

Klare Modulgrenzen (Apps) halten eine spätere Zerlegung offen. **Microservices,
Kubernetes und eine SPA werden erst bei nachgewiesenem Bedarf geprüft.**

## Konsequenz

- Vorgesehene Apps: `core`, `accounts`, `privacy`, `content`, `learning`,
  `events`, `support`, `feedback`, `audit`.
- Kein Einsatz des Django-Entwicklungsservers in Produktion; vor jedem Deploy
  `manage.py check --deploy`, `DEBUG=False`, korrekte `ALLOWED_HOSTS`, sichere
  `SECRET_KEY`-Verwaltung, HTTPS/Cookie-Prüfung, Back-up-/Restore-Test.

## Abgelehnt `[VERWORFEN]`

Kubernetes/Argo CD/Atlantis/HSM/Microservices für den Pilot; Raspberry Pi als
Produktivsystem mit Personendaten (nur als Dev/Staging/Demo zulässig, ADR-003).
