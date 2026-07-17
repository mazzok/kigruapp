# Platzzuweisung Eintritts-/Austrittsdatum Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two per-child, per-semester date fields (Eintrittsdatum, Austrittsdatum) to Platzzuweisung, editable inline once a group is assigned.

**Architecture:** `entryDate`/`exitDate` (ISO `YYYY-MM-DD` strings, nullable) are stored directly on the existing `semester_assignments` document for `section: "group"` — no new collection. A new `PATCH /api/v1/persons/{id}/enrollment-dates?semesterId=...` endpoint replaces both fields together (full-pair replace, not a partial patch — the frontend always sends the row's current desired state for both fields). Reading them piggybacks on the existing group-assignment lookup in `toChildDTO`. Because `patchGroup` already does delete-then-reinsert of the whole assignment document, changing or clearing the group automatically drops any previously set dates — no extra reset logic needed there.

**Tech Stack:** Quarkus (Java 21, MongoDB driver, RESTEasy Reactive), Angular 18 (standalone components, Angular Material `MatDatepicker`), JUnit 5 + REST Assured (`@QuarkusTest`), Karma/Jasmine.

## Global Constraints

- Dates are date-only strings (`YYYY-MM-DD`), no time component.
- `exitDate` requires `entryDate` to already be present (in the same request or already stored) — 400 otherwise.
- `exitDate` must not be before `entryDate` (string comparison is safe for `YYYY-MM-DD`).
- Dates can only be set when a group assignment already exists for that (person, semester) — 400 otherwise.
- No filtering/hiding of children based on these dates (out of scope per spec).
- Backend package `at.kigruapp` (not `de.kigruapp`).

---

## File Structure

| File | Change |
|------|--------|
| `backend/src/main/java/at/kigruapp/entity/SemesterAssignment.java` | Add `entryDate`/`exitDate` fields, wire into `fromDocument`/`toDocument` |
| `backend/src/main/java/at/kigruapp/resource/PersonResource.java` | `ChildDTO` gains 2 fields; `toChildDTO` resolves them; new `EnrollmentDatesRequest` record + `patchEnrollmentDates` endpoint |
| `backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java` | New tests for the read path, write path, validation, and the group-change reset behavior |
| `frontend/src/app/shared/models/person.model.ts` | `ChildDTO` gains `entryDate`/`exitDate` |
| `frontend/src/app/shared/services/person.service.ts` | New `setEnrollmentDates()` method |
| `frontend/src/app/administration/platzzuweisung/platzzuweisung.component.ts` | Two new table columns, date-change handlers, disabled logic, reset-on-group-change |
| `frontend/src/app/administration/platzzuweisung/platzzuweisung.component.spec.ts` | Update existing `ChildDTO` literal; new tests for the new behavior |

---

### Task 1: Backend — resolve entryDate/exitDate in the children list (read path)

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/SemesterAssignment.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/PersonResource.java:154-161` (`ChildDTO` record), `:468-495` (`toChildDTO`)
- Test: `backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java`

**Interfaces:**
- Produces: `ChildDTO(String id, String firstName, String lastName, String dateOfBirth, String groupDefinitionId, String groupInstanceId, String entryDate, String exitDate)` — consumed by Task 2's tests and by the frontend in Task 4.

- [ ] **Step 1: Write the failing test**

Add to `PersonResourceTest.java`, right after `testGroupAssignmentIsolatedPerSemester`:

```java
    @Test
    public void testChildEntryExitDatesDefaultNull() {
        String familyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Testfamilie-Dates\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        String personId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"basicProperties\": []}")
            .when().post("/api/v1/persons")
            .then().statusCode(201)
            .extract().path("id");

        String groupDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"group\", \"label\": {\"de\": \"Gruppen\"}, \"jsonSchema\": {\"type\": \"object\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String groupInstId = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"value\": {\"label\": \"Baeren-Dates\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String semesterId = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2024-09-01T00:00:00Z\", \"end\": \"2025-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"fieldInstanceId\": \"" + groupInstId + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/group?semesterId=" + semesterId)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/persons/children?semesterId=" + semesterId)
            .then()
            .statusCode(200)
            .body("find { it.id == '" + personId + "' }.entryDate", nullValue())
            .body("find { it.id == '" + personId + "' }.exitDate", nullValue());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=PersonResourceTest#testChildEntryExitDatesDefaultNull`
