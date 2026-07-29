# Kosten-Rabatt & Aliquot Refinement — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Rework the just-built Kosten-Rabatt/Aliquot feature per user feedback: split aliquot into independent Stunden/Kosten modes, make discount eligibility per-semester, move the Geschwisterrabatt UI into Kosten-pro-Semester, auto-copy config to new semesters, shorten previews, allow 0-tiers, and add aliquot tooltips.

**Architecture:** Backend config entities gain fields (`AliquotConfig.stundenMode/kostenMode`, `KostenDiscount.eligibleDefinitionIds`) and `KostenDefinition.siblingDiscount` is removed; `SemesterResource.create` copies the previous semester's config + KostenValue amounts. Frontend relocates the discount + Kosten-aliquot config from Organisation to the Kosten-pro-Semester component and fixes preview/validation/tooltips.

**Tech Stack:** Quarkus + MongoDB Panache (Java 17), RESTEasy Reactive, JUnit 5 + RestAssured; Angular 17 standalone, Angular Material (incl. MatTooltip), Karma/Jasmine.

## Global Constraints

- Base branch/workspace: continue on `feature/zu-leistende-stunden` (both prior features + this live here; nothing merged). Autonomous per-task commits authorized.
- Backend tests run via PowerShell (git-bash `mvnw` is broken): `powershell -NoProfile -Command "cd D:\GIT\kigruapp\backend; $env:MAVEN_HOME='D:\Tools\apache-maven-3.9.15'; & 'D:\Tools\apache-maven-3.9.15\bin\mvn.cmd' test '-Dtest=<Class>'"`. MongoDB runs in Docker container `kigru-mongo-test` on localhost:27017.
- Frontend tests: from `frontend/`, `npm test -- --watch=false --browsers=ChromeHeadless` (Chrome installed). One pre-existing `AppComponent` baseline failure is expected.
- Admin-only endpoints — no SecurityFilter whitelist changes.
- German user-facing copy.
- Aliquot enum values exactly `NONE`/`WHOLE_MONTH`/`PER_DAY`; order exactly `MOST_EXPENSIVE_FIRST`/`LEAST_EXPENSIVE_FIRST`.
- Tooltip text (both aliquot info-icons), verbatim: `Aliquotierung: Bei unterjährigem Ein- oder Austritt eines Kindes werden die zu leistenden Stunden bzw. Kosten anteilig zu den Tagen berechnet, an denen das Kind im jeweiligen Monat einen Platz hat. 'Ganze Monate' = angefangener Monat zählt voll; 'Taggenau' = taggenaue Anteilsberechnung; 'Keine' = keine Anteilsberechnung.`
- Commit trailer (every commit body ends with):
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_017WSzJmqykhsf3GCR8CuP7x`

**Cross-task interfaces:**
- `AliquotConfig { ObjectId semesterId; String stundenMode="NONE"; String kostenMode="NONE"; }` (Task 1).
- `AliquotConfigDto { String semesterId; String stundenMode; String kostenMode; }` (Task 1).
- `KostenDiscount` adds `List<ObjectId> eligibleDefinitionIds` (Task 2); `KostenDiscountDto` adds `List<String> eligibleDefinitionIds` (Task 2).
- `BilanzCalculationService.eligible(discountCfg, def)` → `applyToAll || eligibleDefinitionIds.contains(def.id)` (Task 3).
- Kosten uses `AliquotMode.fromString(cfg.kostenMode)`; Stunden uses `stundenMode` (Task 3/4).

---

## Task 1: AliquotConfig — split into stundenMode + kostenMode

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/AliquotConfig.java`
- Modify: `backend/src/main/java/at/kigruapp/dto/AliquotConfigDto.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/AliquotConfigResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/AliquotConfigResourceTest.java`

