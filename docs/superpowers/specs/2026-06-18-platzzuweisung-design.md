# Platzzuweisung — Design Spec

**Datum:** 2026-06-18
**Status:** Approved

---

## Überblick

Eine neue Admin-only-Sektion "Platzzuweisung" erlaubt Admins, alle Kinder in einer Tabellenansicht zu sehen und jedem Kind per Dropdown eine Gruppe zuzuweisen.

---

## Datenmodell

### Bestehende Strukturen

- **Gruppe = FieldDefinition** (`fieldName: "group"`, `label.de: "Bären"`, `properties.color: "#f00"`)
  Verwaltet in Organisation mit `tag: "groups"`.

- **Shared FieldInstance pro Gruppe** (`definitionId: <group-def-id>`, `value: true`)
  Genau eine FieldInstance pro Gruppe, gespeichert in der `field_instances`-Collection.
  Lookup: `find({ definitionId: <group-def-id> }).first()`

### Neue Sektion auf Person: organisationalUnit

`Person` bekommt eine neue Collection `organisationalUnit: List<FieldRef>` (analog zu `basicProperties`, `customProperties` etc.).

- **Zuweisung = FieldRef in Person.organisationalUnit**
  `{ definitionId: <group-def-id>, fieldInstanceId: <group-instance-id> }`
  Mehrere Kinder teilen sich dieselbe FieldInstance. Ändert sich die Zuweisung, wird nur der FieldRef der Person aktualisiert.

Betrifft: `Person.java`, `PersonDTO.java`, `CreatePersonRequest` (Frontend-Modell).

### Invariante

Pro Gruppe existiert in `field_instances` genau eine FieldInstance. Diese wird beim Anlegen der Gruppe erstellt und nie gelöscht (nur outdated gesetzt wenn die Gruppe gelöscht wird).

---

## Änderungen: Organisation → addGroup()

Beim Anlegen einer neuen Gruppe (in `organisation.component.ts`) wird nach dem Erstellen der FieldDefinition sofort eine FieldInstance angelegt:

```
POST /api/v1/field-instances  { definitionId: created.id, value: true }
```

### Migration für bestehende Gruppen

Eine einmalige Backend-Migration (`GroupInstanceMigration`) läuft beim Start und legt für jede bestehende Gruppe-FieldDefinition (ohne vorhandene FieldInstance) eine FieldInstance nach.

---

## Backend

### Neuer Endpoint: GET /api/v1/persons/children

Gibt alle Personen zurück, bei denen `personType = CHILD` (d.h. eine Rolle-FieldInstance mit fieldName "personType" und value "CHILD" existiert).

Response pro Kind:
```json
{
  "id": "...",
  "firstName": "...",
  "lastName": "...",
  "dateOfBirth": "2019-03-15",
  "groupDefinitionId": "...",
  "groupInstanceId": "..."
}
```

- `firstName`, `lastName`, `dateOfBirth` werden aus `basicProperties` aufgelöst (FieldInstance-Werte der entsprechenden FieldDefinitions).
- `groupDefinitionId` + `groupInstanceId`: der FieldRef aus `organisationalUnit` mit einer FieldDefinition vom fieldName "group". Null wenn noch keine Gruppe zugewiesen.

### Neuer Endpoint: PATCH /api/v1/persons/{id}/group

Setzt oder ersetzt die Gruppenzuweisung eines Kindes.

Request body:
```json
{ "definitionId": "...", "fieldInstanceId": "..." }
```

Logik:
- Suche in `person.organisationalUnit` nach einem FieldRef mit einer FieldDefinition vom fieldName "group".
- Gefunden → ersetze `fieldInstanceId` (und `definitionId`) in diesem FieldRef.
- Nicht gefunden → füge neuen FieldRef hinzu.
- Speichere Person.

---

## Frontend

### Route & Navigation

- Route: `/administration/platzzuweisung` (unter bestehendem `adminGuard`)
- Sidebar-Eintrag unter dem Admin-Block (wie "Familien"), Icon: `groups`

### Komponente: PlatzzuweisungComponent

**Laden:**
1. `GET /api/v1/persons/children` → Kinderliste
2. `GET /api/v1/organisation/groups` → Gruppen (FieldDefinitions mit label + color)

**Tabelle** mit Spalten:

| Name | Alter | Gruppe |
|------|-------|--------|
| Max Muster | 6 | [Dropdown: Bären ▾] |
| Anna Beispiel | 4 | [Dropdown: — ▾] |

- **Alter**: berechnet aus `dateOfBirth` zum heutigen Datum (Jahre)
- **Gruppe**: `mat-select` mit einer Option pro Gruppe (Gruppenname als Label). Leere Option "—" für keine Zuweisung.
- **Auto-save**: bei Dropdown-Änderung sofort `PATCH /api/v1/persons/{id}/group`

### Kein neuer Service

Nutzt bestehenden `PersonService` (neuer `getChildren()`-Aufruf) und `OrganisationService` (bestehender `getByTag('groups')`-Aufruf). `FieldInstanceService` wird nicht direkt genutzt — Zuweisung geht über den neuen PATCH-Endpoint.

---

## Nicht im Scope

- Farbanzeige der Gruppe im Dropdown (kann später ergänzt werden)
- Kapazitätsprüfung pro Gruppe
- Zuweisung entfernen (Gruppe auf "—" setzen) erfordert DELETE des FieldRef — kann in einem Folge-Task ergänzt werden
