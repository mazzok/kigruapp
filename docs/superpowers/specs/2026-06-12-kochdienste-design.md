# Kochdienste — Sektion + Organisation

**Datum:** 2026-06-12
**Status:** Approved

## Zusammenfassung

Neue Sektion "Kochen" fuer alle Elternteile: Monatskalender (nur Mo-Fr) mit farbcodierten Kochdiensten aller Familien, gefiltert nach Gruppen. Dazu eine neue `organisation`-Collection (Admin-only) fuer globale Einstellungen wie Gruppen und Dienst-spezifische Konfiguration. Kalenderansicht via `angular-calendar`.

---

## 1. Datenmodell

### 1.1 FieldDefinition (erweitert)

Neues generisches Feld `properties` auf der bestehenden FieldDefinition-Entity:

```java
public Map<String, Object> properties;  // generische Metadaten (Farbe, Icon, etc.)
```

Verwendung:
- **Gruppen**: `properties: { "color": "#4285f4" }` — Farbe fuer Kalender-Hintergrund
- **Essen-Eigenschaften**: `properties: { "icon": "eco" }` — Material Icon Name fuer Kalender-Anzeige

### 1.2 `organisation` Collection (neu)

Polymorphe Collection mit zwei Dokumenten. Organisation referenziert **nur DefinitionIds** — keine FieldRefs, keine FieldInstances. Organisation ist rein konfigurativ.

**Gruppen-Dokument:**
```json
{
  "_id": "ObjectId",
  "tag": "groups",
  "definitionIds": ["ObjectId", "ObjectId"]
}
```

Jede Gruppe ist eine FieldDefinition:
- `{ fieldName: "group", label: { de: "Kindergruppe" }, jsonSchema: { type: "string" }, properties: { "color": "#4285f4" } }`
- Name lebt im `label`, Farbe in `properties`
- Gruppen haben keine FieldInstances

**Duty-Settings-Dokument:**
```json
{
  "_id": "ObjectId",
  "tag": "duty-settings",
  "entries": [
    { "name": "cooking", "definitionIds": ["ObjectId", "ObjectId"] },
    { "name": "cleaning", "definitionIds": [] },
    { "name": "time-substitution", "definitionIds": [] }
  ]
}
```

Essen-Eigenschaften unter `cooking` als boolean FieldDefinitions:
- `{ fieldName: "food-property", label: { de: "Glutenfrei" }, jsonSchema: { type: "boolean" }, properties: { "icon": "grain" } }`
- Keine FieldInstances — Organisation definiert nur welche Definitionen zu cooking gehoeren

### 1.3 Kochdienst-Eintrag (in Person.schedules)

Eine FieldDefinition `cookingDuty` mit komplexem Schema:
```json
{
  "fieldName": "cookingDuty",
  "label": { "de": "Kochdienst" },
  "jsonSchema": {
    "type": "object",
    "properties": {
      "date": { "type": "string", "format": "date" },
      "groups": { "type": "array", "items": { "type": "string" } },
      "description": { "type": "string" },
      "foodProperties": { "type": "object" }
    },
    "required": ["date", "groups"]
  }
}
```

Beispiel FieldInstance (referenziert in Person.schedules via FieldRef):
```json
{
  "definitionId": "<cookingDuty-defId>",
  "value": {
    "date": "2026-06-15",
    "groups": ["<group-defId-1>", "<group-defId-2>"],
    "description": "Pizza mit Gemuese und Obst",
    "foodProperties": {
      "<glutenfrei-defId>": true,
      "<vegetarisch-defId>": true
    }
  }
}
```

---

## 2. Backend

### 2.1 Organisation Entity

**`Organisation.java`** — Panache Entity (polymorph):
```java
@MongoEntity(collection = "organisation")
public class Organisation extends PanacheMongoEntity {
    public String tag;                         // "groups" | "duty-settings"
    public List<ObjectId> definitionIds;       // fuer tag "groups"
    public List<DutyEntry> entries;            // fuer tag "duty-settings"
}
```

**`DutyEntry.java`**:
```java
public class DutyEntry {
    public String name;                        // "cooking" | "cleaning" | "time-substitution"
    public List<ObjectId> definitionIds;
}
```

