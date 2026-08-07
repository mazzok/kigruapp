# Kochdienst-Erstellung durch Eltern — Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eltern koennen im Kochdienst-Dialog aus den echten Elternteilen ihrer Familie waehlen, und sehen beim Datum-Auswaehlen per Hover-Tooltip, welche Art von Schliesstag ein Tag hat.

**Architecture:** Backend bekommt einen neuen schlanken `/persons/parents`-Endpoint (analog zum bestehenden `/persons/children`-Muster) plus fehlende SecurityFilter-Whitelist-Eintraege fuer Closure-/Feiertags-GETs. Frontend ersetzt im Kochdienst-Dialog den nativen `MatDatepicker` durch die bestehende `ClosureCalendarComponent` (neuer `mode: 'single'`) und laedt die "Wer kocht"-Liste ueber den neuen Endpoint.

**Tech Stack:** Quarkus/Java (Backend), Angular/TypeScript mit Angular Material (Frontend), MongoDB, RestAssured/JUnit (Backend-Tests), Karma/Jasmine (Frontend-Tests).

## Global Constraints

- Bestehende Aufrufer von `PersonResource.list()` / `PersonService.list()` (family-wizard, board, elterneinteilung) duerfen sich nicht aendern — deren Rueckgabeform (`Person[]`) bleibt unangetastet.
- Bestehendes Verhalten der `ClosureCalendarComponent` im Default-Modus (`mode: 'range'`, Schliesstage-Admin- und Eltern-Ansicht) darf sich nicht aendern — alle bestehenden Tests in `closure-calendar.component.spec.ts` und `closure-calendar.util.spec.ts` muessen weiter gruen bleiben.
- Serverseitige Schliesszeiten-Sperre ueber `ClosureGuard` bleibt unveraendert die massgebliche Pruefinstanz — die neue UI ergaenzt nur die Darstellung, ersetzt keine Validierung.
- Neue optionale Felder in `CookingDutyDialogData` duerfen bestehende Aufrufstellen/Specs nicht brechen (Konvention: optional mit sinnvollem Default, wie schon bei `closedDates?`).

---

## File Structure

**Backend:**
- Modify: `backend/src/main/java/at/kigruapp/resource/PersonResource.java` — neuer `GET /persons/parents`-Endpoint + `ParentSummaryDTO`-Record.
- Modify: `backend/src/main/java/at/kigruapp/security/SecurityFilter.java` — Whitelist fuer `/persons/parents` (familien-gescoped) sowie `/closure-definitions`, `/closure-periods`, `/holidays` (GET, alle angemeldeten Nutzer).
- Test: `backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java` — Tests fuer den neuen Endpoint.
- Test: `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java` — Tests fuer die neuen Whitelist-Eintraege.

**Frontend:**
- Modify: `frontend/src/app/shared/models/person.model.ts` — neues `ParentSummaryDTO`-Interface.
- Modify: `frontend/src/app/shared/services/person.service.ts` — neue Methode `listParents(familyId)`.
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.util.ts` — `buildMonths` bekommt optionalen `restrictWeekends`-Parameter.
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.ts` — neue Inputs `mode`, `restrictWeekends`, `initialSelection`; neue Methode `effectivelySelectable`.
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.html` — nutzt `effectivelySelectable(day)` statt `day.selectable`.
- Modify: `frontend/src/app/cooking/cooking.component.ts` — laedt `familyParents` ueber `listParents`, laedt zusaetzlich `closurePeriods`/`closureDefinitions`/`holidays`/`calendarFrom`/`calendarTo` und reicht sie an den Dialog weiter.
- Modify: `frontend/src/app/cooking/cooking-duty-dialog.component.ts` — `CookingDutyDialogData` erweitert, `MatDatepickerModule` raus, `ClosureCalendarComponent` rein, `getParentName`/`onDateSelected` angepasst.
- Modify: `frontend/src/app/cooking/cooking-duty-dialog.component.html` — Datumsfeld wird `<app-closure-calendar mode="single">` statt `MatDatepicker`.
- Test: `frontend/src/app/shared/components/closure-calendar/closure-calendar.util.spec.ts` — Tests fuer `restrictWeekends`.
- Test: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.spec.ts` — Tests fuer `mode: 'single'`.
- Test: `frontend/src/app/cooking/cooking-duty-dialog.component.spec.ts` — Tests fuer Parent-Namen-Anzeige und Datum-Auswahl ueber den neuen Kalender.

---

### Task 1: SecurityFilter — fehlende Whitelist-Eintraege fuer Closure-/Feiertags-GETs

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/security/SecurityFilter.java:117` (nach dem `cooking-reminder-settings`-Eintrag, vor dem `Default: admin-only`-Kommentar)
- Test: `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java`

**Interfaces:**
- Produces: keine neuen Methoden, nur zusaetzliche `isAllowed(...)`-Whitelist-Bedingungen fuer `GET /api/v1/closure-definitions`, `GET /api/v1/closure-periods`, `GET /api/v1/holidays`.

- [x] **Step 1: Failing Tests schreiben**

In `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java`, nach dem letzten Test (`cookingReminderSettingsPut_isForbidden_forNonAdmin`) einfuegen:

```java
    @Test
    void getClosureDefinitions_nonAdmin_allowed() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/closure-definitions", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(new Person());
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    @Test
    void postClosureDefinitions_nonAdmin_forbidden() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/closure-definitions", "POST");
        when(currentUserService.getCurrentPerson()).thenReturn(new Person());
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }

    @Test
    void getClosurePeriods_nonAdmin_allowed() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/closure-periods", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(new Person());
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    @Test
    void postClosurePeriodsApply_nonAdmin_forbidden() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/closure-periods/apply", "POST");
        when(currentUserService.getCurrentPerson()).thenReturn(new Person());
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }

    @Test
    void getHolidays_nonAdmin_allowed() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/holidays", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(new Person());
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }
```

- [x] **Step 2: Tests laufen lassen, sicherstellen dass sie fehlschlagen**

Run: `cd backend && mvnw.cmd test -Dtest=SecurityFilterTest`
Expected: `getClosureDefinitions_nonAdmin_allowed`, `getClosurePeriods_nonAdmin_allowed`, `getHolidays_nonAdmin_allowed` schlagen fehl (FORBIDDEN statt PassThrough); `postClosureDefinitions_nonAdmin_forbidden` und `postClosurePeriodsApply_nonAdmin_forbidden` sind bereits gruen (Default-Deny greift schon).

- [x] **Step 3: Whitelist-Eintraege ergaenzen**

In `backend/src/main/java/at/kigruapp/security/SecurityFilter.java`, nach Zeile 117 (`if (path.equals("/api/v1/cooking-reminder-settings") && "GET".equals(method)) return true;`) einfuegen:

```java
        // Schliesstage: Eltern muessen Arten und Zeitraeume lesen koennen (Schliesstage-Uebersicht,
        // Kochdienst-Dialog). Schreiben bleibt admin-only (Default-Deny).
        if (path.equals("/api/v1/closure-definitions") && "GET".equals(method)) return true;
        if (path.equals("/api/v1/closure-periods") && "GET".equals(method)) return true;
        if (path.equals("/api/v1/holidays") && "GET".equals(method)) return true;
