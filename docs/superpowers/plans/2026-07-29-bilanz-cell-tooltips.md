# Bilanz-Zellen Hover-Tooltips Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jede Zelle der Bilanzen-Matrix zeigt beim Hover eine Detailkarte, die den Betrag aus Kostenpositionen, Geschwisterrabatt und aliquoter Abrechnung herleitet.

**Architecture:** Das Backend reichert jede `MonthCell` der Matrix-Payload um eine Positions-Aufschlüsselung (`lines`) plus Zell-Meta (Aliquot-Modus, Ein-/Austrittsdatum, Grund bei inaktiven Zellen) an — berechnet aus denselben Werten, die bereits den Betrag ergeben. Das Frontend rendert diese Daten in einer per CDK-Overlay eingeblendeten Detailkarte.

**Tech Stack:** Backend Quarkus/Java 21 (Panache, MongoDB, JUnit 5 + RestAssured). Frontend Angular 18 standalone + Angular Material + `@angular/cdk/overlay`, Jasmine/Karma.

## Global Constraints

- Beträge/Berechnungslogik dürfen sich **nicht** ändern — nur additive Anreicherung der Payload. Bestehende Backend- und Frontend-Tests bleiben grün.
- Neue DTO-Felder werden **hinten** an die Records angehängt (Reihenfolge der bestehenden Felder unverändert).
- Geld = `BigDecimal`, Rundung `HALF_UP`, 2 Nachkommastellen für Effektivbeträge (wie bestehend).
- Aliquot-Bruch: Scale 6, `HALF_UP` (wie `AliquotService.monthFraction`).
- Frontend-Anzeige der Beträge wie bisher: `{{ amount }} {{ symbol }}` (Symbol als Suffix).
- Sonderfall-Grund als String-Enum: `"FUTURE"` (zukünftiger Monat) | `"NO_PLACE"` (kein Platz) | `null` (aktiv).

---

### Task 1: `AliquotService.monthPresence` — Tage verfügbar machen

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/AliquotService.java`
- Test: `backend/src/test/java/at/kigruapp/service/AliquotServiceTest.java`

**Interfaces:**
- Produces: `record MonthPresence(BigDecimal fraction, int presentDays, int daysInMonth)`; `MonthPresence monthPresence(AliquotMode mode, String entryDate, String exitDate, int year, int month)`. `monthFraction(...)` bleibt als Delegator erhalten.

- [ ] **Step 1: Write the failing tests**

In `AliquotServiceTest.java` hinzufügen:

```java
@Test
void monthPresence_perDayReportsDayCounts() {
    // enter Nov 16 in a 30-day month -> days 16..30 = 15 present of 30
    AliquotService.MonthPresence p =
            service.monthPresence(AliquotMode.PER_DAY, "2026-11-16", null, 2026, 11);
    assertEquals(15, p.presentDays());
    assertEquals(30, p.daysInMonth());
    assertEquals(0, new java.math.BigDecimal("0.5").compareTo(p.fraction().stripTrailingZeros()));
}

@Test
void monthPresence_wholeMonthIsFullDaysAndFractionOne() {
    AliquotService.MonthPresence p =
            service.monthPresence(AliquotMode.WHOLE_MONTH, "2026-11-16", null, 2026, 11);
    assertEquals(30, p.presentDays());
    assertEquals(30, p.daysInMonth());
    assertEquals(0, java.math.BigDecimal.ONE.compareTo(p.fraction()));
}

@Test
void monthPresence_outsideWindowIsZeroPresentDays() {
    AliquotService.MonthPresence p =
            service.monthPresence(AliquotMode.PER_DAY, "2026-11-01", "2027-01-31", 2026, 10);
    assertEquals(0, p.presentDays());
    assertEquals(31, p.daysInMonth());
    assertEquals(0, p.fraction().signum());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=AliquotServiceTest`
Expected: FAIL — `MonthPresence` / `monthPresence` nicht vorhanden (Kompilierfehler).

- [ ] **Step 3: Implement `monthPresence` and delegate `monthFraction`**

In `AliquotService.java` den Rechenkern in `monthPresence` verschieben und `monthFraction` delegieren lassen:

```java
public record MonthPresence(BigDecimal fraction, int presentDays, int daysInMonth) {}

/** Presence weight in [0,1] for a child in the given month, plus the day counts behind it. */
public MonthPresence monthPresence(AliquotMode mode, String entryDate, String exitDate, int year, int month) {
    LocalDate monthStart = LocalDate.of(year, month, 1);
    LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());
    int daysInMonth = monthEnd.getDayOfMonth();

    LocalDate entry = parse(entryDate);
    LocalDate exit = parse(exitDate);

    LocalDate effStart = (entry != null && entry.isAfter(monthStart)) ? entry : monthStart;
    LocalDate effEnd = (exit != null && exit.isBefore(monthEnd)) ? exit : monthEnd;

    if (effStart.isAfter(effEnd)) {
        return new MonthPresence(BigDecimal.ZERO, 0, daysInMonth); // not present at all
    }
    if (mode == AliquotMode.PER_DAY) {
        int presentDays = (int) (ChronoUnit.DAYS.between(effStart, effEnd) + 1);
        BigDecimal frac = BigDecimal.valueOf(presentDays)
                .divide(BigDecimal.valueOf(daysInMonth), 6, RoundingMode.HALF_UP);
        return new MonthPresence(frac, presentDays, daysInMonth);
    }
    // NONE / WHOLE_MONTH: present any day -> full month
    return new MonthPresence(BigDecimal.ONE, daysInMonth, daysInMonth);
}