**Interfaces:**
- Produces: entity/DTO with `stundenMode` + `kostenMode` (both default `"NONE"`); `GET/PUT /api/v1/aliquot-config?semesterId=` round-trips both; PUT validates each mode ∈ {NONE,WHOLE_MONTH,PER_DAY}.

- [ ] **Step 1: Rewrite the resource test**

Replace mode assertions with two-field ones:

```java
    @Test
    void getDefaultsToNoneNone() {
        String id = persistSemester();
        given().when().get("/api/v1/aliquot-config?semesterId=" + id)
            .then().statusCode(200)
            .body("stundenMode", is("NONE"))
            .body("kostenMode", is("NONE"));
    }

    @Test
    void putThenGetRoundTripsBothModes() {
        String id = persistSemester();
        given().contentType(ContentType.JSON).body("{\"stundenMode\":\"PER_DAY\",\"kostenMode\":\"WHOLE_MONTH\"}")
            .when().put("/api/v1/aliquot-config?semesterId=" + id)
            .then().statusCode(200).body("stundenMode", is("PER_DAY")).body("kostenMode", is("WHOLE_MONTH"));
        given().when().get("/api/v1/aliquot-config?semesterId=" + id)
            .then().statusCode(200).body("stundenMode", is("PER_DAY")).body("kostenMode", is("WHOLE_MONTH"));
    }

    @Test
    void putRejectsUnknownMode() {
        String id = persistSemester();
        given().contentType(ContentType.JSON).body("{\"stundenMode\":\"DAILY\",\"kostenMode\":\"NONE\"}")
            .when().put("/api/v1/aliquot-config?semesterId=" + id).then().statusCode(400);
    }
```
Keep the missing-semesterId 400 test.

- [ ] **Step 2: Run test — expect FAIL** (old fields).

Run: `... '-Dtest=AliquotConfigResourceTest'` → FAIL/compile error.

- [ ] **Step 3: Entity**

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

@MongoEntity(collection = "aliquot_configs")
public class AliquotConfig extends PanacheMongoEntity {
    public ObjectId semesterId;
    public String stundenMode = "NONE";
    public String kostenMode = "NONE";

    public static AliquotConfig findBySemesterId(ObjectId semesterId) {
        return find("semesterId", semesterId).firstResult();
    }
}
```

- [ ] **Step 4: DTO**

```java
package at.kigruapp.dto;

public class AliquotConfigDto {
    public String semesterId;
    public String stundenMode;
    public String kostenMode;
}
```

- [ ] **Step 5: Resource** — validate both, upsert both.

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
        return toDto(semesterId, AliquotConfig.findBySemesterId(semesterId));
    }

    @PUT
    public AliquotConfigDto put(@QueryParam("semesterId") String semesterIdParam, AliquotConfigDto in) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        if (in == null || !ALLOWED.contains(in.stundenMode) || !ALLOWED.contains(in.kostenMode)) {
            throw new BadRequestException("stundenMode/kostenMode müssen NONE, WHOLE_MONTH oder PER_DAY sein");
        }
        AliquotConfig cfg = AliquotConfig.findBySemesterId(semesterId);
        if (cfg == null) {
            cfg = new AliquotConfig();
            cfg.semesterId = semesterId;
        }
        cfg.stundenMode = in.stundenMode;
        cfg.kostenMode = in.kostenMode;
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
        dto.stundenMode = cfg != null ? cfg.stundenMode : "NONE";
        dto.kostenMode = cfg != null ? cfg.kostenMode : "NONE";
        return dto;
    }
}
```

- [ ] **Step 6: Run test — expect PASS.**
- [ ] **Step 7: Commit** `feat: split AliquotConfig into stundenMode + kostenMode`.

---