```

- [x] **Step 4: Tests erneut laufen lassen**

Run: `cd backend && mvnw.cmd test -Dtest=SecurityFilterTest`
Expected: alle Tests PASS.

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/security/SecurityFilter.java backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java
git commit -m "fix(be): Closure- und Feiertags-GETs fuer angemeldete Eltern freigeben"
```

---

### Task 2: Backend — `GET /persons/parents` Endpoint

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/PersonResource.java`
- Modify: `backend/src/main/java/at/kigruapp/security/SecurityFilter.java`
- Test: `backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java`
- Test: `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java`

**Interfaces:**
- Consumes: `PersonLookupService.isParent(Person)` (bereits vorhanden, `backend/src/main/java/at/kigruapp/service/PersonLookupService.java:39`), `Person.findByFamilyId(ObjectId)` (bereits vorhanden).
- Produces: `PersonResource.ParentDTO(String id, String firstName, String lastName)` (record), Endpoint `GET /api/v1/persons/parents?familyId=<id>` → `List<ParentDTO>`. Spaeter genutzt von Task 4 (Frontend `PersonService.listParents`).

- [x] **Step 1: Failing Backend-Test schreiben**

In `backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java`, nach `testGroupAssignmentIsolatedPerSemester` (nach Zeile 143) einfuegen:

```java
    @Test
    public void testListParentsFiltersByFamilyAndPersonType() {
        String familyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Testfamilie-Eltern\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        String otherFamilyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Andere-Familie\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        String personTypeDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"personType\", \"label\": {\"de\": \"Typ\"}, \"jsonSchema\": {\"type\": \"string\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String firstNameDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"firstName\", \"label\": {\"de\": \"Vorname\"}, \"jsonSchema\": {\"type\": \"string\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String lastNameDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"lastName\", \"label\": {\"de\": \"Nachname\"}, \"jsonSchema\": {\"type\": \"string\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String parentId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"basicProperties\": ["
                + "{\"definitionId\": \"" + personTypeDefId + "\", \"value\": \"PARENT\"},"
                + "{\"definitionId\": \"" + firstNameDefId + "\", \"value\": \"Anna\"},"
                + "{\"definitionId\": \"" + lastNameDefId + "\", \"value\": \"Muster\"}]}")
            .when().post("/api/v1/persons")
            .then().statusCode(201)
            .extract().path("id");

        String childId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"basicProperties\": ["
                + "{\"definitionId\": \"" + personTypeDefId + "\", \"value\": \"CHILD\"},"
                + "{\"definitionId\": \"" + firstNameDefId + "\", \"value\": \"Ben\"}]}")
            .when().post("/api/v1/persons")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + otherFamilyId + "\", \"basicProperties\": ["
                + "{\"definitionId\": \"" + personTypeDefId + "\", \"value\": \"PARENT\"}]}")
            .when().post("/api/v1/persons")
            .then().statusCode(201);

        given()
            .when().get("/api/v1/persons/parents?familyId=" + familyId)
            .then()
            .statusCode(200)
            .body("size()", is(1))
            .body("[0].id", is(parentId))
            .body("[0].firstName", is("Anna"))
            .body("[0].lastName", is("Muster"))
            .body("find { it.id == '" + childId + "' }", nullValue());
    }
```

- [x] **Step 2: Test laufen lassen, sicherstellen dass er fehlschlaegt**

Run: `cd backend && mvnw.cmd test -Dtest=PersonResourceTest#testListParentsFiltersByFamilyAndPersonType`
Expected: FAIL — 404, da `/persons/parents` noch nicht existiert.

- [x] **Step 3: `ParentDTO`-Record und Endpoint implementieren**

In `backend/src/main/java/at/kigruapp/resource/PersonResource.java`, den `ParentDTO`-Record direkt nach dem bestehenden `ChildDTO`-Record einfuegen (nach Zeile 164):

```java
    public record ParentDTO(
        String id,
        String firstName,
        String lastName
    ) {}
```

Direkt nach der bestehenden `listChildren`-Methode (nach Zeile 396, vor `@PATCH @Path("/{id}/group")`) einfuegen:

```java
    @GET
    @Path("/parents")
    public List<ParentDTO> listParents(@QueryParam("familyId") String familyId) {
        if (familyId == null || familyId.isBlank()) {
            throw new BadRequestException("familyId is required");
        }
        List<Person> familyPersons = Person.findByFamilyId(new ObjectId(familyId));
        List<ParentDTO> result = new ArrayList<>();
        for (Person person : familyPersons) {
            if (!personLookup.isParent(person)) continue;
            result.add(new ParentDTO(
                person.id.toHexString(),
                resolveBasicValue(person, "firstName"),
                resolveBasicValue(person, "lastName")));
        }
        return result;
    }
```

- [x] **Step 4: Test laufen lassen**

Run: `cd backend && mvnw.cmd test -Dtest=PersonResourceTest#testListParentsFiltersByFamilyAndPersonType`
Expected: PASS.

- [x] **Step 5: Failing SecurityFilter-Test fuer den neuen Endpoint schreiben**

In `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java`, nach den in Task 1 hinzugefuegten Tests einfuegen:

