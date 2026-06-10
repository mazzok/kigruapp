# Person-Architektur mit kategorisierten Field Implementations

**Datum:** 2026-06-10
**Status:** Approved

## Zusammenfassung

Umbau der Datenarchitektur: Die separaten `parents`- und `children`-Collections werden durch eine einheitliche `persons`-Collection ersetzt. Alle Personen-Attribute werden als kategorisierte Field Implementations abgebildet — referenziert über FieldDefinitions (JSON Schema) und gespeichert in einer `field_instances`-Collection. Die Person selbst hält nur Referenzen (definitionId + fieldInstanceId), gruppiert in feste Sektionen.

## 1. Datenmodell

### `persons` Collection

```json
{
  "_id": "ObjectId",
  "familyId": "ObjectId",
  "keycloakUserId": "String|null",
  "basicProperties":  [{"definitionId": "ObjectId", "fieldInstanceId": "ObjectId"}],
  "roles":            [{"definitionId": "ObjectId", "fieldInstanceId": "ObjectId"}],
  "schedules":        [{"definitionId": "ObjectId", "fieldInstanceId": "ObjectId"}],
  "duties":           [{"definitionId": "ObjectId", "fieldInstanceId": "ObjectId"}],
  "finance":          [{"definitionId": "ObjectId", "fieldInstanceId": "ObjectId"}],
  "customProperties": [{"definitionId": "ObjectId", "fieldInstanceId": "ObjectId"}],
  "createdAt": "Instant",
  "updatedAt": "Instant"
}
```

- Ersetzt `parents` und `children` Collections
- `familyId` und `keycloakUserId` sind die einzigen hardcoded Felder (Systembeziehungen)
- `personType` (PARENT/CHILD) ist eine basicProperty (FieldImplementation)
- Sektionszuordnung liegt in der Person, nicht in der FieldDefinition
- 5 feste Sektionen: `basicProperties`, `roles`, `schedules`, `duties`, `finance`
- 1 dynamische Sektion: `customProperties` (Admin-definiert)

### `field_instances` Collection

```json
{
  "_id": "ObjectId",
  "definitionId": "ObjectId",
  "value": "Object",
  "createdAt": "Instant",
  "updatedAt": "Instant"
}
```

- Hält nur den Wert + Referenz auf Definition
- Kein `entityType`/`entityId` — Zuordnung liegt in der Person
- Eine FieldDefinition kann in mehreren Sektionen/Personen referenziert werden
- Jede Person bekommt eigene field_instances (Instances werden nicht zwischen Personen geteilt)
- Value wird gegen `FieldDefinition.jsonSchema` validiert

### `field_definitions` Collection (angepasst)

```json
{
  "_id": "ObjectId",
  "fieldName": "String",
  "label": {"de": "String", "en": "String"},
  "description": "String",
  "jsonSchema": "Object (JSON Schema v7)",
  "required": "boolean",
  "keycloakMapping": "String|null",
  "createdAt": "Instant",
  "outdatedAt": "Instant|null"
}
```

- `entity`-Feld entfällt (war CHILD/PARENT/FAMILY)
- Neues Feld `keycloakMapping` (z.B. `"email"`, `"firstName"`, `"lastName"`) fuer System-Integration
- FieldDefinitions sind sektionsunabhaengig und wiederverwendbar
- Labels werden ueber Admin-UI gepflegt (DB-basiert, kein i18n-Build noetig)

### `families` Collection (unveraendert)

```json
{
  "_id": "ObjectId",
  "name": "String",
  "createdAt": "Instant"
}
```

## 2. Initiale FieldDefinitions (Seed Data)

Beim ersten Start muessen diese FieldDefinitions existieren:

| fieldName | jsonSchema | required | keycloakMapping |
|---|---|---|---|
| `personType` | `{type: "string", enum: ["PARENT", "CHILD"]}` | true | null |
| `firstName` | `{type: "string"}` | true | `"firstName"` |
| `lastName` | `{type: "string"}` | true | `"lastName"` |
| `email` | `{type: "string", format: "email"}` | false | `"email"` |
| `phone` | `{type: "string"}` | false | null |
| `dateOfBirth` | `{type: "string", format: "date"}` | false | null |
| `gender` | `{type: "string", enum: ["male", "female", "diverse"]}` | false | null |
| `entryDate` | `{type: "string", format: "date"}` | false | null |
| `exitDate` | `{type: "string", format: "date"}` | false | null |
| `notes` | `{type: "string"}` | false | null |
| `address` | `{type: "object", properties: {street: {type: "string"}, zip: {type: "string"}, city: {type: "string"}}, required: ["street", "zip", "city"]}` | false | null |

Alle bekommen Labels in `de` und `en`. Alle landen initial in `basicProperties`.

## 3. GUI-Mapping

### JSON Schema zu Angular Material Mapping

| JSON Schema | GUI Element | Angular Material |
|---|---|---|
| `{type: "boolean"}` | Checkbox/Toggle | `mat-slide-toggle` |
| `{type: "string"}` | Textfeld | `mat-input` |
| `{type: "string", format: "date"}` | Datepicker | `mat-datepicker` |
| `{type: "string", format: "time"}` | Timepicker | `mat-input type="time"` |
| `{type: "number"}` / `{type: "integer"}` | Zahlenfeld | `mat-input type="number"` |
| `{type: "string", enum: [...]}` | Dropdown | `mat-select` |
| `{type: "object", properties: {...}}` | Fieldgroup | `mat-card` mit rekursiv gerenderten Sub-Feldern |

