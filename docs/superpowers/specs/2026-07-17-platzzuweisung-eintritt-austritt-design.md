# Platzzuweisung — Eintritts-/Austrittsdatum — Design Spec

**Datum:** 2026-07-17
**Status:** Approved

---

## Überblick

Kinder können innerhalb eines Semesters in eine Gruppe ein- und wieder austreten. Platzzuweisung bekommt zwei neue, pro Kind und Semester befüllbare Datumsfelder: **Eintrittsdatum** und **Austrittsdatum**.

---

## Ausgangslage

Die Gruppenzuweisung eines Kindes ist bereits semester-scoped: pro (Person, Semester) existiert höchstens ein Datensatz in der `semester_assignments`-Collection mit `section: "group"`, der auf die gewählte Gruppen-`FieldInstance` verweist (siehe `SemesterAssignment.java`, `PersonResource.assignGroup`).

Eintritts-/Austrittsdatum sind keine Auswahl aus einer vordefinierten Liste (wie Gruppe/Team/Rolle), sondern freie Datumswerte. Sie werden deshalb nicht über das `definitionId`/`fieldInstanceId`-Auswahlmuster abgebildet, sondern als zusätzliche Felder direkt auf demselben Gruppen-Zuweisungsdatensatz gespeichert.

---

## Datenmodell

### `SemesterAssignment` (Backend-Entity)

Zwei neue, nullable Felder — gelten ausschließlich für Datensätze mit `section: "group"`:

```java
public String entryDate;  // ISO "YYYY-MM-DD", nullable
public String exitDate;   // ISO "YYYY-MM-DD", nullable
```

`toDocument()`/`fromDocument()` persistieren/lesen beide Felder (nicht gesetzt → `null`, Feld fehlt im Dokument).

### Invarianten

1. `entryDate`/`exitDate` können nur gesetzt werden, wenn für (Person, Semester) bereits eine Gruppenzuweisung existiert.
2. `exitDate` kann nur gesetzt werden, wenn `entryDate` bereits gesetzt ist.
3. `exitDate >= entryDate` (String-Vergleich reicht bei `YYYY-MM-DD`-Format).
4. Wird die Gruppenzuweisung entfernt (Gruppe auf „—“) oder auf eine andere Gruppe geändert, werden `entryDate` und `exitDate` auf `null` zurückgesetzt — die Daten gehören zur bisherigen Zuweisung, eine neue Zuweisung startet ohne Daten.

---

## Backend

### `ChildDTO` — neue Felder

```java
public record ChildDTO(
    String id,
    String firstName,
    String lastName,
    String dateOfBirth,
    String groupDefinitionId,
    String groupInstanceId,
    String entryDate,
    String exitDate
) {}
```

`toChildDTO()` löst `entryDate`/`exitDate` aus demselben Gruppen-Zuweisungslookup auf, der bereits `groupDefinitionId`/`groupInstanceId` liefert. Kein Zuweisungsdatensatz → beide `null`.

### `PATCH /api/v1/persons/{id}/group` — Reset bei Änderung

Wenn diese bestehende Route die Gruppe wechselt oder auf `null` setzt, werden `entryDate`/`exitDate` im (ggf. neuen) Zuweisungsdatensatz auf `null` gesetzt bzw. bleiben `null`.

### Neuer Endpoint: `PATCH /api/v1/persons/{id}/enrollment-dates`

Setzt Eintritts- und/oder Austrittsdatum für die Gruppenzuweisung eines Kindes in einem Semester.

**Query-Parameter:** `semesterId` (required)

**Request Body:**
```json
{ "entryDate": "2026-09-01", "exitDate": null }
```

**Validierung (400 Bad Request):**
- Keine Gruppenzuweisung für (Person, Semester) vorhanden.
- `exitDate` gesetzt, aber `entryDate` ist `null` (weder im Request noch im bestehenden Datensatz).
- `exitDate < entryDate`.

**Logik:**
- Lade den bestehenden `section: "group"`-Zuweisungsdatensatz für (Person, Semester).
- Aktualisiere `entryDate`/`exitDate` auf die übergebenen Werte (Feld nicht im Request enthalten → unverändert; explizit `null` → löschen).
- Speichere den Datensatz.

**Response:** `204 No Content`

---

## Frontend

### `ChildDTO` (Frontend-Modell, `person.model.ts`)

Ergänzt um `entryDate: string | null` und `exitDate: string | null`.

### `PersonService`

Neue Methode `setEnrollmentDates(childId: string, semesterId: string, entryDate: string | null, exitDate: string | null): Observable<void>`, ruft den neuen PATCH-Endpoint auf.

### `PlatzzuweisungComponent` — Tabelle

Zwei neue Spalten zwischen „Gruppe“ und Tabellenende: **Eintritt** und **Austritt**, je ein `mat-datepicker`-Feld.

| Name | Alter | Gruppe | Eintritt | Austritt |
|------|-------|--------|----------|----------|
| Max Muster | 6 | Bären ▾ | 📅 01.09.2026 | 📅 — |
| Anna Beispiel | 4 | — ▾ | (disabled) | (disabled) |

- **Eintritt** ist disabled, solange `child.groupInstanceId` `null` ist.
- **Austritt** ist zusätzlich disabled, solange `child.entryDate` `null` ist.
- **Auto-save:** Änderung eines Datums löst sofort `setEnrollmentDates(...)` aus (gleiches UX-Muster wie das Gruppen-Dropdown).
- Wird die Gruppe auf „—“ gesetzt oder gewechselt, werden `entryDate`/`exitDate` in der lokalen Tabellenzeile ebenfalls auf `null` gesetzt (Antwort von `assignGroup` bzw. optimistisches Update), damit die UI sofort den zurückgesetzten Zustand zeigt.

---

## Nicht im Scope

- Kein Filtern/Ausblenden von Kindern anhand der Daten (z. B. „nur aktive Kinder anzeigen“) — mögliches Folge-Feature.
- Keine Uhrzeit, nur Datum.
- Keine Änderungen am Family-Wizard / Kind-Bearbeiten-Dialog.
- Keine Migration bestehender Daten nötig — neue Felder sind rein additiv und nullable.