## Task 2: KostenDiscount — per-semester eligibleDefinitionIds

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/KostenDiscount.java`
- Modify: `backend/src/main/java/at/kigruapp/dto/KostenDiscountDto.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/KostenDiscountResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/KostenDiscountResourceTest.java`

**Interfaces:**
- Produces: `KostenDiscount.eligibleDefinitionIds : List<ObjectId>`; DTO `eligibleDefinitionIds : List<String>`; round-trips through GET/PUT; percent 0 accepted.

- [ ] **Step 1: Extend the test**

Add to the round-trip PUT body `"eligibleDefinitionIds":["<24hex>","<24hex>"]` and assert `body("eligibleDefinitionIds.size()", is(2))` on GET. Add a test `putAcceptsZeroPercentTier` (tier `{fromChild:2,percent:0}` → 200). Keep existing validation tests.

- [ ] **Step 2: Run test — expect FAIL.**

- [ ] **Step 3: Entity** — add field + list of ObjectId:

```java
    public java.util.List<org.bson.types.ObjectId> eligibleDefinitionIds = new java.util.ArrayList<>();
```
(Place beside `tiers`; keep existing fields/`Tier`/`findBySemesterId`.)

- [ ] **Step 4: DTO** — add:

```java
    public java.util.List<String> eligibleDefinitionIds = new java.util.ArrayList<>();
```

- [ ] **Step 5: Resource** — map the list both ways in `put`/`toDto`; validate each id via `ObjectId.isValid` (400 on invalid). In `put`:

```java
        cfg.eligibleDefinitionIds = new java.util.ArrayList<>();
        if (in.eligibleDefinitionIds != null) {
            for (String id : in.eligibleDefinitionIds) {
                if (!org.bson.types.ObjectId.isValid(id)) {
                    throw new BadRequestException("ungültige definitionId: " + id);
                }
                cfg.eligibleDefinitionIds.add(new org.bson.types.ObjectId(id));
            }
        }
```
In `toDto`:
```java
        dto.eligibleDefinitionIds = new java.util.ArrayList<>();
        if (cfg != null && cfg.eligibleDefinitionIds != null) {
            for (org.bson.types.ObjectId id : cfg.eligibleDefinitionIds) dto.eligibleDefinitionIds.add(id.toHexString());
        }
```
(Percent validation already allows 0..100 — no change; keep fromChild ascending/≥2 checks.)

- [ ] **Step 6: Run test — expect PASS.**
- [ ] **Step 7: Commit** `feat: KostenDiscount eligibleDefinitionIds (per-semester eligibility)`.

---

## Task 3: Remove siblingDiscount flag; wire eligibility + kostenMode into Bilanz; stundenMode into Stunden

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/KostenDefinition.java`
- Modify: `backend/src/main/java/at/kigruapp/dto/KostenDefinitionDTO.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/KostenDefinitionResource.java`
- Modify: `backend/src/main/java/at/kigruapp/service/BilanzCalculationService.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/HourEntryResource.java`
- Tests: `backend/src/test/java/at/kigruapp/resource/KostenDefinitionResourceTest.java`, `BilanzResourceTest.java`, `HourEntryFamilySummaryTest.java`, `HourEntryOurTest.java`

**Interfaces:**
- Consumes: `KostenDiscount.eligibleDefinitionIds` (Task 2), `AliquotConfig.stundenMode/kostenMode` (Task 1).
- Produces: `eligible(KostenDiscount, KostenDefinition) = applyToAll || eligibleDefinitionIds.contains(def.id)`.

- [ ] **Step 1: Update tests first (RED)**
  - `KostenDefinitionResourceTest`: **remove** `setSiblingDiscountPersists`; remove any `siblingDiscount` assertions/fixtures.
  - `BilanzResourceTest` discount test: instead of PATCHing a definition flag, put the definition's id into the `KostenDiscount.eligibleDefinitionIds` (via `PUT /kosten-discount` body or direct persist in setup) with `applyToAll=false`; keep asserting the 2nd-ranked child = 50% and a non-eligible definition full. Set the semester's `kostenMode` via `AliquotConfig` where a PER_DAY case is asserted.
  - `HourEntryFamilySummaryTest`/`HourEntryOurTest`: where they set aliquot, use `stundenMode` (not `mode`).

