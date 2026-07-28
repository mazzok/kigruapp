# Zu leistende Stunden Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-semester required-hours quota (default + nested per-child discounts), compute Soll/Ist per family, and surface it in a family-grouped admin overview and a family-wide "Unsere Stunden" parent view.

**Architecture:** A dedicated `RequiredHours` MongoDB entity stores the config per semester. A `HoursBalanceService` holds the pure calculation (rate-per-child, family monthly sum, months-in-semester) plus a placed-children counter. Three endpoint groups expose config CRUD, an admin family-summary, and a family-scoped "our hours" view. The Angular frontend gets a config tab, a reworked admin overview, and a reworked parent view.

**Tech Stack:** Quarkus (RESTEasy Reactive, Panache MongoDB), MongoDB, JUnit 5 + RestAssured; Angular 18 standalone components, Angular Material, Karma/Jasmine.

## Global Constraints

- Backend package root: `at.kigruapp`. Java 17, Quarkus. Entities extend `io.quarkus.mongodb.panache.PanacheMongoEntity` with `@MongoEntity(collection = "...")`.
- Times are stored as **minutes** (int); UI converts via `frontend/src/app/shared/util/time-format.util.ts` (`parseHhmm(str): number|null`, `formatMinutes(min): "HH:mm"`).
- Dates on `HourEntry.date` are `YYYY-MM-DD` strings. `Semester.start`/`end` are `Instant`.
- **A placed child** = a `Person` whose `familyId` matches the family AND which has a `semester_assignments` document `{ personId, semesterId, section: "group" }`. (Only children receive group placements.)
- Security: `SecurityFilter.isAllowed` short-circuits `true` for admins; anything not explicitly whitelisted is **admin-only** by default. Owner-or-admin edit rule on `PUT/DELETE /hour-entries/{id}` stays unchanged.
- Tests run in dev mode with OIDC disabled: the **single** persisted non-admin `Person` becomes the "current person"; a single `Person` whose `roles` reference a `field_instances` doc with `value = "ADMIN"` becomes the admin. Follow `HourEntryResourceTest` conventions.
- German UI copy. Existing per-semester admin screens use a `mat-select` semester dropdown (see `PlatzzuweisungComponent`).
- Never commit without the checkbox `Commit` step; use the exact commit messages given.

---

### Task 1: `RequiredHours` entity + `HoursBalanceService` pure calculation

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/RequiredHours.java`
- Create: `backend/src/main/java/at/kigruapp/service/HoursBalanceService.java`
- Test: `backend/src/test/java/at/kigruapp/service/HoursBalanceServiceTest.java`

**Interfaces:**
- Produces:
  - `RequiredHours` (PanacheMongoEntity): `public ObjectId semesterId; public int defaultMinutesPerMonth; public List<RequiredHours.Tier> tiers;` and nested `public static class Tier { public int fromChild; public int minutesPerMonth; }`; static `RequiredHours findBySemesterId(ObjectId semesterId)`.
  - `HoursBalanceService`:
    - `int rateForChild(RequiredHours cfg, int childOrdinal)` — minutes/month for the n-th child.
    - `int familyMonthlyMinutes(RequiredHours cfg, int childCount)` — Σ rateForChild(1..childCount).
    - `int monthsInSemester(Semester semester)` — distinct calendar months in `[start, end]` inclusive.
  - `cfg == null` is treated as `defaultMinutesPerMonth = 0`, no tiers.

- [ ] **Step 1: Write the failing test**

```java
package at.kigruapp.service;

import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HoursBalanceServiceTest {

    private final HoursBalanceService service = new HoursBalanceService();

    private RequiredHours cfg(int def, RequiredHours.Tier... tiers) {
        RequiredHours c = new RequiredHours();
        c.defaultMinutesPerMonth = def;
        c.tiers = new java.util.ArrayList<>(List.of(tiers));
        return c;
    }

    private RequiredHours.Tier tier(int fromChild, int minutes) {
        RequiredHours.Tier t = new RequiredHours.Tier();
        t.fromChild = fromChild;
        t.minutesPerMonth = minutes;
        return t;
    }

    @Test
    void noTiers_multipliesDefaultByChildCount() {
        RequiredHours c = cfg(480); // 8h
        assertEquals(0, service.familyMonthlyMinutes(c, 0));
        assertEquals(480, service.familyMonthlyMinutes(c, 1));
        assertEquals(960, service.familyMonthlyMinutes(c, 2));
        assertEquals(1440, service.familyMonthlyMinutes(c, 3));
    }

    @Test
    void singleTierFromSecondChild() {
        RequiredHours c = cfg(480, tier(2, 360)); // default 8h, ab 2. Kind 6h
        assertEquals(480, service.familyMonthlyMinutes(c, 1));
        assertEquals(840, service.familyMonthlyMinutes(c, 2));   // 480 + 360
        assertEquals(1200, service.familyMonthlyMinutes(c, 3));  // 480 + 360 + 360
    }

    @Test
    void nestedTiers_examplesFromSpec() {
        RequiredHours c = cfg(480, tier(2, 360), tier(3, 0)); // 8h, ab 2. = 6h, ab 3. = 0h
        assertEquals(480, service.familyMonthlyMinutes(c, 1));
        assertEquals(840, service.familyMonthlyMinutes(c, 2));  // 480 + 360
        assertEquals(840, service.familyMonthlyMinutes(c, 3));  // 480 + 360 + 0
        assertEquals(840, service.familyMonthlyMinutes(c, 4));  // 480 + 360 + 0 + 0
    }

    @Test
    void tierFromFirstChildOverridesDefault() {
        RequiredHours c = cfg(480, tier(1, 120)); // pure function supports fromChild=1
        assertEquals(120, service.familyMonthlyMinutes(c, 1));
        assertEquals(240, service.familyMonthlyMinutes(c, 2));
    }

    @Test
    void nullConfigMeansZero() {
        assertEquals(0, service.familyMonthlyMinutes(null, 3));
    }

    @Test
    void unsortedTiersHandled() {
        RequiredHours c = cfg(480, tier(3, 0), tier(2, 360)); // deliberately out of order
        assertEquals(840, service.familyMonthlyMinutes(c, 3)); // 480 + 360 + 0
    }

    @Test
    void monthsInSemester_spanIncludingYearBoundary() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        assertEquals(6, service.monthsInSemester(s)); // Sep, Oct, Nov, Dec, Jan, Feb
    }

    @Test
    void monthsInSemester_sameMonth() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2026-09-30T00:00:00Z");
        assertEquals(1, service.monthsInSemester(s));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=HoursBalanceServiceTest`
Expected: FAIL — `RequiredHours` / `HoursBalanceService` do not exist (compilation error).

- [ ] **Step 3: Write the entity**

Create `backend/src/main/java/at/kigruapp/entity/RequiredHours.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

@MongoEntity(collection = "requiredHours")
public class RequiredHours extends PanacheMongoEntity {
    public ObjectId semesterId;
    public int defaultMinutesPerMonth;
    public List<Tier> tiers = new ArrayList<>();

    public static class Tier {
        public int fromChild;
        public int minutesPerMonth;
    }

    public static RequiredHours findBySemesterId(ObjectId semesterId) {
        return find("semesterId", semesterId).firstResult();
    }
}
```

- [ ] **Step 4: Write the service**

Create `backend/src/main/java/at/kigruapp/service/HoursBalanceService.java`:

```java
package at.kigruapp.service;

import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.ZoneOffset;
import java.time.YearMonth;

@ApplicationScoped
public class HoursBalanceService {

    /** Minutes/month owed for the n-th child (1-based). Highest matching tier wins, else default. */
    public int rateForChild(RequiredHours cfg, int childOrdinal) {
        if (cfg == null) {
            return 0;
        }
        int rate = cfg.defaultMinutesPerMonth;
        if (cfg.tiers != null) {
            int bestFrom = 0;
            for (RequiredHours.Tier t : cfg.tiers) {
                if (t.fromChild <= childOrdinal && t.fromChild >= bestFrom) {
                    bestFrom = t.fromChild;
                    rate = t.minutesPerMonth;
                }
            }
        }
        return rate;
    }

