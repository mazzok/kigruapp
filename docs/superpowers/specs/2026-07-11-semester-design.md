# Semester-Konzept — Design Spec

**Datum:** 2026-07-11
**Status:** Approved

---

## Überblick

Ein neues Zeitkonzept "Semester" wird eingeführt. Ein Semester hat einen fixen Start- und Endzeitraum (z.B. 01.09.xxxx – 31.08.xxxx+1). Die Zuordnung von Kindern zu Gruppen, Erwachsenen zu Teams und die Rollen-Zuordnung innerhalb von Teams werden semesterabhängig: dieselbe Person kann in unterschiedlichen Semestern unterschiedliche Gruppen-/Team-/Rollen-Zuordnungen haben, ohne dass ältere Zuordnungen überschrieben werden.

Gruppen, Teams und Rollen selbst (die Definitionen, z.B. "Bären-Gruppe", "Team Garten") bleiben **global und semesterübergreifend wiederverwendbar** — nur die Zuordnung "wer ist wann welcher Gruppe/Team/Rolle zugeteilt" wird pro Semester gespeichert.

Motivation: sowohl **Historie** (alte Zuordnungen bleiben nachvollziehbar, statt überschrieben zu werden) als auch **Vorausplanung** (Admins können Zuordnungen für ein zukünftiges Semester vorbereiten, ohne die aktuelle Zuordnung zu stören).

---

## Datenmodell

### Neue Entity: `Semester`

Collection `semesters`.

```java
public class Semester extends PanacheMongoEntity {
    public LocalDate start;
    public LocalDate end;
    public Instant createdAt;
}
```

Kein Label-Feld — das Frontend leitet ein Anzeige-Label `"{Startjahr}/{Endjahr}"` (z.B. "2026/27") direkt aus `start`/`end` ab.

**Validierung:** Beim Anlegen eines neuen Semesters wird geprüft, dass sich der Zeitraum `[start, end]` mit keinem bestehenden Semester überschneidet. Überlappung → 400.

### Neue Entity: `SemesterAssignment`

Collection `semester_assignments`. Ersetzt die bisherigen `organisationalUnit`-, `assignedDuty`- und `assignedRole`-Listen auf `Person` vollständig.

```java
public class SemesterAssignment extends PanacheMongoEntity {
    public ObjectId personId;
    public ObjectId semesterId;
    public String section;        // "group" | "team" | "role"
    public ObjectId definitionId;
    public ObjectId fieldInstanceId;
}
```

**Semantik pro `section`:**

- **`"group"`** — 1:1-Beziehung. Pro `(personId, semesterId)` existiert maximal eine Zeile mit `section = "group"`. Eine neue Zuweisung ersetzt die bestehende Zeile für dieses Semester (Zuordnungen anderer Semester bleiben unangetastet).
- **`"team"` / `"role"`** — many-to-many, Toggle-Semantik (wie bisher bei `assignedDuty`/`assignedRole`). Pro `(personId, semesterId, section)` können mehrere Zeilen existieren, eine je `(definitionId, fieldInstanceId)`-Kombination. Zuweisen/Entfernen = Zeile einfügen bzw. löschen.

### Entfernt: `Person.organisationalUnit`, `Person.assignedDuty`, `Person.assignedRole`

Diese drei Felder entfallen aus `Person`, `PersonDTO` und `CreatePersonRequest`. Die generischen Sections `basicProperties`, `roles`, `schedules`, `duties`, `finance`, `customProperties` bleiben unverändert (nicht semesterabhängig).

**Nebeneffekt:** Der Family-Wizard sendet beim Anlegen einer Person aktuell ein leeres `organisationalUnit: []` mit (`family-wizard.component.ts:135,149`); im Edit-Pfad (`submitEdit()`) wird das Feld dagegen gar nicht gesetzt (`request.organisationalUnit()` bleibt `null`), sodass der Backend-Check `if (request.organisationalUnit() != null)` (`PersonResource.java:206`) es unverändert lässt. Mit Entfernung des Felds aus `CreatePersonRequest` entfällt diese Sonderbehandlung komplett: Gruppen-Zuordnung läuft ausschließlich noch über Platzzuweisung, vollständig entkoppelt von Person-Anlage/-Bearbeitung.

---

## Backend

### Neu: `SemesterResource`

- `GET /api/v1/semesters` — Liste aller Semester, sortiert nach `createdAt` absteigend. Bestimmt die Reihenfolge im Frontend-Dropdown (erster Eintrag = zuletzt angelegtes Semester = Standardauswahl).
- `POST /api/v1/semesters` `{ start, end }` — legt ein neues Semester an. 400 bei Zeitraum-Überlappung mit bestehendem Semester.

### Geändert: Zuordnungs-Endpunkte bekommen `semesterId`