```java
    @Test
    void getPersonsParents_ownFamily_allowed() {
        filter.oidcEnabled = true;
        String familyId = "000000000000000000000001";
        givenPath("/api/v1/persons/parents", "GET");
        when(uriInfo.getQueryParameters()).thenReturn(
            new jakarta.ws.rs.core.MultivaluedHashMap<>(java.util.Map.of("familyId", java.util.List.of(familyId))));
        Person person = new Person();
        person.familyId = new ObjectId(familyId);
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    @Test
    void getPersonsParents_otherFamily_forbidden() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/persons/parents", "GET");
        when(uriInfo.getQueryParameters()).thenReturn(
            new jakarta.ws.rs.core.MultivaluedHashMap<>(
                java.util.Map.of("familyId", java.util.List.of("000000000000000000000002"))));
        Person person = new Person();
        person.familyId = new ObjectId("000000000000000000000001");
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }

    @Test
    void getPersonsParents_missingFamilyId_forbidden() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/persons/parents", "GET");
        when(uriInfo.getQueryParameters()).thenReturn(new jakarta.ws.rs.core.MultivaluedHashMap<>());
        Person person = new Person();
        person.familyId = new ObjectId("000000000000000000000001");
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }
```

- [x] **Step 6: Tests laufen lassen, sicherstellen dass sie fehlschlagen**

Run: `cd backend && mvnw.cmd test -Dtest=SecurityFilterTest#getPersonsParents_ownFamily_allowed+getPersonsParents_otherFamily_forbidden+getPersonsParents_missingFamilyId_forbidden`
Expected: `getPersonsParents_ownFamily_allowed` FAILS (403 statt PassThrough), die anderen beiden sind bereits gruen (Default-Deny).

- [x] **Step 7: `isAllowed` um familien-gescopte Pruefung erweitern**

In `backend/src/main/java/at/kigruapp/security/SecurityFilter.java` die Signatur von `isAllowed` anpassen, damit sie auf Query-Parameter zugreifen kann. Zeile 64 aendern von:

```java
        if (!isAllowed(path, method, person)) {
```

zu:

```java
        if (!isAllowed(ctx, path, method, person)) {
```

Zeile 69 aendern von:

```java
    private boolean isAllowed(String path, String method, Person person) {
```

zu:

```java
    private boolean isAllowed(ContainerRequestContext ctx, String path, String method, Person person) {
```

Direkt nach den in Task 1 ergaenzten Closure-/Holiday-Zeilen einfuegen:

```java
        // Wer-kocht-Auswahl im Kochdienst-Dialog: nur die eigene Familie einsehbar.
        if (path.equals("/api/v1/persons/parents") && "GET".equals(method)) {
            return checkOwnFamilyByQueryParam(ctx, person);
        }
```

Am Ende der Klasse, nach `checkFieldInstanceFamily` (nach Zeile 156), die neue Hilfsmethode einfuegen:

```java
    private boolean checkOwnFamilyByQueryParam(ContainerRequestContext ctx, Person person) {
        String familyId = ctx.getUriInfo().getQueryParameters().getFirst("familyId");
        if (familyId == null || !ObjectId.isValid(familyId)) return false;
        return person.familyId != null && person.familyId.equals(new ObjectId(familyId));
    }
```

- [x] **Step 8: Tests laufen lassen**

Run: `cd backend && mvnw.cmd test -Dtest=SecurityFilterTest`
Expected: alle Tests PASS (auch die aus Task 1).

- [x] **Step 9: Vollen Backend-Testlauf pruefen**

Run: `cd backend && mvnw.cmd test`
Expected: keine neuen Fehlschlaege gegenueber dem bekannten Baseline-Stand (siehe `project_broken_baseline`-Notiz: 13 vorbestehende fehlschlagende Tests sind unabhaengig von dieser Aenderung).

- [x] **Step 10: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/PersonResource.java backend/src/main/java/at/kigruapp/security/SecurityFilter.java backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java
git commit -m "feat(be): GET /persons/parents liefert aufgeloeste Elternteile der eigenen Familie"
```

---

### Task 3: Frontend — `PersonService.listParents` und `ParentSummaryDTO`

**Files:**
- Modify: `frontend/src/app/shared/models/person.model.ts`
- Modify: `frontend/src/app/shared/services/person.service.ts`

**Interfaces:**
- Consumes: `ApiService.get<T>(path)` (bestehend, `frontend/src/app/core/services/api.service.ts`).
- Produces: `ParentSummaryDTO { id: string; firstName: string | null; lastName: string | null }`, `PersonService.listParents(familyId: string): Observable<ParentSummaryDTO[]>`. Genutzt von Task 5 (`CookingComponent`) und Task 6 (`CookingDutyDialogComponent`).

Kein eigener Testschritt: reine Service-/Modell-Ergaenzung ohne Verzweigungslogik, wird ueber die Tests aus Task 5/6 (dort wird der Service gemockt bzw. real durchlaufen) und den Backend-Test aus Task 2 abgedeckt. Ein isolierter Unit-Test ohne Verzweigung waere reines Getter-Testing ohne Aussagekraft.

- [x] **Step 1: `ParentSummaryDTO` ergaenzen**

In `frontend/src/app/shared/models/person.model.ts`, nach dem `ChildDTO`-Interface (nach Zeile 62) einfuegen:

```ts
export interface ParentSummaryDTO {
  id: string;
  firstName: string | null;
  lastName: string | null;
}
```

- [x] **Step 2: `listParents` in `PersonService` ergaenzen**

In `frontend/src/app/shared/services/person.service.ts`, Import in Zeile 3 erweitern:

```ts
import { Person, CreatePersonRequest, PersonDTO, ChildDTO, ParentSummaryDTO } from '../models/person.model';
```

Nach der bestehenden `getChildren`-Methode (nach Zeile 27) einfuegen:

```ts
  listParents(familyId: string): Observable<ParentSummaryDTO[]> {
    return this.api.get<ParentSummaryDTO[]>(`/persons/parents?familyId=${familyId}`);
  }
```

- [x] **Step 3: Frontend-Build pruefen**

Run: `cd frontend && npm run build`
Expected: Build erfolgreich, keine neuen TypeScript-Fehler.

- [x] **Step 4: Commit**

```bash
git add frontend/src/app/shared/models/person.model.ts frontend/src/app/shared/services/person.service.ts
git commit -m "feat(fe): PersonService.listParents fuer aufgeloeste Elternteile"
```

---

### Task 4: Frontend — `closure-calendar` bekommt `mode: 'single'` und `restrictWeekends`

**Files:**
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.util.ts`
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.ts`
- Modify: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.html`
- Test: `frontend/src/app/shared/components/closure-calendar/closure-calendar.util.spec.ts`
- Test: `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.spec.ts`

