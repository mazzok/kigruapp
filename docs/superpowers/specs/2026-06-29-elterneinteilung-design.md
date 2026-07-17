# Design: Elterneinteilung — Teamzuweisung für Eltern

**Datum:** 2026-06-29
**Status:** Approved

## Überblick

Eltern können einem oder mehreren organisatorischen Teams zugeordnet werden (z.B. Garten, Marketing, Finanzen, Haus, Anwerbung Familien). Die verfügbaren Teams werden von Admins als FieldDefinition-Konfiguration verwaltet. Die Zuweisung erfolgt über einen dedizierten Admin-Screen.

---

## Datenmodell

### Neue Person-Sektion: `assignedDuty`

`Person` (Backend + Frontend) bekommt eine neue Sektion `assignedDuty: FieldRef[]`, analog zu den bestehenden Sektionen `organisationalUnit`, `schedules`, etc.

Ein `FieldRef` enthält:
- `definitionId` — ID der FieldDefinition (Template für `parent-team`)
- `fieldInstanceId` — ID der konkreten Team-FieldInstance (z.B. "Garten")

Mehrere Teams → mehrere FieldRefs in `assignedDuty`. Initial leer (`[]`) für alle bestehenden Personen — keine Migration nötig.

### Organisation-Konfiguration: tag `'parent-teams'`

Ein neuer `Organisation`-Eintrag mit tag `'parent-teams'` wird beim ersten Anlegen eines Teams automatisch erstellt (wie `'groups'`).

Enthält:
- **1 FieldDefinition** als Template:
  - `fieldName: 'parent-team'`
  - `jsonSchema: { type: 'object', properties: { label: { type: 'string' } } }`
  - `required: false`
- **N FieldInstances** — eine pro Team (value: `{ label: 'Garten' }`, `{ label: 'Marketing' }`, …)

### Zuweisung

Jede Teamzuweisung eines Elternteils = ein FieldRef in `Person.assignedDuty`, der auf die Team-FieldInstance zeigt. Alle Eltern desselben Teams teilen sich **dieselbe** FieldInstance (1:1 zwischen Team und FieldInstance).

---

## UI / Screens

### A) Settings > Organisation — Tab "Elterneinteilung"

- Neuer Tab im bestehenden `OrganisationComponent` (`settings/organisation`)
- Tabelle: Spalten `Name` + `Aktionen` (Löschen)
- Formular darunter: Textfeld "Teamname" + Button "Hinzufügen"
- Hinzufügen: erstellt FieldInstance (und beim ersten Mal auch FieldDefinition + Organisation-Eintrag)
- Löschen: Soft-Delete via `outdatedAt` auf der FieldInstance
- Zugriff: durch bestehenden `adminGuard` auf der `settings`-Route geschützt

### B) Administration > Elterneinteilung — Zuteilungsscreen

- Neue Route: `administration/elterneinteilung`
- Neue Standalone-Komponente `ElterneinteilungComponent`
- Lädt alle Eltern (PersonService) und alle Teams (OrganisationService tag `'parent-teams'` + FieldInstanceService)
- Pro Elternteil: Name + Chips/Toggle-Buttons für alle verfügbaren Teams
  - Team aktiv (in `assignedDuty`) → Chip hervorgehoben
  - Klick auf Chip → Toggle: FieldRef hinzufügen oder entfernen via PersonService PATCH
- Optional: Dropdown-Filter "Nur Team X anzeigen" zum Filtern der Liste
- Zugriff: durch bestehenden `adminGuard` auf der `administration`-Route geschützt

---

## Backend-Änderungen

### Person-Entity & DTOs

| Datei | Änderung |
|---|---|
| `Person.java` | Neues Feld `List<FieldRef> assignedDuty` |
| `PersonDTO.java` | Neues Feld `List<FieldInstanceDTO> assignedDuty` |
| `PersonSectionDTO.java` | `assignedDuty` als optionale Sektion im Update-Request |

`PersonResource` verarbeitet `assignedDuty` analog zu den anderen Sektionen beim PATCH-Endpoint.

### Keine Änderungen nötig an

- `FieldDefinitionResource` / `FieldInstanceResource` — werden unverändert wiederverwendet
- `OrganisationResource` — `GET /organisations/tag/parent-teams` funktioniert automatisch

---

## Komponenten-Struktur

### Neue Dateien

```
frontend/src/app/administration/elterneinteilung/
  elterneinteilung.component.ts
  elterneinteilung.component.html
  elterneinteilung.component.scss
```

### Geänderte Dateien

```
frontend/src/app/
  app.routes.ts                              ← Route administration/elterneinteilung
  settings/organisation/
    organisation.component.ts               ← neuer Tab "Elterneinteilung"
    organisation.component.html
  shared/models/person.model.ts             ← assignedDuty: FieldRef[]

backend/src/main/java/at/kigruapp/
  entity/Person.java                        ← assignedDuty: List<FieldRef>
  dto/PersonDTO.java                        ← assignedDuty: List<FieldInstanceDTO>
  dto/PersonSectionDTO.java                 ← assignedDuty optional
```

### Keine neuen Services

`OrganisationService`, `FieldDefinitionService`, `FieldInstanceService` und `PersonService` werden unverändert wiederverwendet.

---

## Sicherheit

- Teamverwaltung (Settings-Tab) und Zuweisung (Admin-Screen) sind beide durch den bestehenden `adminGuard` geschützt
- Kein öffentlicher Zugriff auf `assignedDuty`-Daten