- [ ] **Step 2: Run the touched tests — expect FAIL/compile error.**

- [ ] **Step 3: Entity/DTO/resource — remove the flag**
  - `KostenDefinition.java`: delete `public boolean siblingDiscount;`.
  - `KostenDefinitionDTO.java`: revert to `record KostenDefinitionDTO(String id, String label, boolean active, Currency currency)`.
  - `KostenDefinitionResource.java`: delete the `SetSiblingDiscountRequest` record and the `PATCH /{id}/sibling-discount` method; revert `toDTO` to the 4-arg form.

- [ ] **Step 4: BilanzCalculationService — eligibility via ids + kostenMode**
  - Change `eligible`:
    ```java
    private boolean eligible(KostenDiscount discountCfg, KostenDefinition def) {
        if (discountCfg == null) return false;
        if (discountCfg.applyToAll) return true;
        return discountCfg.eligibleDefinitionIds != null
                && discountCfg.eligibleDefinitionIds.contains(def.id);
    }
    ```
  - Change `aliquotMode(semesterId)` to read the Kosten mode:
    ```java
    private AliquotMode aliquotMode(ObjectId semesterId) {
        AliquotConfig cfg = AliquotConfig.findBySemesterId(semesterId);
        return AliquotMode.fromString(cfg != null ? cfg.kostenMode : null);
    }
    ```

- [ ] **Step 5: HourEntryResource — Stunden uses stundenMode**
  In both `familySummary` and (from the prior fix wave) any `/our` aliquot resolution, change the mode source to `stundenMode`:
  ```java
  AliquotConfig aliquotCfg = AliquotConfig.findBySemesterId(semesterId);
  AliquotMode mode = AliquotMode.fromString(aliquotCfg != null ? aliquotCfg.stundenMode : null);
  ```
  (Search the file for `.mode` on an `AliquotConfig` and replace with `.stundenMode`.)

- [ ] **Step 6: Run tests — expect PASS**

Run: `... '-Dtest=KostenDefinitionResourceTest,BilanzResourceTest,BilanzDiscountTest,HourEntryFamilySummaryTest,HourEntryOurTest'`
Expected: all green (existing Bilanz regression tests still 20/… since NONE+no-discount unchanged).

- [ ] **Step 7: Commit** `refactor: eligibility via KostenDiscount ids; split Stunden/Kosten aliquot modes`.

---

## Task 4: Auto-copy previous semester's config on create

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/SemesterResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/SemesterResourceTest.java` (new)

**Interfaces:**
- Consumes: `RequiredHours`, `KostenDiscount`, `AliquotConfig`, `KostenValue`.
- Produces: `POST /api/v1/semesters` copies the latest existing semester's config + KostenValue amounts to the new semester.

- [ ] **Step 1: Write the test (RED)**

```java
package at.kigruapp.resource;