**Interfaces:**
- Consumes: nichts Neues von aussen.
- Produces: `buildMonths(from, to, periods, definitions, holidays, restrictWeekends = true)`, `ClosureCalendarComponent` Inputs `mode: 'range' | 'single' = 'range'`, `restrictWeekends = true`, `initialSelection: string[] = []`, Methode `effectivelySelectable(day: CalendarDay): boolean`. Genutzt von Task 6 (`CookingDutyDialogComponent`-Template).

- [x] **Step 1: Failing Util-Tests schreiben**

In `frontend/src/app/shared/components/closure-calendar/closure-calendar.util.spec.ts`, im `describe('buildMonths', ...)`-Block nach dem Test `'markiert Wochenenden als nicht auswaehlbar'` einfuegen:

```ts
    it('erlaubt Wochenenden als auswaehlbar, wenn restrictWeekends=false', () => {
      const months = buildMonths('2026-09-07', '2026-09-13', [], [], [], false);

      expect(findDay(months, '2026-09-12').selectable).toBe(true);
      expect(findDay(months, '2026-09-13').selectable).toBe(true);
    });

    it('blockiert Feiertage weiterhin, auch wenn restrictWeekends=false', () => {
      const holiday: Holiday = { date: '2026-09-08', name: 'Test-Feiertag' };
      const months = buildMonths('2026-09-07', '2026-09-09', [], [], [holiday], false);

      expect(findDay(months, '2026-09-08').selectable).toBe(false);
    });
```

`Holiday` muss importiert werden — Zeile 2 erweitern:

```ts
import { ClosureDefinition, ClosurePeriod, Holiday } from '../../models/closure.model';
```

- [x] **Step 2: Tests laufen lassen, sicherstellen dass sie fehlschlagen**

Run: `cd frontend && npx ng test --include='**/closure-calendar.util.spec.ts' --watch=false`
Expected: FAIL — `buildMonths` akzeptiert noch keinen sechsten Parameter (TS-Fehler) bzw. `selectable` ist bei Wochenendtagen `false`.

- [x] **Step 3: `buildMonths` um `restrictWeekends` erweitern**

In `frontend/src/app/shared/components/closure-calendar/closure-calendar.util.ts`, Signatur (Zeile 54-60) aendern zu:

```ts
export function buildMonths(
  from: string,
  to: string,
  periods: ClosurePeriod[],
  definitions: ClosureDefinition[],
  holidays: Holiday[],
  restrictWeekends: boolean = true,
): CalendarMonth[] {
```

Zeile 112 aendern von:

```ts
      selectable: !isWeekend(iso) && holidayName === null,
```

zu:

```ts
      selectable: (!restrictWeekends || !isWeekend(iso)) && holidayName === null,
```

- [x] **Step 4: Tests laufen lassen**

Run: `cd frontend && npx ng test --include='**/closure-calendar.util.spec.ts' --watch=false`
Expected: PASS, alle bisherigen Tests in dieser Datei bleiben ebenfalls gruen.

- [x] **Step 5: Failing Komponenten-Tests schreiben**

In `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.spec.ts`, am Ende der Datei, vor dem letzten schliessenden `});` der aeussersten `describe`, einen neuen Block einfuegen:

```ts
  describe('mode: single', () => {
    beforeEach(() => {
      component.mode = 'single';
      component.restrictWeekends = false;
      fixture.detectChanges();
    });

    it('ersetzt die Auswahl bei jedem Klick statt sie zu erweitern', () => {
      const emitted: string[][] = [];
      component.selectionChange.subscribe(days => emitted.push(days));

      press('2026-09-08');
      press('2026-09-10');

      expect(emitted).toEqual([['2026-09-08'], ['2026-09-10']]);
      expect(component.selectedDays).toEqual(['2026-09-10']);
    });

    it('ignoriert Ziehen (kein Range-Select)', () => {
      const emitted: string[][] = [];
      component.selectionChange.subscribe(days => emitted.push(days));

      press('2026-09-07');
      moveOver('2026-09-09');

      expect(component.selectedDays).toEqual(['2026-09-07']);
      expect(emitted).toEqual([['2026-09-07']]);
    });

    it('erlaubt die Auswahl von Wochenendtagen, wenn restrictWeekends=false', () => {
      press('2026-09-12');

      expect(component.selectedDays).toEqual(['2026-09-12']);
    });

    it('blockiert Tage, die einer Schliessperiode zugeordnet sind', () => {
      component.periods = [{ id: 'p1', from: '2026-09-08', to: '2026-09-08', definitionId: 'def-ferien' }];
      component.ngOnChanges();
      fixture.detectChanges();

      const emitted: string[][] = [];
      component.selectionChange.subscribe(days => emitted.push(days));

      press('2026-09-08');

      expect(emitted.length).toBe(0);
      expect(component.selectedDays).toEqual([]);
    });

    it('zeigt eine initiale Auswahl aus initialSelection', () => {
      component.initialSelection = ['2026-09-09'];
      component.ngOnChanges();
      fixture.detectChanges();

      expect(component.selectedDays).toEqual(['2026-09-09']);
      expect(component.isSelected(findDayFixture('2026-09-09'))).toBe(true);
    });
  });

  function findDayFixture(iso: string) {
    return component.months.flatMap(m => m.days).find(d => d.date === iso)!;
  }
```

- [x] **Step 6: Tests laufen lassen, sicherstellen dass sie fehlschlagen**

Run: `cd frontend && npx ng test --include='**/closure-calendar.component.spec.ts' --watch=false`
Expected: FAIL — `mode`, `restrictWeekends`, `initialSelection` existieren noch nicht als Inputs; Verhalten entspricht noch dem `range`-Default.

- [x] **Step 7: Component um `mode`, `restrictWeekends`, `initialSelection` und `effectivelySelectable` erweitern**

In `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.ts`:

Nach `@Input() layout: 'stacked' | 'row' = 'stacked';` (Zeile 30) einfuegen:

```ts
  /** 'single': ein Klick ersetzt die Auswahl (kein Ziehen), genutzt fuer Tagesauswahl (z. B. Kochdienst). */
  @Input() mode: 'range' | 'single' = 'range';
  /** false erlaubt die Auswahl von Wochenendtagen — nur relevant im 'single'-Modus. */
  @Input() restrictWeekends = true;
  /** Vorbelegte Auswahl im 'single'-Modus, z. B. beim Bearbeiten eines bestehenden Eintrags. */
  @Input() initialSelection: string[] = [];
```

`rebuild()` (Zeile 58-61) aendern von:

