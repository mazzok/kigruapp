# Kosten-Geschwisterrabatt & Aliquot nach Ein-/Austrittsdatum

**Datum:** 2026-07-29
**Status:** Design freigegeben, bereit für Implementierungsplan

## Kontext

Zwei zusammenhängende Erweiterungen des Familien-/Semester-Abrechnungsmodells:

1. **Geschwisterrabatt für Kosten** — analog zum Staffel-Rabatt der
   [Zu leistende Stunden](2026-07-28-zu-leistende-stunden-design.md): ab dem
   2. Kind einer Familie wird die Kosten-Position reduziert.
2. **Aliquot (anteilige) Berechnung nach Ein-/Austrittsdatum** — ein pro Semester
   admin-schaltbarer Proportionalmodus, der **sowohl** das Stunden-Soll **als auch**
   die Kosten anteilig zur Anwesenheit eines Kindes berechnet.

Beide sind admin-only und werden **pro Semester** konfiguriert; historische Semester
bleiben durch Defaults (`NONE` / kein Rabatt) unverändert.

### Bestehendes Modell (relevant)

- **Zu leistende Stunden** (`feature/zu-leistende-stunden`): `RequiredHours` pro
  Semester (`defaultMinutesPerMonth` + `tiers[{fromChild, minutesPerMonth}]`).
  `HoursBalanceService` rechnet `familyMonthlyMinutes × monthsInSemester`. **Keine
  Proration**: ein platziertes Kind zählt binär, `entryDate`/`exitDate` werden ignoriert.
