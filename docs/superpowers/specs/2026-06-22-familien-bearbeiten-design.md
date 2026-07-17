# Design: Familien bearbeiten

**Datum:** 2026-06-22
**Status:** Approved

## Überblick

Familien sollen vollständig bearbeitbar sein: Familienmetadaten (Name, Adresse), bestehende Personen (Felder ändern), Personen hinzufügen und permanent löschen. Der bestehende `FamilyWizardComponent` wird im Dual-Mode betrieben (Create / Edit).

## Einstiegspunkt

`FamilyListComponent` bekommt pro Familien-Accordion-Header einen Edit-Button (Stift-Icon). Klick öffnet `FamilyWizardComponent` als `MatDialog` mit `MAT_DIALOG_DATA: { familyId: string }`. Ist `familyId` gesetzt → Edit-Modus, sonst Create-Modus (unverändertes Verhalten).

## Wizard Edit-Modus

### Initialisierung

Beim Öffnen im Edit-Modus lädt der Wizard:
- `GET /families/{id}` → Familienmetadaten
- `GET /persons?familyId={id}` → Personen-IDs
- `GET /persons/{id}/full` (pro Person) → `PersonDTO` mit aufgelösten Feldwerten

### Internes State-Tracking

```typescript
existingPersons: { id: string; dto: PersonDTO; type: 'child' | 'parent' }[]
removedPersonIds: string[]
```

`existingPersons` wird beim Laden befüllt. Entfernt eine Nutzer eine Person, wandert ihre ID in `removedPersonIds`.

### Stepper-Titel

Im Edit-Modus: "Familie bearbeiten" statt "Familie anlegen".

## Schritte

### Schritt 1 — Familie

- **Create-Modus:** unverändertes Verhalten (Radio: neue / bestehende Familie, Felder)
- **Edit-Modus:** Radio-Toggle ausgeblendet; Name und Adresse vorausgefüllt aus geladener Family

### Schritt 2 — Kind(er)

- **Create-Modus:** ein Pflicht-Kind-Formular (unverändertes Verhalten)
- **Edit-Modus:** Liste von Kind-Formularen (analog Parents-Step); jedes mit Entfernen-Button; "Kind hinzufügen"-Button für neue Kinder

`ChildStepComponent` bekommt eine `@Input() existingChildren: PersonDTO[]` für Edit-Pre-Fill.

### Schritt 3 — Elternteile

- Unterstützt bereits mehrere Einträge mit Add/Remove
- Bekommt `@Input() existingParents: PersonDTO[]` für Edit-Pre-Fill
- Bestehende Elternteile werden in `SectionFormComponent`-Instanzen vorausgefüllt

## Submit-Logik (Edit-Modus)

```
1. PUT /families/{id}              — Name/Adresse aktualisieren
2. Für neue Personen (kein id):    POST /persons
3. Für bestehende (id vorhanden):  PUT /persons/{id}  mit CreatePersonRequest
4. Für entfernte IDs:              DELETE /persons/{id}
```

Neue und aktualisierte Personen werden sequenziell abgearbeitet (wie im Create-Modus). Fehler → `submitting = false`, kein Dialog-Close.

## Backend: PUT /persons/{id} — neues Format

Der bestehende `PUT /persons/{id}` Endpoint wird auf `CreatePersonRequest` als Request-Body umgestellt (identisches Format wie `POST /persons`).

**Serverlogik:**
1. Alle bestehenden FieldInstances der Person löschen (alle Sections: basicProperties, roles, schedules, duties, finance, customProperties, organisationalUnit)
2. Neue FieldInstances aus `SectionInput`-Listen erstellen
3. Person mit neuen FieldRef-Listen persistieren

Der rohe `Person`-Entity-PUT entfällt. `PersonService.update()` im Frontend wird auf `CreatePersonRequest` umgestellt.

`PUT /families/{id}` bleibt unverändert.

## Betroffene Dateien

### Frontend
- `family-list/family-list.component.ts` + `.html` — Edit-Button pro Familie
- `family-wizard/family-wizard.component.ts` + `.html` — Dual-Mode, MAT_DIALOG_DATA, Submit-Logik
- `family-wizard/steps/family-step.component.ts` + `.html` — Edit-Pre-Fill, Toggle ausblenden
- `family-wizard/steps/child-step.component.ts` + `.html` — Multi-Child-Support im Edit-Modus
- `family-wizard/steps/parents-step.component.ts` — Pre-Fill mit existingParents Input
- `shared/services/person.service.ts` — `update()` auf `CreatePersonRequest` umstellen

### Backend
- `PersonResource.java` — `PUT /persons/{id}` auf CreatePersonRequest umstellen, FieldInstance-Replace-Logik