public BigDecimal monthFraction(AliquotMode mode, String entryDate, String exitDate, int year, int month) {
    return monthPresence(mode, entryDate, exitDate, year, month).fraction();
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AliquotServiceTest`
Expected: PASS (neue + alle bestehenden `monthFraction`-Tests grün).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/AliquotService.java backend/src/test/java/at/kigruapp/service/AliquotServiceTest.java
git commit -m "feat(be): AliquotService.monthPresence exposes present/total day counts"
```

---

### Task 2: `discountResult` — Rang (Ordinal) des Kindes mitliefern

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/BilanzCalculationService.java:406-437`
- Test: `backend/src/test/java/at/kigruapp/service/BilanzDiscountTest.java`

**Interfaces:**
- Produces: `public record DiscountResult(BigDecimal factor, int ordinal)`; `public DiscountResult discountResult(KostenDiscount cfg, String targetChildId, List<ChildBase> present)`. `discountFactor(...)` bleibt und delegiert an `discountResult(...).factor()`. `ordinal` ist 1-basiert (1 = erstes/volles Kind), `0` wenn Kind nicht gefunden oder `cfg == null`.

- [ ] **Step 1: Write the failing test**

In `BilanzDiscountTest.java` hinzufügen:

```java
@Test
void discountResult_reportsOneBasedOrdinal() {
    KostenDiscount c = cfg("MOST_EXPENSIVE_FIRST", new int[]{2, 50});
    List<BilanzCalculationService.ChildBase> present =
            List.of(cb("a", "100"), cb("b", "80"), cb("c", "60"));
    assertEquals(1, svc.discountResult(c, "a", present).ordinal()); // most expensive -> 1st
    assertEquals(2, svc.discountResult(c, "b", present).ordinal());
    assertEquals(3, svc.discountResult(c, "c", present).ordinal());
    assertEquals(0, new java.math.BigDecimal("0.5000")
            .compareTo(svc.discountResult(c, "b", present).factor()));
}

@Test
void discountResult_nullConfigOrdinalZero() {
    assertEquals(0, svc.discountResult(null, "a", List.of()).ordinal());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=BilanzDiscountTest`
Expected: FAIL — `discountResult` / `DiscountResult` nicht vorhanden.

- [ ] **Step 3: Refactor `discountFactor` into `discountResult`**

`discountFactor` durch die folgenden zwei Member ersetzen (Ranking-Logik unverändert, nur Ordinal wird zusätzlich zurückgegeben):

```java
public record DiscountResult(BigDecimal factor, int ordinal) {}

/** Discount factor (0..1) and 1-based sibling rank for targetChild among present children. */
public DiscountResult discountResult(KostenDiscount cfg, String targetChildId, List<ChildBase> present) {
    if (cfg == null || cfg.tiers == null || cfg.tiers.isEmpty()) {
        return new DiscountResult(BigDecimal.ONE, ordinalOf(cfg, targetChildId, present));
    }
    int ordinal = ordinalOf(cfg, targetChildId, present);
    if (ordinal < 1) {
        return new DiscountResult(BigDecimal.ONE, 0);
    }
    int percent = 0;
    int bestFrom = 0;
    for (KostenDiscount.Tier t : cfg.tiers) {
        if (t.fromChild <= ordinal && t.fromChild >= bestFrom) {
            bestFrom = t.fromChild;
            percent = t.percent;
        }
    }
    BigDecimal factor = BigDecimal.valueOf(100 - percent)
            .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    return new DiscountResult(factor, ordinal);
}

/** 1-based rank of targetChild by discountable base per cfg.order; 0 if cfg null or not found. */
private int ordinalOf(KostenDiscount cfg, String targetChildId, List<ChildBase> present) {
    if (cfg == null) {
        return 0;
    }
    List<ChildBase> ranked = new ArrayList<>(present);
    Comparator<ChildBase> byBase = Comparator.comparing((ChildBase b) -> b.base);
    boolean leastFirst = "LEAST_EXPENSIVE_FIRST".equals(cfg.order);
    Comparator<ChildBase> cmp = (leastFirst ? byBase : byBase.reversed())
            .thenComparing(b -> b.childId);
    ranked.sort(cmp);
    for (int i = 0; i < ranked.size(); i++) {
        if (ranked.get(i).childId.equals(targetChildId)) {
            return i + 1;
        }
    }
    return 0;
}

public BigDecimal discountFactor(KostenDiscount cfg, String targetChildId, List<ChildBase> present) {
    return discountResult(cfg, targetChildId, present).factor();
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=BilanzDiscountTest`
Expected: PASS (neue Ordinal-Tests + bestehende `discountFactor`-Tests grün).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/BilanzCalculationService.java backend/src/test/java/at/kigruapp/service/BilanzDiscountTest.java
git commit -m "feat(be): expose sibling ordinal via discountResult"
```

---

### Task 3: Matrix-Payload um Positions-Aufschlüsselung anreichern

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/dto/BilanzMatrixDTO.java`
- Modify: `backend/src/main/java/at/kigruapp/service/BilanzCalculationService.java` (`CellComputation`, `computeCellInternal`, `computeMatrix`)
- Test: `backend/src/test/java/at/kigruapp/resource/BilanzResourceTest.java`

**Interfaces:**
- Consumes: `AliquotService.monthPresence` (Task 1), `discountResult` (Task 2).
- Produces: erweiterte `BilanzMatrixDTO.MonthCell` mit Feldern `String reason, String aliquotMode, String entryDate, String exitDate, List<LineBreakdown> lines` (hinten angehängt); neuer Record `BilanzMatrixDTO.LineBreakdown`.

- [ ] **Step 1: Write the failing tests**

In `BilanzResourceTest.java` hinzufügen (nutzt die vorhandenen Helfer). Test A: aktive Zelle mit Rabatt + Taggenau-Aliquot liefert korrekte Zeilen. Es braucht einen `AliquotConfig` (kostenMode=PER_DAY) und `KostenDiscount`. Dafür zwei kleine Helfer am Testende ergänzen und in den Tests nutzen:

```java
private void setAliquotKostenMode(ObjectId semesterId, String kostenMode) {
    at.kigruapp.entity.AliquotConfig c = new at.kigruapp.entity.AliquotConfig();
    c.semesterId = semesterId;
    c.stundenMode = "NONE";
    c.kostenMode = kostenMode;
    c.persist();
}

private void setDiscount(ObjectId semesterId, int fromChild, int percent) {
    at.kigruapp.entity.KostenDiscount d = new at.kigruapp.entity.KostenDiscount();
    d.semesterId = semesterId;
    d.applyToAll = true;
    d.order = "MOST_EXPENSIVE_FIRST";
    at.kigruapp.entity.KostenDiscount.Tier t = new at.kigruapp.entity.KostenDiscount.Tier();
    t.fromChild = fromChild; t.percent = percent;
    d.tiers = java.util.List.of(t);
    d.eligibleDefinitionIds = null;
    d.persist();
}
```

Und die Cleanups um die neuen Collections erweitern (in `fullCleanup()` ergänzen):

```java
at.kigruapp.entity.AliquotConfig.deleteAll();
at.kigruapp.entity.KostenDiscount.deleteAll();
```

Testfälle:

```java
@Test
void matrixCellCarriesLineBreakdownWithBaseDiscountAndAliquot() {
    fullCleanup();
    ObjectId semesterId = createSemester(2020);
    ObjectId currencyId = createCurrency("EUR", "€");
    ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
    ObjectId groupId = new ObjectId();
    setDefault(semesterId, groupId, defId, "100.00");
    setAliquotKostenMode(semesterId, "PER_DAY");
    setDiscount(semesterId, 2, 50); // ab 2. Kind -50%
    ObjectId familyId = createFamily("Meier");
    // zwei Kinder -> jüngeres ist 2. Kind (gleiche base -> tie-break per childId)
    createChild(familyId, "Anna", semesterId, groupId, null, null);
    createChild(familyId, "Ben", semesterId, groupId, "2020-05-16", null); // Eintritt Mai

    var months = given().queryParam("year", 2020)
            .when().get("/api/v1/bilanzen").then().statusCode(200)
            .extract().jsonPath();

    // Mai-Zelle (Index 4) von Ben: Eintritt 16.05. in 31-Tage-Monat -> 16 Tage
    // Wir prüfen strukturell: lines vorhanden, Basis 100, Aliquot-Tage gesetzt, reason null.
    int benRow = months.getString("children[0].name").equals("Anna") ? 1 : 0;
    String base = "children[" + benRow + "].months[4]";
    org.junit.jupiter.api.Assertions.assertNull(months.getString(base + ".reason"));
    org.junit.jupiter.api.Assertions.assertEquals("PER_DAY", months.getString(base + ".aliquotMode"));
    org.junit.jupiter.api.Assertions.assertEquals(1, months.getList(base + ".lines").size());
    org.junit.jupiter.api.Assertions.assertEquals(100.0f, months.getFloat(base + ".lines[0].baseAmount"));
    org.junit.jupiter.api.Assertions.assertEquals(16, months.getInt(base + ".lines[0].presentDays"));
    org.junit.jupiter.api.Assertions.assertEquals(31, months.getInt(base + ".lines[0].daysInMonth"));
    org.junit.jupiter.api.Assertions.assertEquals("2020-05-16", months.getString(base + ".entryDate"));
}

@Test
void matrixInactiveCellHasNoPlaceReason_futureCellHasFutureReason() {
    fullCleanup();
    int futureYear = YearMonth.now().getYear() + 1;
    // Semester deckt Vorjahr..Zukunft, Kind aktiv nur ab März des Zukunftsjahres
    Semester s = new Semester();
    s.start = utc(YearMonth.now().getYear() - 1, 1, 1);
    s.end = utc(futureYear, 12, 31);
    s.createdAt = Instant.now();
    s.persist();
    ObjectId currencyId = createCurrency("EUR", "€");
    ObjectId defId = createDefinition(currencyId, "Elternbeitrag");
    ObjectId groupId = new ObjectId();
    setDefault(s.id, groupId, defId, "100.00");
    ObjectId familyId = createFamily("Meier");
    createChild(familyId, "Anna", s.id, groupId, futureYear + "-03-01", null);

    given().queryParam("year", futureYear)
        .when().get("/api/v1/bilanzen").then().statusCode(200)
        // Januar Zukunftsjahr: zukünftig UND vor Eintritt -> FUTURE hat Vorrang
        .body("children[0].months[0].reason", is("FUTURE"));

    given().queryParam("year", YearMonth.now().getYear() - 1)
        .when().get("/api/v1/bilanzen").then().statusCode(200)
        // Vorjahr: nicht aktiv (vor Eintritt), nicht zukünftig -> NO_PLACE
        .body("children[0].months[0].reason", is("NO_PLACE"));
}

@Test
void matrixLineEffectiveSumEqualsCellAmount() {
    fullCleanup();
    ObjectId semesterId = createSemester(2020);
    ObjectId currencyId = createCurrency("EUR", "€");
    ObjectId defA = createDefinition(currencyId, "Elternbeitrag");
    ObjectId defB = createDefinition(currencyId, "Materialbeitrag");
    ObjectId groupId = new ObjectId();
    setDefault(semesterId, groupId, defA, "100.00");
    setDefault(semesterId, groupId, defB, "40.00");
    ObjectId familyId = createFamily("Meier");
    createChild(familyId, "Anna", semesterId, groupId, null, null);

    var jp = given().queryParam("year", 2020)
            .when().get("/api/v1/bilanzen").then().statusCode(200).extract().jsonPath();
    float cell = jp.getFloat("children[0].months[2].amount");
    float lineSum = jp.getList("children[0].months[2].lines.effectiveAmount", Float.class)
            .stream().reduce(0f, Float::sum);
    org.junit.jupiter.api.Assertions.assertEquals(cell, lineSum, 0.001f);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=BilanzResourceTest`
Expected: FAIL — neue Felder (`reason`, `aliquotMode`, `lines`, ...) fehlen / Kompilierfehler.

- [ ] **Step 3: Extend the DTO**

`BilanzMatrixDTO.java` ersetzen durch:

```java
package at.kigruapp.dto;

import java.math.BigDecimal;
import java.util.List;

public record BilanzMatrixDTO(int year, String currentYearMonth, List<ChildRow> children) {
    public record ChildRow(String personId, String name, List<MonthCell> months, BigDecimal total) {}

    public record MonthCell(
            int month,
            BigDecimal amount,
            String currencySymbol,
            boolean mixedCurrency,
            boolean future,
            boolean editable,
            boolean active,
            boolean entryMarker,
            boolean exitMarker,
            String reason,        // "FUTURE" | "NO_PLACE" | null
            String aliquotMode,   // "NONE" | "WHOLE_MONTH" | "PER_DAY" | null
            String entryDate,     // ISO date if entry falls in this month, else null
            String exitDate,      // ISO date if exit falls in this month, else null
            List<LineBreakdown> lines) {}

    public record LineBreakdown(
            String label,
            String currencySymbol,
            BigDecimal baseAmount,
            int discountPercent,
            int discountOrdinal,
            int presentDays,
            int daysInMonth,
            boolean fullMonth,
            boolean overridden,
            BigDecimal effectiveAmount) {}
}
```

- [ ] **Step 4: Populate lines + meta in the service**

In `BilanzCalculationService.java`:

(a) `CellComputation` um Felder erweitern:

```java
private static class CellComputation {
    BigDecimal amount = BigDecimal.ZERO;
    String currencySymbol = "";
    boolean mixedCurrency = false;
    boolean future = false;
    boolean active = false;
    boolean editable = false;
    boolean entryMarker = false;
    boolean exitMarker = false;
    String aliquotMode = null;
    String entryDate = null;
    String exitDate = null;
    List<BilanzMatrixDTO.LineBreakdown> lines = new ArrayList<>();
}
```

(b) In `computeCellInternal` die Positions-Schleife so anpassen, dass sie Aliquot-Tage über `monthPresence` holt, das Ordinal über `discountResult` bestimmt und pro Position eine `LineBreakdown` an `cc.lines` hängt. Den Modus/Ein-/Austrittsdatum am `cc` setzen. Konkret der geänderte innere Block (ersetzt Zeilen ~173-217):

```java
if (semester != null) {
    AliquotMode mode = aliquotMode(semester.id);
    cc.aliquotMode = mode.name();
    KostenDiscount discountCfg = KostenDiscount.findBySemesterId(semester.id);
    for (Person child : children) {
        GroupRef gref = groupAssignment(child.id, semester.id);
        if (gref == null) {
            continue;
        }
        if (matchesYearMonth(gref.entryDate, year, month)) {
            cc.entryMarker = true;
            cc.entryDate = gref.entryDate;
        }
        if (matchesYearMonth(gref.exitDate, year, month)) {
            cc.exitMarker = true;
            cc.exitDate = gref.exitDate;
        }
        AliquotService.MonthPresence presence = aliquotService.monthPresence(
                mode, gref.entryDate, gref.exitDate, year, month);
        BigDecimal frac = presence.fraction();
        if (frac.signum() == 0) {
            continue;
        }
        cc.active = true;
        List<ChildBase> present = presentSiblings(
                child.familyId, semester, year, month, mode, activeDefs, discountCfg);
        DiscountResult dr = discountResult(discountCfg, child.id.toHexString(), present);
        BigDecimal factor = dr.factor();
        for (KostenDefinition def : activeDefs) {
            BigDecimal def0 = defaultAmount(semester.id, gref.groupId, def.id);
            BilanzOverride o = BilanzOverride.findByKeys(child.id, year, month, def.id);
            BigDecimal eff;
            int discountPercent;
            int discountOrdinal;
            int presentDays;
            boolean fullMonth;
            boolean overridden;
            boolean elig = eligible(discountCfg, def);
            if (o != null) {
                eff = o.amount; // override bypasses discount and aliquot
                discountPercent = 0;
                discountOrdinal = 0;
                presentDays = presence.daysInMonth();
                fullMonth = true;
                overridden = true;
            } else if (def0 == null) {
                continue;
            } else {
                BigDecimal defFactor = elig ? factor : BigDecimal.ONE;
                eff = def0.multiply(defFactor).multiply(frac)
                        .setScale(2, RoundingMode.HALF_UP);
                discountPercent = elig
                        ? (int) Math.round((1 - factor.doubleValue()) * 100) : 0;
                discountOrdinal = (discountPercent > 0) ? dr.ordinal() : 0;
                presentDays = presence.presentDays();
                fullMonth = presence.presentDays() == presence.daysInMonth();
                overridden = false;
            }
            cc.amount = cc.amount.add(eff);
            Currency cur = Currency.findById(def.currencyId);
            String symbol = cur != null ? cur.symbol : "";
            if (cur != null) {
                if (currencies.isEmpty()) {
                    firstSymbol = cur.symbol;
                }
                currencies.add(def.currencyId);
            }
            cc.lines.add(new BilanzMatrixDTO.LineBreakdown(
                    def.label, symbol,
                    def0 != null ? def0 : eff,
                    discountPercent, discountOrdinal,
                    presentDays, presence.daysInMonth(),
                    fullMonth, overridden, eff));
        }
    }
}
```

(c) Am Ende von `computeCellInternal`, im Invariant-Block für inaktive Zellen, auch `lines`/Meta zurücksetzen:

```java
if (!cc.active) {
    cc.amount = BigDecimal.ZERO;
    cc.mixedCurrency = false;
    cc.entryMarker = false;
    cc.exitMarker = false;
    cc.entryDate = null;
    cc.exitDate = null;
    cc.lines = new ArrayList<>();
}
```

(d) In `computeMatrix` die `MonthCell`-Konstruktion um Grund + Meta erweitern:

```java
CellComputation cc = computeCellInternal(single, year, m, semesters, activeDefs, current);
String reason = cc.future ? "FUTURE" : (!cc.active ? "NO_PLACE" : null);
months.add(new BilanzMatrixDTO.MonthCell(
        m, cc.amount, cc.currencySymbol, cc.mixedCurrency,
        cc.future, cc.editable, cc.active, cc.entryMarker, cc.exitMarker,
        reason, cc.active ? cc.aliquotMode : null,
        cc.entryDate, cc.exitDate, cc.lines));
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=BilanzResourceTest,BilanzDiscountTest,AliquotServiceTest`
Expected: PASS — neue Tests grün, alle bestehenden Matrix-/Cell-Tests unverändert grün (Beträge gleich).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/BilanzMatrixDTO.java backend/src/main/java/at/kigruapp/service/BilanzCalculationService.java backend/src/test/java/at/kigruapp/resource/BilanzResourceTest.java
git commit -m "feat(be): embed per-cell cost breakdown in Bilanz matrix payload"
```

---

### Task 4: Frontend-Modell + Detailkarten-Komponente

**Files:**
- Modify: `frontend/src/app/shared/models/bilanz.model.ts`
- Create: `frontend/src/app/administration/bilanzen/bilanz-cell-detail-card.component.ts`
- Test: `frontend/src/app/administration/bilanzen/bilanz-cell-detail-card.component.spec.ts`

**Interfaces:**
- Produces: Interface `BilanzLineBreakdown`; erweitertes `BilanzMonthCell`; standalone `BilanzCellDetailCardComponent` mit Inputs `cell: BilanzMonthCell`, `monthLabel: string`, `year: number`.

- [ ] **Step 1: Extend the model**

In `bilanz.model.ts` `BilanzMonthCell` erweitern und Interface ergänzen:

```typescript
export interface BilanzLineBreakdown {
  label: string;
  currencySymbol: string;
  baseAmount: number;
  discountPercent: number;
  discountOrdinal: number;
  presentDays: number;
  daysInMonth: number;
  fullMonth: boolean;
  overridden: boolean;
  effectiveAmount: number;
}

export interface BilanzMonthCell {
  month: number;
  amount: number;
  currencySymbol: string;
  mixedCurrency: boolean;
  future: boolean;
  editable: boolean;
  active: boolean;
  entryMarker: boolean;
  exitMarker: boolean;
  reason: string | null;          // "FUTURE" | "NO_PLACE" | null
  aliquotMode: string | null;     // "NONE" | "WHOLE_MONTH" | "PER_DAY" | null
  entryDate: string | null;
  exitDate: string | null;
  lines: BilanzLineBreakdown[];
}
```

- [ ] **Step 2: Write the failing component test**

`bilanz-cell-detail-card.component.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BilanzCellDetailCardComponent } from './bilanz-cell-detail-card.component';
import { BilanzMonthCell } from '../../shared/models/bilanz.model';

function cell(partial: Partial<BilanzMonthCell>): BilanzMonthCell {
  return {
    month: 10, amount: 77.41, currencySymbol: '€', mixedCurrency: false,
    future: false, editable: true, active: true, entryMarker: true, exitMarker: false,
    reason: null, aliquotMode: 'PER_DAY', entryDate: '2026-10-17', exitDate: null,
    lines: [], ...partial,
  };
}

describe('BilanzCellDetailCardComponent', () => {
  let fixture: ComponentFixture<BilanzCellDetailCardComponent>;
  let component: BilanzCellDetailCardComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [BilanzCellDetailCardComponent] }).compileComponents();
    fixture = TestBed.createComponent(BilanzCellDetailCardComponent);
    component = fixture.componentInstance;
    component.monthLabel = 'Okt';
    component.year = 2026;
  });

  it('renders NO_PLACE reason instead of a table', () => {
    component.cell = cell({ active: false, reason: 'NO_PLACE', lines: [] });
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Kein Platz');
    expect((fixture.nativeElement as HTMLElement).querySelector('table')).toBeNull();
  });

  it('renders a breakdown row with discount and aliquot for an active cell', () => {
    component.cell = cell({
      lines: [{
        label: 'Materialbeitrag', currencySymbol: '€', baseAmount: 50,
        discountPercent: 20, discountOrdinal: 2, presentDays: 15, daysInMonth: 31,
        fullMonth: false, overridden: false, effectiveAmount: 19.35,
      }],
    });
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Materialbeitrag');
    expect(text).toContain('20');       // Rabatt %
    expect(text).toContain('2. Kind');  // Ordinal
    expect(text).toContain('15/31');    // Aliquot
    expect(text).toContain('19');       // effektiv
  });

  it('marks overridden lines', () => {
    component.cell = cell({
      lines: [{
        label: 'Materialbeitrag', currencySymbol: '€', baseAmount: 100,
        discountPercent: 0, discountOrdinal: 0, presentDays: 31, daysInMonth: 31,
        fullMonth: true, overridden: true, effectiveAmount: 100,
      }],
    });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('manuell');
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/bilanz-cell-detail-card.component.spec.ts'`
Expected: FAIL — Komponente existiert nicht.

- [ ] **Step 4: Implement the card component**

`bilanz-cell-detail-card.component.ts`:

```typescript
import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { BilanzMonthCell } from '../../shared/models/bilanz.model';

@Component({
  selector: 'app-bilanz-cell-detail-card',
  standalone: true,
  imports: [CommonModule, MatIconModule],
  template: `
    <div class="card">
      <div class="head">
        <div class="title">{{ monthLabel }} {{ year }}</div>
        @if (cell.active) {
          <div class="chips">
            <span class="chip mode">Aliquot: {{ modeLabel(cell.aliquotMode) }}</span>
            @if (cell.entryDate) { <span class="chip io">Eintritt {{ cell.entryDate | date:'dd.MM.' }}</span> }
            @if (cell.exitDate) { <span class="chip io">Austritt {{ cell.exitDate | date:'dd.MM.' }}</span> }
          </div>
        }
      </div>

      @if (cell.reason === 'NO_PLACE') {
        <div class="empty">Kein Platz in diesem Monat.</div>
      } @else if (cell.reason === 'FUTURE') {
        <div class="empty">Zukünftiger Monat — noch nicht abgerechnet.</div>
      } @else {
        <table>
          <thead>
            <tr><th>Position</th><th>Basis</th><th>Rabatt</th><th>Aliquot</th><th>Effektiv</th></tr>
          </thead>
          <tbody>
            @for (l of cell.lines; track l.label) {
              <tr>
                <td class="pos">{{ l.label }}
                  @if (l.overridden) { <div class="ovr-note">Rabatt &amp; Aliquot umgangen</div> }
                </td>
                @if (l.overridden) {
                  <td colspan="3" class="ovr"><span class="badge">manuell gesetzt</span></td>
                } @else {
                  <td class="base">{{ l.baseAmount }} {{ l.currencySymbol }}</td>
                  <td>
                    @if (l.discountPercent > 0) {
                      <span class="redu">−{{ l.discountPercent }} %</span>
                      @if (l.discountOrdinal > 0) { <span class="sub">{{ l.discountOrdinal }}. Kind</span> }
                    } @else { <span class="none">—</span> }
                  </td>
                  <td>
                    @if (l.fullMonth) { <span class="none">voller Monat</span> }
                    @else { <span class="ali">×{{ l.presentDays }}/{{ l.daysInMonth }}</span> }
                  </td>
                }
                <td class="eff">{{ l.effectiveAmount }} {{ l.currencySymbol }}</td>
              </tr>
            }
          </tbody>
        </table>

        @if (cell.mixedCurrency) {
          <div class="warn"><mat-icon>warning</mat-icon> Gemischte Währungen — keine gemeinsame Summe.</div>
        } @else {
          <div class="foot"><span>Summe</span><strong>{{ cell.amount }} {{ cell.currencySymbol }}</strong></div>
        }
      }
    </div>
  `,
  styles: [`
    .card { background: #fff; border: 1px solid #e6e8eb; border-radius: 10px;
      box-shadow: 0 8px 28px rgba(20,24,31,.16); min-width: 320px; overflow: hidden;
      font-size: 13px; color: #1f2328; }
    .head { padding: 11px 14px; border-bottom: 1px solid #eef0f2; display: flex; flex-direction: column; gap: 5px; }
    .title { font-weight: 650; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; }
    .chip { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: #eef0f3; color: #61656c; }
    .chip.mode { background: #e8f0fc; color: #1a66d6; }
    .chip.io { background: #e7f4ec; color: #1f7a43; }
    table { width: 100%; border-collapse: collapse; }
    th { text-align: right; font-size: 10.5px; text-transform: uppercase; letter-spacing: .04em;
      color: #8a8f98; padding: 8px 14px 5px; }
    th:first-child { text-align: left; }
    td { text-align: right; padding: 5px 14px; border-top: 1px solid #f2f3f5; vertical-align: top;
      font-variant-numeric: tabular-nums; }
    td.pos { text-align: left; font-weight: 550; }
    .base { color: #61656c; }
    .redu { color: #1f7a43; font-weight: 550; }
    .sub { display: block; color: #1f7a43; font-size: 11px; }
    .ali { color: #1f2328; font-weight: 600; }
    .none { color: #8a8f98; }
    .eff { font-weight: 700; }
    .ovr { text-align: right; }
    .badge { font-size: 10.5px; font-weight: 600; color: #a85a00; background: #fbefe0;
      padding: 1px 7px; border-radius: 999px; }
    .ovr-note { color: #a85a00; font-size: 11px; }
    .foot { display: flex; justify-content: space-between; align-items: baseline;
      padding: 10px 14px; border-top: 1px solid #eef0f2; background: #fbfcfd; }
    .foot strong { font-size: 15px; font-variant-numeric: tabular-nums; }
    .empty { padding: 16px 14px; color: #61656c; }
    .warn { display: flex; align-items: center; gap: 6px; padding: 9px 14px;
      background: #fff7ec; color: #b0640a; font-size: 12px; border-top: 1px solid #eef0f2; }
    .warn mat-icon { font-size: 17px; width: 17px; height: 17px; }
  `],
})
export class BilanzCellDetailCardComponent {
  @Input({ required: true }) cell!: BilanzMonthCell;
  @Input({ required: true }) monthLabel!: string;
  @Input({ required: true }) year!: number;

  modeLabel(mode: string | null): string {
    switch (mode) {
      case 'PER_DAY': return 'Taggenau';
      case 'WHOLE_MONTH': return 'Ganze Monate';
      default: return 'Keine';
    }
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/bilanz-cell-detail-card.component.spec.ts'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/models/bilanz.model.ts frontend/src/app/administration/bilanzen/bilanz-cell-detail-card.component.ts frontend/src/app/administration/bilanzen/bilanz-cell-detail-card.component.spec.ts
git commit -m "feat(fe): Bilanz cell detail card component"
```

---

### Task 5: Hover-Overlay-Direktive + Einbindung in die Matrix

**Files:**
- Create: `frontend/src/app/administration/bilanzen/bilanz-cell-detail.directive.ts`
- Test: `frontend/src/app/administration/bilanzen/bilanz-cell-detail.directive.spec.ts`
- Modify: `frontend/src/app/administration/bilanzen/bilanzen.component.ts`

**Interfaces:**
- Consumes: `BilanzCellDetailCardComponent` (Task 4), `BilanzMonthCell`.
- Produces: standalone `BilanzCellDetailDirective` (`[appBilanzCellDetail]`) mit Inputs `appBilanzCellDetail: BilanzMonthCell`, `detailMonthLabel: string`, `detailYear: number`; öffnet ein CDK-Overlay bei `mouseenter`, schließt bei `mouseleave`.

- [ ] **Step 1: Write the failing directive test**

`bilanz-cell-detail.directive.spec.ts`:

```typescript
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BilanzCellDetailDirective } from './bilanz-cell-detail.directive';
import { BilanzMonthCell } from '../../shared/models/bilanz.model';

const CELL: BilanzMonthCell = {
  month: 10, amount: 40, currencySymbol: '€', mixedCurrency: false,
  future: false, editable: true, active: true, entryMarker: false, exitMarker: false,
  reason: null, aliquotMode: 'NONE', entryDate: null, exitDate: null,
  lines: [{ label: 'Elternbeitrag', currencySymbol: '€', baseAmount: 40, discountPercent: 0,
    discountOrdinal: 0, presentDays: 31, daysInMonth: 31, fullMonth: true, overridden: false,
    effectiveAmount: 40 }],
};

@Component({
  standalone: true,
  imports: [BilanzCellDetailDirective],
  template: `<div [appBilanzCellDetail]="cell" detailMonthLabel="Okt" [detailYear]="2026"></div>`,
})
class HostComponent { cell = CELL; }

describe('BilanzCellDetailDirective', () => {
  let fixture: ComponentFixture<HostComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  });

  it('opens an overlay with the breakdown on mouseenter and closes on mouseleave', () => {
    const host: HTMLElement = fixture.nativeElement.querySelector('[appBilanzCellDetail]');
    host.dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    expect(document.body.textContent).toContain('Elternbeitrag');

    host.dispatchEvent(new MouseEvent('mouseleave'));
    fixture.detectChanges();
    expect(document.body.textContent).not.toContain('Elternbeitrag');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/bilanz-cell-detail.directive.spec.ts'`
Expected: FAIL — Direktive existiert nicht.

- [ ] **Step 3: Implement the directive**

`bilanz-cell-detail.directive.ts`:

```typescript
import { Directive, ElementRef, HostListener, Input, OnDestroy } from '@angular/core';
import { Overlay, OverlayRef } from '@angular/cdk/overlay';
import { ComponentPortal } from '@angular/cdk/portal';
import { BilanzMonthCell } from '../../shared/models/bilanz.model';
import { BilanzCellDetailCardComponent } from './bilanz-cell-detail-card.component';

@Directive({
  selector: '[appBilanzCellDetail]',
  standalone: true,
})
export class BilanzCellDetailDirective implements OnDestroy {
  @Input('appBilanzCellDetail') cell!: BilanzMonthCell;
  @Input() detailMonthLabel = '';
  @Input() detailYear = 0;

  private overlayRef: OverlayRef | null = null;

  constructor(private overlay: Overlay, private host: ElementRef<HTMLElement>) {}

  @HostListener('mouseenter')
  open(): void {
    if (this.overlayRef || !this.cell) return;
    const positionStrategy = this.overlay.position()
      .flexibleConnectedTo(this.host)
      .withPositions([
        { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 6 },
        { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -6 },
      ]);
    this.overlayRef = this.overlay.create({
      positionStrategy,
      scrollStrategy: this.overlay.scrollStrategies.reposition(),
    });
    const portal = new ComponentPortal(BilanzCellDetailCardComponent);
    const ref = this.overlayRef.attach(portal);
    ref.instance.cell = this.cell;
    ref.instance.monthLabel = this.detailMonthLabel;
    ref.instance.year = this.detailYear;
  }

  @HostListener('mouseleave')
  close(): void {
    this.overlayRef?.dispose();
    this.overlayRef = null;
  }

  ngOnDestroy(): void {
    this.close();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/bilanz-cell-detail.directive.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Wire the directive into the matrix cells**

In `bilanzen.component.ts`: Import ergänzen und zur `imports`-Liste hinzufügen:

```typescript
import { BilanzCellDetailDirective } from './bilanz-cell-detail.directive';
```

`imports: [ ... , MatDialogModule, BilanzCellDetailDirective ]`

Die Zell-`<td>` (der Monatsspalten) um die Direktive erweitern — bestehende Attribute bleiben:

```html
<td mat-cell *matCellDef="let row"
    [ngClass]="'cell-' + cellState(row.months[m - 1])"
    [appBilanzCellDetail]="row.months[m - 1]"
    [detailMonthLabel]="monthLabels[m - 1]"
    [detailYear]="selectedYear"
    (click)="onCellClick(row, row.months[m - 1])">
```

Optional: das `matTooltip="Gemischte Währungen"` am Warn-Icon kann bleiben oder entfallen (im Popover erklärt) — nicht erforderlich für den Test.

- [ ] **Step 6: Verify existing component spec still passes + build**

Run: `cd frontend && npx ng test --watch=false --include='**/bilanzen.component.spec.ts' && npx ng build --configuration development`
Expected: PASS + erfolgreicher Build (keine Template-/Typfehler).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/administration/bilanzen/bilanz-cell-detail.directive.ts frontend/src/app/administration/bilanzen/bilanz-cell-detail.directive.spec.ts frontend/src/app/administration/bilanzen/bilanzen.component.ts
git commit -m "feat(fe): hover overlay showing Bilanz cell cost breakdown"
```

---

## Self-Review

**Spec coverage:**
- Backend Breakdown in Matrix-Payload → Task 3 (DTO + Service), stützt sich auf Task 1 (Tage) + Task 2 (Ordinal). ✓
- `AliquotService` Tag-Details → Task 1. ✓
- Gemeinsame/duplizierungsarme Zeilenberechnung → Task 3 reichert `computeCellInternal` an (bewusste Abweichung vom Spec-Vorschlag „computeCell mitverwenden": `computeCell` bleibt unangetastet, um Risiko/Umfang klein zu halten; Beträge bleiben identisch, Konsistenz wird über die geteilten Helfer `monthPresence`/`discountResult` gewahrt). ✓
- DTO-Erweiterung (`reason`, `aliquotMode`, `entryDate`, `exitDate`, `lines`, `LineBreakdown`) → Task 3. ✓
- Frontend-Modell → Task 4. ✓
- Detailkarte (Kopf, Tabelle Position/Basis/Rabatt/Aliquot/Effektiv, Override-Badge, Mixed-Currency-Warnung, Sonderfall-Texte) → Task 4. ✓
- Hover-Popover via CDK Overlay + Einbindung → Task 5. ✓
- Tests Backend/Frontend → jede Task hat TDD-Zyklus. ✓
- Out of scope (kein HTTP-Nachladen, keine Logikänderung, Dialog unverändert) → eingehalten. ✓

**Placeholder scan:** Keine TBD/TODO/„appropriate handling"; alle Code-Schritte enthalten realen Code. ✓

**Type consistency:** `MonthPresence(fraction, presentDays, daysInMonth)` und `DiscountResult(factor, ordinal)` konsistent zwischen Definition (Task 1/2) und Nutzung (Task 3). `LineBreakdown`-Felder identisch zwischen Java-Record (Task 3) und TS-Interface `BilanzLineBreakdown` (Task 4) und Kartennutzung (Task 4) und Direktive (Task 5). `BilanzCellDetailCardComponent`-Inputs (`cell`, `monthLabel`, `year`) konsistent zwischen Task 4 und Task 5. ✓