### Rendering-Logik

Der `JsonSchemaFieldComponent` rendert sich bei `type: "object"` rekursiv selbst fuer jede Property — verschachtelte Strukturen wie Adresse funktionieren automatisch.

### Formular-Aufbau pro Person

```
PersonFormComponent
+-- BasicPropertiesSection    -> laedt Definitions, rendert Fields
+-- RolesSection              -> gleiche Logik
+-- SchedulesSection          -> gleiche Logik
+-- DutiesSection             -> gleiche Logik
+-- FinanceSection            -> gleiche Logik
+-- CustomPropertiesSection   -> gleiche Logik
```

Jede Sektion ist ein `SectionFormComponent` das:
- Die FieldDefinitions laedt (per definitionIds aus der Person)
- Die FieldInstances laedt (per fieldInstanceIds aus der Person)
- Pro Feld einen `JsonSchemaFieldComponent` rendert
- Beim Speichern die field_instances per Batch-API upserted und die Person-Referenzen aktualisiert

## 4. API-Design

### PersonResource (ersetzt ParentResource + ChildResource)

| Method | Endpoint | Beschreibung |
|---|---|---|
| GET | `/api/v1/persons` | Liste aller Personen (Filter: `?familyId=...&personType=PARENT`) |
| GET | `/api/v1/persons/{id}` | Person mit allen Sektions-Referenzen |
| GET | `/api/v1/persons/{id}/full` | Person mit aufgeloesten field_instances (Values inline) |
| POST | `/api/v1/persons` | Person anlegen mit initialen field_instances |
| PUT | `/api/v1/persons/{id}` | Person-Struktur aktualisieren (Sektionszuordnungen) |
| DELETE | `/api/v1/persons/{id}` | Person + zugehoerige field_instances loeschen |

### FieldInstanceResource (vereinfacht)

| Method | Endpoint | Beschreibung |
|---|---|---|
| GET | `/api/v1/field-instances/{id}` | Einzelne Instance |
| PUT | `/api/v1/field-instances/batch` | Batch-Upsert (Values speichern) |

### FieldDefinitionResource (weitgehend gleich)

| Method | Endpoint | Beschreibung |
|---|---|---|
| GET | `/api/v1/field-definitions` | Alle Definitionen (`?active=true`) |
| POST | `/api/v1/field-definitions` | Neue Definition (mit JSON Schema Validierung) |
| PUT | `/api/v1/field-definitions/{id}` | Update (Labels, Schema, keycloakMapping) |
| PATCH | `/api/v1/field-definitions/{id}/outdate` | Soft-Delete |

### FamilyResource (angepasst)

| Method | Endpoint | Beschreibung |
|---|---|---|
| GET | `/api/v1/families/{id}/persons` | Alle Personen einer Familie (ersetzt `/children` + `/parents`) |

### Keycloak-Provisioning

Beim POST `/api/v1/persons`:
1. Backend prueft basicProperties auf field_instances deren FieldDefinition ein `keycloakMapping` hat
2. Wenn `email` vorhanden: Keycloak-User wird mit firstName, lastName, email erstellt
3. Temporaeres Passwort wird generiert
4. `keycloakUserId` wird in der Person gespeichert

## 5. Migration

### Schritt 1: `parents` + `children` -> `persons`

- Fuer jeden Parent/Child ein Person-Dokument anlegen
- Feste Felder (firstName, lastName, etc.) werden zu field_instances
- Referenzen in basicProperties eintragen
- `personType` = "PARENT" bzw. "CHILD" als field_instance
- `keycloakUserId` und `familyId` direkt uebernehmen

### Schritt 2: `field_instances` (alt) -> `field_instances` (neu)

- `entityType`/`entityId` Felder entfernen
- Bestehende Instances bleiben, Referenzen werden in die neue Person eingetragen

### Schritt 3: `field_definitions` anpassen

- `entity`-Feld entfernen
- `keycloakMapping`-Feld hinzufuegen (fuer firstName, lastName, email setzen)

### Schritt 4: Aufraeumen

- `parents` und `children` Collections nach erfolgreicher Migration loeschen
- Bestehende `CustomFieldsMigration.java` als Vorlage fuer die neue Migration verwenden

## 6. Wizard-Anpassung

### Step 1 — Familie

Unveraendert: neue Family anlegen oder bestehende auswaehlen.

### Step 2 — Kind

- Wird zu "Person anlegen" mit `personType` vorbelegt auf "CHILD"
- `SectionFormComponent` rendert basicProperties
- Andere Sektionen (roles, schedules, etc.) optional ausklappbar oder erst nach Erstellung pflegbar

### Step 3 — Elternteile

- Wird zu "Personen anlegen" mit `personType` vorbelegt auf "PARENT"
- Weiterhin dynamisch (mehrere Elternteile hinzufuegbar)
- Adress-Uebernahme vom ersten Elternteil bleibt
- Keycloak-Provisioning bei email-Angabe

### Speicher-Reihenfolge

1. Family erstellen/auswaehlen
2. Kind-Person erstellen -> field_instances speichern -> Person-Referenzen setzen
3. Pro Elternteil: Person erstellen -> field_instances speichern -> Person-Referenzen setzen -> ggf. Keycloak-User