```ts
  private rebuild(): void {
    this.months = buildMonths(this.from, this.to, this.periods, this.definitions, this.holidays);
    this.clearSelection();
  }
```

zu:

```ts
  private rebuild(): void {
    this.months = buildMonths(
      this.from, this.to, this.periods, this.definitions, this.holidays, this.restrictWeekends);
    const seed = this.mode === 'single' ? this.initialSelection : [];
    this.selected = new Set(seed);
    this.base = new Set(seed);
    this.selectedDays = [...seed].sort();
  }
```

`onDayMouseDown` (Zeile 67-80) aendern von:

```ts
  onDayMouseDown(day: CalendarDay, event: MouseEvent): void {
    if (this.readonly || !day.selectable) {
      return;
    }
    event.preventDefault();
    const additive = event.ctrlKey || event.metaKey;
    // Mit STRG bleibt Bestehendes erhalten; ohne STRG ersetzt die Ziehung alles.
    this.base = new Set(additive ? this.selected : []);
    // Beginnt die Ziehung mit STRG auf einem markierten Tag, nimmt sie weg.
    this.removing = additive && this.selected.has(day.date);
    this.anchor = day.date;
    this.dragging = true;
    this.applyRange(day.date);
  }
```

zu:

```ts
  onDayMouseDown(day: CalendarDay, event: MouseEvent): void {
    if (this.readonly || !this.effectivelySelectable(day)) {
      return;
    }
    event.preventDefault();

    if (this.mode === 'single') {
      this.selected = new Set([day.date]);
      this.base = new Set(this.selected);
      this.selectedDays = [day.date];
      this.selectionChange.emit(this.selectedDays);
      return;
    }

    const additive = event.ctrlKey || event.metaKey;
    // Mit STRG bleibt Bestehendes erhalten; ohne STRG ersetzt die Ziehung alles.
    this.base = new Set(additive ? this.selected : []);
    // Beginnt die Ziehung mit STRG auf einem markierten Tag, nimmt sie weg.
    this.removing = additive && this.selected.has(day.date);
    this.anchor = day.date;
    this.dragging = true;
    this.applyRange(day.date);
  }
```

Nach `tooltip(day)` (nach Zeile 147) eine neue Methode einfuegen:

```ts
  /** 'single': Tage innerhalb einer Schliessperiode sind sichtbar, aber nicht anklickbar. */
  effectivelySelectable(day: CalendarDay): boolean {
    if (this.mode === 'single' && day.colors.length > 0) {
      return false;
    }
    return day.selectable;
  }
```

- [x] **Step 8: Template um `effectivelySelectable` erweitern**

In `frontend/src/app/shared/components/closure-calendar/closure-calendar.component.html`, Zeile 25 aendern von:

```html
                [class.selectable]="day.selectable && !readonly"
```

zu:

```html
                [class.selectable]="effectivelySelectable(day) && !readonly"
```

- [x] **Step 9: Tests laufen lassen**

Run: `cd frontend && npx ng test --include='**/closure-calendar.component.spec.ts' --include='**/closure-calendar.util.spec.ts' --watch=false`
Expected: alle Tests PASS, inklusive aller bestehenden (Range-Modus unveraendert).

- [x] **Step 10: Commit**

```bash
git add frontend/src/app/shared/components/closure-calendar
git commit -m "feat(fe): closure-calendar unterstuetzt Einzeltag-Auswahl (mode: single)"
```

---

### Task 5: Frontend — `CookingComponent` laedt Parents + Closure-Daten fuer den Dialog

**Files:**
- Modify: `frontend/src/app/cooking/cooking.component.ts`

**Interfaces:**
- Consumes: `PersonService.listParents(familyId)` (Task 3), `ClosureDefinitionService.getAll()` (bestehend), `ClosurePeriodService.getRange(from, to)` (bestehend), `HolidayService.getRange(from, to)` (bestehend).
- Produces: erweiterte `CookingDutyDialogData` beim Oeffnen des Dialogs (`closurePeriods`, `closureDefinitions`, `holidays`, `calendarFrom`, `calendarTo`, `familyParents: ParentSummaryDTO[]`). Genutzt von Task 6.

Kein isolierter Unit-Test fuer diese Ladeorchestrierung — es gibt keine bestehende `cooking.component.spec.ts` und der Verkabelungscode besteht nur aus Service-Aufrufen ohne eigene Verzweigungslogik; die Wirkung wird end-to-end durch den manuellen Smoke-Test in Task 7 sowie die Dialog-Tests in Task 6 abgedeckt.

- [x] **Step 1: Imports und Felder anpassen**

In `frontend/src/app/cooking/cooking.component.ts`:

Zeile 23 aendern von:

```ts
import { PersonDTO, SectionInput } from '../shared/models/person.model';
```

zu:

```ts
import { ParentSummaryDTO, SectionInput } from '../shared/models/person.model';
```

Import fuer `ClosureDefinitionService` und Closure-Modelle ergaenzen (nach Zeile 12, `import { HolidayService } from '../shared/services/holiday.service';`):

```ts
import { ClosureDefinitionService } from '../shared/services/closure-definition.service';
import { ClosureDefinition, ClosurePeriod, Holiday } from '../shared/models/closure.model';
```

Zeile 53 aendern von:

```ts
  familyParents: PersonDTO[] = [];
```

zu:

```ts
  familyParents: ParentSummaryDTO[] = [];
```

Nach `closedDates = new Set<string>();` (Zeile 76) ergaenzen:

```ts
  closurePeriods: ClosurePeriod[] = [];
  closureDefinitions: ClosureDefinition[] = [];
  holidays: Holiday[] = [];
  calendarFrom = '';
  calendarTo = '';
```

Konstruktor (Zeile 62-73) um `ClosureDefinitionService` erweitern:

```ts
  constructor(
    private orgService: OrganisationService,
    private cookingDutyService: CookingDutyService,
    private personService: PersonService,
    private fieldInstanceService: FieldInstanceService,
    private currentUserService: CurrentUserService,
    private dialog: MatDialog,
    private closurePeriodService: ClosurePeriodService,
    private closureDefinitionService: ClosureDefinitionService,
    private holidayService: HolidayService,
    private cookingReminderSettingsService: CookingReminderSettingsService,
    private fieldDefinitionService: FieldDefinitionService,
  ) {}
```

- [x] **Step 2: `loadClosedDates` um Definitions/Periods/Holidays-Speicherung erweitern**

