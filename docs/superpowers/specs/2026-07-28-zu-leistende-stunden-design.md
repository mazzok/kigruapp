# Zu leistende Stunden — Soll/Ist pro Familie & Semester

**Datum:** 2026-07-28
**Status:** Design freigegeben, bereit für Implementierungsplan

## Kontext

Die Stundenerfassung (Feature `Stundenerfassung`, gemerged auf `main`, HEAD `e00a25c`)
erlaubt Eltern, geleistete Stunden pro Person und Semester zu erfassen
(`HourEntry`: `personId`, `semesterId`, `roleLabel`, `date`, `minutes`). Es fehlt
bisher jegliches **Soll**: wie viele Stunden eine Familie leisten *muss*.

Dieses Feature ergänzt:

1. Eine **Konfigurationsmaske pro Semester** ("Organisation > Zu leistende Stunden"),
   in der der Default-Monatssatz pro Kind sowie gestaffelte Rabatte ab dem X-ten Kind
   eingetragen werden.
2. Eine **Soll/Ist-Berechnung pro Familie**, gruppiert und farblich als positiv/negativ.
3. Umbau der Admin-**Stundenübersicht** von Personen- auf Familien-Gruppierung mit
   Soll/Ist und Detail-Tooltip.
4. Umbenennung von **"Meine Stunden" → "Unsere Stunden"**: familienweite Ansicht mit
   Soll/Ist im Monatsheader, sichtbar für alle Familienmitglieder.

### Bestehendes Modell (relevant)

- `Semester` = `{ start, end, createdAt }` (Instants, kein Name/Typ).
- `Family` = `{ name, address }`; `Person` hat `familyId`.
- **Kind** = `Person` mit Custom-Field `personType = 'CHILD'` in `basicProperties`.
- **Platz im Semester** = Kind hat eine Gruppen-`SemesterAssignment` (`section`,
  `entryDate`/`exitDate`) für das Semester (Collection `semester_assignments`).
- `HourEntry` (Collection `hourEntries`): pro Person + Semester, `minutes`, `date`
  (`YYYY-MM-DD`).
- **Organisation** (`/settings/organisation`) ist eine **tab-basierte** Komponente
  (Groups, Teams, Rollen, Kosten-Definitionen); `SemesterService` bereits injiziert.
- Zeit-Ein-/Ausgabe über `frontend/src/app/shared/util/time-format.util.ts`
  (`parseHhmm`, `formatMinutes`).

## Kern: Soll-Berechnung

### Konfiguration (pro Semester)