Expected: FAIL — `ChildDTO` has no `entryDate` property, JSON path resolves to a missing-property error (Groovy/REST Assured throws when the property doesn't exist on the response object).

- [ ] **Step 3: Add the fields to `SemesterAssignment`**

In `backend/src/main/java/at/kigruapp/entity/SemesterAssignment.java`, replace the whole file:

```java
package at.kigruapp.entity;

import org.bson.Document;
import org.bson.types.ObjectId;

public class SemesterAssignment {
    public ObjectId id;
    public ObjectId personId;
    public ObjectId semesterId;
    public String section;
    public ObjectId definitionId;
    public ObjectId fieldInstanceId;
    public String entryDate;
    public String exitDate;

    public static SemesterAssignment fromDocument(Document doc) {
        if (doc == null) return null;
        SemesterAssignment sa = new SemesterAssignment();
        sa.id = doc.getObjectId("_id");
        sa.personId = doc.getObjectId("personId");
        sa.semesterId = doc.getObjectId("semesterId");
        sa.section = doc.getString("section");
        sa.definitionId = doc.getObjectId("definitionId");
        sa.fieldInstanceId = doc.getObjectId("fieldInstanceId");
        sa.entryDate = doc.getString("entryDate");
        sa.exitDate = doc.getString("exitDate");
        return sa;
    }

    public Document toDocument() {
        return new Document("_id", id != null ? id : new ObjectId())
                .append("personId", personId)
                .append("semesterId", semesterId)
                .append("section", section)
                .append("definitionId", definitionId)
                .append("fieldInstanceId", fieldInstanceId)
                .append("entryDate", entryDate)
                .append("exitDate", exitDate);
    }
}
```

- [ ] **Step 4: Update `ChildDTO` and `toChildDTO` in `PersonResource.java`**

Replace the `ChildDTO` record (around line 154-161):

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

Replace `toChildDTO` (around line 468-495):

```java
    private ChildDTO toChildDTO(Person person, ObjectId semesterId) {
        String firstName = resolveBasicValue(person, "firstName");
        String lastName = resolveBasicValue(person, "lastName");
        String dateOfBirth = resolveBasicValue(person, "dateOfBirth");

        String groupDefinitionId = null;
        String groupInstanceId = null;
        String entryDate = null;
        String exitDate = null;
        if (semesterId != null) {
            Document groupAssignment = getSemesterAssignmentsCollection()
                    .find(new Document("personId", person.id)
                            .append("semesterId", semesterId)
                            .append("section", "group"))
                    .first();
            if (groupAssignment != null) {
                groupDefinitionId = groupAssignment.getObjectId("definitionId").toHexString();
                groupInstanceId = groupAssignment.getObjectId("fieldInstanceId").toHexString();
                entryDate = groupAssignment.getString("entryDate");
                exitDate = groupAssignment.getString("exitDate");
            }
        }

        return new ChildDTO(
            person.id.toHexString(),
            firstName,
            lastName,
            dateOfBirth,
            groupDefinitionId,
            groupInstanceId,
            entryDate,
            exitDate
        );
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=PersonResourceTest#testChildEntryExitDatesDefaultNull`
Expected: PASS

- [ ] **Step 6: Run the full backend test suite to check for regressions**

Run: `cd backend && ./mvnw test -Dtest=PersonResourceTest`
Expected: All tests PASS (existing `testGroupAssignmentIsolatedPerSemester` etc. still pass since they don't assert on the full `ChildDTO` shape).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/SemesterAssignment.java backend/src/main/java/at/kigruapp/resource/PersonResource.java backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java
git commit -m "feat: resolve entryDate/exitDate on ChildDTO"
```

---

### Task 2: Backend — PATCH endpoint to set entryDate/exitDate

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/PersonResource.java` (new record + new endpoint, placed after `patchGroup`, i.e. after line 417)
- Test: `backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java`

**Interfaces:**
- Consumes: `ChildDTO` from Task 1 (to verify persisted values via `GET /persons/children`).
- Produces: `PATCH /api/v1/persons/{id}/enrollment-dates?semesterId=...` with body `{"entryDate": string|null, "exitDate": string|null}` → `204` on success, `400` if no group assignment exists / `exitDate` without `entryDate` / `exitDate < entryDate`, `404` if person doesn't exist.

- [ ] **Step 1: Write the failing tests**

Add to `PersonResourceTest.java`:

```java
    @Test
    public void testSetEnrollmentDatesRequiresGroupAssignment() {
        String familyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Testfamilie-NoGroup\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        String personId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"basicProperties\": []}")
            .when().post("/api/v1/persons")
            .then().statusCode(201)
            .extract().path("id");

        String semesterId = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2024-09-01T00:00:00Z\", \"end\": \"2025-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"entryDate\": \"2024-09-01\", \"exitDate\": null}")
            .when().patch("/api/v1/persons/" + personId + "/enrollment-dates?semesterId=" + semesterId)
            .then()
            .statusCode(400);
    }

    @Test
    public void testSetEnrollmentDatesValidatesOrderAndDependency() {
        String familyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Testfamilie-Order\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        String personId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"basicProperties\": []}")
            .when().post("/api/v1/persons")
            .then().statusCode(201)
            .extract().path("id");

        String groupDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"group\", \"label\": {\"de\": \"Gruppen\"}, \"jsonSchema\": {\"type\": \"object\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String groupInstId = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"value\": {\"label\": \"Baeren-Order\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String semesterId = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2024-09-01T00:00:00Z\", \"end\": \"2025-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"fieldInstanceId\": \"" + groupInstId + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/group?semesterId=" + semesterId)
            .then().statusCode(204);

        // exitDate without entryDate -> 400
        given()
            .contentType(ContentType.JSON)
            .body("{\"entryDate\": null, \"exitDate\": \"2025-01-01\"}")
            .when().patch("/api/v1/persons/" + personId + "/enrollment-dates?semesterId=" + semesterId)
            .then().statusCode(400);

        // exitDate before entryDate -> 400
        given()
            .contentType(ContentType.JSON)
            .body("{\"entryDate\": \"2025-01-01\", \"exitDate\": \"2024-12-01\"}")
            .when().patch("/api/v1/persons/" + personId + "/enrollment-dates?semesterId=" + semesterId)
            .then().statusCode(400);

        // valid pair -> 204, then reflected in children list
        given()
            .contentType(ContentType.JSON)
            .body("{\"entryDate\": \"2024-09-15\", \"exitDate\": \"2025-06-30\"}")
            .when().patch("/api/v1/persons/" + personId + "/enrollment-dates?semesterId=" + semesterId)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/persons/children?semesterId=" + semesterId)
            .then()
            .statusCode(200)
            .body("find { it.id == '" + personId + "' }.entryDate", is("2024-09-15"))
            .body("find { it.id == '" + personId + "' }.exitDate", is("2025-06-30"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=PersonResourceTest#testSetEnrollmentDatesRequiresGroupAssignment+testSetEnrollmentDatesValidatesOrderAndDependency`
Expected: FAIL with `404` (route doesn't exist yet, RESTEasy returns 404 for unknown path) instead of the expected status codes.

- [ ] **Step 3: Implement the endpoint**

In `PersonResource.java`, add this record next to the other request records (near `GroupAssignmentRequest` around line 163):

```java
    public record EnrollmentDatesRequest(String entryDate, String exitDate) {}
```

Add this endpoint directly after `patchGroup` (after the closing brace at line 417):

```java
    @PATCH
    @Path("/{id}/enrollment-dates")
    public Response patchEnrollmentDates(
            @PathParam("id") String id,
            @QueryParam("semesterId") String semesterIdParam,
            EnrollmentDatesRequest request) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) throw new NotFoundException();

        ObjectId semesterId = requireSemesterId(semesterIdParam);
        String entryDate = request.entryDate();
        String exitDate = request.exitDate();

        if (exitDate != null && entryDate == null) {
            throw new BadRequestException("exitDate requires entryDate");
        }
        if (entryDate != null && exitDate != null && exitDate.compareTo(entryDate) < 0) {
            throw new BadRequestException("exitDate must not be before entryDate");
        }

        MongoCollection<Document> assignments = getSemesterAssignmentsCollection();
        Document filter = new Document("personId", person.id)
                .append("semesterId", semesterId)
                .append("section", "group");
        Document existing = assignments.find(filter).first();
        if (existing == null) {
            throw new BadRequestException("No group assignment for this semester");
        }

        assignments.deleteMany(filter);
        assignments.insertOne(new Document("_id", new ObjectId())
                .append("personId", person.id)
                .append("semesterId", semesterId)
                .append("section", "group")
                .append("definitionId", existing.getObjectId("definitionId"))
                .append("fieldInstanceId", existing.getObjectId("fieldInstanceId"))
                .append("entryDate", entryDate)
                .append("exitDate", exitDate));

        return Response.noContent().build();
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=PersonResourceTest#testSetEnrollmentDatesRequiresGroupAssignment+testSetEnrollmentDatesValidatesOrderAndDependency`
Expected: PASS

- [ ] **Step 5: Run full backend suite**

Run: `cd backend && ./mvnw test -Dtest=PersonResourceTest`
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/PersonResource.java backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java
git commit -m "feat: add PATCH /persons/{id}/enrollment-dates endpoint"
```

---

### Task 3: Backend — verify group change resets entry/exit dates

This is a regression-guard test only; `patchGroup`'s existing delete-then-reinsert already drops `entryDate`/`exitDate` since the freshly inserted document never includes them. No production code change in this task.

**Files:**
- Test: `backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java`

**Interfaces:**
- Consumes: `PATCH /persons/{id}/group` (existing), `PATCH /persons/{id}/enrollment-dates` (Task 2), `GET /persons/children` (Task 1).

- [ ] **Step 1: Write the test**

```java
    @Test
    public void testChangingGroupResetsEnrollmentDates() {
        String familyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Testfamilie-Reset\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        String personId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"basicProperties\": []}")
            .when().post("/api/v1/persons")
            .then().statusCode(201)
            .extract().path("id");

        String groupDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"group\", \"label\": {\"de\": \"Gruppen\"}, \"jsonSchema\": {\"type\": \"object\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String groupInstId1 = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"value\": {\"label\": \"Baeren-Reset\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String groupInstId2 = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"value\": {\"label\": \"Fuechse-Reset\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String semesterId = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2024-09-01T00:00:00Z\", \"end\": \"2025-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"fieldInstanceId\": \"" + groupInstId1 + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/group?semesterId=" + semesterId)
            .then().statusCode(204);

        given()
            .contentType(ContentType.JSON)
            .body("{\"entryDate\": \"2024-09-15\", \"exitDate\": null}")
            .when().patch("/api/v1/persons/" + personId + "/enrollment-dates?semesterId=" + semesterId)
            .then().statusCode(204);

        // switch to a different group
        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"fieldInstanceId\": \"" + groupInstId2 + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/group?semesterId=" + semesterId)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/persons/children?semesterId=" + semesterId)
            .then()
            .statusCode(200)
            .body("find { it.id == '" + personId + "' }.groupInstanceId", is(groupInstId2))
            .body("find { it.id == '" + personId + "' }.entryDate", nullValue());
    }
```

- [ ] **Step 2: Run the test**

Run: `cd backend && ./mvnw test -Dtest=PersonResourceTest#testChangingGroupResetsEnrollmentDates`
Expected: PASS immediately (no production code change needed — this confirms the existing delete/reinsert behavior already satisfies the invariant).

- [ ] **Step 3: Run full backend suite**

Run: `cd backend && ./mvnw test -Dtest=PersonResourceTest`
Expected: All PASS

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java
git commit -m "test: verify group change resets entry/exit dates"
```

---

### Task 4: Frontend — model and service

**Files:**
- Modify: `frontend/src/app/shared/models/person.model.ts:53-60` (`ChildDTO`)
- Modify: `frontend/src/app/shared/services/person.service.ts`

**Interfaces:**
- Produces: `ChildDTO.entryDate: string | null`, `ChildDTO.exitDate: string | null`; `PersonService.setEnrollmentDates(personId: string, entryDate: string | null, exitDate: string | null, semesterId?: string): Observable<void>` — consumed by Task 5.

- [ ] **Step 1: Update the `ChildDTO` interface**

In `frontend/src/app/shared/models/person.model.ts`, replace the `ChildDTO` interface:

```typescript
export interface ChildDTO {
  id: string;
  firstName: string | null;
  lastName: string | null;
  dateOfBirth: string | null;
  groupDefinitionId: string | null;
  groupInstanceId: string | null;
  entryDate: string | null;
  exitDate: string | null;
}
```

- [ ] **Step 2: Add the service method**

In `frontend/src/app/shared/services/person.service.ts`, add after `assignGroup`:

```typescript
  setEnrollmentDates(personId: string, entryDate: string | null, exitDate: string | null, semesterId?: string): Observable<void> {
    const params = semesterId ? `?semesterId=${semesterId}` : '';
    return this.api.patch<void>(`/persons/${personId}/enrollment-dates${params}`, { entryDate, exitDate });
  }
```

- [ ] **Step 3: Type-check**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`
Expected: Fails at this point — `platzzuweisung.component.spec.ts:94` constructs a `ChildDTO` object literal missing `entryDate`/`exitDate`, which now violates the interface.

- [ ] **Step 4: Fix the existing spec literal**

In `frontend/src/app/administration/platzzuweisung/platzzuweisung.component.spec.ts:94`, replace:

```typescript
    component.onGroupChange({ id: 'child-1', firstName: 'Max', lastName: 'Muster', dateOfBirth: null, groupDefinitionId: null, groupInstanceId: null }, 'inst-1');
```

with:

```typescript
    component.onGroupChange({ id: 'child-1', firstName: 'Max', lastName: 'Muster', dateOfBirth: null, groupDefinitionId: null, groupInstanceId: null, entryDate: null, exitDate: null }, 'inst-1');
```

- [ ] **Step 5: Type-check again**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`
Expected: No output (clean)

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/models/person.model.ts frontend/src/app/shared/services/person.service.ts frontend/src/app/administration/platzzuweisung/platzzuweisung.component.spec.ts
git commit -m "feat: add entryDate/exitDate to ChildDTO and PersonService"
```

---

### Task 5: Frontend — Platzzuweisung table columns and behavior

**Files:**
- Modify: `frontend/src/app/administration/platzzuweisung/platzzuweisung.component.ts`
- Test: `frontend/src/app/administration/platzzuweisung/platzzuweisung.component.spec.ts`

**Interfaces:**
- Consumes: `ChildDTO.entryDate`/`exitDate`, `PersonService.setEnrollmentDates(...)` from Task 4.

- [ ] **Step 1: Write the failing tests**

Add to `platzzuweisung.component.spec.ts`. First extend `FakePersonService` (replace the whole class):

```typescript
class FakePersonService {
  getChildrenCalls: (string | undefined)[] = [];
  assignGroupCalls: { personId: string; definitionId: string; fieldInstanceId: string; semesterId: string | undefined }[] = [];
  setEnrollmentDatesCalls: { personId: string; entryDate: string | null; exitDate: string | null; semesterId: string | undefined }[] = [];
  children: ChildDTO[] = [];

  getChildren(semesterId?: string) {
    this.getChildrenCalls.push(semesterId);
    return of(this.children);
  }

  assignGroup(personId: string, definitionId: string, fieldInstanceId: string, semesterId?: string) {
    this.assignGroupCalls.push({ personId, definitionId, fieldInstanceId, semesterId });
    return of(undefined);
  }

  setEnrollmentDates(personId: string, entryDate: string | null, exitDate: string | null, semesterId?: string) {
    this.setEnrollmentDatesCalls.push({ personId, entryDate, exitDate, semesterId });
    return of(undefined);
  }
}
```

Then add these tests inside the existing `describe('PlatzzuweisungComponent - Semester', ...)` block, after the last `it(...)`:

```typescript
  it('sends entryDate and current exitDate when entry date changes', () => {
    semesterService.semesters = [
      { id: 'semester-1', start: '2024-09-01T00:00:00Z', end: '2025-08-31T00:00:00Z', createdAt: '2025-02-01T00:00:00Z' },
    ];
    component.ngOnInit();
    const child: ChildDTO = {
      id: 'child-1', firstName: 'Max', lastName: 'Muster', dateOfBirth: null,
      groupDefinitionId: 'def-group', groupInstanceId: 'inst-1', entryDate: null, exitDate: '2025-06-30',
    };

    component.onEntryDateChange(child, '2024-09-15');

    expect(personService.setEnrollmentDatesCalls).toEqual([
      { personId: 'child-1', entryDate: '2024-09-15', exitDate: '2025-06-30', semesterId: 'semester-1' },
    ]);
  });

  it('sends current entryDate and new exitDate when exit date changes', () => {
    semesterService.semesters = [
      { id: 'semester-1', start: '2024-09-01T00:00:00Z', end: '2025-08-31T00:00:00Z', createdAt: '2025-02-01T00:00:00Z' },
    ];
    component.ngOnInit();
    const child: ChildDTO = {
      id: 'child-1', firstName: 'Max', lastName: 'Muster', dateOfBirth: null,
      groupDefinitionId: 'def-group', groupInstanceId: 'inst-1', entryDate: '2024-09-15', exitDate: null,
    };

    component.onExitDateChange(child, '2025-06-30');

    expect(personService.setEnrollmentDatesCalls).toEqual([
      { personId: 'child-1', entryDate: '2024-09-15', exitDate: '2025-06-30', semesterId: 'semester-1' },
    ]);
  });

  it('disables entry date until a group is assigned', () => {
    const noGroup: ChildDTO = {
      id: 'child-1', firstName: 'Max', lastName: 'Muster', dateOfBirth: null,
      groupDefinitionId: null, groupInstanceId: null, entryDate: null, exitDate: null,
    };
    const withGroup: ChildDTO = { ...noGroup, groupDefinitionId: 'def-group', groupInstanceId: 'inst-1' };

    expect(component.isEntryDateDisabled(noGroup)).toBe(true);
    expect(component.isEntryDateDisabled(withGroup)).toBe(false);
  });

  it('disables exit date until entry date is set', () => {
    const withGroupNoEntry: ChildDTO = {
      id: 'child-1', firstName: 'Max', lastName: 'Muster', dateOfBirth: null,
      groupDefinitionId: 'def-group', groupInstanceId: 'inst-1', entryDate: null, exitDate: null,
    };
    const withEntry: ChildDTO = { ...withGroupNoEntry, entryDate: '2024-09-15' };

    expect(component.isExitDateDisabled(withGroupNoEntry)).toBe(true);
    expect(component.isExitDateDisabled(withEntry)).toBe(false);
  });

  it('resets entry and exit dates locally when the group assignment changes', () => {
    semesterService.semesters = [
      { id: 'semester-1', start: '2024-09-01T00:00:00Z', end: '2025-08-31T00:00:00Z', createdAt: '2025-02-01T00:00:00Z' },
    ];
    component.ngOnInit();
    (component as any).groupDefinitionId = 'def-group';
    const child: ChildDTO = {
      id: 'child-1', firstName: 'Max', lastName: 'Muster', dateOfBirth: null,
      groupDefinitionId: 'def-group', groupInstanceId: 'inst-1', entryDate: '2024-09-15', exitDate: '2025-06-30',
    };

    component.onGroupChange(child, 'inst-2');

    expect(child.entryDate).toBeNull();
    expect(child.exitDate).toBeNull();
  });
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && ng test --watch=false --include='**/platzzuweisung.component.spec.ts'`
Expected: FAIL — `onEntryDateChange`, `onExitDateChange`, `isEntryDateDisabled`, `isExitDateDisabled` don't exist yet, and `FakePersonService` now has an extra method the real `PersonService` interface will need to match (compile step will also fail without Step 3-4 done in the component).

- [ ] **Step 3: Add the new columns to the template**

In `frontend/src/app/administration/platzzuweisung/platzzuweisung.component.ts`, update `imports` to include the datepicker module, and update the template. Replace:

```typescript
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
```

with:

```typescript
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatInputModule } from '@angular/material/input';
```

Replace the `imports: [...]` array:

```typescript
  imports: [
    CommonModule,
    MatTableModule, MatSelectModule, MatFormFieldModule, MatProgressSpinnerModule,
    MatDatepickerModule, MatInputModule,
  ],
```

Replace `displayedColumns`:

```typescript
  displayedColumns = ['name', 'alter', 'gruppe', 'eintritt', 'austritt'];
```

Insert two new `ng-container` column defs right after the `gruppe` column's closing `</ng-container>` (before the `<tr mat-header-row ...>` line):

```html
          <ng-container matColumnDef="eintritt">
            <th mat-header-cell *matHeaderCellDef>Eintritt</th>
            <td mat-cell *matCellDef="let child">
              <mat-form-field appearance="outline" class="date-field">
                <input matInput [matDatepicker]="entryPicker"
                  [value]="parseDate(child.entryDate)"
                  [disabled]="isEntryDateDisabled(child)"
                  (dateChange)="onEntryDateChange(child, formatDate($event.value))">
                <mat-datepicker-toggle matIconSuffix [for]="entryPicker" [disabled]="isEntryDateDisabled(child)"></mat-datepicker-toggle>
                <mat-datepicker #entryPicker></mat-datepicker>
              </mat-form-field>
            </td>
          </ng-container>

          <ng-container matColumnDef="austritt">
            <th mat-header-cell *matHeaderCellDef>Austritt</th>
            <td mat-cell *matCellDef="let child">
              <mat-form-field appearance="outline" class="date-field">
                <input matInput [matDatepicker]="exitPicker"
                  [value]="parseDate(child.exitDate)"
                  [disabled]="isExitDateDisabled(child)"
                  (dateChange)="onExitDateChange(child, formatDate($event.value))">
                <mat-datepicker-toggle matIconSuffix [for]="exitPicker" [disabled]="isExitDateDisabled(child)"></mat-datepicker-toggle>
                <mat-datepicker #exitPicker></mat-datepicker>
              </mat-form-field>
            </td>
          </ng-container>
```

Add a `.date-field { width: 160px; }` rule to the `styles` array (alongside the existing `.semester-select` rule).

- [ ] **Step 4: Implement the component methods**

Add these methods to `PlatzzuweisungComponent`, right after `getAge`:

```typescript
  parseDate(dateStr: string | null): Date | null {
    if (!dateStr) return null;
    const [y, m, d] = dateStr.split('-').map(Number);
    return new Date(y, m - 1, d);
  }

  formatDate(date: Date | null): string | null {
    if (!date) return null;
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }

  isEntryDateDisabled(child: ChildDTO): boolean {
    return !child.groupInstanceId;
  }

  isExitDateDisabled(child: ChildDTO): boolean {
    return !child.groupInstanceId || !child.entryDate;
  }

  onEntryDateChange(child: ChildDTO, entryDate: string | null): void {
    if (!this.selectedSemesterId) return;
    this.personService.setEnrollmentDates(child.id, entryDate, child.exitDate, this.selectedSemesterId).subscribe(() => {
      child.entryDate = entryDate;
    });
  }

  onExitDateChange(child: ChildDTO, exitDate: string | null): void {
    if (!this.selectedSemesterId) return;
    this.personService.setEnrollmentDates(child.id, child.entryDate, exitDate, this.selectedSemesterId).subscribe(() => {
      child.exitDate = exitDate;
    });
  }
```

- [ ] **Step 5: Reset dates locally in `onGroupChange`**

Replace the existing `onGroupChange` method:

```typescript
  onGroupChange(child: ChildDTO, groupInstanceId: string | null): void {
    if (!groupInstanceId || !this.groupDefinitionId || !this.selectedSemesterId) return;
    this.personService.assignGroup(child.id, this.groupDefinitionId, groupInstanceId, this.selectedSemesterId).subscribe(() => {
      child.groupDefinitionId = this.groupDefinitionId!;
      child.groupInstanceId = groupInstanceId;
      child.entryDate = null;
      child.exitDate = null;
    });
  }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && ng test --watch=false --include='**/platzzuweisung.component.spec.ts'`
Expected: PASS — all new and existing tests green.

- [ ] **Step 7: Type-check the whole app**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`
Expected: No output (clean)

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/administration/platzzuweisung/platzzuweisung.component.ts frontend/src/app/administration/platzzuweisung/platzzuweisung.component.spec.ts
git commit -m "feat: add Eintritt/Austritt date columns to Platzzuweisung"
```

---

### Task 6: Manual smoke test

**Files:** none (verification only)

- [ ] **Step 1: Start backend and frontend dev servers** (if not already running)

Run backend: `cd backend && ./mvnw quarkus:dev`
Run frontend: `cd frontend && npm start`

- [ ] **Step 2: Walk through the flow in the browser**

1. Open Platzzuweisung as an admin.
2. Confirm Eintritt/Austritt columns are disabled (greyed out, no picker) for a child with no group.
3. Assign a group to that child — confirm Eintritt becomes enabled, Austritt stays disabled.
4. Pick an Eintritt date — confirm it saves (reload the page, value persists) and Austritt becomes enabled.
5. Try picking an Austritt date before the Eintritt date — confirm the picker still lets you pick it client-side, but reload afterwards to check the value; if the backend rejected it, the field should not reflect the invalid date after reload (surfacing the network error to the user is not in scope for this task, but note it as a known gap if observed).
6. Pick a valid Austritt date — confirm it saves.
7. Change the child's group to a different one — confirm both dates reset to empty and Austritt becomes disabled again.

- [ ] **Step 3: Report findings**

No commit for this task — it's verification. If the manual walkthrough surfaces a bug, fix it in a follow-up task before considering the plan complete.

---

## Self-Review Notes

- **Spec coverage:** Data model (Task 1, 3), write endpoint + validation (Task 2), reset-on-group-change (Task 3, already covered by existing logic), frontend columns/disabled-state/auto-save (Task 5), model/service (Task 4). All spec sections have a corresponding task.
- **Deviation from spec wire format:** the spec described `entryDate`/`exitDate` as independently omittable in the PATCH body ("Feld nicht im Request enthalten → unverändert"). This plan implements a simpler full-pair-replace instead — the frontend always sends both fields (the changed one plus the row's current other value) — because a plain Java record can't distinguish "field absent" from "field explicitly null" without extra wrapper types, and the frontend already has both values in memory for the row it's editing. All four invariants from the spec still hold under this implementation.
- **Type consistency:** `ChildDTO` (backend record and frontend interface), `EnrollmentDatesRequest`, and `PersonService.setEnrollmentDates` signatures are used identically across Tasks 1, 2, 4, and 5.