Zeilen 92-105 aendern von:

```ts
  private loadClosedDates(): void {
    const year = this.viewDate.getFullYear();
    const month = this.viewDate.getMonth();
    const from = toIsoDate(new Date(year, month, 1));
    const to = toIsoDate(new Date(year, month + 1, 0));

    forkJoin({
      periods: this.closurePeriodService.getRange(from, to),
      holidays: this.holidayService.getRange(from, to),
    }).subscribe(result => {
      this.closedDates = closedDatesFrom(result.periods, result.holidays);
      this.refresh.next();
    });
  }
```

zu:

```ts
  private loadClosedDates(): void {
    const year = this.viewDate.getFullYear();
    const month = this.viewDate.getMonth();
    const from = toIsoDate(new Date(year, month, 1));
    const to = toIsoDate(new Date(year, month + 1, 0));
    this.calendarFrom = from;
    this.calendarTo = to;

    forkJoin({
      periods: this.closurePeriodService.getRange(from, to),
      holidays: this.holidayService.getRange(from, to),
      definitions: this.closureDefinitionService.getAll(),
    }).subscribe(result => {
      this.closedDates = closedDatesFrom(result.periods, result.holidays);
      this.closurePeriods = result.periods;
      this.holidays = result.holidays;
      this.closureDefinitions = result.definitions;
      this.refresh.next();
    });
  }
```

- [x] **Step 3: `loadOrganisationData` auf `listParents` umstellen**

Zeilen 136-141 aendern von:

```ts
    const familyId = this.currentUserService.currentFamilyId;
    if (familyId) {
      this.personService.list(familyId).subscribe((persons) => {
        this.familyParents = persons.filter(p => !!p.id) as unknown as PersonDTO[];
      });
    }
```

zu:

```ts
    const familyId = this.currentUserService.currentFamilyId;
    if (familyId) {
      this.personService.listParents(familyId).subscribe((parents) => {
        this.familyParents = parents;
      });
    }
```

- [x] **Step 4: `openDialog` um die neuen Felder erweitern**

Zeilen 234-243 aendern von:

```ts
    const data: CookingDutyDialogData = {
      groups: this.groups,
      foodProperties: this.foodProperties,
      familyParents: this.familyParents,
      currentUserId: this.currentPersonId,
      existingDuty,
      canEdit,
      closedDates: [...this.closedDates],
      reminderAvailable: this.reminderAvailable,
    };
```

zu:

```ts
    const data: CookingDutyDialogData = {
      groups: this.groups,
      foodProperties: this.foodProperties,
      familyParents: this.familyParents,
      currentUserId: this.currentPersonId,
      existingDuty,
      canEdit,
      reminderAvailable: this.reminderAvailable,
      closurePeriods: this.closurePeriods,
      closureDefinitions: this.closureDefinitions,
      holidays: this.holidays,
      calendarFrom: this.calendarFrom,
      calendarTo: this.calendarTo,
    };
```

(Das bisherige Feld `closedDates` entfaellt hier — es wird in Task 6 auch aus `CookingDutyDialogData` entfernt, da die Datumsauswahl jetzt ueber `closurePeriods`/`closureDefinitions`/`holidays` direkt im Dialog berechnet wird.)

- [x] **Step 5: Build pruefen**

Run: `cd frontend && npm run build`
Expected: schlaegt zu diesem Zeitpunkt fehl, weil `CookingDutyDialogData` das Feld `closedDates` noch verlangt bzw. die neuen Felder noch nicht kennt — das wird in Task 6 behoben. Dieser Schritt dient nur der Kontrolle, dass keine anderen unerwarteten Fehler auftreten (z. B. Tippfehler). Notiere die erwartete Fehlermeldung (fehlende/unbekannte Properties in `CookingDutyDialogData`) und fahre mit Task 6 fort, bevor der Build erneut gepruft wird.

- [x] **Step 6: Commit**

```bash
git add frontend/src/app/cooking/cooking.component.ts
git commit -m "feat(fe): CookingComponent laedt aufgeloeste Eltern und Closure-Daten fuer den Dialog"
```

(Hinweis: Dieser Commit kompiliert erst zusammen mit Task 6 fehlerfrei — beide Tasks werden im selben Arbeitsgang unmittelbar nacheinander ausgefuehrt, bevor der Build final geprueft wird.)

---

### Task 6: Frontend — `CookingDutyDialogComponent` nutzt `ParentSummaryDTO` und `closure-calendar`

**Files:**
- Modify: `frontend/src/app/cooking/cooking-duty-dialog.component.ts`
- Modify: `frontend/src/app/cooking/cooking-duty-dialog.component.html`
- Test: `frontend/src/app/cooking/cooking-duty-dialog.component.spec.ts`

**Interfaces:**
- Consumes: `ClosureCalendarComponent` mit `mode: 'single'`, `restrictWeekends`, `initialSelection`, `selectionChange` (Task 4); `ParentSummaryDTO` (Task 3); `CookingComponent`s erweiterte `CookingDutyDialogData` (Task 5).
- Produces: unveraendertes `CookingDutyDialogResult` (keine Breaking Changes fuer `CookingComponent.createCookingDuty`/`updateCookingDuty`).

- [x] **Step 1: Failing Tests schreiben**

In `frontend/src/app/cooking/cooking-duty-dialog.component.spec.ts`, `baseData` (Zeile 14-21) erweitern:

```ts
  const baseData: CookingDutyDialogData = {
    groups: [{ id: 'g1', fieldName: 'group', label: { de: 'Gruppe 1' }, jsonSchema: {}, required: false }],
    foodProperties: [],
    familyParents: [{ id: 'p1', firstName: 'Anna', lastName: 'Muster' }],
    currentUserId: 'p1',
    canEdit: true,
    reminderAvailable: true,
    closurePeriods: [],
    closureDefinitions: [],
    holidays: [],
    calendarFrom: '2026-09-01',
    calendarTo: '2026-09-30',
  };
```

Am Ende der Datei, vor dem letzten `});`, einen neuen `describe`-Block einfuegen:

```ts
  describe('Wer kocht', () => {
    it('zeigt Vor- und Nachname der Familienmitglieder an', async () => {
      await createComponent(baseData);
      fixture.detectChanges();

      const option = fixture.nativeElement.textContent as string;
      expect(component.getParentName(baseData.familyParents[0])).toBe('Muster Anna');
      expect(option).toContain('Muster Anna');
    });

    it('zeigt keine Namen an, wenn firstName/lastName fehlen', async () => {
      await createComponent({ ...baseData, familyParents: [{ id: 'p2', firstName: null, lastName: null }] });

      expect(component.getParentName({ id: 'p2', firstName: null, lastName: null })).toBe('');
    });
  });

  describe('Datumsauswahl ueber closure-calendar', () => {
    it('setzt das Datum-Formularfeld bei Auswahl im Kalender', async () => {
      await createComponent(baseData);

      component.onDateSelected(['2026-09-15']);

      expect(component.form.value.date).toEqual(new Date('2026-09-15T00:00:00'));
    });

    it('leert das Datum-Formularfeld, wenn die Auswahl aufgehoben wird', async () => {
      await createComponent(baseData);
      component.onDateSelected(['2026-09-15']);

      component.onDateSelected([]);

      expect(component.form.value.date).toBeNull();
    });

    it('setzt die initiale Kalenderauswahl beim Bearbeiten auf das bestehende Datum', async () => {
      await createComponent({
        ...baseData,
        existingDuty: {
          id: 'd1', personId: 'p1', familyId: 'f1', personName: 'Anna',
          date: '2026-09-15', groups: [], description: '', foodProperties: {},
          reminderEnabled: false, reminderDaysBefore: null,
        },
      });

      expect(component.initialDateSelection).toEqual(['2026-09-15']);
    });

    it('hat keine initiale Kalenderauswahl beim Neuanlegen', async () => {
      await createComponent(baseData);

      expect(component.initialDateSelection).toEqual([]);
    });
  });
```

- [x] **Step 2: Tests laufen lassen, sicherstellen dass sie fehlschlagen**

Run: `cd frontend && npx ng test --include='**/cooking-duty-dialog.component.spec.ts' --watch=false`
Expected: FAIL — Compile-Fehler, da `CookingDutyDialogData` die neuen Felder noch nicht kennt, `getParentName` noch `PersonDTO` erwartet, `onDateSelected`/`initialDateSelection` noch nicht existieren.

- [x] **Step 3: `CookingDutyDialogData`, Imports und Klassenfelder anpassen**

In `frontend/src/app/cooking/cooking-duty-dialog.component.ts`:

Imports (Zeilen 1-15) aendern zu:

```ts
import { Component, Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FieldDefinition } from '../shared/models/field-definition.model';
import { CookingDutyDTO } from '../shared/models/organisation.model';
import { ParentSummaryDTO } from '../shared/models/person.model';
import { ClosureDefinition, ClosurePeriod, Holiday } from '../shared/models/closure.model';
import { ClosureCalendarComponent } from '../shared/components/closure-calendar/closure-calendar.component';
```

(`MatDatepickerModule`-Import und `toIsoDate`-Import aus `cooking-closure.util` entfallen — beide werden nicht mehr benoetigt, siehe Step 4/5.)

`CookingDutyDialogData` (Zeilen 17-30) aendern zu:

```ts
export interface CookingDutyDialogData {
  groups: FieldDefinition[];
  foodProperties: FieldDefinition[];
  familyParents: ParentSummaryDTO[];
  currentUserId: string;
  existingDuty?: CookingDutyDTO;
  canEdit: boolean;
  reminderAvailable: boolean;
  closurePeriods?: ClosurePeriod[];
  closureDefinitions?: ClosureDefinition[];
  holidays?: Holiday[];
  calendarFrom?: string;
  calendarTo?: string;
}
```

`@Component`-Decorator `imports` (Zeilen 46-51) aendern zu:

```ts
  imports: [
    CommonModule, ReactiveFormsModule,
    MatDialogModule, MatFormFieldModule, MatInputModule,
    MatCheckboxModule, MatSelectModule, MatButtonModule, MatIconModule,
    ClosureCalendarComponent,
  ],
```

- [x] **Step 4: `dateFilter`/`closedDates`-Feld durch Closure-Calendar-Felder ersetzen**

Zeilen 56-80 (Klassenkoerper von `form!` bis Ende Konstruktor) aendern von:

```ts
export class CookingDutyDialogComponent implements OnInit {
  form!: FormGroup;
  isEdit: boolean;
  canEdit: boolean;

  private closedDates = new Set<string>();

  /**
   * Als Property gebunden, damit `this` im Datepicker-Filter erhalten bleibt.
   * null ist zulaessig, sonst liesse sich das Feld nicht leeren.
   */
  dateFilter = (date: Date | null): boolean =>
    date === null || !this.closedDates.has(toIsoDate(date));

  /** Klartextdatum der Erinnerung, null solange Datum oder Vorlaufzeit fehlen. */
  reminderDate: string | null = null;
  reminderInPast = false;

  constructor(
    private dialogRef: MatDialogRef<CookingDutyDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: CookingDutyDialogData,
  ) {
    this.isEdit = !!data.existingDuty;
    this.canEdit = data.canEdit;
    this.closedDates = new Set(data.closedDates ?? []);
  }
```

zu:

```ts
export class CookingDutyDialogComponent implements OnInit {
  form!: FormGroup;
  isEdit: boolean;
  canEdit: boolean;

  closurePeriods: ClosurePeriod[];
  closureDefinitions: ClosureDefinition[];
  holidays: Holiday[];
  calendarFrom: string;
  calendarTo: string;
  /** Vorbelegte Kalenderauswahl beim Bearbeiten eines bestehenden Kochdienstes. */
  initialDateSelection: string[] = [];

  /** Klartextdatum der Erinnerung, null solange Datum oder Vorlaufzeit fehlen. */
  reminderDate: string | null = null;
  reminderInPast = false;

  constructor(
    private dialogRef: MatDialogRef<CookingDutyDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: CookingDutyDialogData,
  ) {
    this.isEdit = !!data.existingDuty;
    this.canEdit = data.canEdit;
    this.closurePeriods = data.closurePeriods ?? [];
    this.closureDefinitions = data.closureDefinitions ?? [];
    this.holidays = data.holidays ?? [];
    this.calendarFrom = data.calendarFrom ?? '';
    this.calendarTo = data.calendarTo ?? '';
  }
```

- [x] **Step 5: `ngOnInit` um `initialDateSelection` ergaenzen, `onDateSelected` und `getParentName` anpassen**

`ngOnInit` (Zeile 82-83) direkt nach `const duty = this.data.existingDuty;` ergaenzen:

