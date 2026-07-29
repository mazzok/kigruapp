# Kosten-Geschwisterrabatt & Aliquot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-semester sibling discount to Kosten (percentage tiers, per-definition eligibility, admin-selectable child ranking) and a per-semester aliquot (pro-rata by entry/exit date) mode that applies to both the Stunden-Soll and Kosten.

**Architecture:** Two new tiny per-semester config entities (`KostenDiscount`, `AliquotConfig`) mirroring the existing `RequiredHours` pattern, each with a GET/PUT resource. A new pure `AliquotService.monthFraction()` helper is the single source of truth for pro-rata weighting, consumed by both `HoursBalanceService` (Stunden-Soll) and `BilanzCalculationService` (Kosten). Sibling ranking + discount factor is computed per child-month inside `BilanzCalculationService`. Frontend adds two admin config surfaces in `organisation.component` plus a per-definition checkbox.

**Tech Stack:** Quarkus + MongoDB Panache (Java 17), RESTEasy Reactive, JUnit 5 + RestAssured; Angular 17 standalone components, Angular Material, Karma/Jasmine.

## Global Constraints

- Java package root: `at.kigruapp`. Mongo entities extend `PanacheMongoEntity` with `@MongoEntity(collection = "...")`.
- All new config endpoints are **admin-only** — do **not** add them to the `SecurityFilter` whitelist (default = authenticated/admin, like `/required-hours`).
- Config is **per semester**; a missing config document means **defaults** (`mode = NONE`, no discount) so historic semesters are unchanged.
- Entry/exit dates are stored on `semester_assignments` as `String` `"YYYY-MM-DD"`, may be `null`/blank.
- Money is `BigDecimal`, rounded HALF_UP to 2 decimals after applying factor × fraction. Minutes are `int`, rounded HALF_UP to whole minutes after `rate × fraction`.
- `BilanzOverride` (manual per-child/month/definition amount) is final: it bypasses **both** discount and aliquot.
- German user-facing copy (labels, error messages), matching existing components.
- Verify commands: backend `cd backend && ./mvnw test -Dtest=<ClassName>`; frontend `cd frontend && npm test -- --watch=false`.

**Cross-cutting definitions used by multiple tasks:**

- `AliquotMode` enum (Task 2): `NONE`, `WHOLE_MONTH`, `PER_DAY`.
- `AliquotService.monthFraction(AliquotMode mode, String entryDate, String exitDate, int year, int month) → BigDecimal` in `[0,1]` (Task 2).
- **Stunden ordinal rule under aliquot (Task 5):** for a given month, children present that month (`fraction > 0`) are sorted by fraction **descending** (tie-break: `childId`); ordinal `1..N` in that order; `monthSoll += rate(ordinal) × fraction`. When `mode = NONE`, Stunden keeps the legacy `familyMonthlyMinutes(cfg,N) × monthsInSemester` formula (window ignored).
- **Kosten child ranking (Task 6b):** children present that month are ranked by discountable base amount; direction from `KostenDiscount.order`; tie-break `childId`.

---

## File Structure

**Backend — create:**
- `backend/src/main/java/at/kigruapp/service/AliquotMode.java` — enum.
- `backend/src/main/java/at/kigruapp/service/AliquotService.java` — pure `monthFraction`.
- `backend/src/main/java/at/kigruapp/entity/AliquotConfig.java` — per-semester mode.
- `backend/src/main/java/at/kigruapp/dto/AliquotConfigDto.java`
- `backend/src/main/java/at/kigruapp/resource/AliquotConfigResource.java`
- `backend/src/main/java/at/kigruapp/entity/KostenDiscount.java` — per-semester tiers + order + applyToAll.
- `backend/src/main/java/at/kigruapp/dto/KostenDiscountDto.java`
- `backend/src/main/java/at/kigruapp/resource/KostenDiscountResource.java`
- Tests: `AliquotServiceTest`, `AliquotConfigResourceTest`, `KostenDiscountResourceTest`, plus additions to `HoursBalanceServiceTest`, `BilanzCalculationServiceTest` (new), `KostenDefinitionResourceTest`.

**Backend — modify:**
- `backend/.../entity/KostenDefinition.java` — add `boolean siblingDiscount`.
- `backend/.../dto/KostenDefinitionDTO.java` — expose flag.
- `backend/.../resource/KostenDefinitionResource.java` — PATCH to set flag.
- `backend/.../service/HoursBalanceService.java` — aliquot-aware Soll.
- `backend/.../service/BilanzCalculationService.java` — discount + aliquot.
- `backend/.../dto/BilanzCellDTO.java` — breakdown fields.

**Frontend — create:**
- `frontend/src/app/shared/models/aliquot-config.model.ts`
- `frontend/src/app/shared/services/aliquot-config.service.ts`
- `frontend/src/app/shared/models/kosten-discount.model.ts`
- `frontend/src/app/shared/services/kosten-discount.service.ts`
- `frontend/src/app/settings/organisation/kosten-discount-preview.util.ts` (+ spec)

**Frontend — modify:**
- `frontend/.../shared/models/kosten-definition.model.ts` — add `siblingDiscount`.
- `frontend/.../shared/services/kosten-definition.service.ts` — `setSiblingDiscount`.
- `frontend/.../settings/organisation/organisation.component.ts` + `.html` (+ `.spec.ts`) — aliquot dropdown, discount config, per-definition checkbox.

---

## Task 1: AliquotConfig entity, DTO, resource (GET/PUT)

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/AliquotConfig.java`
- Create: `backend/src/main/java/at/kigruapp/dto/AliquotConfigDto.java`
- Create: `backend/src/main/java/at/kigruapp/resource/AliquotConfigResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/AliquotConfigResourceTest.java`

**Interfaces:**
- Produces: `AliquotConfig { ObjectId semesterId; String mode; static AliquotConfig findBySemesterId(ObjectId) }`. Stored `mode` is one of `"NONE"`, `"WHOLE_MONTH"`, `"PER_DAY"`. `GET/PUT /api/v1/aliquot-config?semesterId=` returning `AliquotConfigDto { String semesterId; String mode }` (default `mode="NONE"` when unset).

- [ ] **Step 1: Write the failing resource test**

```java
package at.kigruapp.resource;