- `defaultMinutesPerMonth` (D): Stunden/Monat pro Kind (Default für Kind #1).
- `tiers`: Liste `{ fromChild: k, minutesPerMonth: r }`, aufsteigend nach `k` sortiert,
  `k` ganzzahlig ≥ 2, `k`-Werte eindeutig.

### Satz pro Kind

`rate(n)` = `minutesPerMonth` des Tiers mit größtem `k ≤ n`, sonst `D`.

### Familien-Monatssatz

`familyMonthlyMinutes = Σ_{n=1..N} rate(n)`

wobei **N = Anzahl Kinder der Familie mit Platz (Gruppen-Assignment) im gewählten
Semester**.

### Semester-Soll

`semesterSollMinutes = familyMonthlyMinutes × monthsInSemester`

`monthsInSemester` = Anzahl verschiedener Kalendermonate, die `[Semester.start,
Semester.end]` berührt (inkl. Jahresgrenze).

### Ist & Differenz

- `istMinutes` = Summe aller `HourEntry.minutes` **aller Familienmitglieder** im Semester.
- `differenz = istMinutes − semesterSollMinutes`; ≥ 0 grün, < 0 rot.

### Regeln / Annahmen

- **Keine Proration** bei unterjährigem Ein-/Austritt eines Kindes: ein Kind mit Platz
  zählt binär als 1 zu N (bestätigt).
- Kind #1 nutzt immer `D`. Die **pure Berechnungsfunktion** `monthlyRate` behandelt
  `fromChild ≥ 1` generisch (ein Tier mit `k = 1` würde den Default überschreiben —
  wird getestet); das **PUT-Endpoint** akzeptiert jedoch nur `fromChild ≥ 2`, und das
  UI staffelt ab 2. So bleibt die Funktion robust, ohne dass ein `k = 1` je gespeichert
  werden kann.
- Reihenfolge der Kinder irrelevant — nur N zählt (Soll = Σ rate(1..N)).

### Bestätigte Beispiele (D = 8 h)

| Config | N=1 | N=2 | N=3 | N=4 |
|---|---|---|---|---|
| keine Tiers | 8 | 16 | 24 | 32 |
| ab 2. Kind = 6 h | 8 | 14 | 20 | 26 |
| ab 2. = 6 h, ab 3. = 0 h | 8 | 14 | 14 | 14 |

## Backend

### Entity `RequiredHours` (Collection `requiredHours`)

```
semesterId: ObjectId
defaultMinutesPerMonth: int
tiers: List<Tier>   // { fromChild: int, minutesPerMonth: int }
```

Ein Dokument pro Semester. Kein Auto-Copy beim Anlegen eines neuen Semesters (leer,
Admin befüllt).

### Service `HoursBalanceService`

- `int monthlyRate(RequiredHours cfg, int n)` — Satz für das n-te Kind (pure).
- `int familyMonthlyMinutes(RequiredHours cfg, int n)` — Σ rate(1..n) (pure, Test-Hotspot).
- `int monthsInSemester(Semester s)` — verschiedene Kalendermonate in `[start, end]`.
- `int countPlacedChildren(ObjectId familyId, ObjectId semesterId)` — Kinder mit
  Gruppen-`SemesterAssignment` im Semester.
- Aggregation: familien-gruppierte Balances (Soll/Ist/Breakdown) und Einzel-Familie.

### DTOs

- `RequiredHoursDto` — `{ semesterId, defaultMinutesPerMonth, tiers[] }`.
- `FamilyHoursSummaryDto` — `{ familyId, familyName, childCount, familyMonthlyMinutes,
  monthsInSemester, sollMinutes, istMinutes, entries[] }` (entries = Personen-Einträge
  für die Aufklappansicht).
- `OurHoursDto` — `{ familyId, sollMinutes, istMinutes, familyMonthlyMinutes,
  months: [{ month: "YYYY-MM", sollMinutes, istMinutes }], entries: [HourEntryDto +
  personName + personId] }`.

### Endpoints

| Methode | Pfad | Zugriff |
|---|---|---|
| `GET` | `/api/v1/required-hours?semesterId=` | Admin (nicht whitelisted) |
| `PUT` | `/api/v1/required-hours?semesterId=` | Admin |
| `GET` | `/api/v1/hour-entries/family-summary?semesterId=` | Admin |
| `GET` | `/api/v1/hour-entries/our?semesterId=` | Eltern (whitelisted, server-seitig auf `familyId` des Aufrufers beschränkt) |

- `/our` in `SecurityFilter` whitelisten (analog `/me`), Datenzugriff strikt auf die
  Familie des angemeldeten Elternteils.
- Validierung `PUT /required-hours`: `defaultMinutesPerMonth > 0`; `tiers[*].fromChild`
  aufsteigend eindeutig ≥ 2; `minutesPerMonth ≥ 0`.
- Bearbeitungsrecht unverändert: bestehende `PUT`/`DELETE /hour-entries/{id}` bleiben
  **Owner-or-Admin** (Elternteil ändert nur eigene Einträge).

## Frontend

### Konfig-Tab "Zu leistende Stunden" (in `organisation.component`)

- Semester-Dropdown (Muster wie Platzzuweisung/Kosten).
- Default-Feld "Stunden pro Monat pro Kind" (HH:mm via `time-format.util`).
- Staffel-Liste: Zeilen `Ab dem [X.] Kind: [HH:mm] pro Monat`, `+ Staffel hinzufügen`,
  Löschen-Icon je Zeile. Neues Tier defaultet `fromChild` = bisheriges Max + 1.
- Client-Validierung: `fromChild` eindeutig und aufsteigend.
- **Live-Vorschau**: Tabelle "Familie mit 1/2/3/4 Kindern → x Std/Monat", client-seitig
  gerechnet.
- Speichern → `PUT /required-hours?semesterId=`.

### Admin-Übersicht "Stundenübersicht" (Umbau, familien-gruppiert)

- Eine Zeile pro Familie: **Familienname · Ist · Soll · Differenz** (Differenz grün ≥0 /
  rot <0).
- **Tooltip** auf der Zahl: Breakdown (N Kinder, Satzherleitung `8 + 6 = 14 Std/Monat`,
  × Monate, = Soll; Ist = Summe der Mitglieder).
- **Aufklappen** einer Familienzeile → bestehende Personen-Einträge-Liste (Edit/Delete
  bleibt, admin-only) darunter geschachtelt.
- Datenquelle: `GET /hour-entries/family-summary`.

### "Meine Stunden" → "Unsere Stunden" (`stunden.component`)

- Nav-Label + Überschrift umbenennen, Route `/stunden` bleibt.
- Datenquelle: `GET /hour-entries/our`.
- **Header-Block**: Familien-Soll vs. Ist (Semester), farblich positiv/negativ, für alle
  Mitglieder sichtbar.
- **Monatsgruppierung**: **alle** Kalendermonate des Semesters als Zeile, Header je Monat
  mit Soll (Monatssatz) / Ist / farblichem Delta — auch leere Monate (Ist = 0, rot).
- Einträge je Monat mit **Name des erfassenden Elternteils**; beide Elternteile sichtbar.
- Erfassungsformular bleibt; ein Elternteil kann nur **eigene** Einträge ändern/löschen
  (Partner read-only).

## Testing

### `HoursBalanceServiceTest` (Kernabsicherung "alle Kombinationen")

- keine Tiers; einzelner Tier; verschachtelte Tiers (8/6/0-Beispiel).
- N unter / auf / über Schwellen; N = 0.
- Tier mit `fromChild = 1` überschreibt Default.
- `monthsInSemester` inkl. Jahresgrenze (z.B. Sept–Feb = 6).

### Resource-Tests

- `required-hours` GET/PUT inkl. Validierung (aufsteigend/eindeutig `fromChild`,
  positive Minuten).
- `family-summary` Gruppierung + Breakdown-Werte.
- `/our` Scoping (nur eigene Familie, alle Mitglieder) und dass ein Nicht-Owner via
  bestehendem PUT/DELETE nicht ändern kann.

### Frontend-Specs

- Konfig-Tab: Live-Vorschau-Mathematik.
- Übersicht: Farbe + Tooltip.
- "Unsere Stunden": Monatsgruppierung inkl. leerer Monate.

## Bewusst außerhalb des Scopes

- Proration bei unterjährigem Kind-Ein-/Austritt.
- Automatisches Übernehmen der Config ins Folgesemester.
- Benachrichtigungen bei Soll-Unterschreitung.
```