- **Kosten / Bilanz** (`main`): `KostenDefinition` (`label`, `currencyId`, `active`);
  `KostenValue` = **Monatsbetrag pro (semesterId, groupId, definitionId)`.
  `BilanzCalculationService` baut eine Matrix pro **Kind** × 12 Monate. Jede Monatszelle
  berücksichtigt `entryDate`/`exitDate` bereits auf **Monatsebene** (`activeInMonth`:
  Kind in einem Teil des Monats anwesend ⇒ voller Monat; außerhalb ⇒ 0).
  `BilanzOverride` erlaubt manuelle Beträge pro Kind/Jahr/Monat/Definition.
  **Kein Geschwisterrabatt** vorhanden.
- **Kind** = `Person` mit `personType = 'CHILD'`. **Platz im Semester** = Gruppen-
  `SemesterAssignment` (`section='group'`, `entryDate`/`exitDate` als `YYYY-MM-DD`).
- **Organisation** (`/settings/organisation`) ist tab-basiert (Groups, Teams, Rollen,
  Kosten-Definitionen, Zu leistende Stunden); Semester-Auswahl per Dropdown üblich.

## Kern-Entscheidungen (bestätigt)

| Frage | Entscheidung |
|---|---|
| Verpackung | **Ein** kombiniertes Spec/Plan für beide Features. |
| Rabatt-Typ Kosten | **Prozentual pro Staffel** ("ab dem 2. Kind −50 %"), auf den jeweiligen Basisbetrag des Kindes. |
| Rabatt-Geltung | Master-Checkbox "auf alle Positionen" ODER Auswahl einzelner Positionen (per-Definition-Flag). |
| Kind-Reihenfolge | Rang nach rabattfähigem Basisbetrag; Richtung per Dropdown **Teuerstes / Günstigstes Kind zuerst**. |
| Aliquot-Ort | **Ein** Umschalter pro Semester, gilt für Stunden **und** Kosten. |
| Aliquot-Methode | Enum `NONE` / `WHOLE_MONTH` / `PER_DAY`. |
| `WHOLE_MONTH` Teilmonat | **Aufrunden** (Anwesenheit an einem Tag ⇒ voller Monat); Monate außerhalb des Fensters ⇒ 0. |
| Override-Vorrang | `BilanzOverride` ist final — umgeht Rabatt **und** Aliquot. |

## Datenmodell

### `KostenDiscount` (neue Entity, Collection `kosten_discounts`)

Ein Dokument pro Semester.

```
semesterId: ObjectId
applyToAll: boolean                       // Master: Rabatt auf ALLE aktiven Positionen
tiers: List<Tier>                         // { fromChild: int >= 2, percent: int 0..100 }
order: enum { MOST_EXPENSIVE_FIRST, LEAST_EXPENSIVE_FIRST }
```

`percent` = Rabatt-Prozent auf den Basisbetrag (z.B. `{fromChild:2, percent:50}` ⇒
2. Kind −50 %). Kein Auto-Copy in Folgesemester (leer, Admin befüllt).

### `KostenDefinition` — neues Feld

```
siblingDiscount: boolean   // greift NUR wenn KostenDiscount.applyToAll == false
```

### `AliquotConfig` (neue Entity, Collection `aliquot_configs`)

Ein Dokument pro Semester.

```
semesterId: ObjectId
mode: enum { NONE, WHOLE_MONTH, PER_DAY }
```

## Berechnungssemantik

### Rabattfähigkeit einer Definition

```
eligible(def) = discountCfg.applyToAll ? true : def.siblingDiscount
```

### Aliquot-Gewicht `w(child, month) ∈ [0,1]`

Abhängig vom `AliquotConfig.mode` des Semesters:

- **`NONE`** — bestehendes Verhalten unverändert.
  - Kosten: Kind in einem Teil des Monats anwesend ⇒ `1`, sonst `0` (heutiges `activeInMonth`).
  - Stunden: `1` für **jeden** Semester-Monat, sofern das Kind einen Platz im Semester
    hat (Fenster wird ignoriert — heutiges Verhalten).
- **`WHOLE_MONTH`** — `1`, wenn das Kind im Monat (an mind. einem Tag) einen Platz hält,
  sonst `0`. Monate vor `entryDate` / nach `exitDate` ⇒ `0`.
- **`PER_DAY`** — Randmonate `presentDays / daysInMonth`; volle Innen-Monate `1`;
  außerhalb `0`. `presentDays` = Anzahl Tage im Monat innerhalb `[entryDate, exitDate]`.

Für Kosten entspricht `WHOLE_MONTH` praktisch dem heutigen Monatsverhalten; der spürbare
Zugewinn ist `PER_DAY`. Für Stunden bewirkt `WHOLE_MONTH` zusätzlich, dass Monate
außerhalb des Kind-Fensters nicht mehr zählen.

### Kosten-Geschwisterrabatt (in `BilanzCalculationService`)

Die Matrix wird pro Kind gerechnet, der Rabatt braucht aber Familienkontext. Pro
Kind+Monat-Zelle:

1. **Familien-Kinder dieses Monats** ermitteln (Gruppen-Platz, `w > 0`). Für jedes den
   **rabattfähigen Basisbetrag** berechnen: Σ `defaultAmount` über Definitionen mit
   `eligible(def) == true` (gruppen-korrekt).
2. Diese Kinder nach rabattfähigem Basisbetrag **ranken**; Richtung aus `order`. Gleichstand
   deterministisch nach `childId`.
3. **Ordinalzahl** des Kindes ⇒ `percent` = höchste Staffel mit `fromChild ≤ ordinal`
   (sonst 0). `factor = (100 − percent) / 100`.
4. `factor` **nur** auf rabattfähige Definitionen anwenden; nicht-rabattfähige (z.B. Essen)
   bleiben voll und gehen **nicht** in die Rang-Basis ein. Geldbeträge auf 2 Nachkommastellen runden.

### Zusammenführung

- **Kosten-Monatszelle** = Σ über aktive Definitionen von
  `base × (eligible ? factor : 1) × w`.
  `w` verfeinert das bestehende `activeInMonth`.
- **Stunden-Soll** = Σ über Semester-Monate von Σ über die in diesem Monat platzierten
  Familien-Kinder (`w > 0`) von `rate(ordinal) × w`. `ordinal` aus der Anzahl der in
  diesem Monat anwesenden Kinder; da alle Kinder denselben Basissatz teilen, ist `order`
  hier irrelevant. Bei `mode = NONE` reduziert sich das exakt auf das heutige
  `familyMonthlyMinutes × monthsInSemester`.
- **`BilanzOverride`**: existiert ein Override, ist dessen Betrag final — weder Rabatt
  noch `w` werden angewandt.

### Rundung

- Geld: `BigDecimal`, HALF_UP auf 2 Nachkommastellen nach Anwendung von `factor × w`.
- Minuten (Stunden): auf ganze Minuten runden nach `rate × w`.

## Backend — Endpoints

Analog zu `required-hours`, alle admin (nicht whitelisted):

| Methode | Pfad | Zweck |
|---|---|---|
| `GET` | `/api/v1/kosten-discount?semesterId=` | Rabatt-Config lesen |
| `PUT` | `/api/v1/kosten-discount?semesterId=` | Rabatt-Config schreiben |
| `GET` | `/api/v1/aliquot-config?semesterId=` | Aliquot-Modus lesen |
| `PUT` | `/api/v1/aliquot-config?semesterId=` | Aliquot-Modus schreiben |

- `KostenDefinition`-PUT erhält das Feld `siblingDiscount`.
- Validierung `PUT /kosten-discount`: `tiers[*].fromChild` aufsteigend, eindeutig, ≥ 2;
  `percent` 0..100.
- Validierung `PUT /aliquot-config`: `mode ∈ {NONE, WHOLE_MONTH, PER_DAY}`.

### DTOs

- `KostenDiscountDto` — `{ semesterId, applyToAll, tiers[{fromChild, percent}], order }`.
- `AliquotConfigDto` — `{ semesterId, mode }`.
- `BilanzCellDTO.Line` erweitert: Basis → rabattierter Betrag, und bei aktivem Aliquot
  das Gewicht `w`, damit die Herleitung sichtbar ist.

## Frontend (admin-only)

### Kosten-Definitionen-Tab

- Pro Definition eine Checkbox "Geschwisterrabatt". Deaktiviert/ausgegraut dargestellt,
  wenn im Rabatt-Config `applyToAll` aktiv ist.

### Neuer Config-Bereich "Geschwisterrabatt" (pro Semester)

- Semester-Dropdown (Muster wie Zu-leistende-Stunden-Tab).
- Master-Checkbox "Rabatt auf alle Kostenpositionen anwenden".
- Dropdown "Reihenfolge": *Teuerstes Kind zuerst / Günstigstes Kind zuerst*.
- Staffel-Liste: Zeilen "Ab dem [X.] Kind: [−Y %]", `+ Staffel hinzufügen`, Löschen je
  Zeile. Client-Validierung: `fromChild` eindeutig, aufsteigend, ≥ 2; `percent` 0..100.
  Neue Staffel defaultet `fromChild` = bisheriges Max + 1.
- Live-Vorschau: Faktortabelle (1. Kind 100 %, 2. Kind 50 %, …), client-seitig gerechnet.
- Speichern → `PUT /kosten-discount?semesterId=`.

### Aliquot-Umschalter (pro Semester, geteilt)

- Ein Dropdown *Keine / Ganze Monate / Taggenau*, im Organisation-Config-Bereich einmal
  platziert, beschriftet als "gilt für Zu leistende Stunden **und** Kosten".
- Speichern → `PUT /aliquot-config?semesterId=`.

### Bilanz-Ansicht

- Der bestehende Zell-Breakdown (`BilanzCellDTO.Line`) zeigt Basis → rabattiert und — bei
  aktivem Aliquot — das Gewicht, sodass Eltern/Admin die Betragsänderung nachvollziehen.
  Keine neue Seite; erweitert bestehende Zeilen.

## Testing

### `BilanzCalculationServiceTest`

- Ranking beide Richtungen (`MOST_/LEAST_EXPENSIVE_FIRST`); Gleichstand deterministisch.
- `applyToAll = true` vs. per-Definition-Flags; nicht-rabattfähige Position unverändert und
  nicht in Rang-Basis.
- Override umgeht Rabatt und Aliquot.
- Jeder Aliquot-Modus: `NONE` (= heutiges Verhalten), `WHOLE_MONTH` (Aufrunden;
  Außer-Fenster-Monate = 0), `PER_DAY` (Tagesbruch inkl. Rundung; Randmonate).

### `HoursBalanceServiceTest`

- Aliquot pro Monat: `NONE` reduziert sich auf `familyMonthlyMinutes × monthsInSemester`.
- `WHOLE_MONTH` lässt Außer-Fenster-Monate wegfallen.
- `PER_DAY` gewichtet Randmonate taggenau; wechselnde `N`/Ordinalzahl pro Monat.

### Resource-Tests

- `kosten-discount` GET/PUT inkl. Validierung (aufsteigend/eindeutig `fromChild`, `percent`
  0..100).
- `aliquot-config` GET/PUT inkl. Enum-Validierung.
- `KostenDefinition`-PUT mit `siblingDiscount`.

### Frontend-Specs

- Rabatt-Config: Live-Vorschau-Faktortabelle; Ausgrauen der Positions-Checkboxen bei
  `applyToAll`.
- Aliquot-Dropdown-Verdrahtung.

## Bewusst außerhalb des Scopes

- Automatisches Übernehmen der Configs ins Folgesemester.
- Benachrichtigungen bei Soll-Unterschreitung / Rabattänderungen.
- Proration manueller `BilanzOverride`-Beträge.
- Änderungen an Währungs-/Mixed-Currency-Handling.