import at.kigruapp.entity.AliquotConfig;
import at.kigruapp.entity.Semester;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class AliquotConfigResourceTest {

    @BeforeEach
    void cleanup() {
        AliquotConfig.deleteAll();
        Semester.deleteAll();
    }

    private String persistSemester() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        s.createdAt = Instant.now();
        s.persist();
        return s.id.toHexString();
    }

    @Test
    void getDefaultsToNone() {
        String id = persistSemester();
        given().when().get("/api/v1/aliquot-config?semesterId=" + id)
            .then().statusCode(200).body("mode", is("NONE"));
    }

    @Test
    void putThenGetRoundTripsAndUpserts() {
        String id = persistSemester();
        given().contentType(ContentType.JSON).body("{\"mode\":\"PER_DAY\"}")
            .when().put("/api/v1/aliquot-config?semesterId=" + id)
            .then().statusCode(200).body("mode", is("PER_DAY"));
        given().contentType(ContentType.JSON).body("{\"mode\":\"WHOLE_MONTH\"}")
            .when().put("/api/v1/aliquot-config?semesterId=" + id).then().statusCode(200);
        given().when().get("/api/v1/aliquot-config?semesterId=" + id)
            .then().statusCode(200).body("mode", is("WHOLE_MONTH"));
    }

    @Test
    void putRejectsUnknownMode() {
        String id = persistSemester();
        given().contentType(ContentType.JSON).body("{\"mode\":\"DAILY\"}")
            .when().put("/api/v1/aliquot-config?semesterId=" + id).then().statusCode(400);
    }

    @Test
    void putRejectsMissingSemesterId() {
        given().contentType(ContentType.JSON).body("{\"mode\":\"NONE\"}")
            .when().put("/api/v1/aliquot-config").then().statusCode(400);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AliquotConfigResourceTest`
Expected: FAIL — `AliquotConfig` / resource do not exist (compile error).

- [ ] **Step 3: Create the entity**

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

@MongoEntity(collection = "aliquot_configs")
public class AliquotConfig extends PanacheMongoEntity {
    public ObjectId semesterId;
    public String mode = "NONE";

    public static AliquotConfig findBySemesterId(ObjectId semesterId) {
        return find("semesterId", semesterId).firstResult();
    }
}
```

- [ ] **Step 4: Create the DTO**

```java
package at.kigruapp.dto;

public class AliquotConfigDto {
    public String semesterId;
    public String mode;
}
```

- [ ] **Step 5: Create the resource**

```java
package at.kigruapp.resource;

import at.kigruapp.dto.AliquotConfigDto;
import at.kigruapp.entity.AliquotConfig;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;

import java.util.Set;

@Path("/api/v1/aliquot-config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AliquotConfigResource {

    private static final Set<String> ALLOWED = Set.of("NONE", "WHOLE_MONTH", "PER_DAY");

    @GET
    public AliquotConfigDto get(@QueryParam("semesterId") String semesterIdParam) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        AliquotConfig cfg = AliquotConfig.findBySemesterId(semesterId);
        return toDto(semesterId, cfg);
    }

    @PUT
    public AliquotConfigDto put(@QueryParam("semesterId") String semesterIdParam, AliquotConfigDto in) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        if (in == null || in.mode == null || !ALLOWED.contains(in.mode)) {
            throw new BadRequestException("mode muss NONE, WHOLE_MONTH oder PER_DAY sein");
        }
        AliquotConfig cfg = AliquotConfig.findBySemesterId(semesterId);
        if (cfg == null) {
            cfg = new AliquotConfig();
            cfg.semesterId = semesterId;
        }
        cfg.mode = in.mode;
        cfg.persistOrUpdate();
        return toDto(semesterId, cfg);
    }

    private ObjectId requireSemesterId(String semesterIdParam) {
        if (semesterIdParam == null || semesterIdParam.isBlank() || !ObjectId.isValid(semesterIdParam)) {
            throw new BadRequestException("semesterId erforderlich");
        }
        return new ObjectId(semesterIdParam);
    }

    private AliquotConfigDto toDto(ObjectId semesterId, AliquotConfig cfg) {
        AliquotConfigDto dto = new AliquotConfigDto();
        dto.semesterId = semesterId.toHexString();
        dto.mode = cfg != null ? cfg.mode : "NONE";
        return dto;
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AliquotConfigResourceTest`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/AliquotConfig.java \
        backend/src/main/java/at/kigruapp/dto/AliquotConfigDto.java \
        backend/src/main/java/at/kigruapp/resource/AliquotConfigResource.java \
        backend/src/test/java/at/kigruapp/resource/AliquotConfigResourceTest.java
git commit -m "feat: AliquotConfig entity + GET/PUT endpoint (per-semester)"
```

---

## Task 2: AliquotMode enum + pure monthFraction helper

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/AliquotMode.java`
- Create: `backend/src/main/java/at/kigruapp/service/AliquotService.java`
- Test: `backend/src/test/java/at/kigruapp/service/AliquotServiceTest.java`

**Interfaces:**
- Produces: `enum AliquotMode { NONE, WHOLE_MONTH, PER_DAY; static AliquotMode fromString(String) }` (null/unknown → `NONE`).
- Produces: `AliquotService` (`@ApplicationScoped`, no-arg constructable) with
  `BigDecimal monthFraction(AliquotMode mode, String entryDate, String exitDate, int year, int month)`.
  Returns `0` if the child is not present at all that month; `1` for `NONE`/`WHOLE_MONTH` when present any day; `presentDays/daysInMonth` (scale 6, HALF_UP) for `PER_DAY`.

- [ ] **Step 1: Write the failing test**

```java
package at.kigruapp.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliquotServiceTest {

    private final AliquotService service = new AliquotService();

    @Test
    void fromStringDefaultsToNone() {
        assertEquals(AliquotMode.NONE, AliquotMode.fromString(null));
        assertEquals(AliquotMode.NONE, AliquotMode.fromString("garbage"));
        assertEquals(AliquotMode.PER_DAY, AliquotMode.fromString("PER_DAY"));
    }

    @Test
    void noneAndWholeMonth_areBinaryAtMonthLevel() {
        // enters 2026-11-16, present that month -> full month
        assertEquals(0, BigDecimal.ONE.compareTo(
                service.monthFraction(AliquotMode.NONE, "2026-11-16", null, 2026, 11)));
        assertEquals(0, BigDecimal.ONE.compareTo(
                service.monthFraction(AliquotMode.WHOLE_MONTH, "2026-11-16", null, 2026, 11)));
        // month before entry -> 0
        assertEquals(0, BigDecimal.ZERO.compareTo(
                service.monthFraction(AliquotMode.WHOLE_MONTH, "2026-11-16", null, 2026, 10)));
    }

    @Test
    void perDay_proratesEntryMonth() {
        // enter Nov 16 in a 30-day month -> days 16..30 = 15 days -> 15/30 = 0.5
        BigDecimal f = service.monthFraction(AliquotMode.PER_DAY, "2026-11-16", null, 2026, 11);
        assertEquals(0, new BigDecimal("0.5").compareTo(f.stripTrailingZeros()));
    }

    @Test
    void perDay_proratesExitMonth() {
        // exit Feb 10 (28-day month) -> days 1..10 = 10 days -> 10/28
        BigDecimal f = service.monthFraction(AliquotMode.PER_DAY, null, "2027-02-10", 2027, 2);
        assertEquals(new BigDecimal("10").divide(new BigDecimal("28"), 6, java.math.RoundingMode.HALF_UP), f);
    }

    @Test
    void perDay_fullInteriorMonthIsOne() {
        BigDecimal f = service.monthFraction(AliquotMode.PER_DAY, "2026-09-01", "2027-02-28", 2026, 12);
        assertEquals(0, BigDecimal.ONE.compareTo(f));
    }

    @Test
    void perDay_outsideWindowIsZero() {
        BigDecimal f = service.monthFraction(AliquotMode.PER_DAY, "2026-11-01", "2027-01-31", 2026, 10);
        assertTrue(f.signum() == 0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AliquotServiceTest`
Expected: FAIL — `AliquotMode` / `AliquotService` missing.

- [ ] **Step 3: Create the enum**

```java
package at.kigruapp.service;

public enum AliquotMode {
    NONE, WHOLE_MONTH, PER_DAY;

    public static AliquotMode fromString(String s) {
        if (s == null) return NONE;
        try {
            return valueOf(s);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
```

- [ ] **Step 4: Create the service**

```java
package at.kigruapp.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

@ApplicationScoped
public class AliquotService {

    /** Presence weight in [0,1] for a child in the given month, per the aliquot mode. */
    public BigDecimal monthFraction(AliquotMode mode, String entryDate, String exitDate, int year, int month) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());

        LocalDate entry = parse(entryDate);
        LocalDate exit = parse(exitDate);

        LocalDate effStart = (entry != null && entry.isAfter(monthStart)) ? entry : monthStart;
        LocalDate effEnd = (exit != null && exit.isBefore(monthEnd)) ? exit : monthEnd;

        if (effStart.isAfter(effEnd)) {
            return BigDecimal.ZERO; // not present at all this month
        }
        if (mode == AliquotMode.PER_DAY) {
            long presentDays = ChronoUnit.DAYS.between(effStart, effEnd) + 1;
            long daysInMonth = monthEnd.getDayOfMonth();
            return BigDecimal.valueOf(presentDays)
                    .divide(BigDecimal.valueOf(daysInMonth), 6, RoundingMode.HALF_UP);
        }
        return BigDecimal.ONE; // NONE / WHOLE_MONTH: present any day -> full month
    }

    private LocalDate parse(String date) {
        if (date == null || date.isBlank() || date.length() < 10) {
            return null;
        }
        return LocalDate.parse(date.substring(0, 10));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AliquotServiceTest`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/AliquotMode.java \
        backend/src/main/java/at/kigruapp/service/AliquotService.java \
        backend/src/test/java/at/kigruapp/service/AliquotServiceTest.java
git commit -m "feat: AliquotMode + pure monthFraction weighting helper"
```

---

## Task 3: KostenDiscount entity, DTO, resource (GET/PUT)

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/KostenDiscount.java`
- Create: `backend/src/main/java/at/kigruapp/dto/KostenDiscountDto.java`
- Create: `backend/src/main/java/at/kigruapp/resource/KostenDiscountResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/KostenDiscountResourceTest.java`

**Interfaces:**
- Produces: `KostenDiscount { ObjectId semesterId; boolean applyToAll; List<Tier> tiers; String order; static findBySemesterId }`, `Tier { int fromChild; int percent }`, `order ∈ {"MOST_EXPENSIVE_FIRST","LEAST_EXPENSIVE_FIRST"}`.
- Produces: `GET/PUT /api/v1/kosten-discount?semesterId=` ⇄ `KostenDiscountDto { String semesterId; boolean applyToAll; List<TierDto> tiers; String order }`, `TierDto { int fromChild; int percent }`. Default DTO when unset: `applyToAll=false`, `tiers=[]`, `order="MOST_EXPENSIVE_FIRST"`.

- [ ] **Step 1: Write the failing resource test**

```java
package at.kigruapp.resource;

import at.kigruapp.entity.KostenDiscount;
import at.kigruapp.entity.Semester;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class KostenDiscountResourceTest {

    @BeforeEach
    void cleanup() {
        KostenDiscount.deleteAll();
        Semester.deleteAll();
    }

    private String persistSemester() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        s.createdAt = Instant.now();
        s.persist();
        return s.id.toHexString();
    }

    @Test
    void getReturnsDefaults() {
        String id = persistSemester();
        given().when().get("/api/v1/kosten-discount?semesterId=" + id)
            .then().statusCode(200)
            .body("applyToAll", is(false))
            .body("order", is("MOST_EXPENSIVE_FIRST"))
            .body("tiers.size()", is(0));
    }

    @Test
    void putThenGetRoundTrips() {
        String id = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"applyToAll\":true,\"order\":\"LEAST_EXPENSIVE_FIRST\",\"tiers\":[{\"fromChild\":2,\"percent\":50},{\"fromChild\":3,\"percent\":100}]}")
            .when().put("/api/v1/kosten-discount?semesterId=" + id)
            .then().statusCode(200).body("applyToAll", is(true)).body("tiers.size()", is(2));
        given().when().get("/api/v1/kosten-discount?semesterId=" + id)
            .then().statusCode(200)
            .body("order", is("LEAST_EXPENSIVE_FIRST"))
            .body("tiers[0].percent", is(50))
            .body("tiers[1].fromChild", is(3));
    }

    @Test
    void putRejectsNonAscendingTiers() {
        String id = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"applyToAll\":false,\"order\":\"MOST_EXPENSIVE_FIRST\",\"tiers\":[{\"fromChild\":3,\"percent\":10},{\"fromChild\":2,\"percent\":20}]}")
            .when().put("/api/v1/kosten-discount?semesterId=" + id).then().statusCode(400);
    }

    @Test
    void putRejectsFromChildBelowTwo() {
        String id = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"applyToAll\":false,\"order\":\"MOST_EXPENSIVE_FIRST\",\"tiers\":[{\"fromChild\":1,\"percent\":10}]}")
            .when().put("/api/v1/kosten-discount?semesterId=" + id).then().statusCode(400);
    }

    @Test
    void putRejectsPercentOutOfRange() {
        String id = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"applyToAll\":false,\"order\":\"MOST_EXPENSIVE_FIRST\",\"tiers\":[{\"fromChild\":2,\"percent\":150}]}")
            .when().put("/api/v1/kosten-discount?semesterId=" + id).then().statusCode(400);
    }

    @Test
    void putRejectsUnknownOrder() {
        String id = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"applyToAll\":false,\"order\":\"RANDOM\",\"tiers\":[]}")
            .when().put("/api/v1/kosten-discount?semesterId=" + id).then().statusCode(400);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=KostenDiscountResourceTest`
Expected: FAIL — entity/resource missing.

- [ ] **Step 3: Create the entity**

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

@MongoEntity(collection = "kosten_discounts")
public class KostenDiscount extends PanacheMongoEntity {
    public ObjectId semesterId;
    public boolean applyToAll;
    public String order = "MOST_EXPENSIVE_FIRST";
    public List<Tier> tiers = new ArrayList<>();

    public static class Tier {
        public int fromChild;
        public int percent;
    }

    public static KostenDiscount findBySemesterId(ObjectId semesterId) {
        return find("semesterId", semesterId).firstResult();
    }
}
```

- [ ] **Step 4: Create the DTO**

```java
package at.kigruapp.dto;

import java.util.ArrayList;
import java.util.List;

public class KostenDiscountDto {
    public String semesterId;
    public boolean applyToAll;
    public String order;
    public List<TierDto> tiers = new ArrayList<>();

    public static class TierDto {
        public int fromChild;
        public int percent;
    }
}
```

- [ ] **Step 5: Create the resource**

```java
package at.kigruapp.resource;

import at.kigruapp.dto.KostenDiscountDto;
import at.kigruapp.entity.KostenDiscount;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Path("/api/v1/kosten-discount")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KostenDiscountResource {

    private static final Set<String> ORDERS = Set.of("MOST_EXPENSIVE_FIRST", "LEAST_EXPENSIVE_FIRST");

    @GET
    public KostenDiscountDto get(@QueryParam("semesterId") String semesterIdParam) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        return toDto(semesterId, KostenDiscount.findBySemesterId(semesterId));
    }

    @PUT
    public KostenDiscountDto put(@QueryParam("semesterId") String semesterIdParam, KostenDiscountDto in) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        validate(in);

        KostenDiscount cfg = KostenDiscount.findBySemesterId(semesterId);
        if (cfg == null) {
            cfg = new KostenDiscount();
            cfg.semesterId = semesterId;
        }
        cfg.applyToAll = in.applyToAll;
        cfg.order = in.order;
        cfg.tiers = new ArrayList<>();
        for (KostenDiscountDto.TierDto t : in.tiers) {
            KostenDiscount.Tier tier = new KostenDiscount.Tier();
            tier.fromChild = t.fromChild;
            tier.percent = t.percent;
            cfg.tiers.add(tier);
        }
        cfg.persistOrUpdate();
        return toDto(semesterId, cfg);
    }

    private ObjectId requireSemesterId(String semesterIdParam) {
        if (semesterIdParam == null || semesterIdParam.isBlank() || !ObjectId.isValid(semesterIdParam)) {
            throw new BadRequestException("semesterId erforderlich");
        }
        return new ObjectId(semesterIdParam);
    }

    private void validate(KostenDiscountDto in) {
        if (in == null || in.order == null || !ORDERS.contains(in.order)) {
            throw new BadRequestException("order muss MOST_EXPENSIVE_FIRST oder LEAST_EXPENSIVE_FIRST sein");
        }
        List<KostenDiscountDto.TierDto> tiers = in.tiers == null ? List.of() : in.tiers;
        int prevFrom = 1;
        for (KostenDiscountDto.TierDto t : tiers) {
            if (t.fromChild < 2) {
                throw new BadRequestException("fromChild muss mindestens 2 sein");
            }
            if (t.fromChild <= prevFrom) {
                throw new BadRequestException("fromChild muss eindeutig und aufsteigend sein");
            }
            if (t.percent < 0 || t.percent > 100) {
                throw new BadRequestException("percent muss zwischen 0 und 100 liegen");
            }
            prevFrom = t.fromChild;
        }
    }

    private KostenDiscountDto toDto(ObjectId semesterId, KostenDiscount cfg) {
        KostenDiscountDto dto = new KostenDiscountDto();
        dto.semesterId = semesterId.toHexString();
        dto.tiers = new ArrayList<>();
        if (cfg == null) {
            dto.applyToAll = false;
            dto.order = "MOST_EXPENSIVE_FIRST";
            return dto;
        }
        dto.applyToAll = cfg.applyToAll;
        dto.order = cfg.order != null ? cfg.order : "MOST_EXPENSIVE_FIRST";
        if (cfg.tiers != null) {
            for (KostenDiscount.Tier t : cfg.tiers) {
                KostenDiscountDto.TierDto td = new KostenDiscountDto.TierDto();
                td.fromChild = t.fromChild;
                td.percent = t.percent;
                dto.tiers.add(td);
            }
        }
        return dto;
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=KostenDiscountResourceTest`
Expected: PASS (6 tests).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/KostenDiscount.java \
        backend/src/main/java/at/kigruapp/dto/KostenDiscountDto.java \
        backend/src/main/java/at/kigruapp/resource/KostenDiscountResource.java \
        backend/src/test/java/at/kigruapp/resource/KostenDiscountResourceTest.java
git commit -m "feat: KostenDiscount entity + GET/PUT endpoint with validation"
```

---

## Task 4: KostenDefinition siblingDiscount flag + PATCH endpoint

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/KostenDefinition.java`
- Modify: `backend/src/main/java/at/kigruapp/dto/KostenDefinitionDTO.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/KostenDefinitionResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/KostenDefinitionResourceTest.java`

**Interfaces:**
- Produces: `KostenDefinition.siblingDiscount : boolean`; `KostenDefinitionDTO` gains trailing `boolean siblingDiscount`; `PATCH /api/v1/kosten-definitions/{id}/sibling-discount` with body `{ "siblingDiscount": true }` returning the updated DTO.

- [ ] **Step 1: Add a failing test to `KostenDefinitionResourceTest`**

Append this test method (create a definition, flip the flag, assert it persists). Mirror the existing test's setup for creating a definition + currency (reuse whatever helper the file already has for obtaining a `currencyId`; if a definition is created inline via POST, read its `id` from the response).

```java
    @Test
    void setSiblingDiscountPersists() {
        String currencyId = anyCurrencyId(); // existing helper / inline currency creation in this test class
        String defId = io.restassured.RestAssured.given().contentType(io.restassured.http.ContentType.JSON)
                .body("{\"label\":\"Elternbeitrag\",\"currencyId\":\"" + currencyId + "\"}")
                .when().post("/api/v1/kosten-definitions")
                .then().statusCode(201).extract().path("id");

        io.restassured.RestAssured.given().contentType(io.restassured.http.ContentType.JSON)
                .body("{\"siblingDiscount\":true}")
                .when().patch("/api/v1/kosten-definitions/" + defId + "/sibling-discount")
                .then().statusCode(200).body("siblingDiscount", org.hamcrest.Matchers.is(true));

        io.restassured.RestAssured.given().when().get("/api/v1/kosten-definitions")
                .then().statusCode(200)
                .body("find { it.id == '" + defId + "' }.siblingDiscount", org.hamcrest.Matchers.is(true));
    }
```

Note: if `KostenDefinitionResourceTest` lacks an `anyCurrencyId()` helper, add one that POSTs to `/api/v1/currencies` (follow the currency-creation pattern already used elsewhere in the test suite) and returns the new id.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=KostenDefinitionResourceTest#setSiblingDiscountPersists`
Expected: FAIL — no `sibling-discount` endpoint / DTO field.

- [ ] **Step 3: Add the entity field**

In `KostenDefinition.java`, add after `public boolean active;`:

```java
    public boolean siblingDiscount;
```

- [ ] **Step 4: Extend the DTO**

Replace the record in `KostenDefinitionDTO.java`:

```java
package at.kigruapp.dto;

import at.kigruapp.entity.Currency;

public record KostenDefinitionDTO(String id, String label, boolean active, Currency currency, boolean siblingDiscount) {}
```

- [ ] **Step 5: Wire the resource**

In `KostenDefinitionResource.java`: update `toDTO` to pass the flag, and add the PATCH endpoint.

```java
    public record SetSiblingDiscountRequest(boolean siblingDiscount) {}

    @PATCH
    @Path("/{id}/sibling-discount")
    public Response setSiblingDiscount(@PathParam("id") String id, SetSiblingDiscountRequest request) {
        if (!ObjectId.isValid(id)) {
            throw new BadRequestException("Invalid id: " + id);
        }
        KostenDefinition definition = KostenDefinition.findById(new ObjectId(id));
        if (definition == null) {
            throw new NotFoundException();
        }
        definition.siblingDiscount = request.siblingDiscount();
        definition.update();
        return Response.ok(toDTO(definition)).build();
    }
```

And update `toDTO`:

```java
    private KostenDefinitionDTO toDTO(KostenDefinition definition) {
        Currency currency = Currency.findById(definition.currencyId);
        return new KostenDefinitionDTO(
                definition.id.toString(),
                definition.label,
                definition.active,
                currency,
                definition.siblingDiscount
        );
    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=KostenDefinitionResourceTest`
Expected: PASS (existing tests still green + new one).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/KostenDefinition.java \
        backend/src/main/java/at/kigruapp/dto/KostenDefinitionDTO.java \
        backend/src/main/java/at/kigruapp/resource/KostenDefinitionResource.java \
        backend/src/test/java/at/kigruapp/resource/KostenDefinitionResourceTest.java
git commit -m "feat: KostenDefinition siblingDiscount flag + PATCH endpoint"
```

---

## Task 5: HoursBalanceService aliquot-aware Soll (pure)

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/HoursBalanceService.java`
- Test: `backend/src/test/java/at/kigruapp/service/HoursBalanceServiceTest.java`

**Interfaces:**
- Consumes: `AliquotMode`, `AliquotService.monthFraction` (Task 2); `RequiredHours`, `Semester`.
- Produces: pure method
  `int familySollMinutes(RequiredHours cfg, AliquotMode mode, Semester semester, List<ChildPlacement> placements)`
  and nested `static class ChildPlacement { String childId; String entryDate; String exitDate; }`.
  Behavior: `mode=NONE` → `familyMonthlyMinutes(cfg, placements.size()) × monthsInSemester(semester)` (window ignored). Otherwise per-month: for each semester month, collect placements with `fraction>0`, sort by fraction desc then childId, assign ordinal `1..N`, sum `round(rateForChild(cfg, ordinal) × fraction)`.

- [ ] **Step 1: Write failing tests (append to `HoursBalanceServiceTest`)**

```java
    private final AliquotService aliquot = new AliquotService();

    private HoursBalanceService.ChildPlacement placement(String id, String entry, String exit) {
        HoursBalanceService.ChildPlacement p = new HoursBalanceService.ChildPlacement();
        p.childId = id;
        p.entryDate = entry;
        p.exitDate = exit;
        return p;
    }

    private Semester sepToFeb() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        return s; // 6 months
    }

    @Test
    void soll_noneMode_equalsLegacyFormula() {
        RequiredHours c = cfg(480, tier(2, 360)); // 8h, ab 2. Kind 6h
        Semester s = sepToFeb();
        // two children, windows irrelevant under NONE
        var placements = List.of(placement("a", "2026-11-01", null), placement("b", null, null));
        // familyMonthlyMinutes(c,2)=840, x6 = 5040
        assertEquals(5040, service.familySollMinutes(c, AliquotMode.NONE, s, placements));
    }

    @Test
    void soll_wholeMonth_dropsOutOfWindowMonths() {
        RequiredHours c = cfg(480); // 8h flat, no tiers
        Semester s = sepToFeb();
        // single child present only Dec..Feb (enters 2026-12-01) -> 3 months x 480 = 1440
        var placements = List.of(placement("a", "2026-12-01", null));
        assertEquals(1440, service.familySollMinutes(c, AliquotMode.WHOLE_MONTH, s, placements));
    }

    @Test
    void soll_perDay_proratesEntryMonth() {
        RequiredHours c = cfg(480); // 8h flat
        Semester s = sepToFeb();
        // enters Nov 16 -> Nov = 15/30*480 = 240; Dec,Jan,Feb full = 3*480=1440; total 1680
        var placements = List.of(placement("a", "2026-11-16", null));
        assertEquals(1680, service.familySollMinutes(c, AliquotMode.PER_DAY, s, placements));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=HoursBalanceServiceTest`
Expected: FAIL — `familySollMinutes` / `ChildPlacement` missing.

- [ ] **Step 3: Implement in `HoursBalanceService`**

Add the import `import java.math.BigDecimal;`, `import java.math.RoundingMode;`, `import java.time.YearMonth;` (already present), `import java.util.ArrayList;`, `import java.util.Comparator;`. Inject the aliquot helper and add the method + nested class:

```java
    @Inject
    AliquotService aliquotService;

    public static class ChildPlacement {
        public String childId;
        public String entryDate;
        public String exitDate;
    }

    /** Family Soll (minutes) over the semester, applying the aliquot mode. Pure given placements. */
    public int familySollMinutes(RequiredHours cfg, AliquotMode mode, Semester semester,
                                 List<ChildPlacement> placements) {
        if (mode == AliquotMode.NONE) {
            return familyMonthlyMinutes(cfg, placements.size()) * monthsInSemester(semester);
        }
        if (semester == null || semester.start == null || semester.end == null) {
            return 0;
        }
        YearMonth cur = YearMonth.from(semester.start.atZone(ZoneOffset.UTC));
        YearMonth last = YearMonth.from(semester.end.atZone(ZoneOffset.UTC));
        int total = 0;
        while (!cur.isAfter(last)) {
            final YearMonth ym = cur;
            List<BigDecimal> presentFractions = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (ChildPlacement p : placements) {
                BigDecimal f = aliquotService.monthFraction(
                        mode, p.entryDate, p.exitDate, ym.getYear(), ym.getMonthValue());
                if (f.signum() > 0) {
                    presentFractions.add(f);
                    ids.add(p.childId);
                }
            }
            // ordinal by fraction desc, tie-break childId
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < presentFractions.size(); i++) idx.add(i);
            final List<BigDecimal> fr = presentFractions;
            final List<String> idList = ids;
            idx.sort(Comparator.<Integer, BigDecimal>comparing(fr::get).reversed()
                    .thenComparing(idList::get));
            for (int ordinalPos = 0; ordinalPos < idx.size(); ordinalPos++) {
                int childIdx = idx.get(ordinalPos);
                int rate = rateForChild(cfg, ordinalPos + 1);
                total += BigDecimal.valueOf(rate).multiply(fr.get(childIdx))
                        .setScale(0, RoundingMode.HALF_UP).intValue();
            }
            cur = cur.plusMonths(1);
        }
        return total;
    }
```

Note: this pure method takes the already-fetched placements; the DB-facing caller (Task 6b integration or the family-summary endpoint) builds `List<ChildPlacement>` from `semester_assignments`. Wiring the family-summary endpoint to pass the semester's `AliquotMode` is done in Task 6b's integration step for `HourEntryResource` (load `AliquotConfig.findBySemesterId`, map via `AliquotMode.fromString`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=HoursBalanceServiceTest`
Expected: PASS (existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/HoursBalanceService.java \
        backend/src/test/java/at/kigruapp/service/HoursBalanceServiceTest.java
git commit -m "feat: aliquot-aware familySollMinutes in HoursBalanceService"
```

---

## Task 6a: Sibling discount factor (pure helper)

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/BilanzCalculationService.java`
- Test: `backend/src/test/java/at/kigruapp/service/BilanzDiscountTest.java` (new, pure — no `@QuarkusTest`)

**Interfaces:**
- Consumes: `KostenDiscount` (Task 3).
- Produces: pure static-style helper on `BilanzCalculationService`:
  `BigDecimal discountFactor(KostenDiscount cfg, String targetChildId, List<ChildBase> present)` and
  `public static class ChildBase { public String childId; public BigDecimal base; }`.
  Ranks `present` by `base` (direction from `cfg.order`; tie-break `childId`), finds `targetChildId`'s ordinal (1-based), returns `(100 - percentForOrdinal) / 100` as `BigDecimal` (scale 4). Returns `BigDecimal.ONE` when `cfg` null, tiers empty, or target not present.

- [ ] **Step 1: Write the failing pure test**

```java
package at.kigruapp.service;

import at.kigruapp.entity.KostenDiscount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BilanzDiscountTest {

    private final BilanzCalculationService svc = new BilanzCalculationService();

    private KostenDiscount cfg(String order, int[]... tiers) {
        KostenDiscount c = new KostenDiscount();
        c.order = order;
        c.tiers = new java.util.ArrayList<>();
        for (int[] t : tiers) {
            KostenDiscount.Tier tier = new KostenDiscount.Tier();
            tier.fromChild = t[0];
            tier.percent = t[1];
            c.tiers.add(tier);
        }
        return c;
    }

    private BilanzCalculationService.ChildBase cb(String id, String base) {
        BilanzCalculationService.ChildBase b = new BilanzCalculationService.ChildBase();
        b.childId = id;
        b.base = new BigDecimal(base);
        return b;
    }

    @Test
    void mostExpensiveFirst_topChildFullPrice() {
        KostenDiscount c = cfg("MOST_EXPENSIVE_FIRST", new int[]{2, 50}, new int[]{3, 100});
        List<BilanzCalculationService.ChildBase> present =
                List.of(cb("a", "100"), cb("b", "80"), cb("c", "60"));
        assertEquals(0, new BigDecimal("1.0000").compareTo(svc.discountFactor(c, "a", present))); // 1st
        assertEquals(0, new BigDecimal("0.5000").compareTo(svc.discountFactor(c, "b", present))); // 2nd -50%
        assertEquals(0, new BigDecimal("0.0000").compareTo(svc.discountFactor(c, "c", present))); // 3rd -100%
    }

    @Test
    void leastExpensiveFirst_reversesRanking() {
        KostenDiscount c = cfg("LEAST_EXPENSIVE_FIRST", new int[]{2, 50});
        List<BilanzCalculationService.ChildBase> present = List.of(cb("a", "100"), cb("b", "80"));
        assertEquals(0, new BigDecimal("1.0000").compareTo(svc.discountFactor(c, "b", present))); // cheapest = 1st
        assertEquals(0, new BigDecimal("0.5000").compareTo(svc.discountFactor(c, "a", present)));
    }

    @Test
    void noTiers_alwaysFullPrice() {
        KostenDiscount c = cfg("MOST_EXPENSIVE_FIRST");
        List<BilanzCalculationService.ChildBase> present = List.of(cb("a", "100"), cb("b", "80"));
        assertEquals(0, BigDecimal.ONE.compareTo(svc.discountFactor(c, "b", present)));
    }

    @Test
    void nullConfig_fullPrice() {
        assertEquals(0, BigDecimal.ONE.compareTo(svc.discountFactor(null, "a", List.of())));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=BilanzDiscountTest`
Expected: FAIL — `discountFactor` / `ChildBase` missing.

- [ ] **Step 3: Implement the helper in `BilanzCalculationService`**

Add imports `import at.kigruapp.entity.KostenDiscount;`, `import java.math.RoundingMode;` (BigDecimal/Comparator/List/ArrayList already imported). Add:

```java
    public static class ChildBase {
        public String childId;
        public BigDecimal base;
    }

    /** Discount factor (0..1) for targetChild given the family's present children this month. */
    public BigDecimal discountFactor(KostenDiscount cfg, String targetChildId, List<ChildBase> present) {
        if (cfg == null || cfg.tiers == null || cfg.tiers.isEmpty()) {
            return BigDecimal.ONE;
        }
        List<ChildBase> ranked = new ArrayList<>(present);
        Comparator<ChildBase> byBase = Comparator.comparing((ChildBase b) -> b.base);
        boolean leastFirst = "LEAST_EXPENSIVE_FIRST".equals(cfg.order);
        Comparator<ChildBase> cmp = (leastFirst ? byBase : byBase.reversed())
                .thenComparing(b -> b.childId);
        ranked.sort(cmp);

        int ordinal = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).childId.equals(targetChildId)) {
                ordinal = i + 1;
                break;
            }
        }
        if (ordinal < 1) {
            return BigDecimal.ONE;
        }
        int percent = 0;
        int bestFrom = 0;
        for (KostenDiscount.Tier t : cfg.tiers) {
            if (t.fromChild <= ordinal && t.fromChild >= bestFrom) {
                bestFrom = t.fromChild;
                percent = t.percent;
            }
        }
        return BigDecimal.valueOf(100 - percent)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=BilanzDiscountTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/BilanzCalculationService.java \
        backend/src/test/java/at/kigruapp/service/BilanzDiscountTest.java
git commit -m "feat: pure sibling-discount factor helper in BilanzCalculationService"
```

---

## Task 6b: Integrate discount + aliquot into Bilanz computation

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/BilanzCalculationService.java`
- Modify: `backend/src/main/java/at/kigruapp/dto/BilanzCellDTO.java`
- Test: `backend/src/test/java/at/kigruapp/resource/BilanzResourceTest.java` (extend)

**Interfaces:**
- Consumes: `AliquotService.monthFraction`, `AliquotMode` (Task 2); `discountFactor`/`ChildBase` (Task 6a); `KostenDiscount`, `AliquotConfig`, `KostenDefinition.siblingDiscount`.
- Produces: Kosten month amounts reflect `base × (eligible ? factor : 1) × monthFraction`, overrides bypassing both. `BilanzCellDTO.Line` gains trailing `int discountPercent` and `String weight` (the fraction as plain string, `"1"` when full) so the breakdown is explainable.

- [ ] **Step 1: Write a failing integration test in `BilanzResourceTest`**

Follow the existing `BilanzResourceTest` fixtures (it already seeds families, children, group assignments, semesters, `KostenDefinition`, `KostenValue`). Add a test that: creates a family with two children in a group, a `KostenValue` for one active `siblingDiscount=true` definition, a `KostenDiscount` with `applyToAll=false` and tier `{fromChild:2, percent:50}`, then asserts via `GET /api/v1/bilanz/cell?...` (or the matrix endpoint the suite already exercises) that the 2nd-ranked child's discountable line is half the base. Mirror the assertion style already used in the file. Also assert a `siblingDiscount=false` definition (e.g. Essen) stays full price for both children.

If `BilanzResourceTest` seeds data through helpers, reuse them; only add the `KostenDefinition.siblingDiscount` flag (via the Task 4 PATCH or by direct entity persist in the test setup) and the `KostenDiscount` document.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=BilanzResourceTest`
Expected: FAIL — discount not yet applied (2nd child still full price).

- [ ] **Step 3: Extend `BilanzCellDTO.Line`**

```java
package at.kigruapp.dto;

import java.math.BigDecimal;
import java.util.List;

public record BilanzCellDTO(List<Line> lines, BigDecimal sum, boolean mixedCurrency) {
    public record Line(
            String personId,
            String childName,
            String definitionId,
            String label,
            String currencySymbol,
            BigDecimal defaultAmount,
            BigDecimal effectiveAmount,
            int discountPercent,
            String weight) {}
}
```

- [ ] **Step 4: Inject config + helpers and apply in the computation**

In `BilanzCalculationService`:

1. Add injections:

```java
    @Inject
    AliquotService aliquotService;
```

2. Add helpers to load per-semester config and a child's discountable base:

```java
    private AliquotMode aliquotMode(ObjectId semesterId) {
        AliquotConfig cfg = AliquotConfig.findBySemesterId(semesterId);
        return AliquotMode.fromString(cfg != null ? cfg.mode : null);
    }

    private boolean eligible(KostenDiscount discountCfg, KostenDefinition def) {
        if (discountCfg == null) return false;
        return discountCfg.applyToAll || def.siblingDiscount;
    }

    /** Sum of default amounts over discount-eligible defs for a child's group (ranking base). */
    private BigDecimal discountableBase(ObjectId semesterId, ObjectId groupId,
                                        List<KostenDefinition> defs, KostenDiscount discountCfg) {
        BigDecimal base = BigDecimal.ZERO;
        for (KostenDefinition def : defs) {
            if (!eligible(discountCfg, def)) continue;
            BigDecimal d0 = defaultAmount(semesterId, groupId, def.id);
            if (d0 != null) base = base.add(d0);
        }
        return base;
    }

    /** Present family children this month with their discountable base, for ranking. */
    private List<ChildBase> presentSiblings(ObjectId familyId, Semester semester, int year, int month,
                                            AliquotMode mode, List<KostenDefinition> defs,
                                            KostenDiscount discountCfg) {
        List<ChildBase> out = new ArrayList<>();
        for (Person c : childrenOf(familyId)) {
            GroupRef g = groupAssignment(c.id, semester.id);
            if (g == null) continue;
            BigDecimal frac = aliquotService.monthFraction(mode, g.entryDate, g.exitDate, year, month);
            if (frac.signum() == 0) continue;
            ChildBase cb = new ChildBase();
            cb.childId = c.id.toHexString();
            cb.base = discountableBase(semester.id, g.groupId, defs, discountCfg);
            out.add(cb);
        }
        return out;
    }
```

3. In **`computeCellInternal`** and **`computeCell`**, replace the per-definition amount logic so that, for each processed child, you:
   - load `AliquotMode mode = aliquotMode(semester.id)` and `KostenDiscount discountCfg = KostenDiscount.findBySemesterId(semester.id)` once per cell;
   - compute `BigDecimal frac = aliquotService.monthFraction(mode, gref.entryDate, gref.exitDate, year, month)` — treat `frac.signum()==0` exactly like the current `!activeInMonth` (skip / inactive);
   - compute the child's factor once: `List<ChildBase> present = presentSiblings(child.familyId, semester, year, month, mode, activeDefs, discountCfg); BigDecimal factor = discountFactor(discountCfg, child.id.toHexString(), present);`
   - for each def: `boolean elig = eligible(discountCfg, def); BigDecimal defFactor = elig ? factor : BigDecimal.ONE;` and when there is **no** `BilanzOverride`, set
     `eff = def0.multiply(defFactor).multiply(frac).setScale(2, RoundingMode.HALF_UP);`
     When a `BilanzOverride` exists, use its amount unchanged (bypass — do **not** multiply by factor or frac).
   - In `computeCell`, populate the new `Line` fields: `discountPercent = elig ? (100 - factor×100) : 0` (compute as `100 - factor.multiply(BigDecimal.valueOf(100)).intValueExact()` guarded, or store `Math.round`), and `weight = frac.stripTrailingZeros().toPlainString()`.

   Keep the existing `entryMarker`/`exitMarker` logic. Keep the "inactive cell carries nothing" invariant: `frac.signum()==0` ⇒ inactive.

   Replace the gating line `if (!activeInMonth(gref, year, month)) { continue; }` with the fraction check:
   ```java
   BigDecimal frac = aliquotService.monthFraction(mode, gref.entryDate, gref.exitDate, year, month);
   if (frac.signum() == 0) { continue; }
   ```
   (`activeInMonth` may then be removed if unused, or left; `monthFraction` under `NONE`/`WHOLE_MONTH` reproduces its binary result.)

Add imports: `import at.kigruapp.entity.AliquotConfig;`, `import at.kigruapp.entity.KostenDiscount;`, `import java.math.RoundingMode;`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=BilanzResourceTest,BilanzDiscountTest`
Expected: PASS. Then run the Bilanz-adjacent suite to catch regressions:
Run: `cd backend && ./mvnw test -Dtest=BilanzResourceTest,KostenValueResourceTest`
Expected: PASS (no regression in existing Kosten behavior, since `NONE`+no-discount reproduces prior amounts).

- [ ] **Step 6: Wire HourEntryResource family-summary to pass AliquotMode (Stunden side)**

In `HourEntryResource` (family-summary endpoint), where the Soll is currently computed via `HoursBalanceService`, build `List<HoursBalanceService.ChildPlacement>` for each family from `semester_assignments` (`section='group'`, entry/exit dates), load `AliquotMode mode = AliquotMode.fromString(AliquotConfig.findBySemesterId(semesterId)?.mode)`, and call `familySollMinutes(cfg, mode, semester, placements)` instead of the legacy `familyMonthlyMinutes × months`. Preserve the existing per-month reconciliation (out-of-window synthetic rows) established in commit `c5426f8`. Add/adjust a `HourEntryFamilySummaryTest` case asserting a `PER_DAY` semester reduces a mid-semester entrant's Soll.

Run: `cd backend && ./mvnw test -Dtest=HourEntryFamilySummaryTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/BilanzCalculationService.java \
        backend/src/main/java/at/kigruapp/dto/BilanzCellDTO.java \
        backend/src/main/java/at/kigruapp/resource/HourEntryResource.java \
        backend/src/test/java/at/kigruapp/resource/BilanzResourceTest.java \
        backend/src/test/java/at/kigruapp/resource/HourEntryFamilySummaryTest.java
git commit -m "feat: apply sibling discount + aliquot to Kosten and Stunden-Soll"
```

---

## Task 7: Frontend — AliquotConfig model, service, dropdown UI

**Files:**
- Create: `frontend/src/app/shared/models/aliquot-config.model.ts`
- Create: `frontend/src/app/shared/services/aliquot-config.service.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.html`
- Test: `frontend/src/app/settings/organisation/organisation.component.spec.ts`

**Interfaces:**
- Produces: `AliquotConfig { semesterId: string; mode: 'NONE'|'WHOLE_MONTH'|'PER_DAY' }`; `AliquotConfigService.get/save`. Component: `aliquotMode`, `onAliquotSemesterChange`, `saveAliquot`, reusing the existing `rhSelectedSemesterId` semester selection (the aliquot control lives in the same "Zu leistende Stunden" tab, labelled as applying to Stunden **und** Kosten).

- [ ] **Step 1: Write failing spec (append to `organisation.component.spec.ts`)**

```ts
it('loads and saves aliquot mode for the selected semester', () => {
  const http = TestBed.inject(HttpTestingController);
  component.rhSelectedSemesterId = 'sem1';
  component.loadAliquot();
  http.expectOne('/api/v1/aliquot-config?semesterId=sem1').flush({ semesterId: 'sem1', mode: 'PER_DAY' });
  expect(component.aliquotMode).toBe('PER_DAY');

  component.aliquotMode = 'WHOLE_MONTH';
  component.saveAliquot();
  const req = http.expectOne('/api/v1/aliquot-config?semesterId=sem1');
  expect(req.request.method).toBe('PUT');
  expect(req.request.body.mode).toBe('WHOLE_MONTH');
  req.flush({ semesterId: 'sem1', mode: 'WHOLE_MONTH' });
});
```

(Reuse the file's existing `TestBed`/`HttpTestingController` setup; if none imports `HttpClientTestingModule`, add it to the test module imports.)

- [ ] **Step 2: Run spec to verify it fails**

Run: `cd frontend && npm test -- --watch=false --include='**/organisation.component.spec.ts'`
Expected: FAIL — `loadAliquot`/`aliquotMode` undefined.

- [ ] **Step 3: Create model + service**

`aliquot-config.model.ts`:
```ts
export type AliquotMode = 'NONE' | 'WHOLE_MONTH' | 'PER_DAY';

export interface AliquotConfig {
  semesterId: string;
  mode: AliquotMode;
}
```

`aliquot-config.service.ts`:
```ts
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AliquotConfig } from '../models/aliquot-config.model';

@Injectable({ providedIn: 'root' })
export class AliquotConfigService {
  private readonly base = '/api/v1/aliquot-config';

  constructor(private http: HttpClient) {}

  get(semesterId: string): Observable<AliquotConfig> {
    return this.http.get<AliquotConfig>(`${this.base}?semesterId=${semesterId}`);
  }

  save(semesterId: string, dto: AliquotConfig): Observable<AliquotConfig> {
    return this.http.put<AliquotConfig>(`${this.base}?semesterId=${semesterId}`, dto);
  }
}
```

- [ ] **Step 4: Wire the component**

In `organisation.component.ts`: import `AliquotConfigService` and `AliquotMode`, inject it, add field `aliquotMode: AliquotMode = 'NONE';`, and load it whenever the RH semester loads. Extend `loadRequiredHours()`/`onRhSemesterChange` to also call `loadAliquot()`:

```ts
  loadAliquot(): void {
    if (!this.rhSelectedSemesterId) return;
    this.aliquotConfigService.get(this.rhSelectedSemesterId).subscribe((cfg) => {
      this.aliquotMode = cfg.mode ?? 'NONE';
    });
  }

  saveAliquot(): void {
    if (!this.rhSelectedSemesterId) return;
    this.aliquotConfigService
      .save(this.rhSelectedSemesterId, { semesterId: this.rhSelectedSemesterId, mode: this.aliquotMode })
      .subscribe();
  }
```

Call `this.loadAliquot()` inside `loadRequiredHours()` (after the config loads) and in `onRhSemesterChange`.

- [ ] **Step 5: Add the dropdown to `organisation.component.html`**

In the "Zu leistende Stunden" tab, add a `mat-form-field` with a `mat-select` bound to `aliquotMode` (options: Keine / Ganze Monate / Taggenau ⇒ `NONE`/`WHOLE_MONTH`/`PER_DAY`) and a save button calling `saveAliquot()`. Include a caption: "Gilt für Zu leistende Stunden **und** Kosten." Follow the markup style of the existing semester `mat-select` in the file.

- [ ] **Step 6: Run spec to verify it passes**

Run: `cd frontend && npm test -- --watch=false --include='**/organisation.component.spec.ts'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/shared/models/aliquot-config.model.ts \
        frontend/src/app/shared/services/aliquot-config.service.ts \
        frontend/src/app/settings/organisation/organisation.component.ts \
        frontend/src/app/settings/organisation/organisation.component.html \
        frontend/src/app/settings/organisation/organisation.component.spec.ts
git commit -m "feat: aliquot mode dropdown in organisation settings"
```

---

## Task 8: Frontend — Kosten sibling-discount config UI

**Files:**
- Create: `frontend/src/app/shared/models/kosten-discount.model.ts`
- Create: `frontend/src/app/shared/services/kosten-discount.service.ts`
- Create: `frontend/src/app/settings/organisation/kosten-discount-preview.util.ts`
- Test: `frontend/src/app/settings/organisation/kosten-discount-preview.util.spec.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts` + `.html` + `.spec.ts`

**Interfaces:**
- Produces: `KostenDiscount { semesterId; applyToAll: boolean; order: 'MOST_EXPENSIVE_FIRST'|'LEAST_EXPENSIVE_FIRST'; tiers: {fromChild:number;percent:number}[] }`; `KostenDiscountService.get/save`; pure `discountFactors(tiers, maxChildren) → {child:number, percent:number}[]` for the preview.

- [ ] **Step 1: Write the failing preview-util spec**

```ts
import { discountFactors } from './kosten-discount-preview.util';

describe('discountFactors', () => {
  it('assigns full price to child 1 and tier percents thereafter', () => {
    const rows = discountFactors([{ fromChild: 2, percent: 50 }, { fromChild: 3, percent: 100 }], 4);
    expect(rows).toEqual([
      { child: 1, percent: 0 },
      { child: 2, percent: 50 },
      { child: 3, percent: 100 },
      { child: 4, percent: 100 },
    ]);
  });

  it('is full price everywhere with no tiers', () => {
    expect(discountFactors([], 2)).toEqual([{ child: 1, percent: 0 }, { child: 2, percent: 0 }]);
  });
});
```

- [ ] **Step 2: Run spec to verify it fails**

Run: `cd frontend && npm test -- --watch=false --include='**/kosten-discount-preview.util.spec.ts'`
Expected: FAIL — module missing.

- [ ] **Step 3: Create the preview util**

```ts
export interface KostenDiscountTier {
  fromChild: number;
  percent: number;
}

export function discountFactors(tiers: KostenDiscountTier[], maxChildren: number): { child: number; percent: number }[] {
  const sorted = [...tiers].sort((a, b) => a.fromChild - b.fromChild);
  const rows: { child: number; percent: number }[] = [];
  for (let n = 1; n <= maxChildren; n++) {
    let percent = 0;
    let bestFrom = 0;
    for (const t of sorted) {
      if (t.fromChild <= n && t.fromChild >= bestFrom) {
        bestFrom = t.fromChild;
        percent = t.percent;
      }
    }
    rows.push({ child: n, percent });
  }
  return rows;
}
```

- [ ] **Step 4: Create model + service**

`kosten-discount.model.ts`:
```ts
export type KostenDiscountOrder = 'MOST_EXPENSIVE_FIRST' | 'LEAST_EXPENSIVE_FIRST';

export interface KostenDiscountTier {
  fromChild: number;
  percent: number;
}

export interface KostenDiscount {
  semesterId: string;
  applyToAll: boolean;
  order: KostenDiscountOrder;
  tiers: KostenDiscountTier[];
}
```

`kosten-discount.service.ts`:
```ts
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { KostenDiscount } from '../models/kosten-discount.model';

@Injectable({ providedIn: 'root' })
export class KostenDiscountService {
  private readonly base = '/api/v1/kosten-discount';

  constructor(private http: HttpClient) {}

  get(semesterId: string): Observable<KostenDiscount> {
    return this.http.get<KostenDiscount>(`${this.base}?semesterId=${semesterId}`);
  }

  save(semesterId: string, dto: KostenDiscount): Observable<KostenDiscount> {
    return this.http.put<KostenDiscount>(`${this.base}?semesterId=${semesterId}`, dto);
  }
}
```

- [ ] **Step 5: Write a failing component spec for save**

```ts
it('saves kosten discount tiers for the selected semester', () => {
  const http = TestBed.inject(HttpTestingController);
  component.kdSelectedSemesterId = 'sem1';
  component.kdApplyToAll = false;
  component.kdOrder = 'MOST_EXPENSIVE_FIRST';
  component.kdTiers = [{ fromChild: 2, percent: 50 }];
  component.saveKostenDiscount();
  const req = http.expectOne('/api/v1/kosten-discount?semesterId=sem1');
  expect(req.request.method).toBe('PUT');
  expect(req.request.body.tiers[0].percent).toBe(50);
  req.flush({ semesterId: 'sem1', applyToAll: false, order: 'MOST_EXPENSIVE_FIRST', tiers: [{ fromChild: 2, percent: 50 }] });
});
```

- [ ] **Step 6: Run spec to verify it fails**

Run: `cd frontend && npm test -- --watch=false --include='**/organisation.component.spec.ts'`
Expected: FAIL — `saveKostenDiscount` undefined.

- [ ] **Step 7: Wire component fields + methods**

In `organisation.component.ts` add fields and methods mirroring the RH block, using percentages:

```ts
  // --- Geschwisterrabatt (Kosten) ---
  kdSelectedSemesterId: string | null = null;
  kdApplyToAll = false;
  kdOrder: 'MOST_EXPENSIVE_FIRST' | 'LEAST_EXPENSIVE_FIRST' = 'MOST_EXPENSIVE_FIRST';
  kdTiers: { fromChild: number; percent: number }[] = [];
  kdPreview: { child: number; percent: number }[] = [];
  kdError: string | null = null;

  loadKostenDiscount(): void {
    if (!this.kdSelectedSemesterId) return;
    this.kostenDiscountService.get(this.kdSelectedSemesterId).subscribe((cfg) => {
      this.kdApplyToAll = cfg.applyToAll;
      this.kdOrder = cfg.order ?? 'MOST_EXPENSIVE_FIRST';
      this.kdTiers = (cfg.tiers ?? []).map((t) => ({ fromChild: t.fromChild, percent: t.percent }));
      this.recomputeKdPreview();
    });
  }

  onKdSemesterChange(semesterId: string): void {
    this.kdSelectedSemesterId = semesterId;
    this.loadKostenDiscount();
  }

  addKdTier(): void {
    const nextFrom = this.kdTiers.length === 0 ? 2 : Math.max(...this.kdTiers.map((t) => t.fromChild)) + 1;
    this.kdTiers.push({ fromChild: nextFrom, percent: 0 });
    this.recomputeKdPreview();
  }

  removeKdTier(index: number): void {
    this.kdTiers.splice(index, 1);
    this.recomputeKdPreview();
  }

  recomputeKdPreview(): void {
    this.kdPreview = discountFactors(this.kdTiers, 4);
  }

  saveKostenDiscount(): void {
    this.kdError = null;
    const froms = this.kdTiers.map((t) => t.fromChild);
    const ascendingUnique = froms.every((f, i) => f >= 2 && (i === 0 || f > froms[i - 1]));
    const percentsValid = this.kdTiers.every((t) => t.percent >= 0 && t.percent <= 100);
    if (!ascendingUnique || !percentsValid) {
      this.kdError = 'Staffeln müssen ab dem 2. Kind, eindeutig, aufsteigend und 0–100 % sein';
      return;
    }
    if (!this.kdSelectedSemesterId) return;
    this.kostenDiscountService.save(this.kdSelectedSemesterId, {
      semesterId: this.kdSelectedSemesterId,
      applyToAll: this.kdApplyToAll,
      order: this.kdOrder,
      tiers: this.kdTiers,
    }).subscribe({
      next: () => { this.kdError = null; },
      error: () => { this.kdError = 'Speichern fehlgeschlagen'; },
    });
  }
```

Add imports for `KostenDiscountService` + `discountFactors`, inject the service, default `kdSelectedSemesterId` from the first semester in `loadRhSemesters()` (reuse the same semester list; set `this.kdSelectedSemesterId` and call `loadKostenDiscount()`).

- [ ] **Step 8: Add the config UI to `organisation.component.html`**

New tab or section "Geschwisterrabatt (Kosten)": semester `mat-select` (bound to `kdSelectedSemesterId`, `(selectionChange)="onKdSemesterChange($event.value)"`), a `mat-checkbox` "Rabatt auf alle Kostenpositionen anwenden" (bound to `kdApplyToAll`), a `mat-select` order dropdown (Teuerstes/Günstigstes Kind zuerst), the tier rows ("Ab dem [X.] Kind: [Y] %" with add/remove), a preview table from `kdPreview`, `kdError`, and a save button. Mirror the RH tab markup. Add `MatCheckboxModule` to the component imports.

- [ ] **Step 9: Run specs to verify they pass**

Run: `cd frontend && npm test -- --watch=false --include='**/kosten-discount-preview.util.spec.ts' --include='**/organisation.component.spec.ts'`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/app/shared/models/kosten-discount.model.ts \
        frontend/src/app/shared/services/kosten-discount.service.ts \
        frontend/src/app/settings/organisation/kosten-discount-preview.util.ts \
        frontend/src/app/settings/organisation/kosten-discount-preview.util.spec.ts \
        frontend/src/app/settings/organisation/organisation.component.ts \
        frontend/src/app/settings/organisation/organisation.component.html \
        frontend/src/app/settings/organisation/organisation.component.spec.ts
git commit -m "feat: Kosten sibling-discount config UI (tiers, order, preview)"
```

---

## Task 9: Frontend — per-definition Geschwisterrabatt checkbox

**Files:**
- Modify: `frontend/src/app/shared/models/kosten-definition.model.ts`
- Modify: `frontend/src/app/shared/services/kosten-definition.service.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts` + `.html` + `.spec.ts`

**Interfaces:**
- Consumes: `KostenDefinition` list (existing). 
- Produces: `KostenDefinition.siblingDiscount: boolean`; `KostenDefinitionService.setSiblingDiscount(id, value)`; component `toggleSiblingDiscount(def)`.

- [ ] **Step 1: Write failing spec (append to `organisation.component.spec.ts`)**

```ts
it('toggles a definition sibling-discount flag', () => {
  const http = TestBed.inject(HttpTestingController);
  const def = { id: 'd1', label: 'Elternbeitrag', active: true, currency: { id: 'c', code: 'EUR', symbol: '€' }, siblingDiscount: false } as any;
  component.toggleSiblingDiscount(def);
  const req = http.expectOne('/api/v1/kosten-definitions/d1/sibling-discount');
  expect(req.request.method).toBe('PATCH');
  expect(req.request.body.siblingDiscount).toBe(true);
  req.flush({ ...def, siblingDiscount: true });
  http.expectOne('/api/v1/kosten-definitions').flush([{ ...def, siblingDiscount: true }]);
});
```

- [ ] **Step 2: Run spec to verify it fails**

Run: `cd frontend && npm test -- --watch=false --include='**/organisation.component.spec.ts'`
Expected: FAIL — `toggleSiblingDiscount` / `setSiblingDiscount` undefined.

- [ ] **Step 3: Extend model**

In `kosten-definition.model.ts` add to `KostenDefinition`:
```ts
  siblingDiscount: boolean;
```

- [ ] **Step 4: Extend service**

In `kosten-definition.service.ts` add:
```ts
  setSiblingDiscount(id: string, siblingDiscount: boolean): Observable<KostenDefinition> {
    return this.api.patch<KostenDefinition>(`/kosten-definitions/${id}/sibling-discount`, { siblingDiscount });
  }
```

- [ ] **Step 5: Add component method**

In `organisation.component.ts`:
```ts
  toggleSiblingDiscount(definition: KostenDefinition): void {
    this.kostenDefinitionService.setSiblingDiscount(definition.id, !definition.siblingDiscount)
      .subscribe(() => this.loadKostenDefinitions());
  }
```

- [ ] **Step 6: Add checkbox column to the Kosten-Definitionen table (`.html`)**

Add a column/cell with a `mat-checkbox` bound to `definition.siblingDiscount`, `(change)="toggleSiblingDiscount(definition)"`, disabled when the semester's discount config has `applyToAll` active is out of scope here (the flag is org-wide per definition; `applyToAll` is per-semester) — so keep it always enabled, with a hint tooltip "Bei 'Rabatt auf alle Positionen' wird dieses Flag ignoriert." Add `siblingDiscount` to `kostenDefColumns` if the table is column-driven.

- [ ] **Step 7: Run spec to verify it passes**

Run: `cd frontend && npm test -- --watch=false --include='**/organisation.component.spec.ts'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/shared/models/kosten-definition.model.ts \
        frontend/src/app/shared/services/kosten-definition.service.ts \
        frontend/src/app/settings/organisation/organisation.component.ts \
        frontend/src/app/settings/organisation/organisation.component.html \
        frontend/src/app/settings/organisation/organisation.component.spec.ts
git commit -m "feat: per-definition Geschwisterrabatt checkbox"
```

---

## Task 10: Full-suite verification

**Files:** none (verification only).

- [ ] **Step 1: Backend full suite**

Run: `cd backend && ./mvnw test`
Expected: no **new** failures vs. the known baseline (12 pre-existing failures per project memory `project-broken-baseline`). Record the failing test names; confirm each matches the baseline list.

- [ ] **Step 2: Frontend full suite**

Run: `cd frontend && npm test -- --watch=false`
Expected: no new failures (1 pre-existing `AppComponent` baseline failure allowed).

- [ ] **Step 3: Manual smoke (optional but recommended)**

Use the `run` skill to launch the app; as admin: set an aliquot mode + a Kosten discount tier for a semester, flag a definition, and confirm the Bilanz matrix reflects the discount and pro-rata for a mid-semester entrant.

- [ ] **Step 4: Commit (only if verification produced doc/fixups)**

```bash
git add -A
git commit -m "test: full-suite verification for kosten-rabatt-aliquot"
```

---

## Self-Review

**Spec coverage:**
- Sibling discount percentage tiers → Tasks 3, 6a, 6b, 8. ✓
- Per-definition eligibility + applyToAll master → Tasks 3, 4, 6b, 8, 9. ✓
- Child ordering dropdown (most/least expensive) → Tasks 3, 6a, 8. ✓
- Aliquot per-semester mode (NONE/WHOLE_MONTH/PER_DAY), shared → Tasks 1, 2, 5, 6b, 7. ✓
- Whole-month = round up; per-day = calendar fraction → Task 2 (`monthFraction`) + tests. ✓
- Override bypasses discount + aliquot → Task 6b. ✓
- Rounding rules (money 2dp, minutes whole) → Tasks 2, 5, 6b. ✓
- Bilanz breakdown shows base/discount/weight → Task 6b (`BilanzCellDTO.Line`). ✓
- Admin-only (not whitelisted) → Global Constraints; Tasks 1, 3. ✓
- Out-of-scope items (auto-copy, notifications, override proration, currency) → untouched. ✓

**Placeholder scan:** Tasks that touch large existing files (6b integration, HTML markup in 7/8/9) describe exact fields/methods and reference the mirrored pattern; test bodies for `BilanzResourceTest` reuse existing fixtures (the one place full inline code is impractical without the file open) — flagged explicitly, not left as "TODO".

**Type consistency:** `AliquotMode` (enum) vs stored `String mode` bridged by `AliquotMode.fromString`. `monthFraction` returns `BigDecimal` used by both services. `ChildBase`/`ChildPlacement` field names match across producer/consumer tasks. `discountFactor` scale (4) and money scale (2) consistent. `KostenDiscount.order` string values identical across backend validation, helper, and frontend model.

**Known assumption to confirm with user:** the Stunden aliquot **ordinal rule** (present children ranked by fraction descending) is an implementation-level disambiguation not explicitly chosen during brainstorming — see Global Constraints. Adjust ranking direction there if a different fairness rule is preferred.