- `PATCH /api/v1/persons/{id}/group?semesterId=...` `{ definitionId, fieldInstanceId }` — Upsert in `SemesterAssignment` (section="group") für das angegebene Semester.
- `PATCH /api/v1/persons/{id}/assigned-duty?semesterId=...` `{ definitionId, fieldInstanceId }` — Toggle in `SemesterAssignment` (section="team").
- `PATCH /api/v1/persons/{id}/assigned-role?semesterId=...` `{ definitionId, fieldInstanceId }` — Toggle in `SemesterAssignment` (section="role").
- `GET /api/v1/persons/children?semesterId=...` (Platzzuweisung) — löst die Gruppen-Zuordnung pro Kind für das angegebene Semester auf (statt aus `Person.organisationalUnit`).
- Der von Elterneinteilung genutzte Personen-Listen-Endpoint bekommt ebenfalls `?semesterId=...` und löst Team-/Rollen-Zuordnung entsprechend über `SemesterAssignment` auf.

### Migration: `SemesterBootstrapMigration`

Läuft beim Start (analog zu bestehenden Migrationen wie `OrganisationSeedMigration`):

1. Falls keine `Semester`-Dokumente existieren: automatisch eines erzeugen, dessen Zeitraum das heutige Datum enthält (01.09.–31.08. des Folgejahres, passend zum aktuellen Kalenderdatum).
2. Für jede bestehende `Person`: die bisherigen `organisationalUnit`/`assignedDuty`/`assignedRole`-FieldRefs auslesen und als `SemesterAssignment`-Zeilen mit `semesterId` = dem soeben erzeugten Bootstrap-Semester neu anlegen.
3. Die alten Felder werden aus dem Code entfernt. Evtl. noch in Mongo vorhandene Altfelder auf bestehenden `Person`-Dokumenten werden von Panache beim Lesen ignoriert — kein explizites Cleanup nötig.

### Löschen einer Person

`DELETE /persons/{id}` bekommt einen zusätzlichen Cleanup-Schritt: alle `SemesterAssignment`-Zeilen mit dieser `personId` werden mitgelöscht.

---

## Frontend

### Neuer Tab "Semester" in Organisation-Settings

`organisation.component.html`/`.ts` bekommt einen vierten `mat-tab` "Semester", analog zu den bestehenden Tabs (Gruppen, Dienst-Einstellungen, Elterneinteilung):

- Formular: Start-Datum, End-Datum (Date-Picker) → `POST /semesters`.
- Tabelle darunter: Spalten "Zeitraum" (abgeleitetes Label `"{Startjahr}/{Endjahr}"`), Start, Ende — sortiert nach `createdAt` absteigend.
- Kein Edit/Delete in dieser Version.

Neuer `SemesterService` in `shared/services`: `getAll()`, `create(start, end)`.

### Platzzuweisung-Screen

- Neues `mat-select`-Dropdown über der Tabelle, befüllt aus `SemesterService.getAll()`. Vorausgewählt ist der erste Eintrag der Liste (= zuletzt angelegtes Semester).
- Wechsel im Dropdown lädt `getChildren(semesterId)` neu und rendert die Gruppen-Spalte für das gewählte Semester.
- `onGroupChange` sendet den aktuell gewählten `semesterId` als Query-Param mit.

### Elterneinteilung-Screen

- Gleiches Dropdown-Muster wie Platzzuweisung.
- `toggleTeam`/`toggleRole` senden den gewählten `semesterId` mit; die Tabelle zeigt Team-/Rollen-Zuweisung für das aktuell gewählte Semester.

### Family-Wizard

- `organisationalUnit: []` wird aus den Create- und Update-Payloads entfernt (Feld existiert nicht mehr in `CreatePersonRequest`).

---

## Randfälle

- Gruppe/Team/Rolle wird in Organisation-Settings gelöscht, während `SemesterAssignment`-Zeilen darauf verweisen: wie bisher (siehe Elterneinteilung 07-01-Design) — dangling Refs werden beim Auflösen gefiltert/ignoriert, kein Fehler.
- Zwei Semester mit identischem oder überlappendem Zeitraum: durch die Überlappungs-Validierung beim Anlegen ausgeschlossen.
- Nur ein Semester vorhanden (Normalfall direkt nach Rollout): Dropdown zeigt eine Option, keine Sonderbehandlung nötig.
- "Zuletzt angelegtes Semester" bezieht sich auf `createdAt`, nicht auf den Datumsbereich — legt ein Admin nachträglich ein Semester für einen vergangenen Zeitraum an, wird trotzdem dieses (weil zuletzt angelegt) zum neuen Standard im Dropdown.

---

## Nicht im Scope

- Bearbeiten/Löschen von Semestern nach dem Anlegen.
- Semester-Zuordnung für Dienste/Kochdienste (nur Gruppen/Teams/Rollen betroffen).
- Kapazitätsprüfung pro Gruppe/Semester.
- Automatischer Rollover (z.B. Zuordnungen eines endenden Semesters automatisch ins nächste kopieren).
