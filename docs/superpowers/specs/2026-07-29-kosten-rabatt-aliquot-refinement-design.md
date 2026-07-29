# Kosten-Geschwisterrabatt & Aliquot — Refinement

**Datum:** 2026-07-29
**Status:** Design freigegeben, bereit für Implementierungsplan
**Baut auf:** [2026-07-29-kosten-rabatt-aliquot-design.md](2026-07-29-kosten-rabatt-aliquot-design.md)
(Commits c5426f8..0298117 auf `feature/zu-leistende-stunden`, nicht gemerged — Rework erlaubt.)

## Kontext / Auslöser

Nutzer-Feedback nach Live-Test der ersten Ausbaustufe:

1. Staffelwert **0** muss gültig sein ("ab dem X. Kind keine zusätzlichen Stunden/Kosten").
   Aktuelle Frontend-Validierung lehnt leere/0-Felder ab (irreführende Meldung
   "Staffeln müssen ab dem 2. Kind, eindeutig, aufsteigend und gültig sein").
2. Vorschau soll nur **Default + eine Zeile pro Staffel** zeigen (1 Staffel → 2 Zeilen),
   statt fix 1–4 Kinder.
3. Aliquotierung ist **getrennt** zu konfigurieren: eine für **Zu leistende Stunden**,
   eine für **Kosten** — je mit **ⓘ-Info-Icon + Hover-Tooltip** zur Erklärung.
4. Der **Geschwisterrabatt** (samt eigener Kosten-Aliquotierung) wandert von
   *Organisation* nach **Kosten pro Semester** (dort ist das Semester eindeutig).
5. Beim Anlegen eines **neuen Semesters** werden die Werte des zuletzt angelegten
   Semesters **als Default kopiert** (spart Konfigurationsaufwand).

## Entscheidungen (bestätigt)

| Frage | Entscheidung |
|---|---|
| Aliquot-Aufteilung | **Zwei unabhängige Modi** pro Semester: `stundenMode` (Stunden) + `kostenMode` (Kosten). |
| Auto-Copy-Umfang | **Alle** Semester-Config: RequiredHours, KostenDiscount, AliquotConfig (beide Modi), **und** KostenValue-Beträge. |
| Rabatt-Eligibility | Eligibility **wandert mit** in die Semester-Config: `KostenDiscount.eligibleDefinitionIds` (per Semester). `KostenDefinition.siblingDiscount` entfällt. |

## Datenmodell

### `AliquotConfig` (Collection `aliquot_configs`) — Feld-Split

```
semesterId: ObjectId
stundenMode: enum { NONE, WHOLE_MONTH, PER_DAY }   // default NONE
kostenMode:  enum { NONE, WHOLE_MONTH, PER_DAY }   // default NONE
```

Ersetzt das bisherige einzelne `mode`. Kein Alt-Daten-Migrationspfad nötig
(Feature ungemerged; leere/fehlende Config ⇒ beide `NONE`).

### `KostenDiscount` (Collection `kosten_discounts`) — Eligibility per Semester

```
semesterId: ObjectId
applyToAll: boolean
order: enum { MOST_EXPENSIVE_FIRST, LEAST_EXPENSIVE_FIRST }
tiers: List<{ fromChild: int>=2, percent: int 0..100 }>
eligibleDefinitionIds: List<ObjectId>   // greift NUR wenn applyToAll == false
```

`eligible(def) = applyToAll || eligibleDefinitionIds.contains(def.id)`.

### `KostenDefinition` — Feld entfernt

`siblingDiscount` **entfällt** (inkl. `PATCH /kosten-definitions/{id}/sibling-discount`).

### Staffelwert 0

Backend erlaubt bereits `minutesPerMonth >= 0` bzw. `percent 0..100`. **Nur Frontend**:
leeres Staffelfeld ⇒ Wert 0 (kein Fehler); unparsebare Nicht-Leer-Eingabe ⇒ Fehler.

## Auto-Copy beim Semester-Anlegen (`SemesterResource.create`)

Ablauf im POST-Handler:
1. **Vor** dem Persistieren: zuletzt angelegtes Semester ermitteln
   (`Semester.find(Sort.descending("createdAt")).firstResult()`) → `prev` (kann null sein).
2. Neues Semester persistieren.
3. Falls `prev != null`, kopieren (jeweils neues Dokument mit `semesterId = new.id`):
   - `RequiredHours` von `prev`.
   - `KostenDiscount` von `prev` (inkl. tiers, order, applyToAll, eligibleDefinitionIds).
   - `AliquotConfig` von `prev` (stundenMode, kostenMode).
   - **Alle** `KostenValue` mit `semesterId == prev.id` → dupliziert mit neuer `semesterId`
     (gleiche groupId, definitionId, amount).

Fehlt beim `prev` eine Config, wird sie nicht kopiert (kein leeres Dokument anlegen).

## Berechnungs-Wiring

- **Stunden-Soll** (`GET /hour-entries/family-summary`, `GET /hour-entries/our`):
  Aliquot-Modus = `AliquotConfig.stundenMode`.