import at.kigruapp.entity.*;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SemesterResourceTest {

    @BeforeEach
    void clean() {
        Semester.deleteAll(); RequiredHours.deleteAll(); KostenDiscount.deleteAll();
        AliquotConfig.deleteAll(); KostenValue.deleteAll();
    }

    private ObjectId seedPrev() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        s.createdAt = Instant.parse("2026-08-01T00:00:00Z");
        s.persist();
        RequiredHours rh = new RequiredHours(); rh.semesterId = s.id; rh.defaultMinutesPerMonth = 480; rh.persist();
        KostenDiscount kd = new KostenDiscount(); kd.semesterId = s.id; kd.applyToAll = true;
        kd.order = "MOST_EXPENSIVE_FIRST"; kd.persist();
        AliquotConfig ac = new AliquotConfig(); ac.semesterId = s.id; ac.stundenMode = "PER_DAY"; ac.kostenMode = "WHOLE_MONTH"; ac.persist();
        KostenValue kv = new KostenValue(); kv.semesterId = s.id; kv.groupId = new ObjectId();
        kv.definitionId = new ObjectId(); kv.amount = new BigDecimal("123.45"); kv.persist();
        return s.id;
    }

    @Test
    void createCopiesPreviousSemesterConfig() {
        seedPrev();
        String newId = given().contentType(ContentType.JSON)
                .body("{\"start\":\"2027-09-01T00:00:00Z\",\"end\":\"2028-02-28T00:00:00Z\"}")
                .when().post("/api/v1/semesters").then().statusCode(201).extract().path("id");
        ObjectId nid = new ObjectId(newId);
        assertEquals(480, RequiredHours.findBySemesterId(nid).defaultMinutesPerMonth);
        assertTrue(KostenDiscount.findBySemesterId(nid).applyToAll);
        AliquotConfig ac = AliquotConfig.findBySemesterId(nid);
        assertEquals("PER_DAY", ac.stundenMode);
        assertEquals("WHOLE_MONTH", ac.kostenMode);
        assertEquals(1, KostenValue.find("semesterId", nid).count());
    }

    @Test
    void createWithoutPreviousDoesNotCopy() {
        String newId = given().contentType(ContentType.JSON)
                .body("{\"start\":\"2027-09-01T00:00:00Z\",\"end\":\"2028-02-28T00:00:00Z\"}")
                .when().post("/api/v1/semesters").then().statusCode(201).extract().path("id");
        assertNull(RequiredHours.findBySemesterId(new ObjectId(newId)));
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (no copy yet).

- [ ] **Step 3: Implement copy in `SemesterResource.create`**

Before persisting the new semester, capture the previous; after persisting, copy. Add:

```java
    private Semester latestSemesterOrNull() {
        return Semester.find("", io.quarkus.panache.common.Sort.descending("createdAt")).firstResult();
    }

    private void copyConfig(org.bson.types.ObjectId from, org.bson.types.ObjectId to) {
        at.kigruapp.entity.RequiredHours rh = at.kigruapp.entity.RequiredHours.findBySemesterId(from);
        if (rh != null) {
            at.kigruapp.entity.RequiredHours c = new at.kigruapp.entity.RequiredHours();
            c.semesterId = to; c.defaultMinutesPerMonth = rh.defaultMinutesPerMonth;
            c.tiers = new java.util.ArrayList<>(rh.tiers == null ? java.util.List.of() : rh.tiers);
            c.persist();
        }
        at.kigruapp.entity.KostenDiscount kd = at.kigruapp.entity.KostenDiscount.findBySemesterId(from);
        if (kd != null) {
            at.kigruapp.entity.KostenDiscount c = new at.kigruapp.entity.KostenDiscount();
            c.semesterId = to; c.applyToAll = kd.applyToAll; c.order = kd.order;
            c.tiers = new java.util.ArrayList<>(kd.tiers == null ? java.util.List.of() : kd.tiers);
            c.eligibleDefinitionIds = new java.util.ArrayList<>(kd.eligibleDefinitionIds == null ? java.util.List.of() : kd.eligibleDefinitionIds);
            c.persist();
        }
        at.kigruapp.entity.AliquotConfig ac = at.kigruapp.entity.AliquotConfig.findBySemesterId(from);
        if (ac != null) {
            at.kigruapp.entity.AliquotConfig c = new at.kigruapp.entity.AliquotConfig();
            c.semesterId = to; c.stundenMode = ac.stundenMode; c.kostenMode = ac.kostenMode;
            c.persist();
        }
        for (at.kigruapp.entity.KostenValue v : at.kigruapp.entity.KostenValue.<at.kigruapp.entity.KostenValue>list("semesterId", from)) {
            at.kigruapp.entity.KostenValue c = new at.kigruapp.entity.KostenValue();
            c.semesterId = to; c.groupId = v.groupId; c.definitionId = v.definitionId; c.amount = v.amount;
            c.persist();
        }
    }
```
In `create`, capture `Semester prev = latestSemesterOrNull();` **before** `semester.persist();`, then after persist: `if (prev != null) copyConfig(prev.id, semester.id);`. Keep the existing response (201 + entity). Do not alter validation.

- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit** `feat: new semester inherits previous semester's config + Kosten amounts`.

---

## Task 5: Frontend shared models/services

**Files:**
- Modify: `frontend/src/app/shared/models/aliquot-config.model.ts`
- Modify: `frontend/src/app/shared/models/kosten-discount.model.ts`
- Modify: `frontend/src/app/shared/models/kosten-definition.model.ts`
- Modify: `frontend/src/app/shared/services/kosten-definition.service.ts`

**Interfaces:**
- Produces: `AliquotConfig { semesterId; stundenMode: AliquotMode; kostenMode: AliquotMode }`; `KostenDiscount` gains `eligibleDefinitionIds: string[]`; `KostenDefinition` loses `siblingDiscount`; service loses `setSiblingDiscount`.

- [ ] **Step 1:** Update `aliquot-config.model.ts`:
```ts
export type AliquotMode = 'NONE' | 'WHOLE_MONTH' | 'PER_DAY';
export interface AliquotConfig {
  semesterId: string;
  stundenMode: AliquotMode;
  kostenMode: AliquotMode;
}
```
- [ ] **Step 2:** `kosten-discount.model.ts` — add `eligibleDefinitionIds: string[];` to `KostenDiscount`.
- [ ] **Step 3:** `kosten-definition.model.ts` — remove `siblingDiscount: boolean;`.
- [ ] **Step 4:** `kosten-definition.service.ts` — remove `setSiblingDiscount`.
- [ ] **Step 5:** Build check — from `frontend/`, `npm test -- --watch=false --browsers=ChromeHeadless` will fail to compile until Tasks 6–7 update consumers; instead run `npx tsc -p tsconfig.json --noEmit` is not configured — so **defer test run to Task 7** and note here that this task has no standalone test. Commit with Task 6/7? No — commit models now.
- [ ] **Step 6: Commit** `refactor(fe): aliquot two modes, discount eligibleDefinitionIds, drop siblingDiscount model`.

(Consumers compile in Tasks 6–7; this task is a type-only change reviewed by inspection.)

---

## Task 6: Organisation — Stunden aliquot relabel + tooltip, preview length, 0-tier fix, remove discount block & definition checkbox

**Files:**
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.html`
- Modify: `frontend/src/app/settings/organisation/organisation.component.spec.ts`
- Modify: `frontend/src/app/settings/organisation/required-hours-preview.util.ts` (no change needed; preview length handled in component)

**Interfaces:**
- Consumes: `AliquotConfig` two modes (Task 5).
- Produces: Organisation no longer hosts the Geschwisterrabatt config or the per-definition checkbox; Stunden aliquot binds `stundenMode`.

- [ ] **Step 1: Update spec (RED)**
  - Change the aliquot test to load/save `{stundenMode, kostenMode}` — the component sends `stundenMode` from the dropdown and preserves `kostenMode` (load it, echo it back on save).
  - Remove the `toggleSiblingDiscount` test and the discount-config (`saveKostenDiscount`) test from this spec (moved to Kosten-pro-Semester in Task 7).
  - Add a preview-length test: with `rhTiers = [{fromChild:2,...}]`, `rhPreview.length === 2`; with two tiers, `=== 3`.
  - Add a 0-tier test: `rhTiers=[{fromChild:2, hhmm:''}]` → `saveRequiredHours()` posts `minutesPerMonth: 0` and sets no error.

- [ ] **Step 2: Run spec — expect FAIL.**

- [ ] **Step 3: Component TS**
  - Aliquot: keep `aliquotMode` but rename to `stundenMode`; `loadAliquot` reads `cfg.stundenMode` and stashes `this.kostenMode = cfg.kostenMode` to echo back; `saveAliquot` PUTs `{semesterId, stundenMode: this.stundenMode, kostenMode: this.kostenMode}`.
  - `recomputeRhPreview`: build rows for `[1, ...tiers.map(t=>t.fromChild)]` (sorted unique), not `[1,2,3,4]`:
    ```ts
    const childCounts = Array.from(new Set([1, ...this.rhTiers.map(t => t.fromChild)])).sort((a,b)=>a-b);
    this.rhPreview = childCounts.map(n => ({ children: n, hhmm: formatMinutes(familyMonthlyMinutes({ defaultMinutesPerMonth: def, tiers }, n)) }));
    ```
  - `saveRequiredHours`: treat blank tier as 0 — `minutesPerMonth: (t.hhmm.trim() === '' ? 0 : (parseHhmm(t.hhmm) ?? NaN))`; only error when a non-blank value is unparseable, or fromChild not ascending/unique/≥2. Update the error message to: `Staffeln müssen ab dem 2. Kind eindeutig und aufsteigend sein; Werte dürfen 0 sein.`
  - **Remove** all `kd*` fields/methods (`kdSelectedSemesterId`, `saveKostenDiscount`, etc.) and the `KostenDiscountService` injection and `discountFactors` import (moved to Task 7). **Remove** `toggleSiblingDiscount` and `siblingDiscount` from `kostenDefColumns`, and the `MatCheckboxModule`/`MatTooltipModule` imports if now unused (keep MatTooltip — used by the aliquot info icon).

- [ ] **Step 4: Component HTML**
  - Zu-leistende-Stunden tab: relabel the aliquot select "Aliquotierung (Zu leistende Stunden)"; add a `<mat-icon matTooltip="…verbatim tooltip text…" class="info-icon">info</mat-icon>` next to the label.
  - **Remove** the entire Geschwisterrabatt config section and the per-definition Geschwisterrabatt column/checkbox from the Kosten-Definitionen table.

- [ ] **Step 5: Run spec — expect PASS.**
- [ ] **Step 6: Commit** `feat(fe): Stunden aliquot label+tooltip, preview per tier, 0-tiers; remove discount UI from Organisation`.

---

## Task 7: Kosten pro Semester — Geschwisterrabatt + Kosten aliquot

**Files:**
- Modify: `frontend/src/app/administration/kosten-pro-semester/kosten-pro-semester.component.ts`
- Modify: `frontend/src/app/administration/kosten-pro-semester/kosten-pro-semester.component.html`
- Modify: `frontend/src/app/administration/kosten-pro-semester/kosten-pro-semester.component.spec.ts`
- Use: `frontend/src/app/settings/organisation/kosten-discount-preview.util.ts` (import `discountFactors`; move it to `shared/util/` if an import across feature folders is undesirable — otherwise import by relative path).

**Interfaces:**
- Consumes: `KostenDiscountService`, `AliquotConfigService`, `KostenDefinitionService` (list for eligibility checkboxes), the component's existing selected-semester id.
- Produces: per-semester Geschwisterrabatt (applyToAll, eligibleDefinitionIds, order, %tiers, preview) + Kosten aliquot (`kostenMode`), both saved via their PUT endpoints, with ⓘ tooltips.

- [ ] **Step 1: Read the current component** to find its selected-semester field/observable and how it lists cost definitions per semester; reuse that semester id for the new config loads/saves.

- [ ] **Step 2: Write spec (RED)** — using the file's existing test setup pattern:
  - saving posts `PUT /kosten-discount?semesterId=` with `applyToAll`, `order`, `tiers`, `eligibleDefinitionIds`.
  - saving the Kosten aliquot posts `PUT /aliquot-config?semesterId=` with the loaded `stundenMode` echoed and the chosen `kostenMode`.
  - preview length = default + one row per tier.

- [ ] **Step 3: Run spec — expect FAIL.**

- [ ] **Step 4: Component TS** — add fields/methods mirroring the (now-removed) Organisation block, using percentages, plus:
  - `kdApplyToAll`, `kdOrder`, `kdTiers`, `kdPreview`, `kdEligibleIds: Set<string>` (toggled by per-definition checkboxes shown when `!kdApplyToAll`), `kdError`.
  - `kostenMode: AliquotMode` + echo `stundenMode` on aliquot save.
  - `recomputeKdPreview()` = `discountFactors(this.kdTiers, ...)` but rows = default + one per tier: pass the tier `fromChild`s (see Task 6 preview-length approach) — build `[1, ...tiers.map(fromChild)]`.
  - `saveKostenDiscount()` validates fromChild unique/ascending/≥2 and percent 0..100 (0 allowed), sends `eligibleDefinitionIds: [...kdEligibleIds]`.
  - Inject `KostenDiscountService`, `AliquotConfigService`, `KostenDefinitionService`; load all three for the selected semester.

- [ ] **Step 5: Component HTML** — add a "Geschwisterrabatt" card (applyToAll checkbox; when unchecked, a checkbox per active definition bound to `kdEligibleIds`; order dropdown; %tier rows with add/remove; preview table; error) with an ⓘ `mat-icon matTooltip` next to the heading; and a "Aliquotierung (Kosten)" select bound to `kostenMode` with its own ⓘ tooltip (verbatim text). Add `MatCheckboxModule`, `MatTooltipModule`, `MatSelectModule`, `MatIconModule` to the component imports as needed.

- [ ] **Step 6: Run spec — expect PASS**, then run the full frontend suite to confirm no regressions:
  Run: `npm test -- --watch=false --browsers=ChromeHeadless` (expect only the AppComponent baseline failure).

- [ ] **Step 7: Commit** `feat(fe): Geschwisterrabatt + Kosten aliquot in Kosten pro Semester (tooltips, eligibility, preview)`.

---

## Task 8: Full-suite verification

- [ ] **Step 1: Backend full suite** — `... mvn.cmd test` (no `-Dtest`). Expect the known 12-failure baseline (FieldDefinitionResourceTest, SecurityFilterTest) and **zero new** failures; all feature classes green.
- [ ] **Step 2: Frontend full suite** — `npm test -- --watch=false --browsers=ChromeHeadless`. Expect only the AppComponent baseline failure.
- [ ] **Step 3:** If clean, no commit needed (verification only).

---

## Self-Review

**Spec coverage:** aliquot split (T1,T3,T5,T6,T7) ✓; discount eligibleDefinitionIds (T2,T3,T7) ✓; remove siblingDiscount (T3,T5,T6) ✓; auto-copy new semester (T4) ✓; preview length (T6,T7) ✓; 0-tier (T6,T7) ✓; move discount+kosten-aliquot to Kosten-pro-Semester (T6 removes, T7 adds) ✓; tooltips (T6,T7) ✓; wiring stundenMode/kostenMode (T3) ✓.

**Placeholder scan:** the two frontend UI tasks (T6/T7) reference the mirrored (now-removed) Organisation block for exact structure and give the concrete new logic (preview array, 0-tier handling, eligibility set); test bodies specify the asserted behavior. No TODO/TBD.

**Type consistency:** `stundenMode`/`kostenMode` strings identical across entity/DTO/frontend union; `eligibleDefinitionIds` is `List<ObjectId>` (entity) / `List<String>` (DTO) / `string[]` (FE); `eligible()` uses `def.id`. `discountFactors` reused from the existing util.

**Note:** Task 5 is a type-only change reviewed by inspection (consumers compile in T6/T7); its commit precedes its consumers, so the frontend suite is first run green at end of Task 7.
