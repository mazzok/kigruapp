# Design: Detaillierte Hover-Tooltips für Bilanz-Zellen

Datum: 2026-07-29
Branch-Ziel: Feature auf `feature/zu-leistende-stunden` (oder eigener Branch)

## Ziel

In der Bilanzen-Matrix soll jede Zelle beim Überfahren mit der Maus (hover) einen
detaillierten Tooltip zeigen, der erklärt, **wie der angezeigte Betrag zustande
kommt**: Kostenpositionen, Geschwisterrabatt-Reduktionen und aliquote
(anteilige) Abrechnung — mit viel Detail.

## Entscheidungen (aus Brainstorming)

1. **Darstellung:** Rich HTML-Popover via Angular CDK Overlay (keine Klartext-
   Tooltips). Tabellarische, formatierte Aufschlüsselung.
2. **Umfang:** Alle Zellen bekommen einen Tooltip — aktive Zellen die volle
   Aufschlüsselung, inaktive/zukünftige einen kurzen Grund, Override-Zellen einen
   Hinweis.
3. **Datenquelle:** Breakdown wird direkt in die Matrix-Payload eingebettet
   (kein zusätzlicher HTTP-Request beim Hover).

## Berechnungsmodell (Ist-Zustand, Referenz)

Pro Zelle gilt je aktiver Kostendefinition:

```
effektiv = override                                  (falls Override existiert)
         | basis × rabattFaktor × aliquotBruch       (sonst, auf 2 Nachkommastellen)
```

- `basis` = `KostenValue` für (Semester, Gruppe, Definition)
- `rabattFaktor` ∈ [0,1] aus `KostenDiscount` (Geschwisterrabatt), nur wenn die
  Definition rabattberechtigt ist; abhängig von der Geschwister-Rangfolge
  (`order`) und dem Ordinal des Kindes.
- `aliquotBruch` ∈ [0,1] aus `AliquotService.monthFraction`; bei `PER_DAY`
  = anwesende Tage / Tage im Monat, sonst 1.
- Override umgeht **sowohl** Rabatt als auch Aliquot.

Zelle-Betrag = Summe der effektiven Beträge über alle Positionen.

## Backend

### 1. `AliquotService` — Tag-Details verfügbar machen

Neue Methode neben `monthFraction`, die zusätzlich die Zähler/Nenner liefert,
damit der Tooltip „×15/31 Tage" zeigen kann:

```java
public record MonthPresence(BigDecimal fraction, int presentDays, int daysInMonth) {}
public MonthPresence monthPresence(AliquotMode mode, String entryDate, String exitDate, int year, int month);
```

`monthFraction` bleibt bestehen (delegiert an `monthPresence().fraction()`), um
bestehende Aufrufer/Tests nicht zu brechen. Bei `NONE`/`WHOLE_MONTH` gilt
`presentDays == daysInMonth` (voller Monat).

### 2. `BilanzCalculationService` — gemeinsame Zeilen-Berechnung

Aktuell dupliziert sich die Positions-Schleife zwischen `computeCellInternal`
(nur Summe) und `computeCell` (Zeilen). Refaktorieren zu **einer** privaten
Methode, die die detaillierten Zeilen erzeugt:

```java
private CellDetail computeCellDetail(List<Person> children, int year, int month,
        List<Semester> semesters, List<KostenDefinition> activeDefs, YearMonth current);
```

`CellDetail` enthält alle bisherigen `CellComputation`-Felder **plus** die Liste
der Positionszeilen und Zell-Meta (Modus, Ein-/Austrittsdatum). `computeMatrix`
und `computeCell` bauen beide darauf auf. Damit ist die Aufschlüsselung in der
Matrix und im Dialog garantiert konsistent.

Hinweis: Die Matrix aggregiert bisher pro Kind einen einzelnen Person-Eintrag
(`single = List.of(child)`), also gibt es genau ein Ein-/Austrittsdatum pro
Zelle. Das vereinfacht die Meta-Felder.

### 3. DTO-Erweiterung — `BilanzMatrixDTO.MonthCell`

Bestehende Felder bleiben. Neu:

- `String reason` — `null` bei aktiven Zellen, sonst `"FUTURE"` oder `"NO_PLACE"`.
- `String aliquotMode` — `"NONE" | "WHOLE_MONTH" | "PER_DAY"` (nur aktive Zellen).
- `String entryDate`, `String exitDate` — ISO-Datum oder `null` (das Datum, das
  im **betreffenden Monat** liegt; sonst null — steuert bereits `entryMarker`/
  `exitMarker`).
- `List<LineBreakdown> lines` — leer bei inaktiven/zukünftigen Zellen.

Neuer Record `BilanzMatrixDTO.LineBreakdown`:

```java
public record LineBreakdown(
    String label,
    String currencySymbol,
    BigDecimal baseAmount,     // Basisbetrag vor Rabatt/Aliquot
    int discountPercent,       // 0 wenn kein/nicht anwendbar
    int discountOrdinal,       // Rang des Kindes (1 = voll), 0 wenn kein Rabatt-Kontext
    int presentDays,           // Aliquot-Zähler
    int daysInMonth,           // Aliquot-Nenner
    boolean fullMonth,         // true wenn presentDays == daysInMonth (kein Aliquot-Abzug)
    boolean overridden,        // true wenn manuell gesetzt
    BigDecimal effectiveAmount // Endbetrag dieser Position
) {}
```

`discountOrdinal` kommt aus `discountFactor` — dafür wird die Rang-Ermittlung so
erweitert, dass sie das Ordinal mitliefert (kleine Signatur-/Hilfsanpassung, rein
additiv). Kein Verhalten ändert sich für die bestehenden Beträge.

### Payload-Größe

Kinder × 12 Monate × Positionen. Bei den erwarteten Datenmengen unkritisch
(Größenordnung ≤ einige hundert KB im Extremfall). Kein Paging nötig.

## Frontend

### 4. Modell (`bilanz.model.ts`)

`BilanzMonthCell` um die neuen Felder erweitern (`reason`, `aliquotMode`,
`entryDate`, `exitDate`, `lines`), neues Interface `BilanzLineBreakdown`
analog zum Backend-Record.

### 5. Hover-Popover-Direktive

Neue standalone Direktive `BilanzCellDetailDirective`
(`[appBilanzCellDetail]`), die per CDK `Overlay` + `OverlayPositionBuilder` beim
`mouseenter` eine Detailkarte einblendet und bei `mouseleave` schließt (mit
kleinem Delay, Flicker-frei). Positionierung: bevorzugt unter der Zelle, Fallback
oben.

Die Karte ist eine kleine standalone Komponente `BilanzCellDetailCardComponent`,
die eine `BilanzMonthCell` (+ Monatslabel) als Input bekommt und rendert:

- **Kopf:** `{Monatslabel} {Jahr}` · Aliquot-Modus (lesbar: „Taggenau" /
  „Ganze Monate" / „Keine") · ggf. „Eintritt: TT.MM." / „Austritt: TT.MM."
- **Tabelle:** Spalten `Position | Basis | Rabatt | Aliquot | Effektiv`
  - Basis: `50,00 €`
  - Rabatt: `−20 % (2. Kind)` oder `—`
  - Aliquot: `×15/31` oder `voller Monat`
  - Effektiv: fett
  - Override-Zeile: statt Rabatt/Aliquot ein Badge „manuell gesetzt"
- **Fußzeile:** `Summe = {amount} {symbol}`; bei `mixedCurrency` Warnhinweis
  „gemischte Währungen".
- **Sonderfälle:** `reason === 'NO_PLACE'` → „Kein Platz in diesem Monat";
  `reason === 'FUTURE'` → „Zukünftiger Monat".

### 6. Einbindung in `bilanzen.component.ts`

Die `<td>`-Zellen erhalten `[appBilanzCellDetail]="row.months[m-1]"` und
`[appBilanzCellDetailMonthLabel]="monthLabels[m-1]"`. Bestehendes `(click)` zum
Editieren und die vorhandenen Marker-Icons bleiben unangetastet. Das alte
`matTooltip` auf dem Mixed-Currency-Icon kann entfallen (im Popover erklärt).

## Formatierung

- Beträge über Angular `DecimalPipe`/`CurrencyPipe`-Logik bzw. dieselbe Anzeige
  wie in der Zelle (Symbol als Suffix, wie bisher `{{ amount }} {{ symbol }}`).
- Datumsanzeige `TT.MM.` via `DatePipe`.
- Prozent/Tage als Klartext.

## Testing

**Backend:**
- `AliquotService`: `monthPresence` liefert korrekte `presentDays`/`daysInMonth`
  bei Eintritt/Austritt mitten im Monat (PER_DAY) und volle Werte bei
  NONE/WHOLE_MONTH.
- `BilanzCalculationService`: `computeMatrix` liefert `lines` mit korrektem
  `baseAmount`, `discountPercent`, `discountOrdinal`, `presentDays/daysInMonth`,
  `overridden`, `effectiveAmount`; Summe der `effectiveAmount` == `amount`.
- `reason` = `FUTURE`/`NO_PLACE`/`null` je nach Zellzustand.
- Regressionscheck: bestehende Matrix-/Cell-Tests bleiben grün (Beträge
  unverändert).

**Frontend:**
- `BilanzCellDetailCardComponent`: rendert Zeilen, Rabatt-/Aliquot-/Override-
  Darstellung, Sonderfall-Texte; Summe stimmt.
- Direktive: öffnet/schließt Overlay bei Hover (Smoke-Test).
- `bilanzen.component.spec` bleibt grün.

## Out of Scope

- Kein Nachladen per HTTP (Daten kommen aus der Matrix).
- Keine Änderung der Berechnungslogik oder der Editier-/Override-Funktion.
- Keine Änderung an der Bilanz-Summenlogik oder am Dialog.