- **Kosten** (`BilanzCalculationService`, `computeMatrix`/`computeCell`):
  Aliquot-Modus = `AliquotConfig.kostenMode`; Eligibility via
  `KostenDiscount.eligibleDefinitionIds`/`applyToAll` (nicht mehr `def.siblingDiscount`).

## Frontend

### Organisation → "Zu leistende Stunden" (bleibt)

- Soll-Config unverändert, **außer**:
  - Aliquot-Dropdown **relabelt** "Aliquotierung (Zu leistende Stunden)", bindet an
    `stundenMode`. **ⓘ-Info-Icon** neben dem Label mit Hover-Tooltip (Text unten).
  - **Vorschau**: Default-Zeile + **eine Zeile pro Staffel** (Anzahl Kinder = Staffel-
    `fromChild`), client-berechnet. Kein fixes 1–4 mehr.
  - Staffel-Validierung: leeres Feld ⇒ 0; nur bei aufsteigend/eindeutig verletzt oder
    unparsebarer Nicht-Leer-Eingabe Fehler. Meldung präzisieren.

### Organisation → Kosten-Definitionen

- Geschwisterrabatt-Checkbox (per Definition) **entfernen** (Eligibility jetzt per Semester
  in Kosten pro Semester).

### Kosten pro Semester (`kosten-pro-semester.component`) — neuer Bereich

Für das dort gewählte Semester:
- **Geschwisterrabatt**:
  - `applyToAll`-Checkbox "Rabatt auf alle Kostenpositionen anwenden".
  - Wenn `applyToAll == false`: Liste der aktiven Kostenpositionen mit **Eligibility-
    Checkboxen** (→ `eligibleDefinitionIds`).
  - Order-Dropdown *Teuerstes / Günstigstes Kind zuerst*.
  - %-Staffeln "Ab dem [X.] Kind: [Y] %", add/remove, `fromChild` eindeutig/aufsteigend/≥2,
    `percent` 0..100, **0 erlaubt**.
  - **Vorschau**: Default (1. Kind 100 %) + eine Zeile pro Staffel.
  - **ⓘ-Info-Icon** neben "Geschwisterrabatt".
- **Aliquotierung (Kosten)**: Dropdown *Keine / Ganze Monate / Taggenau* → `kostenMode`,
  mit **ⓘ-Info-Icon + Tooltip**.
- Speichern → `PUT /kosten-discount?semesterId=` und `PUT /aliquot-config?semesterId=`.

### Tooltip-Text (beide Aliquot-Icons)

> "Aliquotierung: Bei unterjährigem Ein- oder Austritt eines Kindes werden die zu
> leistenden Stunden bzw. Kosten anteilig zu den Tagen berechnet, an denen das Kind im
> jeweiligen Monat einen Platz hat. 'Ganze Monate' = angefangener Monat zählt voll;
> 'Taggenau' = taggenaue Anteilsberechnung; 'Keine' = keine Anteilsberechnung."

## Endpoints (unverändert bis auf Payload)

- `GET/PUT /api/v1/aliquot-config?semesterId=` — DTO jetzt `{ semesterId, stundenMode, kostenMode }`.
- `GET/PUT /api/v1/kosten-discount?semesterId=` — DTO um `eligibleDefinitionIds: [string]` erweitert.
- `DELETE` von `PATCH /kosten-definitions/{id}/sibling-discount` (entfällt); `KostenDefinitionDTO`
  ohne `siblingDiscount`.
- `POST /api/v1/semesters` — kopiert Config des zuletzt angelegten Semesters.

## Testing

### Backend
- `AliquotConfigResourceTest`: zwei Modi round-trip + Default NONE/NONE; Validierung je Modus.
- `KostenDiscountResourceTest`: `eligibleDefinitionIds` round-trip; Staffel percent 0 gültig.
- `KostenDefinitionResourceTest`: `siblingDiscount`-Test **entfernen**; DTO ohne Feld.
- `BilanzCalculationService`/`BilanzResourceTest`: Eligibility via `eligibleDefinitionIds`;
  `computeCell`/Matrix nutzen `kostenMode`.
- `HoursBalanceService`/`HourEntryFamilySummaryTest`/`HourEntryOurTest`: nutzen `stundenMode`.
- **Neu** `SemesterResourceTest` (o.ä.): Anlegen kopiert RequiredHours, KostenDiscount,
  AliquotConfig (beide Modi) und alle KostenValue vom zuletzt angelegten Semester;
  ohne Vorgänger keine Kopie.

### Frontend
- Zu-leistende-Stunden: Vorschau-Länge (Default + n Staffeln); leeres Staffelfeld ⇒ 0 gültig;
  Stunden-Aliquot bindet an `stundenMode`; Tooltip vorhanden.
- Kosten pro Semester: Geschwisterrabatt-Block (applyToAll, Eligibility-Checkboxen, order,
  Staffeln, Vorschau) + Kosten-Aliquot (`kostenMode`) speichern korrekt; Tooltips vorhanden.
- Organisation Kosten-Definitionen: Geschwisterrabatt-Checkbox entfernt.

## Bewusst außerhalb des Scopes

- Migration bestehender Alt-Daten (Feature ungemerged).
- Auto-Copy rückwirkend auf bereits bestehende Semester.
- Kopie von HourEntry/BilanzOverride (nur Config + KostenValue werden kopiert).