```ts
    this.initialDateSelection = duty ? [duty.date] : [];
```

`getParentName`/`getFieldValue` (Zeilen 159-168) aendern von:

```ts
  getParentName(parent: PersonDTO): string {
    const lastName = this.getFieldValue(parent, 'lastName');
    const firstName = this.getFieldValue(parent, 'firstName');
    return `${lastName} ${firstName}`.trim();
  }

  private getFieldValue(person: PersonDTO, fieldName: string): string {
    const field = person.basicProperties?.find((f) => f.fieldName === fieldName);
    return (field?.value as string) ?? '';
  }
```

zu:

```ts
  getParentName(parent: ParentSummaryDTO): string {
    return `${parent.lastName ?? ''} ${parent.firstName ?? ''}`.trim();
  }

  onDateSelected(dates: string[]): void {
    const iso = dates[0] ?? null;
    this.form.get('date')?.setValue(iso ? new Date(`${iso}T00:00:00`) : null);
  }
```

- [x] **Step 6: Template anpassen**

In `frontend/src/app/cooking/cooking-duty-dialog.component.html`, Zeilen 5-11 aendern von:

```html
    <mat-form-field appearance="outline" class="full-width">
      <mat-label>Wann</mat-label>
      <input matInput [matDatepicker]="picker" formControlName="date"
             [matDatepickerFilter]="dateFilter">
      <mat-datepicker-toggle matIconSuffix [for]="picker"></mat-datepicker-toggle>
      <mat-datepicker #picker></mat-datepicker>
    </mat-form-field>
```

zu:

```html
    <div class="date-field">
      <label class="group-label">Wann *</label>
      <app-closure-calendar
        mode="single"
        [restrictWeekends]="false"
        [from]="calendarFrom"
        [to]="calendarTo"
        [periods]="closurePeriods"
        [definitions]="closureDefinitions"
        [holidays]="holidays"
        [initialSelection]="initialDateSelection"
        (selectionChange)="onDateSelected($event)">
      </app-closure-calendar>
    </div>
```

- [x] **Step 7: Tests laufen lassen**

Run: `cd frontend && npx ng test --include='**/cooking-duty-dialog.component.spec.ts' --watch=false`
Expected: PASS — inklusive aller bestehenden Erinnerungs-Tests (die den `date`-FormControl weiterhin direkt per `patchValue` setzen, unabhaengig vom Kalender).

- [x] **Step 8: Frontend-Build und vollen Testlauf pruefen**

Run: `cd frontend && npm run build && npx ng test --watch=false`
Expected: Build erfolgreich; keine neuen Testfehlschlaege gegenueber dem bekannten Baseline-Stand (siehe `project_broken_baseline`-Notiz: 1 vorbestehender fehlschlagender Frontend-Test ist unabhaengig von dieser Aenderung).

- [x] **Step 9: Commit**

```bash
git add frontend/src/app/cooking/cooking-duty-dialog.component.ts frontend/src/app/cooking/cooking-duty-dialog.component.html frontend/src/app/cooking/cooking-duty-dialog.component.spec.ts frontend/src/app/cooking/cooking.component.ts
git commit -m "feat(fe): Kochdienst-Dialog nutzt closure-calendar und aufgeloeste Elternnamen"
```

---

### Task 7: Manueller Smoke-Test

**Files:** keine Code-Aenderungen — Verifikation im Browser.

- [ ] **Step 1: Backend und Frontend lokal starten**

Run: `cd backend && mvnw.cmd quarkus:dev` (separates Terminal) und `cd frontend && npm start`

- [ ] **Step 2: Als Elternteil einloggen und Kochdienst-Kalender oeffnen**

Pruefen:
- "Neuen Kochdienst eintragen" oeffnen → "Wer kocht"-Dropdown zeigt die echten Elternteile der eigenen Familie mit Vor- und Nachnamen (nicht leer).
- Mehrere Gruppen ueber die Checkboxen auswaehlbar (bereits vorhanden, nur gegenpruefen).
- Im Datumsfeld: Tage mit Schliesszeitraum sind farblich markiert und beim Hovern erscheint ein Tooltip mit der Schliesstage-Art; ein Klick darauf waehlt das Datum nicht aus.
- Ein gueltiger Tag laesst sich anklicken, das Formular wird speicherbar (Button "Erstellen" aktiv).
- Nach dem Speichern erscheint der neue Eintrag automatisch im Kalender, ohne manuelles Neuladen.
- Einen bestehenden eigenen Kochdienst zum Bearbeiten oeffnen → das Datumsfeld zeigt den bisherigen Tag bereits als ausgewaehlt an.

- [ ] **Step 3: Ergebnis dokumentieren**

Bei Abweichungen: Root Cause klaeren (siehe `superpowers:systematic-debugging`) bevor der Plan als abgeschlossen gilt. Bei Erfolg: Nutzer informieren, dass der manuelle Smoke-Test durchgefuehrt und bestanden wurde.

---

## Self-Review Notes

- **Spec-Abdeckung:** Design-Abschnitt A → Task 2/3/5/6. Design-Abschnitt B → Task 1 (Security-Voraussetzung) + Task 4/5/6. "Out of Scope"-Punkte (Gruppen-Multi-Select, Auto-Refresh, serverseitige Sperre) bleiben unangetastet, keine Tasks dafuer noetig.
- **Platzhalter-Scan:** keine TBD/TODO; jeder Code-Block ist vollstaendig ausformuliert.
- **Typkonsistenz:** `ParentDTO` (Backend-Record, `PersonResource.java`) ↔ `ParentSummaryDTO` (Frontend-Interface, `person.model.ts`) tragen bewusst unterschiedliche Namen (Backend-Konvention nutzt unqualifizierte `XyzDTO`-Records pro Resource, siehe `ChildDTO`; Frontend nutzt `ParentSummaryDTO` um Verwechslung mit dem bestehenden `PersonDTO` zu vermeiden) — Felder (`id`, `firstName`, `lastName`) sind identisch. `getParentName`/`onDateSelected`/`initialDateSelection`/`effectivelySelectable` werden konsistent ueber alle Tasks hinweg gleich benannt.
- **Reihenfolge-Abhaengigkeit:** Task 5 und Task 6 aendern beide `CookingDutyDialogData`-Konsumenten und muessen im selben Arbeitsgang abgeschlossen werden, bevor der Gesamt-Build gruen ist (in Task 5 Step 5 und Task 6 Step 8 vermerkt).
