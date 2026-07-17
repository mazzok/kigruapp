# Elterneinteilung — Rollen innerhalb von Teams

**Datum:** 2026-07-01

## Überblick

Erweiterung der Elterneinteilung um Rollen innerhalb von Teams. Jedes Team kann eine oder mehrere Rollen haben (z.B. Team "Garten" → Rolle "Spielplatz"). Rollen haben optionale Min/Max-Anzahlen für Elternzuweisungen. Die Zuweisung von Rollen erfolgt im Elterneinteilung-Admin-Screen in einer separaten Spalte neben den Teams.

## Datenmodell

### Organisation-Konfiguration: Tag `'parent-team-roles'`

Neuer Org-Tag `'parent-team-roles'` mit einer FieldDefinition (fieldName: `'parent-team-role'`). Jede Rolle ist eine FieldInstance dieser Definition.

FieldInstance-Value einer Rolle:
```json
{
  "label": "Spielplatz",
  "teamInstanceId": "<id der Team-FieldInstance>",
  "min": 1,
  "max": 2
}
```

- `label`: Pflichtfeld
- `teamInstanceId`: Referenz auf die übergeordnete Team-FieldInstance
- `min`: optional (null = keine Mindestanzahl)
- `max`: optional (null = keine Obergrenze)

Alle Elternteile mit derselben Rolle referenzieren **dieselbe** FieldInstance (1:1 zwischen Rolle und FieldInstance, n:1 zwischen Elternteilen und FieldInstance).

### Neue Person-Sektion: `assignedRole`

`Person` bekommt eine neue Sektion `assignedRole: FieldRef[]`, getrennt von `assignedDuty` (Teams).

```typescript
// Bestehend (unverändert):
assignedDuty: FieldRef[]   // Team-Zuweisungen

// Neu:
assignedRole: FieldRef[]   // Rollen-Zuweisungen
```

Backend: `List<FieldRef>` auf Person-Entity, `List<FieldInstanceDTO>` im PersonDTO.

Initial leer (`[]`) für alle bestehenden Personen — keine Datenmigration nötig.

### Neuer Backend-Endpoint

```
PATCH /persons/{id}/assigned-role
Body: { definitionId: string, fieldInstanceId: string }
```

Toggle-Logik identisch zu `PATCH /persons/{id}/assigned-duty`: enthält Person bereits die Referenz → entfernen, sonst → hinzufügen.

## UI / Screens

### A) Settings > Organisation — Tab "Elterneinteilung"

Team-Zeilen werden ausklappbar (Expand-Icon in der Tabelle). Beim Aufklappen erscheint eine Sub-Tabelle der Rollen dieses Teams:

| Rolle | Min | Max | |
|-------|-----|-----|---|
| Spielplatz | 1 | 2 | 🗑 |
| _(Eingabezeile)_ | [Label*] | [Min] | [Max] | [+ Hinzufügen] |

- Label ist Pflicht, Min und Max sind optionale Zahlenfelder
- Löschen einer Rolle entfernt die FieldInstance (kein Cascade auf Person.assignedRole — analog zum bestehenden Team-Verhalten)
- Löschen eines Teams löscht **nicht** automatisch seine Rollen (kein Scope in diesem Feature)

### B) Administration > Elterneinteilung — Zuteilungsscreen

Spalten: `Name | Teams | Rollen`

**Rollen-Spalte:**
- Zeigt nur Rollen deren `teamInstanceId` auf ein aktuell zugewiesenes Team der Person zeigt
- Chip-Stil identisch zur Teams-Spalte
- Toggle: Klick weist Rolle zu oder entfernt sie
- Max erreicht (andere Personen haben Rolle bereits `max`-mal): Chip disabled + ausgegraut + Tooltip `"Maximale Anzahl (X) erreicht"`
- Max-Zählung: Frontend zählt über alle geladenen Personen wie viele `assignedRole`-Einträge auf dieselbe FieldInstance zeigen

**Confirmation-Dialog beim Team-Abwählen:**

Wenn eine Person in einem Team Rollen zugewiesen hat und das Team abgewählt wird, erscheint ein Bestätigungs-Dialog:

> *"[Vorname Nachname] hat im Team [Teamname] folgende Rollen zugewiesen: [Rolle1, Rolle2]. Team abwählen entfernt diese Rollen. Fortfahren?"*
> **[Ja] [Nein]**

- **Nein** → kein API-Call, Team bleibt ausgewählt
- **Ja** → Team-Toggle-API-Call + Rollen-Entfernung per `PATCH /persons/{id}/assigned-role` für jede betroffene Rolle
- Kein Dialog wenn keine Rollen des Teams zugewiesen sind (direktes Toggle wie bisher)

## Backend-Änderungen

### Person-Entity & DTOs

```java
// Person.java
private List<FieldRef> assignedRole = new ArrayList<>();

// PersonDTO.java
private List<FieldInstanceDTO> assignedRole = new ArrayList<>();

// PersonSectionDTO.java (für PATCH-Endpoint)
private List<SectionInput> assignedRole;
```

Neuer PATCH-Endpoint in `PersonResource`:
```
PATCH /persons/{id}/assigned-role
```

Analog zu `/assigned-duty`: Toggle-Logik in `PersonService`.

### Keine Änderungen nötig an

- FieldDefinition / FieldInstance (bestehende Infrastruktur wird genutzt)
- OrganisationResource (bestehende `getByTag` + `update` Endpoints reichen)
- Auth / Security (gleiche Berechtigungen wie Elterneinteilung-Teams)

## Komponenten-Struktur

### Geänderte Dateien

```
frontend/src/app/
  shared/models/person.model.ts              ← assignedRole: FieldRef[] / FieldInstanceDTO[]
  shared/services/person.service.ts          ← assignRole() Methode
  settings/organisation/
    organisation.component.ts               ← expandierbare Team-Zeilen + Rollen-Sub-Tabelle
    organisation.component.html
  administration/elterneinteilung/
    elterneinteilung.component.ts           ← Rollen-Spalte, Max-Zählung, Confirmation-Dialog
    elterneinteilung.component.html

backend/src/main/java/de/kigruapp/
  entity/Person.java                        ← assignedRole: List<FieldRef>
  dto/PersonDTO.java                        ← assignedRole: List<FieldInstanceDTO>
  resource/PersonResource.java              ← PATCH /persons/{id}/assigned-role
  service/PersonService.java                ← assignRole() Toggle-Logik
```

### Keine neuen Services

`OrganisationService` und `FieldInstanceService` werden unverändert wiederverwendet.

## Sicherheit / Randfälle

- Max-Prüfung erfolgt **nur im Frontend** (optimistic, kein Backend-Enforcement) — analog zum bestehenden Team-Toggle-Verhalten
- Gleichzeitige Zuweisungen durch zwei Admins können Max überschreiten (akzeptiertes Risiko, kein Scope)
- Rollen ohne zugehöriges Team (weil Team-Instance gelöscht wurde) werden in der UI nicht angezeigt (gefiltert nach `teamInstanceId`)