### 2.2 FieldDefinition Entity (erweitert)

```java
public class FieldDefinition extends PanacheMongoEntity {
    // ... bestehende Felder ...
    public Map<String, Object> properties;     // NEU: generische Metadaten
}
```

### 2.3 Organisation Resource

**`OrganisationResource.java`** — REST API:
- `GET /api/organisation` — alle Dokumente (Gruppen fuer alle, Duty-Settings nur Admin)
- `GET /api/organisation/{tag}` — nach Tag (z.B. "groups")
- `PUT /api/organisation/{id}` — Dokument aktualisieren (Admin-only)

### 2.4 Cooking Duties Endpunkt

**`CookingDutyResource.java`** — fuer alle Elternteile:
- `GET /api/cooking-duties?month=2026-06&groups=id1,id2`
  - Laedt alle Personen mit `cookingDuty`-FieldInstances
  - Filtert auf **Anwendungsebene** nach Monat und Gruppen (kein Aggregation Pipeline)
  - Resolved Personennamen aus basicProperties (Format: **Nachname Vorname**)
  - Gibt flache DTOs zurueck

**`CookingDutyDTO.java`**:
```java
public class CookingDutyDTO {
    public String id;                        // fieldInstance ID
    public String personId;
    public String familyId;                  // fuer Berechtigungspruefung im Frontend
    public String personName;                // "Nachname Vorname" aus basicProperties
    public String date;
    public List<String> groups;              // Group-Definition-IDs
    public String description;
    public Map<String, Boolean> foodProperties;
}
```

### 2.5 Seed Migrations

**`OrganisationSeedMigration.java`** (neu):
- Erstellt `groups`-Dokument (initial leer, Admin fuellt Gruppen)
- Erstellt `duty-settings`-Dokument mit 3 Entries (cooking, cleaning, time-substitution — initial leer)

**`FieldDefinitionSeedMigration.java`** (erweitert):
- `cookingDuty` FieldDefinition (komplexes Schema)
- Essen-Eigenschaften als Seed-Startwerte (danach von Admin frei verwaltbar):

| fieldName | Label | Icon |
|-----------|-------|------|
| food-property | Glutenfrei | `grain` |
| food-property | Weizenfrei | `do_not_disturb` |
| food-property | Vegetarisch | `eco` |
| food-property | Vegan | `spa` |
| food-property | Ohne Milchprodukte | `water_drop` |
| food-property | Histaminvertraeglich | `health_and_safety` |

Seed erstellt auch das `duty-settings/cooking` Entry mit Referenzen auf diese Definitionen.

---

## 3. Frontend

### 3.1 Eltern-Seite: Kochdienste

**Route:** `/cooking`
**Sidebar:** "Kochen" mit `mat-icon: restaurant`
**Kalender-Library:** `angular-calendar` (Monatsansicht, nur Mo-Fr)

**`CookingComponent`** — Hauptseite:
- Laedt Gruppen aus `OrganisationService`
- Laedt Kochdienste via `CookingDutyService.getByMonth(month, groups)`
- **Gruppenfilter** oben: Checkboxen fuer alle Gruppen (farbcodiert)
  - Initial nur aktiv wenn ein eigenes Kind in der Gruppe ist
  - (Gruppenzugehoerigkeit kommt spaeter aus Kind-Person-Attribut "organizational", vorerst alle aktiv)
- **Monatskalender** via `angular-calendar`:
  - Nur Mo-Fr sichtbar (Kita-Werktage)
  - Events farbcodiert nach Gruppe (Farbe aus FieldDefinition.properties.color)
  - Event-Anzeige: `Nachname Vorname - Beschreibung [Icons]`
  - Icons aus FieldDefinition.properties.icon der aktiven Essen-Eigenschaften
  - Klick auf Event oeffnet Bearbeitungs-Dialog
- **Navigation:** Vor/Zurueck-Pfeile + "Heute"-Button fuer Monatswechsel
- **Button** "Neuen Kochdienst eintragen" oben rechts → oeffnet Dialog