    /** Σ rateForChild(1..childCount) — the family's per-month Soll. */
    public int familyMonthlyMinutes(RequiredHours cfg, int childCount) {
        int total = 0;
        for (int n = 1; n <= childCount; n++) {
            total += rateForChild(cfg, n);
        }
        return total;
    }

    /** Distinct calendar months touched by [start, end] inclusive. */
    public int monthsInSemester(Semester semester) {
        if (semester == null || semester.start == null || semester.end == null) {
            return 0;
        }
        YearMonth start = YearMonth.from(semester.start.atZone(ZoneOffset.UTC));
        YearMonth end = YearMonth.from(semester.end.atZone(ZoneOffset.UTC));
        if (end.isBefore(start)) {
            return 0;
        }
        int months = 0;
        YearMonth cur = start;
        while (!cur.isAfter(end)) {
            months++;
            cur = cur.plusMonths(1);
        }
        return months;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=HoursBalanceServiceTest`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/RequiredHours.java backend/src/main/java/at/kigruapp/service/HoursBalanceService.java backend/src/test/java/at/kigruapp/service/HoursBalanceServiceTest.java
git commit -m "feat: Zu leistende Stunden — RequiredHours-Entity und Soll-Berechnung"
```

---

### Task 2: `required-hours` config endpoint (GET/PUT + validation)

**Files:**
- Create: `backend/src/main/java/at/kigruapp/dto/RequiredHoursDto.java`
- Create: `backend/src/main/java/at/kigruapp/resource/RequiredHoursResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/RequiredHoursResourceTest.java`

**Interfaces:**
- Consumes: `RequiredHours` entity, `RequiredHours.Tier` (Task 1).
- Produces:
  - `RequiredHoursDto`: `public String semesterId; public int defaultMinutesPerMonth; public List<TierDto> tiers;` with nested `public static class TierDto { public int fromChild; public int minutesPerMonth; }`.
  - `GET /api/v1/required-hours?semesterId=` → `RequiredHoursDto` (empty-but-valid dto if none saved yet: `defaultMinutesPerMonth=0`, `tiers=[]`).
  - `PUT /api/v1/required-hours?semesterId=` — upsert; validates `defaultMinutesPerMonth > 0`, each `minutesPerMonth >= 0`, `fromChild` unique and strictly ascending and `>= 2`.

- [ ] **Step 1: Write the failing test**

```java
package at.kigruapp.resource;

import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import com.mongodb.client.MongoClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RequiredHoursResourceTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        RequiredHours.deleteAll();
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
    void getReturnsEmptyDtoWhenNoneSaved() {
        String semesterId = persistSemester();
        given().when().get("/api/v1/required-hours?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("defaultMinutesPerMonth", is(0))
            .body("tiers.size()", is(0));
    }

    @Test
    void putThenGetRoundTrips() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[{\"fromChild\":2,\"minutesPerMonth\":360},{\"fromChild\":3,\"minutesPerMonth\":0}]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("defaultMinutesPerMonth", is(480))
            .body("tiers.size()", is(2));

        given().when().get("/api/v1/required-hours?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("defaultMinutesPerMonth", is(480))
            .body("tiers[0].fromChild", is(2))
            .body("tiers[1].minutesPerMonth", is(0));
    }

    @Test
    void putUpsertsInsteadOfDuplicating() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(200);
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":600,\"tiers\":[]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(200);

        given().when().get("/api/v1/required-hours?semesterId=" + semesterId)
            .then().statusCode(200).body("defaultMinutesPerMonth", is(600));
    }

    @Test
    void putRejectsNonPositiveDefault() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":0,\"tiers\":[]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(400);
    }

    @Test
    void putRejectsNonAscendingTiers() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[{\"fromChild\":3,\"minutesPerMonth\":0},{\"fromChild\":2,\"minutesPerMonth\":360}]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(400);
    }

    @Test
    void putRejectsTierFromChildBelowTwo() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[{\"fromChild\":1,\"minutesPerMonth\":120}]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(400);
    }

    @Test
    void putRejectsNegativeTierMinutes() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[{\"fromChild\":2,\"minutesPerMonth\":-1}]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(400);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=RequiredHoursResourceTest`
Expected: FAIL — resource/dto do not exist.

- [ ] **Step 3: Write the DTO**

Create `backend/src/main/java/at/kigruapp/dto/RequiredHoursDto.java`:

```java
package at.kigruapp.dto;

import java.util.ArrayList;
import java.util.List;

public class RequiredHoursDto {
    public String semesterId;
    public int defaultMinutesPerMonth;
    public List<TierDto> tiers = new ArrayList<>();

    public static class TierDto {
        public int fromChild;
        public int minutesPerMonth;
    }
}
```

- [ ] **Step 4: Write the resource**

Create `backend/src/main/java/at/kigruapp/resource/RequiredHoursResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.dto.RequiredHoursDto;
import at.kigruapp.entity.RequiredHours;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

@Path("/api/v1/required-hours")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RequiredHoursResource {

    @GET
    public RequiredHoursDto get(@QueryParam("semesterId") String semesterIdParam) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        RequiredHours cfg = RequiredHours.findBySemesterId(semesterId);
        return toDto(semesterId, cfg);
    }

    @PUT
    public RequiredHoursDto put(@QueryParam("semesterId") String semesterIdParam, RequiredHoursDto in) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        validate(in);

        RequiredHours cfg = RequiredHours.findBySemesterId(semesterId);
        if (cfg == null) {
            cfg = new RequiredHours();
            cfg.semesterId = semesterId;
        }
        cfg.defaultMinutesPerMonth = in.defaultMinutesPerMonth;
        cfg.tiers = new ArrayList<>();
        for (RequiredHoursDto.TierDto t : in.tiers) {
            RequiredHours.Tier tier = new RequiredHours.Tier();
            tier.fromChild = t.fromChild;
            tier.minutesPerMonth = t.minutesPerMonth;
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

    private void validate(RequiredHoursDto in) {
        if (in == null || in.defaultMinutesPerMonth <= 0) {
            throw new BadRequestException("defaultMinutesPerMonth muss größer als 0 sein");
        }
        List<RequiredHoursDto.TierDto> tiers = in.tiers == null ? List.of() : in.tiers;
        int prevFrom = 1; // erster gültiger Tier-Wert ist 2 -> strikt größer als 1
        for (RequiredHoursDto.TierDto t : tiers) {
            if (t.fromChild < 2) {
                throw new BadRequestException("fromChild muss mindestens 2 sein");
            }
            if (t.fromChild <= prevFrom) {
                throw new BadRequestException("fromChild muss eindeutig und aufsteigend sein");
            }
            if (t.minutesPerMonth < 0) {
                throw new BadRequestException("minutesPerMonth darf nicht negativ sein");
            }
            prevFrom = t.fromChild;
        }
    }

    private RequiredHoursDto toDto(ObjectId semesterId, RequiredHours cfg) {
        RequiredHoursDto dto = new RequiredHoursDto();
        dto.semesterId = semesterId.toHexString();
        dto.tiers = new ArrayList<>();
        if (cfg != null) {
            dto.defaultMinutesPerMonth = cfg.defaultMinutesPerMonth;
            if (cfg.tiers != null) {
                for (RequiredHours.Tier t : cfg.tiers) {
                    RequiredHoursDto.TierDto td = new RequiredHoursDto.TierDto();
                    td.fromChild = t.fromChild;
                    td.minutesPerMonth = t.minutesPerMonth;
                    dto.tiers.add(td);
                }
            }
        }
        return dto;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=RequiredHoursResourceTest`
Expected: PASS (7 tests). (Endpoint is admin-only via default deny; no SecurityFilter change needed.)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/RequiredHoursDto.java backend/src/main/java/at/kigruapp/resource/RequiredHoursResource.java backend/src/test/java/at/kigruapp/resource/RequiredHoursResourceTest.java
git commit -m "feat: Zu leistende Stunden — Config-Endpoint mit Validierung"
```

---

### Task 3: Placed-children count + admin `family-summary` endpoint

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/HoursBalanceService.java`
- Create: `backend/src/main/java/at/kigruapp/dto/FamilyHoursSummaryDto.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/HourEntryResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/HourEntryFamilySummaryTest.java`

**Interfaces:**
- Consumes: `HoursBalanceService.familyMonthlyMinutes`, `.monthsInSemester` (Task 1); `HourEntryResource.toDto` (existing, package-visible); `PersonPropertyResolver.resolve(List<Person>)` (existing).
- Produces:
  - `HoursBalanceService.countPlacedChildren(ObjectId familyId, ObjectId semesterId): int` — distinct `personId`s in the family with a `{section:"group"}` assignment for the semester.
  - `FamilyHoursSummaryDto`: `public String familyId; public String familyName; public int childCount; public int familyMonthlyMinutes; public int monthsInSemester; public int sollMinutes; public int istMinutes; public List<HourSummaryDto> members;` (reuses existing `HourSummaryDto` per person).
  - `GET /api/v1/hour-entries/family-summary?semesterId=` → `List<FamilyHoursSummaryDto>` (admin-only). Includes a family iff `childCount > 0` OR `istMinutes > 0`.

- [ ] **Step 1: Write the failing test**

```java
package at.kigruapp.resource;

import at.kigruapp.entity.Family;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class HourEntryFamilySummaryTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        HourEntry.deleteAll();
        Person.deleteAll();
        Family.deleteAll();
        Semester.deleteAll();
        RequiredHours.deleteAll();
        FieldDefinition.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments").drop();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();
    }

    private String persistSemester() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z"); // 6 Monate
        s.createdAt = Instant.now();
        s.persist();
        return s.id.toHexString();
    }

    private void adminUser() {
        ObjectId adminInst = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
            .insertOne(new Document("_id", adminInst).append("value", "ADMIN"));
        Person admin = new Person();
        admin.roles = new ArrayList<>();
        admin.roles.add(new FieldRef(new ObjectId(), adminInst));
        admin.createdAt = Instant.now();
        admin.updatedAt = admin.createdAt;
        admin.persist();
    }

    private ObjectId persistFamily(String name) {
        Family f = new Family();
        f.name = name;
        f.createdAt = Instant.now();
        f.persist();
        return f.id;
    }

    private Person persistPerson(ObjectId familyId) {
        Person p = new Person();
        p.familyId = familyId;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p;
    }

    private void placeChild(ObjectId childPersonId, String semesterId) {
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments")
            .insertOne(new Document("_id", new ObjectId())
                .append("personId", childPersonId)
                .append("semesterId", new ObjectId(semesterId))
                .append("section", "group")
                .append("definitionId", new ObjectId())
                .append("fieldInstanceId", new ObjectId()));
    }

    private void persistConfig(String semesterId, int def, int tierFrom, int tierMin) {
        RequiredHours c = new RequiredHours();
        c.semesterId = new ObjectId(semesterId);
        c.defaultMinutesPerMonth = def;
        RequiredHours.Tier t = new RequiredHours.Tier();
        t.fromChild = tierFrom;
        t.minutesPerMonth = tierMin;
        c.tiers.add(t);
        c.persist();
    }

    private void persistEntry(ObjectId personId, String semesterId, String date, int minutes) {
        HourEntry e = new HourEntry();
        e.personId = personId;
        e.semesterId = new ObjectId(semesterId);
        e.roleLabel = "Kochen";
        e.date = date;
        e.minutes = minutes;
        e.comment = "";
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.persist();
    }

    @Test
    void familySummaryComputesSollAndIst() {
        adminUser();
        String semesterId = persistSemester();
        persistConfig(semesterId, 480, 2, 360); // default 8h, ab 2. Kind 6h

        ObjectId famId = persistFamily("Muster");
        Person parent = persistPerson(famId);
        Person child1 = persistPerson(famId);
        Person child2 = persistPerson(famId);
        placeChild(child1.id, semesterId);
        placeChild(child2.id, semesterId);

        persistEntry(parent.id, semesterId, "2026-10-01", 300); // Ist 300 min

        given().when().get("/api/v1/hour-entries/family-summary?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("find { it.familyId == '" + famId.toHexString() + "' }.childCount", is(2))
            .body("find { it.familyId == '" + famId.toHexString() + "' }.familyMonthlyMinutes", is(840)) // 480 + 360
            .body("find { it.familyId == '" + famId.toHexString() + "' }.monthsInSemester", is(6))
            .body("find { it.familyId == '" + famId.toHexString() + "' }.sollMinutes", is(5040)) // 840 * 6
            .body("find { it.familyId == '" + famId.toHexString() + "' }.istMinutes", is(300))
            .body("find { it.familyId == '" + famId.toHexString() + "' }.members.size()", is(1));
    }

    @Test
    void familyWithoutChildrenOrEntriesIsOmitted() {
        adminUser();
        String semesterId = persistSemester();
        persistConfig(semesterId, 480, 2, 360);
        ObjectId emptyFam = persistFamily("Leer");
        persistPerson(emptyFam); // parent only, no placement, no entries

        given().when().get("/api/v1/hour-entries/family-summary?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("find { it.familyId == '" + emptyFam.toHexString() + "' }", nullValue());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=HourEntryFamilySummaryTest`
Expected: FAIL — endpoint/dto/`countPlacedChildren` do not exist.

- [ ] **Step 3: Add `countPlacedChildren` to the service**

In `HoursBalanceService.java`, add the injected Mongo client and the method. Add these imports at the top:

```java
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
```

Add fields + method inside the class:

```java
    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    /** Distinct children (persons in the family) with a group placement in the semester. */
    public int countPlacedChildren(ObjectId familyId, ObjectId semesterId) {
        List<at.kigruapp.entity.Person> familyPersons =
                at.kigruapp.entity.Person.findByFamilyId(familyId);
        if (familyPersons.isEmpty()) {
            return 0;
        }
        Set<ObjectId> personIds = new HashSet<>();
        for (at.kigruapp.entity.Person p : familyPersons) {
            personIds.add(p.id);
        }
        Document filter = new Document("semesterId", semesterId)
                .append("section", "group")
                .append("personId", new Document("$in", new java.util.ArrayList<>(personIds)));
        Set<ObjectId> placed = new HashSet<>();
        for (Document d : mongoClient.getDatabase(databaseName)
                .getCollection("semester_assignments").find(filter)) {
            placed.add(d.getObjectId("personId"));
        }
        return placed.size();
    }
```

> Note: `new HoursBalanceService()` in `HoursBalanceServiceTest` still works — the pure methods don't touch `mongoClient`.

- [ ] **Step 4: Write the DTO**

Create `backend/src/main/java/at/kigruapp/dto/FamilyHoursSummaryDto.java`:

```java
package at.kigruapp.dto;

import java.util.ArrayList;
import java.util.List;

public class FamilyHoursSummaryDto {
    public String familyId;
    public String familyName;
    public int childCount;
    public int familyMonthlyMinutes;
    public int monthsInSemester;
    public int sollMinutes;
    public int istMinutes;
    public List<HourSummaryDto> members = new ArrayList<>();
}
```

- [ ] **Step 5: Add the endpoint to `HourEntryResource`**

Add imports near the top of `HourEntryResource.java`:

```java
import at.kigruapp.dto.FamilyHoursSummaryDto;
import at.kigruapp.entity.Family;
import at.kigruapp.entity.RequiredHours;
import at.kigruapp.service.HoursBalanceService;
```

Inject the service (next to the other `@Inject` fields):

```java
    @Inject
    HoursBalanceService hoursBalanceService;
```

Add the method (place after `summary()`):

```java
    @GET
    @Path("/family-summary")
    public List<FamilyHoursSummaryDto> familySummary(@QueryParam("semesterId") String semesterIdParam) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        Semester semester = Semester.findById(semesterId);
        int months = hoursBalanceService.monthsInSemester(semester);
        RequiredHours cfg = RequiredHours.findBySemesterId(semesterId);

        List<FamilyHoursSummaryDto> result = new ArrayList<>();
        for (Family family : Family.<Family>listAll()) {
            List<Person> members = Person.findByFamilyId(family.id);

            int childCount = hoursBalanceService.countPlacedChildren(family.id, semesterId);
            int familyMonthly = hoursBalanceService.familyMonthlyMinutes(cfg, childCount);
            int soll = familyMonthly * months;

            // Ist + Personen-Einträge über alle Familienmitglieder sammeln.
            Map<ObjectId, HourSummaryDto> byPerson = new LinkedHashMap<>();
            int ist = 0;
            for (Person member : members) {
                List<HourEntry> entries = HourEntry.<HourEntry>find(
                        "personId = ?1 and semesterId = ?2",
                        Sort.descending("date", "createdAt"), member.id, semesterId).list();
                if (entries.isEmpty()) continue;
                HourSummaryDto dto = new HourSummaryDto();
                dto.personId = member.id.toHexString();
                dto.name = "";
                dto.totalMinutes = 0;
                dto.entries = new ArrayList<>();
                for (HourEntry e : entries) {
                    dto.totalMinutes += e.minutes;
                    dto.entries.add(toDto(e));
                    ist += e.minutes;
                }
                byPerson.put(member.id, dto);
            }

            if (childCount == 0 && ist == 0) {
                continue; // Familie ohne Soll und ohne Ist ausblenden.
            }

            // Namen der beteiligten Mitglieder auflösen.
            List<Person> named = new ArrayList<>();
            for (ObjectId pid : byPerson.keySet()) {
                Person p = Person.findById(pid);
                if (p != null) named.add(p);
            }
            Map<ObjectId, Map<String, String>> props = personPropertyResolver.resolve(named);
            for (HourSummaryDto dto : byPerson.values()) {
                Map<String, String> pr = props.getOrDefault(new ObjectId(dto.personId), Map.of());
                String name = (pr.getOrDefault("firstName", "") + " " + pr.getOrDefault("lastName", "")).trim();
                dto.name = name.isEmpty() ? dto.personId : name;
            }

            FamilyHoursSummaryDto fam = new FamilyHoursSummaryDto();
            fam.familyId = family.id.toHexString();
            fam.familyName = family.name == null ? "" : family.name;
            fam.childCount = childCount;
            fam.familyMonthlyMinutes = familyMonthly;
            fam.monthsInSemester = months;
            fam.sollMinutes = soll;
            fam.istMinutes = ist;
            fam.members = new ArrayList<>(byPerson.values());
            result.add(fam);
        }
        return result;
    }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=HourEntryFamilySummaryTest,HoursBalanceServiceTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/HoursBalanceService.java backend/src/main/java/at/kigruapp/dto/FamilyHoursSummaryDto.java backend/src/main/java/at/kigruapp/resource/HourEntryResource.java backend/src/test/java/at/kigruapp/resource/HourEntryFamilySummaryTest.java
git commit -m "feat: Zu leistende Stunden — familien-gruppierte Admin-Übersicht (Soll/Ist)"
```

---

### Task 4: Family-scoped `/our` endpoint + SecurityFilter whitelist

**Files:**
- Create: `backend/src/main/java/at/kigruapp/dto/OurHoursDto.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/HourEntryResource.java`
- Modify: `backend/src/main/java/at/kigruapp/security/SecurityFilter.java:101` (add whitelist before the write-method rule)
- Test: `backend/src/test/java/at/kigruapp/resource/HourEntryOurTest.java`
- Test: `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java` (add one case)

**Interfaces:**
- Consumes: `HoursBalanceService` (Tasks 1/3), `CurrentUserService.getCurrentPerson()` (existing), `PersonPropertyResolver.resolve` (existing), `HourEntryResource.toDto` (existing).
- Produces:
  - `OurHoursDto`: `public String familyId; public int familyMonthlyMinutes; public int monthsInSemester; public int sollMinutes; public int istMinutes; public List<MonthRow> months; public List<Entry> entries;` with nested `public static class MonthRow { public String month; public int sollMinutes; public int istMinutes; }` and `public static class Entry { public String id; public String personId; public String personName; public String roleLabel; public String date; public int minutes; public String comment; }`.
  - `GET /api/v1/hour-entries/our?semesterId=` — scoped to the caller's `familyId`; whitelisted for authenticated parents.

- [ ] **Step 1: Write the failing test**

```java
package at.kigruapp.resource;

import at.kigruapp.entity.Family;
import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class HourEntryOurTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        HourEntry.deleteAll();
        Person.deleteAll();
        Family.deleteAll();
        Semester.deleteAll();
        RequiredHours.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments").drop();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();
    }

    private String persistSemester() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z"); // 6 Monate
        s.createdAt = Instant.now();
        s.persist();
        return s.id.toHexString();
    }

    private ObjectId persistFamily() {
        Family f = new Family();
        f.name = "Muster";
        f.createdAt = Instant.now();
        f.persist();
        return f.id;
    }

    private Person persistPerson(ObjectId familyId) {
        Person p = new Person();
        p.familyId = familyId;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p;
    }

    private void persistEntry(ObjectId personId, String semesterId, String date, int minutes) {
        HourEntry e = new HourEntry();
        e.personId = personId;
        e.semesterId = new ObjectId(semesterId);
        e.roleLabel = "Kochen";
        e.date = date;
        e.minutes = minutes;
        e.comment = "";
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.persist();
    }

    private void persistConfig(String semesterId, int def) {
        RequiredHours c = new RequiredHours();
        c.semesterId = new ObjectId(semesterId);
        c.defaultMinutesPerMonth = def;
        c.persist();
    }

    @Test
    void ourReturnsFamilyScopedEntriesAndMonthlyRows() {
        ObjectId famId = persistFamily();
        Person me = persistPerson(famId);     // single non-admin person -> current user
        Person partner = persistPerson(famId);
        String semesterId = persistSemester();
        persistConfig(semesterId, 480);        // 1 Monatssatz-Basis (kein Kind platziert -> familyMonthly 0)

        persistEntry(me.id, semesterId, "2026-10-05", 120);
        persistEntry(partner.id, semesterId, "2026-11-03", 90);

        given().when().get("/api/v1/hour-entries/our?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("familyId", is(famId.toHexString()))
            .body("monthsInSemester", is(6))
            .body("months.size()", is(6))                       // alle Semester-Monate
            .body("months.find { it.month == '2026-10' }.istMinutes", is(120))
            .body("months.find { it.month == '2026-11' }.istMinutes", is(90))
            .body("months.find { it.month == '2026-09' }.istMinutes", is(0))
            .body("istMinutes", is(210))
            .body("entries.size()", is(2));                     // beide Elternteile sichtbar
    }

    @Test
    void ourExcludesOtherFamilies() {
        ObjectId myFam = persistFamily();
        Person me = persistPerson(myFam);
        String semesterId = persistSemester();
        persistConfig(semesterId, 480);

        ObjectId otherFam = persistFamily();
        ObjectId stranger = new ObjectId();
        persistEntry(stranger, semesterId, "2026-10-05", 999); // gehört nicht zur Familie
        // Achtung: stranger ist keine Person in myFam -> darf nicht auftauchen.
        persistEntry(me.id, semesterId, "2026-10-06", 60);

        given().when().get("/api/v1/hour-entries/our?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("istMinutes", is(60))
            .body("entries.size()", is(1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=HourEntryOurTest`
Expected: FAIL — `/our` endpoint / `OurHoursDto` do not exist.

- [ ] **Step 3: Write the DTO**

Create `backend/src/main/java/at/kigruapp/dto/OurHoursDto.java`:

```java
package at.kigruapp.dto;

import java.util.ArrayList;
import java.util.List;

public class OurHoursDto {
    public String familyId;
    public int familyMonthlyMinutes;
    public int monthsInSemester;
    public int sollMinutes;
    public int istMinutes;
    public List<MonthRow> months = new ArrayList<>();
    public List<Entry> entries = new ArrayList<>();

    public static class MonthRow {
        public String month;        // "YYYY-MM"
        public int sollMinutes;
        public int istMinutes;
    }

    public static class Entry {
        public String id;
        public String personId;
        public String personName;
        public String roleLabel;
        public String date;
        public int minutes;
        public String comment;
    }
}
```

- [ ] **Step 4: Add the `/our` endpoint to `HourEntryResource`**

Add imports:

```java
import at.kigruapp.dto.OurHoursDto;
import java.time.ZoneOffset;
import java.time.YearMonth;
```

Add the method (after `familySummary()`):

```java
    @GET
    @Path("/our")
    public OurHoursDto our(@QueryParam("semesterId") String semesterIdParam) {
        Person me = requireCurrentPerson();
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        Semester semester = Semester.findById(semesterId);

        OurHoursDto dto = new OurHoursDto();
        dto.familyId = me.familyId == null ? null : me.familyId.toHexString();

        int months = hoursBalanceService.monthsInSemester(semester);
        RequiredHours cfg = RequiredHours.findBySemesterId(semesterId);

        List<Person> members = me.familyId == null
                ? List.of(me)
                : Person.findByFamilyId(me.familyId);

        int childCount = me.familyId == null ? 0
                : hoursBalanceService.countPlacedChildren(me.familyId, semesterId);
        int familyMonthly = hoursBalanceService.familyMonthlyMinutes(cfg, childCount);

        dto.familyMonthlyMinutes = familyMonthly;
        dto.monthsInSemester = months;
        dto.sollMinutes = familyMonthly * months;

        // Namen der Mitglieder auflösen.
        Map<ObjectId, Map<String, String>> props = personPropertyResolver.resolve(members);

        // Einträge aller Mitglieder sammeln + Ist je Monat.
        Map<String, Integer> istByMonth = new HashMap<>();
        int ist = 0;
        for (Person member : members) {
            Map<String, String> pr = props.getOrDefault(member.id, Map.of());
            String name = (pr.getOrDefault("firstName", "") + " " + pr.getOrDefault("lastName", "")).trim();
            String personName = name.isEmpty() ? member.id.toHexString() : name;
            List<HourEntry> entries = HourEntry.<HourEntry>find(
                    "personId = ?1 and semesterId = ?2",
                    Sort.descending("date", "createdAt"), member.id, semesterId).list();
            for (HourEntry e : entries) {
                OurHoursDto.Entry en = new OurHoursDto.Entry();
                en.id = e.id.toHexString();
                en.personId = member.id.toHexString();
                en.personName = personName;
                en.roleLabel = e.roleLabel;
                en.date = e.date;
                en.minutes = e.minutes;
                en.comment = e.comment;
                dto.entries.add(en);
                ist += e.minutes;
                String month = e.date == null || e.date.length() < 7 ? "" : e.date.substring(0, 7);
                istByMonth.merge(month, e.minutes, Integer::sum);
            }
        }
        dto.istMinutes = ist;

        // Alle Kalendermonate des Semesters als Zeile ausgeben.
        if (semester != null && semester.start != null && semester.end != null) {
            YearMonth cur = YearMonth.from(semester.start.atZone(ZoneOffset.UTC));
            YearMonth end = YearMonth.from(semester.end.atZone(ZoneOffset.UTC));
            while (!cur.isAfter(end)) {
                OurHoursDto.MonthRow row = new OurHoursDto.MonthRow();
                row.month = String.format("%04d-%02d", cur.getYear(), cur.getMonthValue());
                row.sollMinutes = familyMonthly;
                row.istMinutes = istByMonth.getOrDefault(row.month, 0);
                dto.months.add(row);
                cur = cur.plusMonths(1);
            }
        }
        return dto;
    }
```

- [ ] **Step 5: Whitelist `/our` in SecurityFilter**

In `SecurityFilter.isAllowed`, add this line immediately after the `role-options` whitelist (around line 97), before the `hour-entries/[^/]+` write rule:

```java
        if (path.equals("/api/v1/hour-entries/our") && "GET".equals(method)) return true;
```

- [ ] **Step 6: Add a SecurityFilter test case**

In `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java`, follow the existing test style in that file and add:

```java
    @Test
    void ourEndpointAllowedForNonAdminParent() {
        // Mirror the file's existing non-admin setup (persist a single non-admin Person,
        // ensure oidcEnabled). Then:
        given().when().get("/api/v1/hour-entries/our")
            .then().statusCode(not(403));
    }
```

> If `SecurityFilterTest` gates on an `oidcEnabled` test profile (see existing cases), attach the same profile/annotation this file already uses so the filter actually runs. Match the surrounding tests exactly.

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=HourEntryOurTest,HourEntryResourceTest,SecurityFilterTest`
Expected: PASS for `HourEntryOurTest` and `HourEntryResourceTest`; `SecurityFilterTest` passes for the new case (pre-existing baseline failures in that file, if any, are unrelated — note them but do not fix here).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/OurHoursDto.java backend/src/main/java/at/kigruapp/resource/HourEntryResource.java backend/src/main/java/at/kigruapp/security/SecurityFilter.java backend/src/test/java/at/kigruapp/resource/HourEntryOurTest.java backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java
git commit -m "feat: Zu leistende Stunden — familienweiter /our-Endpoint inkl. Whitelist"
```

---

### Task 5: Frontend models + services

**Files:**
- Create: `frontend/src/app/shared/models/required-hours.model.ts`
- Create: `frontend/src/app/shared/services/required-hours.service.ts`
- Modify: `frontend/src/app/shared/models/hour-entry.model.ts`
- Modify: `frontend/src/app/shared/services/hour-entry.service.ts`
- Test: `frontend/src/app/shared/services/required-hours.service.spec.ts`

**Interfaces:**
- Produces:
  - `RequiredHours` model: `{ semesterId: string; defaultMinutesPerMonth: number; tiers: RequiredHoursTier[] }`, `RequiredHoursTier = { fromChild: number; minutesPerMonth: number }`.
  - `RequiredHoursService.get(semesterId): Observable<RequiredHours>`, `.save(semesterId, dto): Observable<RequiredHours>`.
  - `hour-entry.model.ts` adds `FamilyHoursSummary`, `OurHours`, `OurHoursMonthRow`, `OurHoursEntry` types (fields mirror the backend DTOs in Tasks 3 & 4).
  - `HourEntryService.familySummary(semesterId): Observable<FamilyHoursSummary[]>`, `.our(semesterId): Observable<OurHours>`.

- [ ] **Step 1: Write the failing service test**

```typescript
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { RequiredHoursService } from './required-hours.service';

describe('RequiredHoursService', () => {
  let service: RequiredHoursService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule], providers: [RequiredHoursService] });
    service = TestBed.inject(RequiredHoursService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GETs config for a semester', () => {
    service.get('sem1').subscribe();
    const req = http.expectOne('/api/v1/required-hours?semesterId=sem1');
    expect(req.request.method).toBe('GET');
    req.flush({ semesterId: 'sem1', defaultMinutesPerMonth: 480, tiers: [] });
  });

  it('PUTs config for a semester', () => {
    service.save('sem1', { semesterId: 'sem1', defaultMinutesPerMonth: 480, tiers: [{ fromChild: 2, minutesPerMonth: 360 }] }).subscribe();
    const req = http.expectOne('/api/v1/required-hours?semesterId=sem1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.tiers[0].fromChild).toBe(2);
    req.flush({ semesterId: 'sem1', defaultMinutesPerMonth: 480, tiers: [{ fromChild: 2, minutesPerMonth: 360 }] });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/required-hours.service.spec.ts'`
Expected: FAIL — service does not exist.

- [ ] **Step 3: Write the model**

Create `frontend/src/app/shared/models/required-hours.model.ts`:

```typescript
export interface RequiredHoursTier {
  fromChild: number;
  minutesPerMonth: number;
}

export interface RequiredHours {
  semesterId: string;
  defaultMinutesPerMonth: number;
  tiers: RequiredHoursTier[];
}
```

- [ ] **Step 4: Write the service**

Create `frontend/src/app/shared/services/required-hours.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { RequiredHours } from '../models/required-hours.model';

@Injectable({ providedIn: 'root' })
export class RequiredHoursService {
  private readonly base = '/api/v1/required-hours';

  constructor(private http: HttpClient) {}

  get(semesterId: string): Observable<RequiredHours> {
    return this.http.get<RequiredHours>(`${this.base}?semesterId=${semesterId}`);
  }

  save(semesterId: string, dto: RequiredHours): Observable<RequiredHours> {
    return this.http.put<RequiredHours>(`${this.base}?semesterId=${semesterId}`, dto);
  }
}
```

- [ ] **Step 5: Extend hour-entry model + service**

Append to `frontend/src/app/shared/models/hour-entry.model.ts`:

```typescript
export interface FamilyHoursSummary {
  familyId: string;
  familyName: string;
  childCount: number;
  familyMonthlyMinutes: number;
  monthsInSemester: number;
  sollMinutes: number;
  istMinutes: number;
  members: HourSummary[];
}

export interface OurHoursMonthRow {
  month: string;        // "YYYY-MM"
  sollMinutes: number;
  istMinutes: number;
}

export interface OurHoursEntry {
  id: string;
  personId: string;
  personName: string;
  roleLabel: string;
  date: string;
  minutes: number;
  comment: string;
}

export interface OurHours {
  familyId: string | null;
  familyMonthlyMinutes: number;
  monthsInSemester: number;
  sollMinutes: number;
  istMinutes: number;
  months: OurHoursMonthRow[];
  entries: OurHoursEntry[];
}
```

In `frontend/src/app/shared/services/hour-entry.service.ts`, add the imports for `FamilyHoursSummary` and `OurHours` to the existing model import, and add these methods to the service class (mirror the existing `summary()` method's style/base URL):

```typescript
  familySummary(semesterId: string): Observable<FamilyHoursSummary[]> {
    return this.http.get<FamilyHoursSummary[]>(`${this.base}/family-summary?semesterId=${semesterId}`);
  }

  our(semesterId: string): Observable<OurHours> {
    return this.http.get<OurHours>(`${this.base}/our?semesterId=${semesterId}`);
  }
```

> If the existing service uses a field other than `this.base` for the hour-entries URL, reuse whatever it already uses — check the top of `hour-entry.service.ts` first.

- [ ] **Step 6: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/required-hours.service.spec.ts'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/shared/models/required-hours.model.ts frontend/src/app/shared/services/required-hours.service.ts frontend/src/app/shared/models/hour-entry.model.ts frontend/src/app/shared/services/hour-entry.service.ts frontend/src/app/shared/services/required-hours.service.spec.ts
git commit -m "feat: Zu leistende Stunden — Frontend-Models und Services"
```

---

### Task 6: "Zu leistende Stunden" config tab (Organisation settings)

**Files:**
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.html`
- Create: `frontend/src/app/settings/organisation/required-hours-preview.util.ts`
- Test: `frontend/src/app/settings/organisation/required-hours-preview.util.spec.ts`

**Interfaces:**
- Consumes: `RequiredHoursService` (Task 5), `RequiredHours`/`RequiredHoursTier` (Task 5), `SemesterService` (already injected in the component), `parseHhmm`/`formatMinutes` (time-format util).
- Produces: `familyMonthlyMinutes(cfg: { defaultMinutesPerMonth: number; tiers: RequiredHoursTier[] }, childCount: number): number` — the same math as the backend, for the live preview.

- [ ] **Step 1: Write the failing preview-util test**

```typescript
import { familyMonthlyMinutes } from './required-hours-preview.util';

describe('familyMonthlyMinutes', () => {
  it('multiplies default with no tiers', () => {
    const cfg = { defaultMinutesPerMonth: 480, tiers: [] };
    expect(familyMonthlyMinutes(cfg, 1)).toBe(480);
    expect(familyMonthlyMinutes(cfg, 2)).toBe(960);
  });

  it('applies nested tiers (8h / ab2=6h / ab3=0h)', () => {
    const cfg = { defaultMinutesPerMonth: 480, tiers: [{ fromChild: 2, minutesPerMonth: 360 }, { fromChild: 3, minutesPerMonth: 0 }] };
    expect(familyMonthlyMinutes(cfg, 1)).toBe(480);
    expect(familyMonthlyMinutes(cfg, 2)).toBe(840);
    expect(familyMonthlyMinutes(cfg, 3)).toBe(840);
    expect(familyMonthlyMinutes(cfg, 4)).toBe(840);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/required-hours-preview.util.spec.ts'`
Expected: FAIL — util does not exist.

- [ ] **Step 3: Write the preview util**

Create `frontend/src/app/settings/organisation/required-hours-preview.util.ts`:

```typescript
import { RequiredHoursTier } from '../../shared/models/required-hours.model';

function rateForChild(defaultMinutes: number, tiers: RequiredHoursTier[], n: number): number {
  let rate = defaultMinutes;
  let bestFrom = 0;
  for (const t of tiers) {
    if (t.fromChild <= n && t.fromChild >= bestFrom) {
      bestFrom = t.fromChild;
      rate = t.minutesPerMonth;
    }
  }
  return rate;
}

export function familyMonthlyMinutes(
  cfg: { defaultMinutesPerMonth: number; tiers: RequiredHoursTier[] },
  childCount: number,
): number {
  let total = 0;
  for (let n = 1; n <= childCount; n++) {
    total += rateForChild(cfg.defaultMinutesPerMonth, cfg.tiers, n);
  }
  return total;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/required-hours-preview.util.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Add the tab to the component (TypeScript)**

In `organisation.component.ts`, inject `RequiredHoursService` and import `MAT_DATE_LOCALE`/HH:mm helpers as needed. Add state + methods (place alongside the other tab logic):

```typescript
  // --- Zu leistende Stunden tab ---
  rhSemesters: Semester[] = [];
  rhSelectedSemesterId: string | null = null;
  rhDefaultHhmm = '';                                   // "HH:mm"
  rhTiers: { fromChild: number; hhmm: string }[] = [];
  rhPreview: { children: number; hhmm: string }[] = [];
  rhError: string | null = null;

  private loadRequiredHours(): void {
    if (!this.rhSelectedSemesterId) return;
    this.requiredHoursService.get(this.rhSelectedSemesterId).subscribe((cfg) => {
      this.rhDefaultHhmm = cfg.defaultMinutesPerMonth ? formatMinutes(cfg.defaultMinutesPerMonth) : '';
      this.rhTiers = (cfg.tiers ?? []).map((t) => ({ fromChild: t.fromChild, hhmm: formatMinutes(t.minutesPerMonth) }));
      this.recomputeRhPreview();
    });
  }

  onRhSemesterChange(semesterId: string): void {
    this.rhSelectedSemesterId = semesterId;
    this.loadRequiredHours();
  }

  addRhTier(): void {
    const nextFrom = this.rhTiers.length === 0 ? 2 : Math.max(...this.rhTiers.map((t) => t.fromChild)) + 1;
    this.rhTiers.push({ fromChild: nextFrom, hhmm: '' });
    this.recomputeRhPreview();
  }

  removeRhTier(index: number): void {
    this.rhTiers.splice(index, 1);
    this.recomputeRhPreview();
  }

  recomputeRhPreview(): void {
    const def = parseHhmm(this.rhDefaultHhmm) ?? 0;
    const tiers = this.rhTiers
      .map((t) => ({ fromChild: t.fromChild, minutesPerMonth: parseHhmm(t.hhmm) ?? 0 }))
      .sort((a, b) => a.fromChild - b.fromChild);
    this.rhPreview = [1, 2, 3, 4].map((n) => ({
      children: n,
      hhmm: formatMinutes(familyMonthlyMinutes({ defaultMinutesPerMonth: def, tiers }, n)),
    }));
  }

  saveRequiredHours(): void {
    this.rhError = null;
    const def = parseHhmm(this.rhDefaultHhmm);
    if (def === null || def <= 0) { this.rhError = 'Default-Stunden ungültig'; return; }
    const tiers = this.rhTiers.map((t) => ({ fromChild: t.fromChild, minutesPerMonth: parseHhmm(t.hhmm) ?? -1 }));
    const froms = tiers.map((t) => t.fromChild);
    const ascendingUnique = froms.every((f, i) => f >= 2 && (i === 0 || f > froms[i - 1]));
    if (!ascendingUnique || tiers.some((t) => t.minutesPerMonth < 0)) {
      this.rhError = 'Staffeln müssen ab dem 2. Kind, eindeutig, aufsteigend und gültig sein';
      return;
    }
    if (!this.rhSelectedSemesterId) return;
    this.requiredHoursService.save(this.rhSelectedSemesterId, {
      semesterId: this.rhSelectedSemesterId,
      defaultMinutesPerMonth: def,
      tiers,
    }).subscribe({
      next: () => { this.rhError = null; },
      error: () => { this.rhError = 'Speichern fehlgeschlagen'; },
    });
  }
```

Add the required imports at the top of the file:

```typescript
import { RequiredHoursService } from '../../shared/services/required-hours.service';
import { parseHhmm, formatMinutes } from '../../shared/util/time-format.util';
import { familyMonthlyMinutes } from './required-hours-preview.util';
```

Inject `private requiredHoursService: RequiredHoursService` in the constructor, and in `ngOnInit` load semesters into `rhSemesters` (reuse the existing `SemesterService.getAll()` call — assign to `rhSemesters`, set `rhSelectedSemesterId` to the first, then `loadRequiredHours()`).

- [ ] **Step 6: Add the tab markup (HTML)**

In `organisation.component.html`, add a new `<mat-tab label="Zu leistende Stunden">` after the existing tabs:

```html
<mat-tab label="Zu leistende Stunden">
  <div class="tab-body">
    <mat-form-field appearance="outline">
      <mat-label>Semester</mat-label>
      <mat-select [value]="rhSelectedSemesterId" (selectionChange)="onRhSemesterChange($event.value)">
        @for (s of rhSemesters; track s.id) {
          <mat-option [value]="s.id">{{ s.start | date:'yyyy' }}/{{ s.end | date:'yyyy' }}</mat-option>
        }
      </mat-select>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Stunden pro Monat pro Kind (HH:mm)</mat-label>
      <input matInput [(ngModel)]="rhDefaultHhmm" (ngModelChange)="recomputeRhPreview()" placeholder="08:00">
    </mat-form-field>

    <h4>Staffelung (Rabatte)</h4>
    @for (tier of rhTiers; track $index; let i = $index) {
      <div class="tier-row">
        <span>Ab dem</span>
        <mat-form-field appearance="outline" class="narrow">
          <input matInput type="number" min="2" [(ngModel)]="tier.fromChild" (ngModelChange)="recomputeRhPreview()">
        </mat-form-field>
        <span>. Kind:</span>
        <mat-form-field appearance="outline" class="narrow">
          <input matInput [(ngModel)]="tier.hhmm" (ngModelChange)="recomputeRhPreview()" placeholder="06:00">
        </mat-form-field>
        <span>pro Monat</span>
        <button mat-icon-button color="warn" (click)="removeRhTier(i)"><mat-icon>delete</mat-icon></button>
      </div>
    }
    <button mat-stroked-button (click)="addRhTier()"><mat-icon>add</mat-icon> Staffel hinzufügen</button>

    <h4>Vorschau</h4>
    <table class="preview">
      <tr><th>Kinder</th><th>Stunden/Monat</th></tr>
      @for (row of rhPreview; track row.children) {
        <tr><td>{{ row.children }}</td><td>{{ row.hhmm }}</td></tr>
      }
    </table>

    @if (rhError) { <p class="error">{{ rhError }}</p> }
    <button mat-raised-button color="primary" (click)="saveRequiredHours()">Speichern</button>
  </div>
</mat-tab>
```

Ensure `FormsModule` is in the component's `imports` array (for `[(ngModel)]`); add it if missing.

- [ ] **Step 7: Verify build + tests**

Run: `cd frontend && npx ng test --watch=false --include='**/required-hours-preview.util.spec.ts' && npx ng build`
Expected: tests PASS, build succeeds.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/settings/organisation/ frontend/src/app/settings/organisation/required-hours-preview.util.ts frontend/src/app/settings/organisation/required-hours-preview.util.spec.ts
git commit -m "feat: Zu leistende Stunden — Config-Tab in Organisation mit Live-Vorschau"
```

---

### Task 7: Family-grouped admin overview (Stundenübersicht rework)

**Files:**
- Modify: `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.ts`
- Modify: `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.html`
- Modify: `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.spec.ts`

**Interfaces:**
- Consumes: `HourEntryService.familySummary(semesterId)` (Task 5), `FamilyHoursSummary`/`HourSummary` (Task 5), `formatMinutes` (util).
- Produces: family-grouped view; each family row is expandable to the existing per-person entry list (edit/delete preserved).

- [ ] **Step 1: Write the failing component test**

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { StundenuebersichtComponent } from './stundenuebersicht.component';
import { HourEntryService } from '../../shared/services/hour-entry.service';
import { SemesterService } from '../../shared/services/semester.service';
import { NotificationService } from '../../shared/services/notification.service';

describe('StundenuebersichtComponent (family grouping)', () => {
  let fixture: ComponentFixture<StundenuebersichtComponent>;
  let component: StundenuebersichtComponent;

  const hourService = {
    familySummary: jasmine.createSpy('familySummary').and.returnValue(of([
      { familyId: 'f1', familyName: 'Muster', childCount: 2, familyMonthlyMinutes: 840, monthsInSemester: 6, sollMinutes: 5040, istMinutes: 300, members: [] },
    ])),
    update: jasmine.createSpy('update'),
    delete: jasmine.createSpy('delete'),
  };
  const semesterService = { getAll: () => of([{ id: 's1', start: '2026-09-01', end: '2027-02-28' }]) };
  const notify = { error: () => {}, success: () => {}, extractError: (e: any) => String(e) };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StundenuebersichtComponent],
      providers: [
        { provide: HourEntryService, useValue: hourService },
        { provide: SemesterService, useValue: semesterService },
        { provide: NotificationService, useValue: notify },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(StundenuebersichtComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads family summaries and marks negative balance', () => {
    expect(hourService.familySummary).toHaveBeenCalledWith('s1');
    expect(component.families.length).toBe(1);
    expect(component.isNegative(component.families[0])).toBeTrue(); // 300 < 5040
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/stundenuebersicht.component.spec.ts'`
Expected: FAIL — `families` / `isNegative` / `familySummary` wiring absent.

- [ ] **Step 3: Rework the component TypeScript**

Replace the summary-loading logic. Key changes to `stundenuebersicht.component.ts`:
- Replace `summaries: HourSummary[]` with `families: FamilyHoursSummary[] = []`.
- Import `FamilyHoursSummary` from the hour-entry model.
- In the semester-load subscribe, call `this.loadFamilies()` instead of `loadSummary()`.

```typescript
  families: FamilyHoursSummary[] = [];
  expandedFamilyId: string | null = null;

  loadFamilies(): void {
    if (!this.selectedSemesterId) { this.families = []; return; }
    this.hourService.familySummary(this.selectedSemesterId).subscribe({
      next: (f) => (this.families = f),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  toggleFamily(familyId: string): void {
    this.expandedFamilyId = this.expandedFamilyId === familyId ? null : familyId;
  }

  isNegative(f: FamilyHoursSummary): boolean {
    return f.istMinutes < f.sollMinutes;
  }

  balanceTooltip(f: FamilyHoursSummary): string {
    return `${f.childCount} Kinder · ${formatMinutes(f.familyMonthlyMinutes)}/Monat × ${f.monthsInSemester} Monate ` +
      `= ${formatMinutes(f.sollMinutes)} Soll; Ist ${formatMinutes(f.istMinutes)}`;
  }
```

Keep the existing `startEdit`/`saveEdit`/`delete`/`editForm` logic, but after a successful `saveEdit`/`delete` call `this.loadFamilies()` instead of `loadSummary()`. Keep `onSemesterChange()` calling `loadFamilies()`.

- [ ] **Step 4: Rework the component HTML**

Replace the per-person list with a per-family list. Each family row shows name, Ist, Soll, Differenz (colored), a tooltip on the number, and an expander that renders the existing per-person entries (reuse the current entry-row markup, iterating `family.members` then `member.entries`):

```html
<mat-form-field appearance="outline">
  <mat-label>Semester</mat-label>
  <mat-select [value]="selectedSemesterId" (selectionChange)="selectedSemesterId = $event.value; onSemesterChange()">
    @for (s of semesters; track s.id) {
      <mat-option [value]="s.id">{{ s.start | date:'yyyy' }}/{{ s.end | date:'yyyy' }}</mat-option>
    }
  </mat-select>
</mat-form-field>

@for (fam of families; track fam.familyId) {
  <div class="family-row">
    <div class="family-head" (click)="toggleFamily(fam.familyId)">
      <span class="fam-name">{{ fam.familyName }}</span>
      <span class="fam-balance" [class.negative]="isNegative(fam)" [class.positive]="!isNegative(fam)"
            [title]="balanceTooltip(fam)">
        {{ formatMinutes(fam.istMinutes) }} / {{ formatMinutes(fam.sollMinutes) }}
      </span>
    </div>
    @if (expandedFamilyId === fam.familyId) {
      @for (member of fam.members; track member.personId) {
        <div class="member-block">
          <h4>{{ member.name }} — {{ formatMinutes(member.totalMinutes) }}</h4>
          @for (entry of member.entries; track entry.id) {
            <!-- Reuse the existing entry-row markup (edit/save/delete) here, bound to `entry`. -->
          }
        </div>
      }
    }
  </div>
}
```

> Port the existing entry-row markup (the edit form + save/delete buttons that currently live in this template) into the innermost `@for` so admin editing is preserved. Colors: `.positive { color: green }`, `.negative { color: red }` in the component styles.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/stundenuebersicht.component.spec.ts'`
Expected: PASS.

- [ ] **Step 6: Verify build**

Run: `cd frontend && npx ng build`
Expected: build succeeds.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/administration/stundenuebersicht/
git commit -m "feat: Zu leistende Stunden — Admin-Übersicht familien-gruppiert mit Soll/Ist"
```

---

### Task 8: "Meine Stunden" → "Unsere Stunden" (family parent view)

**Files:**
- Modify: `frontend/src/app/stunden/stunden.component.ts`
- Modify: `frontend/src/app/stunden/stunden.component.html`
- Modify: `frontend/src/app/stunden/stunden.component.spec.ts`
- Modify: `frontend/src/app/app.component.html:11-14` (nav label)

**Interfaces:**
- Consumes: `HourEntryService.our(semesterId)` + existing `create`/`update`/`delete`/`roleOptions`/`listMine` (the form still creates the caller's own entries), `OurHours`/`OurHoursEntry`/`OurHoursMonthRow` (Task 5), `CurrentUserService` (to know which entries are the caller's own → editable), `formatMinutes`.
- Produces: family-wide view grouped by month with Soll/Ist header per month; own entries editable, partner entries read-only.

- [ ] **Step 1: Write the failing component test**

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { StundenComponent } from './stunden.component';
import { HourEntryService } from '../shared/services/hour-entry.service';
import { NotificationService } from '../shared/services/notification.service';

describe('StundenComponent (Unsere Stunden)', () => {
  let fixture: ComponentFixture<StundenComponent>;
  let component: StundenComponent;

  const hourService = {
    our: jasmine.createSpy('our').and.returnValue(of({
      familyId: 'f1', familyMonthlyMinutes: 480, monthsInSemester: 6, sollMinutes: 2880, istMinutes: 120,
      months: [
        { month: '2026-09', sollMinutes: 480, istMinutes: 0 },
        { month: '2026-10', sollMinutes: 480, istMinutes: 120 },
      ],
      entries: [{ id: 'e1', personId: 'p1', personName: 'Anna', roleLabel: 'Kochen', date: '2026-10-05', minutes: 120, comment: '' }],
    })),
    roleOptions: () => of([]),
    listMine: () => of([]),
  };
  const notify = { error: () => {}, success: () => {}, extractError: (e: any) => String(e) };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StundenComponent],
      providers: [
        { provide: HourEntryService, useValue: hourService },
        { provide: NotificationService, useValue: notify },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(StundenComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads family hours and groups entries by month', () => {
    expect(hourService.our).toHaveBeenCalled();
    expect(component.our?.months.length).toBe(2);
    expect(component.entriesForMonth('2026-10').length).toBe(1);
    expect(component.entriesForMonth('2026-09').length).toBe(0);
  });

  it('marks a month negative when ist < soll', () => {
    expect(component.monthIsNegative(component.our!.months[0])).toBeTrue(); // 0 < 480
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/stunden.component.spec.ts'`
Expected: FAIL — `our` / `entriesForMonth` / `monthIsNegative` absent.

- [ ] **Step 3: Extend the component TypeScript**

Add to `stunden.component.ts` (keep the existing create/edit form flow for the caller's own entries):

```typescript
  our: OurHours | null = null;

  loadOur(): void {
    this.hourService.our('').subscribe({    // '' -> Backend nimmt jüngstes Semester
      next: (o) => (this.our = o),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  entriesForMonth(month: string): OurHoursEntry[] {
    return (this.our?.entries ?? []).filter((e) => (e.date ?? '').startsWith(month));
  }

  monthIsNegative(row: OurHoursMonthRow): boolean {
    return row.istMinutes < row.sollMinutes;
  }

  isOwn(entry: OurHoursEntry): boolean {
    return this.currentUser.person?.id === entry.personId;
  }
```

- Import `OurHours`, `OurHoursEntry`, `OurHoursMonthRow` from the hour-entry model, and `CurrentUserService`.
- Inject `public currentUser: CurrentUserService` in the constructor.
- Call `this.loadOur()` in `ngOnInit` (in addition to the existing `load()`/`roleOptions()` calls), and after a successful `save()`/`delete()` also call `this.loadOur()` so totals refresh.

> Check `CurrentUserService` for the exact "current person id" accessor (e.g. `person?.id`); use whatever that service already exposes. If it exposes only an id, compare against `entry.personId` directly.

- [ ] **Step 4: Update the HTML**

Add a family header + per-month grouping above/around the existing editor. Keep the existing create/edit form. Per month, show Soll/Ist and the month's entries; show edit/delete only when `isOwn(entry)`:

```html
<h2>Unsere Stunden</h2>

@if (our) {
  <div class="family-total" [class.negative]="our.istMinutes < our.sollMinutes" [class.positive]="our.istMinutes >= our.sollMinutes">
    Familie gesamt: {{ formatMinutes(our.istMinutes) }} / {{ formatMinutes(our.sollMinutes) }} (Semester)
  </div>

  @for (row of our.months; track row.month) {
    <div class="month-block">
      <div class="month-head" [class.negative]="monthIsNegative(row)" [class.positive]="!monthIsNegative(row)">
        <span>{{ row.month }}</span>
        <span>{{ formatMinutes(row.istMinutes) }} / {{ formatMinutes(row.sollMinutes) }}</span>
      </div>
      @for (entry of entriesForMonth(row.month); track entry.id) {
        <div class="entry-row">
          <span>{{ formatIsoDateDe(entry.date) }} · {{ entry.roleLabel }} · {{ formatMinutes(entry.minutes) }} · {{ entry.personName }}</span>
          @if (isOwn(entry)) {
            <!-- Reuse existing edit/delete controls here, bound to the matching own HourEntry. -->
          }
        </div>
      }
    </div>
  }
}

<!-- existing create/edit form markup stays below -->
```

Add `.positive { color: green } .negative { color: red }` to styles. Keep `formatIsoDateDe`/`formatMinutes` references (already imported in this component).

> Editing still goes through the existing `HourEntry`-based form (own entries only). The `our.entries` list is the display source; `isOwn` gates the controls. Wire the edit button to the existing `selectForEdit` by matching `entry.id`.

- [ ] **Step 5: Rename the nav label**

In `frontend/src/app/app.component.html`, change the "Meine Stunden" link text:

```html
      <a mat-list-item routerLink="/stunden" routerLinkActive="active">
        <mat-icon matListItemIcon>schedule</mat-icon>
        <span matListItemTitle>Unsere Stunden</span>
      </a>
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/stunden.component.spec.ts'`
Expected: PASS.

- [ ] **Step 7: Verify full frontend build + suite**

Run: `cd frontend && npx ng build && npx ng test --watch=false`
Expected: build succeeds; suite green except the known pre-existing `AppComponent` baseline failure (unrelated).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/stunden/ frontend/src/app/app.component.html
git commit -m "feat: Zu leistende Stunden — Unsere Stunden (familienweit, Monatsgruppierung)"
```

---

## Final verification

- [ ] Backend full suite: `cd backend && ./mvnw test` — new tests green; note (don't fix) the documented baseline failures (13 backend, per project memory).
- [ ] Frontend full suite + build: `cd frontend && npx ng test --watch=false && npx ng build`.
- [ ] Manual smoke: as admin, open Organisation → "Zu leistende Stunden", set default 08:00, add "ab 2. Kind 06:00", check preview shows 1→08:00, 2→14:00; save. Open Stundenübersicht, confirm families show Ist/Soll with color + tooltip. As a parent, open "Unsere Stunden", confirm all 6 months render with Soll/Ist and both parents' entries appear.
```