**`CookingDutyDialogComponent`** — Erstell-/Bearbeitungs-Dialog:
- **Wann** — `mat-datepicker` (Pflichtfeld, kein Default-Datum)
- **Fuer welche Gruppen** — Checkboxen aus Organisation/groups (Pflichtfeld)
- **Wer kocht** — Dropdown mit allen Elternteilen der eigenen Familie (vorausgewaehlt: eingeloggter User)
- **Was wird gekocht** — Textarea
- **Essen ist** — Checkboxen mit Icons (boolean FieldDefinitions aus Organisation/duty-settings/cooking)
- **Loeschen-Button** — sichtbar bei Bearbeitung eigener Familien-Eintraege und fuer Admins
- Speichert neuen FieldInstance in `schedules` der ausgewaehlten Person

**Berechtigungen:**
- Jedes Elternteil kann alle Kochdienst-Eintraege der eigenen Familie erstellen/bearbeiten/loeschen
- Admins (Rolle ADMIN) koennen alle Kochdienste bearbeiten
- Fremde Eintraege (andere Familie, kein Admin) oeffnen read-only

### 3.2 Admin-Seite: Organisation

**Route:** `/settings/organisation`
**Sidebar:** "Organisation" unter Settings-Bereich (Admin-only, nach Divider)

**`OrganisationComponent`** — Admin-Verwaltung mit Tabs:
- **Tab "Gruppen"**: CRUD fuer Gruppen-FieldDefinitions (Name + Farbe)
- **Tab "Dienst-Einstellungen"**: Unterteilt in Cooking/Cleaning/Time-Substitution
  - Jeweils CRUD fuer boolean FieldDefinitions (Name + Icon)
  - Aktuell nur Cooking befuellt (Glutenfrei, Weizenfrei, Vegetarisch, Vegan, Ohne Milchprodukte, Histaminvertraeglich)

### 3.3 Neue Services

- **`OrganisationService`** — `GET/PUT /api/organisation`, `GET /api/organisation/{tag}`
- **`CookingDutyService`** — `GET /api/cooking-duties?month=...&groups=...`
- Erstellen/Bearbeiten/Loeschen von Kochdiensten laeuft ueber bestehenden `FieldInstanceService` + `PersonService`

---

## 4. Dateien

### Neu erstellen

| Datei | Zweck |
|-------|-------|
| `backend/.../entity/Organisation.java` | Organisation Entity |
| `backend/.../entity/DutyEntry.java` | DutyEntry Subdokument |
| `backend/.../resource/OrganisationResource.java` | Organisation REST API |
| `backend/.../resource/CookingDutyResource.java` | Kochdienste Aggregations-API |
| `backend/.../dto/CookingDutyDTO.java` | Kochdienst Response DTO |
| `backend/.../migration/OrganisationSeedMigration.java` | Seed fuer Organisation-Dokumente |
| `frontend/.../cooking/cooking.component.ts` | Kalender-Hauptseite |
| `frontend/.../cooking/cooking.component.html` | Kalender-Template |
| `frontend/.../cooking/cooking-duty-dialog.component.ts` | Erstell-/Bearbeitungs-Dialog |
| `frontend/.../cooking/cooking-duty-dialog.component.html` | Dialog-Template |
| `frontend/.../cooking/services/cooking-duty.service.ts` | Kochdienste API Service |
| `frontend/.../cooking/services/organisation.service.ts` | Organisation API Service |
| `frontend/.../settings/organisation/organisation.component.ts` | Admin Organisation |
| `frontend/.../settings/organisation/organisation.component.html` | Admin Organisation Template |

### Bestehend aendern

| Datei | Aenderung |
|-------|-----------|
| `frontend/.../app.routes.ts` | Routen `/cooking` und `/settings/organisation` |
| `frontend/.../app.component.html` | Sidebar-Links "Kochen" und "Organisation" |
| `backend/.../entity/FieldDefinition.java` | Neues Feld `properties` |
| `backend/.../migration/FieldDefinitionSeedMigration.java` | `cookingDuty` + Essen-Definitionen mit properties |
| `frontend/.../shared/models/field-definition.model.ts` | `properties` Feld hinzufuegen |
| `frontend/package.json` | `angular-calendar` Dependency |
