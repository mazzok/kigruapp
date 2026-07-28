# Stundenerfassung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eltern erfassen ihre geleisteten Freiwilligen-Stunden selbst (Rolle des aktiven Semesters oder „Kochen", Datum, Dauer HH:MM, Kommentar); Admins sehen pro Elternteil summierte Übersichten und können Einträge korrigieren/löschen.

**Architecture:** Neue MongoDB-Collection `hourEntries`. Ein `HourEntryResource` (Quarkus) mit eltern-zugänglichen CRUD-Endpunkten (whitelisted im `SecurityFilter`) und einem admin-only `/summary`. Frontend: ein Master-Detail-Bereich für Eltern (`stunden`, Vorbild `mail-template-editor`) und eine Admin-Übersicht (`stundenuebersicht`). Rollen-Optionen stammen aus `semester_assignments` (`section="role"`) plus fixer „Kochen"-Option.

**Tech Stack:** Backend Quarkus + MongoDB Panache (Package `at.kigruapp`), JUnit + RestAssured. Frontend Angular (standalone components) + Angular Material, Jasmine/Karma.

## Global Constraints

- Backend-Package: `at.kigruapp`. MongoDB via Panache (`PanacheMongoEntity`, `@MongoEntity`).
- Aktuelle Person: `CurrentUserService.getCurrentPerson()`; Admin-Check: `CurrentUserService.isAdmin()`.
- „Aktives Semester" = neuestes `Semester` nach `createdAt` (Muster `PersonResource.resolveSemesterId`).
- Rollen-Zuweisungen liegen in Collection **`semester_assignments`** (snake_case), Dokumente wie `SemesterAssignment` (`personId, semesterId, section, definitionId, fieldInstanceId`). Rolle: `section="role"`.
- Rollen-Label steckt in `field_instances`: `value` ist ein Dokument mit Schlüssel `label`.
- „Kochen" = fixe Zusatzoption für **alle** Eltern, gespeichert als `roleFieldInstanceId=null`, `roleLabel="Kochen"`.
- `date` wird als String `YYYY-MM-DD` gespeichert; Dauer als `int minutes`.
- Alle UI-Texte auf Deutsch. Feedback über `NotificationService` (MatSnackBar): `success/error/extractError`.
- Nicht-Admin-Zugriff muss explizit im `SecurityFilter` whitelisted werden (default-deny). `/summary` und die Sammel-`GET`-Liste bleiben admin-only (nicht whitelisten).
- Commit-Nachrichten: `feat:` / `test:` Prefix, deutsche Kurzbeschreibung. **Nicht committen/pushen ohne ausdrückliche Freigabe des Users** — die Commit-Schritte unten ausführen, aber vor `git push` stoppen.

---

## File Structure

**Backend (neu, sofern nicht anders vermerkt):**
- `backend/src/main/java/at/kigruapp/entity/HourEntry.java` — Panache-Entity, Collection `hourEntries`.
- `backend/src/main/java/at/kigruapp/dto/HourEntryDto.java` — Read-View.
- `backend/src/main/java/at/kigruapp/dto/HourEntrySaveDto.java` — Write-Payload.
- `backend/src/main/java/at/kigruapp/dto/RoleOptionDto.java` — Dropdown-Option.
- `backend/src/main/java/at/kigruapp/dto/HourSummaryDto.java` — Admin-Summe pro Person.
- `backend/src/main/java/at/kigruapp/resource/HourEntryResource.java` — REST-Resource.
- `backend/src/main/java/at/kigruapp/security/SecurityFilter.java` — **modify**: Whitelist-Regeln ergänzen.
- `backend/src/test/java/at/kigruapp/resource/HourEntryResourceTest.java` — Resource-Tests.

**Frontend (neu, sofern nicht anders vermerkt):**
- `frontend/src/app/shared/models/hour-entry.model.ts` — Interfaces.
- `frontend/src/app/shared/services/hour-entry.service.ts` — HTTP-Service.
- `frontend/src/app/shared/util/time-format.util.ts` — HH:MM- und ISO-Datum-Helfer.
- `frontend/src/app/shared/util/time-format.util.spec.ts` — Util-Tests.
- `frontend/src/app/stunden/stunden.component.{ts,html,scss}` + `.spec.ts` — Eltern-Bereich.
- `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.{ts,html,scss}` + `.spec.ts` — Admin-Übersicht.
- `frontend/src/app/app.routes.ts` — **modify**: zwei Routen.
- `frontend/src/app/app.component.html` — **modify**: zwei Nav-Links.

---

## Task 1: Backend — Entity, DTOs, Anlegen & eigene Liste

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/HourEntry.java`
- Create: `backend/src/main/java/at/kigruapp/dto/HourEntryDto.java`
- Create: `backend/src/main/java/at/kigruapp/dto/HourEntrySaveDto.java`
- Create: `backend/src/main/java/at/kigruapp/resource/HourEntryResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/HourEntryResourceTest.java`

**Interfaces:**
- Produces (für spätere Tasks):
  - Entity `HourEntry extends PanacheMongoEntity` mit Feldern: `ObjectId personId, semesterId, roleFieldInstanceId, roleDefinitionId; String roleLabel, date, comment; int minutes; Instant createdAt, updatedAt`.
  - `HourEntryDto` mit `String id, personId, semesterId, roleFieldInstanceId, roleLabel, date, comment; int minutes`.
  - `HourEntrySaveDto` mit `String roleFieldInstanceId /*null=Kochen*/, date, comment; int minutes`.
  - `HourEntryResource`: `List<HourEntryDto> listMine()` (GET `/api/v1/hour-entries/me`), `Response create(HourEntrySaveDto)` (POST `/api/v1/hour-entries`).
  - Helfer im Resource, die Task 2–4 wiederverwenden: `ObjectId resolveSemesterId(String)`, `ObjectId requireSemesterId(String)`, `List<RoleOptionDto> resolveRoleOptions(ObjectId personId, ObjectId semesterId)` (kommt in Task 2), `HourEntryDto toDto(HourEntry)`.

- [ ] **Step 1: Entity anlegen**

Create `backend/src/main/java/at/kigruapp/entity/HourEntry.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;

@MongoEntity(collection = "hourEntries")
public class HourEntry extends PanacheMongoEntity {
    public ObjectId personId;
    public ObjectId semesterId;
    /** null bedeutet die fixe Tätigkeit "Kochen". */
    public ObjectId roleFieldInstanceId;
    public ObjectId roleDefinitionId;
    /** Snapshot des Rollen-Labels zum Erfassungszeitpunkt; "Kochen" für den Koch-Fall. */
    public String roleLabel;
    /** Tätigkeitsdatum als YYYY-MM-DD. */
    public String date;
    public int minutes;
    public String comment;
    public Instant createdAt;
    public Instant updatedAt;
}
```

- [ ] **Step 2: DTOs anlegen**

Create `backend/src/main/java/at/kigruapp/dto/HourEntryDto.java`:

```java
package at.kigruapp.dto;

/** Read-View eines Stunden-Eintrags. */
public class HourEntryDto {
    public String id;
    public String personId;
    public String semesterId;
    public String roleFieldInstanceId; // null = Kochen
    public String roleLabel;
    public String date;      // YYYY-MM-DD
    public int minutes;
    public String comment;
}
```

Create `backend/src/main/java/at/kigruapp/dto/HourEntrySaveDto.java`:

```java
package at.kigruapp.dto;

/** Write-Payload. Das Rollen-Label wird serverseitig aus roleFieldInstanceId abgeleitet. */
public class HourEntrySaveDto {
    public String roleFieldInstanceId; // null = Kochen
    public String date;                // YYYY-MM-DD
    public int minutes;
    public String comment;
}
```

- [ ] **Step 3: Failing test schreiben**

Create `backend/src/test/java/at/kigruapp/resource/HourEntryResourceTest.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class HourEntryResourceTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        HourEntry.deleteAll();
        Person.deleteAll();
        Semester.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments").drop();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();
    }

    /** Genau eine (nicht-Admin) Person anlegen -> wird im Dev-Mode zur "aktuellen" Person. */
    private Person persistCurrentPerson() {
        Person p = new Person();
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p;
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
    void createStoresEntryForCurrentPersonAndActiveSemester() {
        persistCurrentPerson();
        String semesterId = persistSemester();

        given()
            .contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-05\",\"minutes\":90,\"comment\":\"Suppe gekocht\"}")
            .when().post("/api/v1/hour-entries")
            .then().statusCode(201)
            .body("roleLabel", is("Kochen"))
            .body("roleFieldInstanceId", nullValue())
            .body("date", is("2026-10-05"))
            .body("minutes", is(90))
            .body("semesterId", is(semesterId));
    }

    @Test
    void listMeReturnsOwnEntriesNewestFirst() {
        persistCurrentPerson();
        persistSemester();

        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-01\",\"minutes\":60,\"comment\":\"\"}")
            .when().post("/api/v1/hour-entries").then().statusCode(201);
        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-09\",\"minutes\":30,\"comment\":\"\"}")
            .when().post("/api/v1/hour-entries").then().statusCode(201);

        given()
            .when().get("/api/v1/hour-entries/me")
            .then().statusCode(200)
            .body("size()", is(2))
            .body("[0].date", is("2026-10-09"))
            .body("[1].date", is("2026-10-01"));
    }

    @Test
    void createRejectsZeroMinutes() {
        persistCurrentPerson();
        persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-05\",\"minutes\":0,\"comment\":\"\"}")
            .when().post("/api/v1/hour-entries")
            .then().statusCode(400);
    }

    @Test
    void createRejectsMalformedDate() {
        persistCurrentPerson();
        persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"05.10.2026\",\"minutes\":30,\"comment\":\"\"}")
            .when().post("/api/v1/hour-entries")
            .then().statusCode(400);
    }
}
```

- [ ] **Step 4: Test ausführen — muss fehlschlagen**

Run: `cd backend && ./mvnw test -Dtest=HourEntryResourceTest`
Expected: FAIL — `HourEntryResource` bzw. Endpunkt existiert noch nicht (404 statt 201/400, oder Kompilierfehler).

- [ ] **Step 5: Resource implementieren (Anlegen + eigene Liste)**

Create `backend/src/main/java/at/kigruapp/resource/HourEntryResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.dto.HourEntryDto;
import at.kigruapp.dto.HourEntrySaveDto;
import at.kigruapp.dto.RoleOptionDto;
import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.Semester;
import at.kigruapp.security.CurrentUserService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Stundenerfassung. /me, /role-options, POST, PUT, DELETE sind für alle
 * angemeldeten Eltern zugänglich (im SecurityFilter whitelisted); PUT/DELETE
 * erzwingen Eigentümer-oder-Admin hier im Resource. GET / und /summary sind
 * nicht whitelisted und damit admin-only.
 */
@Path("/api/v1/hour-entries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class HourEntryResource {

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final String COOKING_LABEL = "Kochen";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    CurrentUserService currentUserService;

    private MongoCollection<Document> semesterAssignments() {
        return mongoClient.getDatabase(databaseName).getCollection("semester_assignments");
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private ObjectId resolveSemesterId(String semesterIdParam) {
        if (semesterIdParam != null && !semesterIdParam.isBlank()) {
            return new ObjectId(semesterIdParam);
        }
        List<Semester> latest = Semester.listAll(Sort.descending("createdAt"));
        return latest.isEmpty() ? null : latest.get(0).id;
    }

    private ObjectId requireSemesterId(String semesterIdParam) {
        ObjectId semesterId = resolveSemesterId(semesterIdParam);
        if (semesterId == null) {
            throw new BadRequestException("Kein Semester vorhanden");
        }
        return semesterId;
    }

    private Person requireCurrentPerson() {
        Person p = currentUserService.getCurrentPerson();
        if (p == null) {
            throw new ForbiddenException();
        }
        return p;
    }

    @GET
    @Path("/me")
    public List<HourEntryDto> listMine() {
        Person me = requireCurrentPerson();
        return HourEntry.<HourEntry>find("personId", Sort.descending("date", "createdAt"), me.id)
                .list().stream().map(HourEntryResource::toDto).toList();
    }

    @POST
    public Response create(HourEntrySaveDto in) {
        Person me = requireCurrentPerson();
        ObjectId semesterId = requireSemesterId(null);
        validatePayload(in);

        RoleOptionDto role = resolveRole(me.id, semesterId, in.roleFieldInstanceId);

        HourEntry entry = new HourEntry();
        entry.personId = me.id;
        entry.semesterId = semesterId;
        entry.roleFieldInstanceId = role.fieldInstanceId == null ? null : new ObjectId(role.fieldInstanceId);
        entry.roleDefinitionId = role.definitionId == null ? null : new ObjectId(role.definitionId);
        entry.roleLabel = role.label;
        entry.date = in.date;
        entry.minutes = in.minutes;
        entry.comment = in.comment == null ? "" : in.comment;
        entry.createdAt = Instant.now();
        entry.updatedAt = entry.createdAt;
        entry.persist();
        return Response.status(201).entity(toDto(entry)).build();
    }

    private void validatePayload(HourEntrySaveDto in) {
        if (in.date == null || !ISO_DATE.matcher(in.date).matches()) {
            throw new BadRequestException("date muss im Format YYYY-MM-DD vorliegen");
        }
        if (in.minutes <= 0) {
            throw new BadRequestException("minutes muss größer als 0 sein");
        }
    }

    /** Leitet Label/Definition aus der gewählten Rolle des Semesters ab; null = Kochen. */
    private RoleOptionDto resolveRole(ObjectId personId, ObjectId semesterId, String roleFieldInstanceId) {
        if (roleFieldInstanceId == null || roleFieldInstanceId.isBlank()) {
            return cookingOption();
        }
        for (RoleOptionDto opt : resolveRoleOptions(personId, semesterId)) {
            if (roleFieldInstanceId.equals(opt.fieldInstanceId)) {
                return opt;
            }
        }
        throw new BadRequestException("Rolle ist der Person im aktiven Semester nicht zugewiesen");
    }

    private RoleOptionDto cookingOption() {
        RoleOptionDto dto = new RoleOptionDto();
        dto.fieldInstanceId = null;
        dto.definitionId = null;
        dto.label = COOKING_LABEL;
        return dto;
    }

    // resolveRoleOptions(...) wird in Task 2 ergänzt.
    List<RoleOptionDto> resolveRoleOptions(ObjectId personId, ObjectId semesterId) {
        return new ArrayList<>(List.of(cookingOption()));
    }

    static HourEntryDto toDto(HourEntry e) {
        HourEntryDto dto = new HourEntryDto();
        dto.id = e.id.toHexString();
        dto.personId = e.personId == null ? null : e.personId.toHexString();
        dto.semesterId = e.semesterId == null ? null : e.semesterId.toHexString();
        dto.roleFieldInstanceId = e.roleFieldInstanceId == null ? null : e.roleFieldInstanceId.toHexString();
        dto.roleLabel = e.roleLabel;
        dto.date = e.date;
        dto.minutes = e.minutes;
        dto.comment = e.comment;
        return dto;
    }
}
```

Also create `backend/src/main/java/at/kigruapp/dto/RoleOptionDto.java` (von `resolveRole`/Task 2 gebraucht):

```java
package at.kigruapp.dto;

/** Eine Dropdown-Option: eine Semester-Rolle oder die fixe Tätigkeit "Kochen". */
public class RoleOptionDto {
    public String fieldInstanceId; // null = Kochen
    public String definitionId;    // null = Kochen
    public String label;
}
```

- [ ] **Step 6: Test ausführen — muss bestehen**

Run: `cd backend && ./mvnw test -Dtest=HourEntryResourceTest`
Expected: PASS (4 Tests grün).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/HourEntry.java \
        backend/src/main/java/at/kigruapp/dto/HourEntryDto.java \
        backend/src/main/java/at/kigruapp/dto/HourEntrySaveDto.java \
        backend/src/main/java/at/kigruapp/dto/RoleOptionDto.java \
        backend/src/main/java/at/kigruapp/resource/HourEntryResource.java \
        backend/src/test/java/at/kigruapp/resource/HourEntryResourceTest.java
git commit -m "feat: Stundenerfassung — Entity, Anlegen und eigene Liste"
```

---

## Task 2: Backend — Rollen-Optionen (`GET /role-options`)

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/HourEntryResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/HourEntryResourceTest.java` (Methode ergänzen)

**Interfaces:**
- Consumes: `RoleOptionDto`, `resolveSemesterId`, `requireCurrentPerson` aus Task 1.
- Produces: `List<RoleOptionDto> roleOptions(String semesterId)` (GET `/api/v1/hour-entries/role-options`) und die vollständige `resolveRoleOptions(ObjectId personId, ObjectId semesterId)`-Implementierung (ersetzt den Task-1-Stub), die von Task 1 (`resolveRole`) genutzt wird.

- [ ] **Step 1: Failing test ergänzen**

In `HourEntryResourceTest` folgende Helfer + Test ergänzen:

```java
    /** Legt eine Rollen-Zuweisung (section="role") inkl. field_instance mit Label an. */
    private String assignRole(ObjectId personId, String semesterId, String label) {
        ObjectId defId = new ObjectId();
        ObjectId instId = new ObjectId();
        fieldInstancesForTest().insertOne(new Document("_id", instId)
                .append("definitionId", defId)
                .append("value", new Document("label", label)));
        semesterAssignmentsForTest().insertOne(new Document("_id", new ObjectId())
                .append("personId", personId)
                .append("semesterId", new ObjectId(semesterId))
                .append("section", "role")
                .append("definitionId", defId)
                .append("fieldInstanceId", instId));
        return instId.toHexString();
    }

    private com.mongodb.client.MongoCollection<Document> semesterAssignmentsForTest() {
        return mongoClient.getDatabase(databaseName).getCollection("semester_assignments");
    }

    private com.mongodb.client.MongoCollection<Document> fieldInstancesForTest() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    @Test
    void roleOptionsReturnsAssignedRolesPlusKochen() {
        Person me = persistCurrentPerson();
        String semesterId = persistSemester();
        assignRole(me.id, semesterId, "Gartenteam");

        given()
            .when().get("/api/v1/hour-entries/role-options")
            .then().statusCode(200)
            .body("label", hasItem("Gartenteam"))
            .body("label", hasItem("Kochen"));
    }
```

Ergänze die Import-Zeile für `org.bson.Document` am Dateikopf, falls noch nicht vorhanden:
`import org.bson.Document;`

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd backend && ./mvnw test -Dtest=HourEntryResourceTest#roleOptionsReturnsAssignedRolesPlusKochen`
Expected: FAIL — Endpunkt `/role-options` liefert 404 (existiert noch nicht).

- [ ] **Step 3: Endpoint + resolveRoleOptions implementieren**

In `HourEntryResource` den Task-1-Stub `resolveRoleOptions` durch die echte Implementierung ersetzen und den GET-Endpunkt ergänzen:

```java
    @GET
    @Path("/role-options")
    public List<RoleOptionDto> roleOptions(@QueryParam("semesterId") String semesterIdParam) {
        Person me = requireCurrentPerson();
        ObjectId semesterId = resolveSemesterId(semesterIdParam);
        if (semesterId == null) {
            return List.of(cookingOption());
        }
        return resolveRoleOptions(me.id, semesterId);
    }

    /** Zugewiesene Rollen (section="role") des Semesters + fixe "Kochen"-Option. */
    List<RoleOptionDto> resolveRoleOptions(ObjectId personId, ObjectId semesterId) {
        List<RoleOptionDto> options = new ArrayList<>();
        Document filter = new Document("personId", personId)
                .append("semesterId", semesterId)
                .append("section", "role");
        for (Document assignment : semesterAssignments().find(filter)) {
            ObjectId instId = assignment.getObjectId("fieldInstanceId");
            ObjectId defId = assignment.getObjectId("definitionId");
            RoleOptionDto opt = new RoleOptionDto();
            opt.fieldInstanceId = instId == null ? null : instId.toHexString();
            opt.definitionId = defId == null ? null : defId.toHexString();
            opt.label = resolveInstanceLabel(instId);
            options.add(opt);
        }
        options.add(cookingOption());
        return options;
    }

    /** Liest field_instances.value.label; Fallback auf value.toString() bzw. leeren String. */
    private String resolveInstanceLabel(ObjectId instanceId) {
        if (instanceId == null) return "";
        Document inst = fieldInstances().find(Filters.eq("_id", instanceId)).first();
        if (inst == null) return "";
        Object value = inst.get("value");
        if (value instanceof Document valueDoc) {
            String label = valueDoc.getString("label");
            return label != null ? label : "";
        }
        return value == null ? "" : value.toString();
    }
```

Den vorhandenen Stub `resolveRoleOptions(...)` aus Task 1 dabei entfernen (nur eine Implementierung darf existieren).

- [ ] **Step 4: Test ausführen — muss bestehen**

Run: `cd backend && ./mvnw test -Dtest=HourEntryResourceTest`
Expected: PASS (alle bisherigen + neuer Test grün).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/HourEntryResource.java \
        backend/src/test/java/at/kigruapp/resource/HourEntryResourceTest.java
git commit -m "feat: Stundenerfassung — Rollen-Optionen des Semesters + Kochen"
```

---

## Task 3: Backend — Bearbeiten & Löschen mit Eigentümer-/Admin-Prüfung

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/HourEntryResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/HourEntryResourceTest.java` (Methoden ergänzen)

**Interfaces:**
- Consumes: `HourEntrySaveDto`, `validatePayload`, `resolveRole`, `requireCurrentPerson`, `toDto`, `resolveRoleOptions` (Task 1/2).
- Produces: `HourEntryDto update(String id, HourEntrySaveDto)` (PUT `/api/v1/hour-entries/{id}`) und `Response delete(String id)` (DELETE `/api/v1/hour-entries/{id}`). Autorisierung: Eigentümer (`entry.personId == current.id`) **oder** `isAdmin()`, sonst 403. Beim Update bleibt `semesterId` unverändert; ändert sich `roleFieldInstanceId` nicht, bleibt das gespeicherte `roleLabel` erhalten (ermöglicht Admin-Korrekturen von Datum/Zeit/Kommentar ohne Zugriff auf fremde Rollen-Optionen).

- [ ] **Step 1: Failing tests ergänzen**

In `HourEntryResourceTest` ergänzen:

```java
    private HourEntry persistEntry(ObjectId personId, String semesterId, String date, int minutes) {
        HourEntry e = new HourEntry();
        e.personId = personId;
        e.semesterId = new ObjectId(semesterId);
        e.roleFieldInstanceId = null;
        e.roleLabel = "Kochen";
        e.date = date;
        e.minutes = minutes;
        e.comment = "";
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.persist();
        return e;
    }

    @Test
    void ownerCanUpdateOwnEntry() {
        Person me = persistCurrentPerson();
        String semesterId = persistSemester();
        HourEntry e = persistEntry(me.id, semesterId, "2026-10-05", 60);

        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-06\",\"minutes\":120,\"comment\":\"korrigiert\"}")
            .when().put("/api/v1/hour-entries/" + e.id)
            .then().statusCode(200)
            .body("minutes", is(120))
            .body("date", is("2026-10-06"))
            .body("comment", is("korrigiert"));
    }

    @Test
    void ownerCanDeleteOwnEntry() {
        Person me = persistCurrentPerson();
        String semesterId = persistSemester();
        HourEntry e = persistEntry(me.id, semesterId, "2026-10-05", 60);

        given().when().delete("/api/v1/hour-entries/" + e.id).then().statusCode(204);
        given().when().get("/api/v1/hour-entries/me").then().statusCode(200).body("size()", is(0));
    }

    @Test
    void nonOwnerNonAdminCannotUpdateForeignEntry() {
        persistCurrentPerson();              // aktuelle (nicht-Admin) Person
        String semesterId = persistSemester();
        HourEntry foreign = persistEntry(new ObjectId(), semesterId, "2026-10-05", 60); // anderer personId

        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-06\",\"minutes\":120,\"comment\":\"x\"}")
            .when().put("/api/v1/hour-entries/" + foreign.id)
            .then().statusCode(403);
    }

    @Test
    void nonOwnerNonAdminCannotDeleteForeignEntry() {
        persistCurrentPerson();
        String semesterId = persistSemester();
        HourEntry foreign = persistEntry(new ObjectId(), semesterId, "2026-10-05", 60);

        given().when().delete("/api/v1/hour-entries/" + foreign.id).then().statusCode(403);
    }

    @Test
    void adminCanUpdateForeignEntry() {
        // Admin-Person: roles verweist auf field_instance mit value "ADMIN".
        ObjectId adminInst = new ObjectId();
        fieldInstancesForTest().insertOne(new Document("_id", adminInst).append("value", "ADMIN"));
        Person admin = new Person();
        admin.roles = new java.util.ArrayList<>();
        admin.roles.add(new at.kigruapp.entity.FieldRef(new ObjectId(), adminInst));
        admin.createdAt = Instant.now();
        admin.updatedAt = admin.createdAt;
        admin.persist(); // einzige Person mit ADMIN-Rolle -> current user = admin

        String semesterId = persistSemester();
        HourEntry foreign = persistEntry(new ObjectId(), semesterId, "2026-10-05", 60);

        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-07\",\"minutes\":45,\"comment\":\"admin-fix\"}")
            .when().put("/api/v1/hour-entries/" + foreign.id)
            .then().statusCode(200)
            .body("minutes", is(45))
            .body("comment", is("admin-fix"));
    }
```

> Hinweis zum Dev-Mode: `getCurrentPerson()` liefert die erste Person mit ADMIN-Rolle, sonst die erste Person überhaupt. Deshalb macht `nonOwner*`-Test **keine** Admin-Person (current = die eine nicht-Admin-Person), und `adminCanUpdateForeignEntry` legt genau eine Admin-Person an.

- [ ] **Step 2: Tests ausführen — müssen fehlschlagen**

Run: `cd backend && ./mvnw test -Dtest=HourEntryResourceTest`
Expected: FAIL — PUT/DELETE liefern 404 (Endpunkte fehlen).

- [ ] **Step 3: PUT/DELETE implementieren**

In `HourEntryResource` ergänzen:

```java
    @PUT
    @Path("/{id}")
    public HourEntryDto update(@PathParam("id") String id, HourEntrySaveDto in) {
        Person me = requireCurrentPerson();
        HourEntry entry = HourEntry.findById(new ObjectId(id));
        if (entry == null) {
            throw new NotFoundException();
        }
        requireOwnerOrAdmin(entry, me);
        validatePayload(in);

        boolean roleUnchanged = java.util.Objects.equals(
                entry.roleFieldInstanceId == null ? null : entry.roleFieldInstanceId.toHexString(),
                in.roleFieldInstanceId == null || in.roleFieldInstanceId.isBlank() ? null : in.roleFieldInstanceId);
        if (!roleUnchanged) {
            RoleOptionDto role = resolveRole(entry.personId, entry.semesterId, in.roleFieldInstanceId);
            entry.roleFieldInstanceId = role.fieldInstanceId == null ? null : new ObjectId(role.fieldInstanceId);
            entry.roleDefinitionId = role.definitionId == null ? null : new ObjectId(role.definitionId);
            entry.roleLabel = role.label;
        }
        entry.date = in.date;
        entry.minutes = in.minutes;
        entry.comment = in.comment == null ? "" : in.comment;
        entry.updatedAt = Instant.now();
        entry.update();
        return toDto(entry);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        Person me = requireCurrentPerson();
        HourEntry entry = HourEntry.findById(new ObjectId(id));
        if (entry == null) {
            throw new NotFoundException();
        }
        requireOwnerOrAdmin(entry, me);
        entry.delete();
        return Response.noContent().build();
    }

    private void requireOwnerOrAdmin(HourEntry entry, Person me) {
        boolean owner = entry.personId != null && entry.personId.equals(me.id);
        if (!owner && !currentUserService.isAdmin()) {
            throw new ForbiddenException();
        }
    }
```

- [ ] **Step 4: Tests ausführen — müssen bestehen**

Run: `cd backend && ./mvnw test -Dtest=HourEntryResourceTest`
Expected: PASS (alle Tests grün).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/HourEntryResource.java \
        backend/src/test/java/at/kigruapp/resource/HourEntryResourceTest.java
git commit -m "feat: Stundenerfassung — Bearbeiten/Löschen mit Eigentümer-/Admin-Prüfung"
```

---

## Task 4: Backend — Admin-Summenübersicht (`GET /summary`)

**Files:**
- Create: `backend/src/main/java/at/kigruapp/dto/HourSummaryDto.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/HourEntryResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/HourEntryResourceTest.java` (Methode ergänzen)

**Interfaces:**
- Consumes: `HourEntryDto`, `toDto`, `requireSemesterId`, `resolveInstanceLabel` (Task 1/2).
- Produces: `HourSummaryDto` mit `String personId, name; int totalMinutes; List<HourEntryDto> entries`. Endpoint `List<HourSummaryDto> summary(String semesterId)` (GET `/api/v1/hour-entries/summary`), gruppiert alle Einträge des Semesters pro Person, `totalMinutes` = Summe. Nicht im SecurityFilter whitelisted → admin-only.

- [ ] **Step 1: DTO anlegen**

Create `backend/src/main/java/at/kigruapp/dto/HourSummaryDto.java`:

```java
package at.kigruapp.dto;

import java.util.List;

/** Admin-Übersicht: Summe und Einzeleinträge eines Elternteils in einem Semester. */
public class HourSummaryDto {
    public String personId;
    public String name;
    public int totalMinutes;
    public List<HourEntryDto> entries;
}
```

- [ ] **Step 2: Failing test ergänzen**

In `HourEntryResourceTest` ergänzen:

```java
    @Test
    void summaryAggregatesMinutesPerPerson() {
        String semesterId = persistSemester();
        ObjectId personA = new ObjectId();
        ObjectId personB = new ObjectId();
        persistEntry(personA, semesterId, "2026-10-01", 60);
        persistEntry(personA, semesterId, "2026-10-02", 30);
        persistEntry(personB, semesterId, "2026-10-03", 45);

        given()
            .when().get("/api/v1/hour-entries/summary?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("size()", is(2))
            .body("find { it.personId == '" + personA.toHexString() + "' }.totalMinutes", is(90))
            .body("find { it.personId == '" + personB.toHexString() + "' }.totalMinutes", is(45));
    }
```

- [ ] **Step 3: Test ausführen — muss fehlschlagen**

Run: `cd backend && ./mvnw test -Dtest=HourEntryResourceTest#summaryAggregatesMinutesPerPerson`
Expected: FAIL — `/summary` liefert 404.

- [ ] **Step 4: Endpoint + Namensauflösung implementieren**

In `HourEntryResource` ergänzen (Imports: `at.kigruapp.dto.HourSummaryDto`, `at.kigruapp.entity.FieldDefinition`, `at.kigruapp.entity.FieldRef`, `java.util.LinkedHashMap`, `java.util.Map`):

```java
    @GET
    @Path("/summary")
    public List<HourSummaryDto> summary(@QueryParam("semesterId") String semesterIdParam) {
        ObjectId semesterId = requireSemesterId(semesterIdParam);
        List<HourEntry> entries = HourEntry.<HourEntry>find(
                "semesterId", Sort.descending("date", "createdAt"), semesterId).list();

        Map<ObjectId, HourSummaryDto> byPerson = new LinkedHashMap<>();
        for (HourEntry e : entries) {
            HourSummaryDto summary = byPerson.computeIfAbsent(e.personId, pid -> {
                HourSummaryDto dto = new HourSummaryDto();
                dto.personId = pid == null ? null : pid.toHexString();
                dto.name = resolvePersonName(pid);
                dto.totalMinutes = 0;
                dto.entries = new ArrayList<>();
                return dto;
            });
            summary.totalMinutes += e.minutes;
            summary.entries.add(toDto(e));
        }
        return new ArrayList<>(byPerson.values());
    }

    private String resolvePersonName(ObjectId personId) {
        if (personId == null) return "";
        Person p = Person.findById(personId);
        if (p == null) return personId.toHexString();
        String first = resolveBasicProperty(p, "firstName");
        String last = resolveBasicProperty(p, "lastName");
        String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return name.isEmpty() ? personId.toHexString() : name;
    }

    private String resolveBasicProperty(Person p, String fieldName) {
        if (p.basicProperties == null) return null;
        FieldDefinition def = FieldDefinition.find("fieldName", fieldName).firstResult();
        if (def == null) return null;
        for (FieldRef ref : p.basicProperties) {
            if (def.id.equals(ref.definitionId)) {
                Document inst = fieldInstances().find(Filters.eq("_id", ref.fieldInstanceId)).first();
                if (inst != null) {
                    Object v = inst.get("value");
                    return v == null ? null : v.toString();
                }
            }
        }
        return null;
    }
```

- [ ] **Step 5: Test ausführen — muss bestehen**

Run: `cd backend && ./mvnw test -Dtest=HourEntryResourceTest`
Expected: PASS (alle Tests grün).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/HourSummaryDto.java \
        backend/src/main/java/at/kigruapp/resource/HourEntryResource.java \
        backend/src/test/java/at/kigruapp/resource/HourEntryResourceTest.java
git commit -m "feat: Stundenerfassung — Admin-Summenübersicht pro Elternteil"
```

---

## Task 5: Backend — SecurityFilter-Whitelist für Eltern-Endpunkte

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/security/SecurityFilter.java`
- Test: `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java` (Methode ergänzen)

**Interfaces:**
- Consumes: bestehende `isAllowed(path, method, person)`- und `isWriteMethod(method)`-Logik.
- Produces: Nicht-Admins dürfen `GET /api/v1/hour-entries/me`, `GET /api/v1/hour-entries/role-options`, `POST /api/v1/hour-entries`, sowie `PUT`/`DELETE /api/v1/hour-entries/{id}` (Ownership prüft das Resource). `GET /api/v1/hour-entries` und `GET /api/v1/hour-entries/summary` bleiben admin-only.

- [ ] **Step 1: Failing tests ergänzen**

`SecurityFilterTest` ist ein reiner Mockito-Unit-Test (Helfer `givenPath`, `assertPassThrough`, `assertForbidden` sind bereits vorhanden). **Wichtig:** `filter.oidcEnabled` muss pro Test auf `true` gesetzt werden, sonst greift der frühe Dev-Mode-Return und der Test wird bedeutungslos (das ist genau die Ursache der bekannten roten Baseline-Fälle — nur die `mail-accounts`-Tests setzen `oidcEnabled = true`). Nach dem `mailAccounts_admin_allowed`-Test folgende Methoden ergänzen:

```java
    // Stundenerfassung: role-options für Nicht-Admin erlaubt.
    @Test
    void hourEntriesRoleOptions_nonAdmin_allowed() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/hour-entries/role-options", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    // Stundenerfassung: eigene Liste für Nicht-Admin erlaubt.
    @Test
    void hourEntriesMe_nonAdmin_allowed() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/hour-entries/me", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    // Stundenerfassung: Anlegen für Nicht-Admin erlaubt.
    @Test
    void hourEntriesCreate_nonAdmin_allowed() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/hour-entries", "POST");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    // Stundenerfassung: PUT eines Eintrags für Nicht-Admin erlaubt (Resource prüft Eigentümer).
    @Test
    void hourEntriesUpdate_nonAdmin_allowedByFilter() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/hour-entries/000000000000000000000000", "PUT");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    // Stundenerfassung: Sammel-GET bleibt admin-only.
    @Test
    void hourEntriesList_nonAdmin_returns403() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/hour-entries", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }

    // Stundenerfassung: /summary bleibt admin-only.
    @Test
    void hourEntriesSummary_nonAdmin_returns403() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/hour-entries/summary", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }
```

- [ ] **Step 2: Tests ausführen — müssen (teils) fehlschlagen**

Run: `cd backend && ./mvnw test -Dtest=SecurityFilterTest`
Expected: Die `*_allowed`-Fälle für `role-options`/`me`/POST/PUT schlagen fehl (aktuell Default-Deny → `abortWith` wird aufgerufen). Die beiden `returns403`-Fälle sind bereits grün (Default-Deny greift schon). Bekannte rote Baseline-Fälle ignorieren.

- [ ] **Step 3: Whitelist-Regeln ergänzen**

In `SecurityFilter.isAllowed(...)` **vor** der `return false;`-Zeile (Default-Deny) ergänzen:

```java
        // Stundenerfassung: eigene Einträge und Rollen-Optionen für alle Eltern.
        if (path.equals("/api/v1/hour-entries/me") && "GET".equals(method)) return true;
        if (path.equals("/api/v1/hour-entries/role-options") && "GET".equals(method)) return true;
        if (path.equals("/api/v1/hour-entries") && "POST".equals(method)) return true;
        // PUT/DELETE eines konkreten Eintrags: Filter lässt angemeldete Eltern durch,
        // die Eigentümer-/Admin-Prüfung erfolgt in HourEntryResource.
        if (path.matches("/api/v1/hour-entries/[^/]+") && isWriteMethod(method)) return true;
        // Hinweis: GET /api/v1/hour-entries und /summary sind NICHT whitelisted -> admin-only.
```

> Reihenfolge beachten: `/me`, `/role-options`, `/summary` sind GET und werden von `isWriteMethod` nicht erfasst; die `[^/]+`-Regel greift nur für PUT/DELETE auf `/{id}` und trifft `/me` bzw. `/summary` nicht (GET). `GET /summary` fällt damit in den Default-Deny und bleibt admin-only.

- [ ] **Step 4: Tests ausführen — die neuen müssen bestehen**

Run: `cd backend && ./mvnw test -Dtest=SecurityFilterTest`
Expected: Alle sechs neuen `hourEntries*`-Fälle grün. (Bekannte rote Baseline-Fälle bleiben unverändert rot — nicht durch diese Aufgabe verursacht.)

- [ ] **Step 5: Voller Backend-Testlauf**

Run: `cd backend && ./mvnw test`
Expected: Neue Tests grün; keine zusätzlichen Regressionen gegenüber der bekannten roten Baseline (`SecurityFilterTest` hat vorbestehende, unabhängige Fehlschläge — nur den neuen Fall bewerten).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/security/SecurityFilter.java \
        backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java
git commit -m "feat: Stundenerfassung — SecurityFilter-Whitelist für Eltern-Endpunkte"
```

---

## Task 6: Frontend — Model, Service und Zeit-/Datum-Utility

**Files:**
- Create: `frontend/src/app/shared/models/hour-entry.model.ts`
- Create: `frontend/src/app/shared/services/hour-entry.service.ts`
- Create: `frontend/src/app/shared/util/time-format.util.ts`
- Test: `frontend/src/app/shared/util/time-format.util.spec.ts`

**Interfaces:**
- Produces:
  - `HourEntry { id; personId; semesterId; roleFieldInstanceId: string | null; roleLabel: string; date: string; minutes: number; comment: string }`
  - `RoleOption { fieldInstanceId: string | null; definitionId: string | null; label: string }`
  - `SaveHourEntryRequest { roleFieldInstanceId: string | null; date: string; minutes: number; comment: string }`
  - `HourSummary { personId: string; name: string; totalMinutes: number; entries: HourEntry[] }`
  - `HourEntryService` mit `listMine(): Observable<HourEntry[]>`, `roleOptions(semesterId?: string): Observable<RoleOption[]>`, `create(req): Observable<HourEntry>`, `update(id, req): Observable<HourEntry>`, `delete(id): Observable<void>`, `summary(semesterId): Observable<HourSummary[]>`
  - Utils: `parseHhmm(text: string): number | null`, `formatMinutes(total: number): string`, `toIsoDate(d: Date): string`, `parseIsoDate(iso: string): Date | null`, `formatIsoDateDe(iso: string): string`

- [ ] **Step 1: Utility-Test schreiben**

Create `frontend/src/app/shared/util/time-format.util.spec.ts`:

```ts
import { parseHhmm, formatMinutes, toIsoDate, parseIsoDate, formatIsoDateDe } from './time-format.util';

describe('time-format.util', () => {
  it('parses HH:MM to total minutes', () => {
    expect(parseHhmm('01:30')).toBe(90);
    expect(parseHhmm('0:45')).toBe(45);
    expect(parseHhmm('10:00')).toBe(600);
  });

  it('rejects invalid HH:MM', () => {
    expect(parseHhmm('1:60')).toBeNull();
    expect(parseHhmm('abc')).toBeNull();
    expect(parseHhmm('')).toBeNull();
    expect(parseHhmm('00:00')).toBeNull(); // Dauer 0 ist ungültig
  });

  it('formats minutes back to HH:MM', () => {
    expect(formatMinutes(90)).toBe('01:30');
    expect(formatMinutes(600)).toBe('10:00');
  });

  it('converts Date to YYYY-MM-DD (local, no TZ shift)', () => {
    expect(toIsoDate(new Date(2026, 9, 5))).toBe('2026-10-05');
  });

  it('parses YYYY-MM-DD to a local Date', () => {
    const d = parseIsoDate('2026-10-05')!;
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(9);
    expect(d.getDate()).toBe(5);
  });

  it('formats YYYY-MM-DD as DD.MM.YYYY', () => {
    expect(formatIsoDateDe('2026-10-05')).toBe('05.10.2026');
  });
});
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd frontend && npm test -- --include='**/time-format.util.spec.ts' --watch=false`
Expected: FAIL — Modul/Funktionen existieren noch nicht.

- [ ] **Step 3: Utility implementieren**

Create `frontend/src/app/shared/util/time-format.util.ts`:

```ts
/** Parst "HH:MM" (Minuten 00–59, Dauer > 0) in Gesamtminuten; sonst null. */
export function parseHhmm(text: string): number | null {
  const m = /^(\d{1,2}):([0-5]\d)$/.exec((text ?? '').trim());
  if (!m) {
    return null;
  }
  const total = parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
  return total > 0 ? total : null;
}

/** Formatiert Gesamtminuten als "HH:MM". */
export function formatMinutes(total: number): string {
  const h = Math.floor(total / 60);
  const min = total % 60;
  return `${String(h).padStart(2, '0')}:${String(min).padStart(2, '0')}`;
}

/** Date -> "YYYY-MM-DD" in lokaler Zeit (kein UTC-Versatz). */
export function toIsoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/** "YYYY-MM-DD" -> lokales Date; null bei ungültigem Format. */
export function parseIsoDate(iso: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso ?? '');
  if (!m) {
    return null;
  }
  return new Date(parseInt(m[1], 10), parseInt(m[2], 10) - 1, parseInt(m[3], 10));
}

/** "YYYY-MM-DD" -> "DD.MM.YYYY"; gibt die Eingabe unverändert zurück, wenn sie nicht passt. */
export function formatIsoDateDe(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso ?? '');
  return m ? `${m[3]}.${m[2]}.${m[1]}` : iso;
}
```

- [ ] **Step 4: Test ausführen — muss bestehen**

Run: `cd frontend && npm test -- --include='**/time-format.util.spec.ts' --watch=false`
Expected: PASS.

- [ ] **Step 5: Model und Service anlegen**

Create `frontend/src/app/shared/models/hour-entry.model.ts`:

```ts
export interface HourEntry {
  id: string;
  personId: string;
  semesterId: string;
  roleFieldInstanceId: string | null; // null = Kochen
  roleLabel: string;
  date: string;   // YYYY-MM-DD
  minutes: number;
  comment: string;
}

export interface RoleOption {
  fieldInstanceId: string | null; // null = Kochen
  definitionId: string | null;
  label: string;
}

export interface SaveHourEntryRequest {
  roleFieldInstanceId: string | null;
  date: string;
  minutes: number;
  comment: string;
}

export interface HourSummary {
  personId: string;
  name: string;
  totalMinutes: number;
  entries: HourEntry[];
}
```

Create `frontend/src/app/shared/services/hour-entry.service.ts`:

```ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { HourEntry, HourSummary, RoleOption, SaveHourEntryRequest } from '../models/hour-entry.model';

@Injectable({ providedIn: 'root' })
export class HourEntryService {
  constructor(private api: ApiService) {}

  listMine(): Observable<HourEntry[]> {
    return this.api.get<HourEntry[]>('/hour-entries/me');
  }

  roleOptions(semesterId?: string): Observable<RoleOption[]> {
    const q = semesterId ? `?semesterId=${semesterId}` : '';
    return this.api.get<RoleOption[]>(`/hour-entries/role-options${q}`);
  }

  create(request: SaveHourEntryRequest): Observable<HourEntry> {
    return this.api.post<HourEntry>('/hour-entries', request);
  }

  update(id: string, request: SaveHourEntryRequest): Observable<HourEntry> {
    return this.api.put<HourEntry>(`/hour-entries/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/hour-entries/${id}`);
  }

  summary(semesterId: string): Observable<HourSummary[]> {
    return this.api.get<HourSummary[]>(`/hour-entries/summary?semesterId=${semesterId}`);
  }
}
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/models/hour-entry.model.ts \
        frontend/src/app/shared/services/hour-entry.service.ts \
        frontend/src/app/shared/util/time-format.util.ts \
        frontend/src/app/shared/util/time-format.util.spec.ts
git commit -m "feat: Stundenerfassung — Frontend-Model, Service und Zeit-Utility"
```

---

## Task 7: Frontend — Eltern-Bereich `stunden` (Master-Detail) + Route + Nav

**Files:**
- Create: `frontend/src/app/stunden/stunden.component.ts`
- Create: `frontend/src/app/stunden/stunden.component.html`
- Create: `frontend/src/app/stunden/stunden.component.scss`
- Test: `frontend/src/app/stunden/stunden.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/app.component.html`

**Interfaces:**
- Consumes: `HourEntryService`, `HourEntry`, `RoleOption`, `SaveHourEntryRequest` (Task 6); Utils `parseHhmm`, `formatMinutes`, `toIsoDate`, `parseIsoDate`, `formatIsoDateDe`; `NotificationService`.
- Produces: `StundenComponent` (Selector `app-stunden`), Route `stunden` (nur `authGuard`), Nav-Link außerhalb des Admin-Blocks.

- [ ] **Step 1: Component-Test schreiben**

Create `frontend/src/app/stunden/stunden.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { StundenComponent } from './stunden.component';
import { HourEntryService } from '../shared/services/hour-entry.service';
import { NotificationService } from '../shared/services/notification.service';
import { HourEntry, RoleOption } from '../shared/models/hour-entry.model';

describe('StundenComponent', () => {
  let fixture: ComponentFixture<StundenComponent>;
  let component: StundenComponent;
  let service: jasmine.SpyObj<HourEntryService>;

  const entry: HourEntry = {
    id: 'e1', personId: 'p1', semesterId: 's1',
    roleFieldInstanceId: null, roleLabel: 'Kochen',
    date: '2026-10-05', minutes: 90, comment: 'Suppe',
  };
  const options: RoleOption[] = [
    { fieldInstanceId: 'r1', definitionId: 'd1', label: 'Gartenteam' },
    { fieldInstanceId: null, definitionId: null, label: 'Kochen' },
  ];

  beforeEach(async () => {
    service = jasmine.createSpyObj<HourEntryService>('HourEntryService',
      ['listMine', 'roleOptions', 'create', 'update', 'delete']);
    service.listMine.and.returnValue(of([entry]));
    service.roleOptions.and.returnValue(of(options));
    service.create.and.returnValue(of(entry));
    service.update.and.returnValue(of(entry));
    service.delete.and.returnValue(of(void 0));

    const notify = jasmine.createSpyObj<NotificationService>('NotificationService',
      ['success', 'error', 'extractError']);

    await TestBed.configureTestingModule({
      imports: [StundenComponent],
      providers: [
        provideNoopAnimations(),
        { provide: HourEntryService, useValue: service },
        { provide: NotificationService, useValue: notify },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StundenComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads the own entries on init', () => {
    expect(service.listMine).toHaveBeenCalled();
    expect(component.entries.length).toBe(1);
  });

  it('renders list shorthand as DD.MM.YYYY – Rolle', () => {
    expect(component.shorthand(entry)).toBe('05.10.2026 – Kochen');
  });

  it('opens an empty editor on newEntry()', () => {
    component.newEntry();
    expect(component.editing).toBeTrue();
    expect(component.selectedId).toBeNull();
  });

  it('saves a new entry via create() with parsed minutes and iso date', () => {
    component.newEntry();
    component.form.setValue({ roleKey: '__kochen__', date: new Date(2026, 9, 6), time: '01:00', comment: 'x' });
    component.save();
    expect(service.create).toHaveBeenCalledWith({
      roleFieldInstanceId: null, date: '2026-10-06', minutes: 60, comment: 'x',
    });
  });

  it('does not save when the time is invalid', () => {
    component.newEntry();
    component.form.setValue({ roleKey: '__kochen__', date: new Date(2026, 9, 6), time: '99:99', comment: '' });
    component.save();
    expect(service.create).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd frontend && npm test -- --include='**/stunden.component.spec.ts' --watch=false`
Expected: FAIL — Component existiert noch nicht.

- [ ] **Step 3: Component-TS implementieren**

Create `frontend/src/app/stunden/stunden.component.ts`:

```ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MAT_DATE_LOCALE } from '@angular/material/core';
import { HourEntryService } from '../shared/services/hour-entry.service';
import { NotificationService } from '../shared/services/notification.service';
import { HourEntry, RoleOption } from '../shared/models/hour-entry.model';
import {
  parseHhmm, formatMinutes, toIsoDate, parseIsoDate, formatIsoDateDe,
} from '../shared/util/time-format.util';

const KOCHEN_KEY = '__kochen__';

@Component({
  selector: 'app-stunden',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatIconModule, MatDatepickerModule,
  ],
  providers: [
    provideNativeDateAdapter(),
    { provide: MAT_DATE_LOCALE, useValue: 'de-AT' },
  ],
  templateUrl: './stunden.component.html',
  styleUrl: './stunden.component.scss',
})
export class StundenComponent implements OnInit {
  entries: HourEntry[] = [];
  options: RoleOption[] = [];
  /** Zusatzoption, falls ein bearbeiteter Alt-Eintrag eine nicht mehr aktive Rolle hat. */
  extraOption: { key: string; label: string } | null = null;
  selectedId: string | null = null;
  editing = false;

  form = new FormGroup({
    roleKey: new FormControl<string | null>(null, Validators.required),
    date: new FormControl<Date | null>(null, Validators.required),
    time: new FormControl<string>('', [Validators.required, this.timeValidator]),
    comment: new FormControl<string>(''),
  });

  constructor(
    private hourService: HourEntryService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
    this.hourService.roleOptions().subscribe((opts) => (this.options = opts));
  }

  load(): void {
    this.hourService.listMine().subscribe((entries) => (this.entries = entries));
  }

  private timeValidator(control: FormControl): { [k: string]: boolean } | null {
    return parseHhmm(control.value ?? '') === null ? { time: true } : null;
  }

  roleKey(opt: RoleOption): string {
    return opt.fieldInstanceId ?? KOCHEN_KEY;
  }

  shorthand(entry: HourEntry): string {
    return `${formatIsoDateDe(entry.date)} – ${entry.roleLabel}`;
  }

  formatMinutes = formatMinutes;

  newEntry(): void {
    this.selectedId = null;
    this.extraOption = null;
    this.editing = true;
    this.form.reset({ roleKey: null, date: null, time: '', comment: '' });
  }

  selectForEdit(entry: HourEntry): void {
    this.selectedId = entry.id;
    this.editing = true;
    const key = entry.roleFieldInstanceId ?? KOCHEN_KEY;
    // Alt-Eintrag mit nicht mehr aktiver Rolle: Option temporär bereitstellen.
    const known = this.options.some((o) => this.roleKey(o) === key);
    this.extraOption = known ? null : { key, label: entry.roleLabel };
    this.form.reset({
      roleKey: key,
      date: parseIsoDate(entry.date),
      time: formatMinutes(entry.minutes),
      comment: entry.comment ?? '',
    });
  }

  closeEditor(): void {
    this.selectedId = null;
    this.extraOption = null;
    this.editing = false;
    this.form.reset({ roleKey: null, date: null, time: '', comment: '' });
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const minutes = parseHhmm(this.form.value.time ?? '');
    const date = this.form.value.date;
    if (minutes === null || !date) {
      return;
    }
    const roleKey = this.form.value.roleKey;
    const request = {
      roleFieldInstanceId: roleKey === KOCHEN_KEY ? null : roleKey,
      date: toIsoDate(date),
      minutes,
      comment: this.form.value.comment ?? '',
    };
    const isUpdate = this.selectedId !== null;
    const save$ = this.selectedId
      ? this.hourService.update(this.selectedId, request)
      : this.hourService.create(request);
    save$.subscribe({
      next: () => {
        this.notify.success(isUpdate ? 'Eintrag aktualisiert' : 'Eintrag gespeichert');
        this.closeEditor();
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  delete(entry: HourEntry): void {
    this.hourService.delete(entry.id).subscribe({
      next: () => {
        this.notify.success('Eintrag gelöscht');
        if (this.selectedId === entry.id) {
          this.closeEditor();
        }
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
```

- [ ] **Step 4: Component-HTML implementieren**

Create `frontend/src/app/stunden/stunden.component.html`:

```html
<div class="stunden-grid">
  <aside class="entry-list">
    <button mat-stroked-button type="button" (click)="newEntry()">
      <mat-icon>add</mat-icon> Neuer Eintrag
    </button>
    <div class="entry-item" *ngFor="let e of entries" [class.selected]="e.id === selectedId">
      <button class="entry-main" type="button" (click)="selectForEdit(e)">
        {{ shorthand(e) }}
      </button>
      <button mat-icon-button type="button"
              (click)="$event.stopPropagation(); delete(e)" aria-label="Löschen">
        <mat-icon>delete_outline</mat-icon>
      </button>
    </div>
    <p class="empty-hint" *ngIf="entries.length === 0">Noch keine Einträge.</p>
  </aside>

  <div class="entry-placeholder" *ngIf="!editing">
    <mat-icon>schedule</mat-icon>
    <p>Wähle links einen Eintrag oder lege einen neuen an.</p>
  </div>

  <form class="entry-form" *ngIf="editing" [formGroup]="form" (ngSubmit)="save()">
    <mat-form-field appearance="outline">
      <mat-label>Tätigkeit / Rolle</mat-label>
      <mat-select formControlName="roleKey">
        <mat-option *ngIf="extraOption" [value]="extraOption.key">{{ extraOption.label }}</mat-option>
        <mat-option *ngFor="let o of options" [value]="roleKey(o)">{{ o.label }}</mat-option>
      </mat-select>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Datum</mat-label>
      <input matInput [matDatepicker]="picker" formControlName="date" />
      <mat-datepicker-toggle matIconSuffix [for]="picker"></mat-datepicker-toggle>
      <mat-datepicker #picker></mat-datepicker>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Dauer (HH:MM)</mat-label>
      <input matInput formControlName="time" placeholder="01:30" />
      <mat-error *ngIf="form.controls.time.hasError('time')">Format HH:MM, größer als 00:00</mat-error>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Kommentar</mat-label>
      <textarea matInput formControlName="comment" rows="3"></textarea>
    </mat-form-field>

    <div class="form-actions">
      <button mat-flat-button color="primary" type="submit">Speichern</button>
      <button mat-button type="button" (click)="closeEditor()">Abbrechen</button>
    </div>
  </form>
</div>
```

- [ ] **Step 5: Component-SCSS implementieren**

Create `frontend/src/app/stunden/stunden.component.scss`:

```scss
.stunden-grid {
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr);
  gap: 16px;
  padding: 16px;
}

.entry-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.entry-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 6px;
  padding-left: 8px;

  &.selected {
    border-color: var(--mat-sys-primary, #3f51b5);
    background: rgba(63, 81, 181, 0.06);
  }
}

.entry-main {
  flex: 1;
  text-align: left;
  background: none;
  border: none;
  padding: 10px 4px;
  cursor: pointer;
  font: inherit;
}

.empty-hint {
  color: rgba(0, 0, 0, 0.54);
  font-size: 0.9rem;
}

.entry-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: rgba(0, 0, 0, 0.4);
  gap: 8px;
}

.entry-form {
  display: flex;
  flex-direction: column;
  max-width: 480px;

  mat-form-field {
    width: 100%;
  }
}

.form-actions {
  display: flex;
  gap: 8px;
}
```

- [ ] **Step 6: Test ausführen — muss bestehen**

Run: `cd frontend && npm test -- --include='**/stunden.component.spec.ts' --watch=false`
Expected: PASS.

- [ ] **Step 7: Route ergänzen**

In `frontend/src/app/app.routes.ts` nach dem `cooking`-Block (vor `administration`) einfügen:

```ts
  {
    path: 'stunden',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./stunden/stunden.component').then(m => m.StundenComponent),
  },
```

- [ ] **Step 8: Nav-Link ergänzen (außerhalb des Admin-Blocks)**

In `frontend/src/app/app.component.html` direkt nach dem `cooking`-Link (Zeilen 7–10, **vor** `@if (currentUser.isAdmin) {`) einfügen:

```html
      <a mat-list-item routerLink="/stunden" routerLinkActive="active">
        <mat-icon matListItemIcon>schedule</mat-icon>
        <span matListItemTitle>Meine Stunden</span>
      </a>
```

- [ ] **Step 9: Build/Full-Test des Frontends**

Run: `cd frontend && npm test -- --watch=false`
Expected: PASS (neue Specs grün, keine neuen Regressionen).

- [ ] **Step 10: Commit**

```bash
git add frontend/src/app/stunden/ frontend/src/app/app.routes.ts frontend/src/app/app.component.html
git commit -m "feat: Stundenerfassung — Eltern-Bereich (Liste, Formular, Route, Nav)"
```

---

## Task 8: Frontend — Admin-Übersicht `stundenuebersicht` + Route + Nav

**Files:**
- Create: `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.ts`
- Create: `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.html`
- Create: `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.scss`
- Test: `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/app.component.html`

**Interfaces:**
- Consumes: `HourEntryService` (`summary`, `update`, `delete`), `HourSummary`, `HourEntry`, `SaveHourEntryRequest` (Task 6); `SemesterService.getAll()`; Utils `formatMinutes`, `formatIsoDateDe`, `parseHhmm`, `parseIsoDate`, `toIsoDate`; `NotificationService`.
- Produces: `StundenuebersichtComponent` (Selector `app-stundenuebersicht`), Route `administration/stundenuebersicht` (`authGuard`+`adminGuard`), Nav-Link im Admin-Block.
- **Bounded decision:** Admin bearbeitet nur **Datum, Dauer, Kommentar** (nicht die Rolle) — die Rollen-Optionen sind nutzerspezifisch. Beim Update wird `roleFieldInstanceId` des Eintrags unverändert mitgesendet, sodass das Label serverseitig erhalten bleibt.

- [ ] **Step 1: Component-Test schreiben**

Create `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { StundenuebersichtComponent } from './stundenuebersicht.component';
import { HourEntryService } from '../../shared/services/hour-entry.service';
import { SemesterService } from '../../shared/services/semester.service';
import { NotificationService } from '../../shared/services/notification.service';
import { HourEntry, HourSummary } from '../../shared/models/hour-entry.model';

describe('StundenuebersichtComponent', () => {
  let fixture: ComponentFixture<StundenuebersichtComponent>;
  let component: StundenuebersichtComponent;
  let hourService: jasmine.SpyObj<HourEntryService>;

  const entry: HourEntry = {
    id: 'e1', personId: 'p1', semesterId: 's1',
    roleFieldInstanceId: null, roleLabel: 'Kochen',
    date: '2026-10-05', minutes: 90, comment: '',
  };
  const summary: HourSummary[] = [
    { personId: 'p1', name: 'Anna Muster', totalMinutes: 90, entries: [entry] },
  ];

  beforeEach(async () => {
    hourService = jasmine.createSpyObj<HourEntryService>('HourEntryService',
      ['summary', 'update', 'delete']);
    hourService.summary.and.returnValue(of(summary));
    hourService.update.and.returnValue(of(entry));
    hourService.delete.and.returnValue(of(void 0));

    const semesterService = jasmine.createSpyObj<SemesterService>('SemesterService', ['getAll']);
    semesterService.getAll.and.returnValue(of([
      { id: 's1', start: '2026-09-01T00:00:00Z', end: '2027-02-28T00:00:00Z' } as any,
    ]));

    const notify = jasmine.createSpyObj<NotificationService>('NotificationService',
      ['success', 'error', 'extractError']);

    await TestBed.configureTestingModule({
      imports: [StundenuebersichtComponent],
      providers: [
        provideNoopAnimations(),
        { provide: HourEntryService, useValue: hourService },
        { provide: SemesterService, useValue: semesterService },
        { provide: NotificationService, useValue: notify },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StundenuebersichtComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads the summary for the default (newest) semester', () => {
    expect(component.selectedSemesterId).toBe('s1');
    expect(hourService.summary).toHaveBeenCalledWith('s1');
    expect(component.summaries.length).toBe(1);
  });

  it('formats the person total as HH:MM', () => {
    expect(component.formatMinutes(component.summaries[0].totalMinutes)).toBe('01:30');
  });

  it('updates an entry keeping its role and reloads', () => {
    component.startEdit(entry);
    component.editForm.setValue({ date: new Date(2026, 9, 6), time: '00:30', comment: 'fix' });
    component.saveEdit(entry);
    expect(hourService.update).toHaveBeenCalledWith('e1', {
      roleFieldInstanceId: null, date: '2026-10-06', minutes: 30, comment: 'fix',
    });
    expect(hourService.summary).toHaveBeenCalledTimes(2); // init + nach Update
  });
});
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd frontend && npm test -- --include='**/stundenuebersicht.component.spec.ts' --watch=false`
Expected: FAIL — Component existiert noch nicht.

- [ ] **Step 3: Component-TS implementieren**

Create `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.ts`:

```ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { provideNativeDateAdapter, MAT_DATE_LOCALE } from '@angular/material/core';
import { HourEntryService } from '../../shared/services/hour-entry.service';
import { SemesterService } from '../../shared/services/semester.service';
import { NotificationService } from '../../shared/services/notification.service';
import { HourEntry, HourSummary } from '../../shared/models/hour-entry.model';
import {
  parseHhmm, formatMinutes, formatIsoDateDe, parseIsoDate, toIsoDate,
} from '../../shared/util/time-format.util';

@Component({
  selector: 'app-stundenuebersicht',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatIconModule, MatExpansionModule, MatDatepickerModule,
  ],
  providers: [
    provideNativeDateAdapter(),
    { provide: MAT_DATE_LOCALE, useValue: 'de-AT' },
  ],
  templateUrl: './stundenuebersicht.component.html',
  styleUrl: './stundenuebersicht.component.scss',
})
export class StundenuebersichtComponent implements OnInit {
  semesters: { id: string; start: string; end: string }[] = [];
  selectedSemesterId: string | null = null;
  summaries: HourSummary[] = [];
  editingEntryId: string | null = null;

  editForm = new FormGroup({
    date: new FormControl<Date | null>(null, Validators.required),
    time: new FormControl<string>('', [Validators.required, this.timeValidator]),
    comment: new FormControl<string>(''),
  });

  formatMinutes = formatMinutes;
  formatIsoDateDe = formatIsoDateDe;

  constructor(
    private hourService: HourEntryService,
    private semesterService: SemesterService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.semesterService.getAll().subscribe((semesters) => {
      this.semesters = semesters as any;
      this.selectedSemesterId = this.semesters[0]?.id ?? null;
      this.loadSummary();
    });
  }

  private timeValidator(control: FormControl): { [k: string]: boolean } | null {
    return parseHhmm(control.value ?? '') === null ? { time: true } : null;
  }

  loadSummary(): void {
    if (!this.selectedSemesterId) {
      this.summaries = [];
      return;
    }
    this.hourService.summary(this.selectedSemesterId).subscribe((s) => (this.summaries = s));
  }

  onSemesterChange(): void {
    this.editingEntryId = null;
    this.loadSummary();
  }

  startEdit(entry: HourEntry): void {
    this.editingEntryId = entry.id;
    this.editForm.reset({
      date: parseIsoDate(entry.date),
      time: formatMinutes(entry.minutes),
      comment: entry.comment ?? '',
    });
  }

  cancelEdit(): void {
    this.editingEntryId = null;
  }

  saveEdit(entry: HourEntry): void {
    if (this.editForm.invalid) {
      this.editForm.markAllAsTouched();
      return;
    }
    const minutes = parseHhmm(this.editForm.value.time ?? '');
    const date = this.editForm.value.date;
    if (minutes === null || !date) {
      return;
    }
    const request = {
      roleFieldInstanceId: entry.roleFieldInstanceId, // Rolle unverändert
      date: toIsoDate(date),
      minutes,
      comment: this.editForm.value.comment ?? '',
    };
    this.hourService.update(entry.id, request).subscribe({
      next: () => {
        this.notify.success('Eintrag aktualisiert');
        this.editingEntryId = null;
        this.loadSummary();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  delete(entry: HourEntry): void {
    this.hourService.delete(entry.id).subscribe({
      next: () => {
        this.notify.success('Eintrag gelöscht');
        this.loadSummary();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
```

> Prüfe vor dem Implementieren die genaue Signatur von `SemesterService.getAll()` und das Semester-Modell (`frontend/src/app/shared/services/semester.service.ts`). Falls das Modell andere Feldnamen nutzt, `semesters`-Typ und `?.id` entsprechend anpassen; das Default-Muster `semesters[0]?.id` entspricht `platzzuweisung.component`.

- [ ] **Step 4: Component-HTML implementieren**

Create `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.html`:

```html
<div class="uebersicht">
  <div class="toolbar">
    <mat-form-field appearance="outline">
      <mat-label>Semester</mat-label>
      <mat-select [(value)]="selectedSemesterId" (selectionChange)="onSemesterChange()">
        <mat-option *ngFor="let s of semesters" [value]="s.id">
          {{ formatIsoDateDe((s.start || '').substring(0,10)) }} – {{ formatIsoDateDe((s.end || '').substring(0,10)) }}
        </mat-option>
      </mat-select>
    </mat-form-field>
  </div>

  <p class="empty-hint" *ngIf="summaries.length === 0">Keine Einträge in diesem Semester.</p>

  <mat-accordion>
    <mat-expansion-panel *ngFor="let s of summaries">
      <mat-expansion-panel-header>
        <mat-panel-title>{{ s.name }}</mat-panel-title>
        <mat-panel-description>Summe: {{ formatMinutes(s.totalMinutes) }}</mat-panel-description>
      </mat-expansion-panel-header>

      <div class="entry-row" *ngFor="let e of s.entries">
        <ng-container *ngIf="editingEntryId !== e.id; else editRow">
          <span class="entry-date">{{ formatIsoDateDe(e.date) }}</span>
          <span class="entry-role">{{ e.roleLabel }}</span>
          <span class="entry-dur">{{ formatMinutes(e.minutes) }}</span>
          <span class="entry-comment">{{ e.comment }}</span>
          <button mat-icon-button type="button" (click)="startEdit(e)" aria-label="Bearbeiten">
            <mat-icon>edit</mat-icon>
          </button>
          <button mat-icon-button type="button" (click)="delete(e)" aria-label="Löschen">
            <mat-icon>delete_outline</mat-icon>
          </button>
        </ng-container>

        <ng-template #editRow>
          <form class="edit-form" [formGroup]="editForm" (ngSubmit)="saveEdit(e)">
            <mat-form-field appearance="outline">
              <mat-label>Datum</mat-label>
              <input matInput [matDatepicker]="picker" formControlName="date" />
              <mat-datepicker-toggle matIconSuffix [for]="picker"></mat-datepicker-toggle>
              <mat-datepicker #picker></mat-datepicker>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Dauer (HH:MM)</mat-label>
              <input matInput formControlName="time" placeholder="01:30" />
              <mat-error *ngIf="editForm.controls.time.hasError('time')">Format HH:MM</mat-error>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Kommentar</mat-label>
              <input matInput formControlName="comment" />
            </mat-form-field>
            <button mat-flat-button color="primary" type="submit">Speichern</button>
            <button mat-button type="button" (click)="cancelEdit()">Abbrechen</button>
          </form>
        </ng-template>
      </div>
    </mat-expansion-panel>
  </mat-accordion>
</div>
```

- [ ] **Step 5: Component-SCSS implementieren**

Create `frontend/src/app/administration/stundenuebersicht/stundenuebersicht.component.scss`:

```scss
.uebersicht {
  padding: 16px;
}

.toolbar {
  margin-bottom: 12px;
}

.empty-hint {
  color: rgba(0, 0, 0, 0.54);
}

.entry-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);

  .entry-date { width: 96px; }
  .entry-role { width: 160px; font-weight: 500; }
  .entry-dur { width: 64px; }
  .entry-comment { flex: 1; color: rgba(0, 0, 0, 0.6); }
}

.edit-form {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
```

- [ ] **Step 6: Test ausführen — muss bestehen**

Run: `cd frontend && npm test -- --include='**/stundenuebersicht.component.spec.ts' --watch=false`
Expected: PASS.

- [ ] **Step 7: Route ergänzen**

In `frontend/src/app/app.routes.ts` innerhalb der `administration`-`children` (z. B. nach `board`) einfügen:

```ts
      {
        path: 'stundenuebersicht',
        loadComponent: () =>
          import('./administration/stundenuebersicht/stundenuebersicht.component').then(
            m => m.StundenuebersichtComponent
          ),
      },
```

- [ ] **Step 8: Nav-Link ergänzen (im Admin-Block)**

In `frontend/src/app/app.component.html` innerhalb des `@if (currentUser.isAdmin) { ... }`-Blocks (z. B. nach dem `board`-Link) einfügen:

```html
        <a mat-list-item routerLink="/administration/stundenuebersicht" routerLinkActive="active">
          <mat-icon matListItemIcon>schedule</mat-icon>
          <span matListItemTitle>Stundenübersicht</span>
        </a>
```

- [ ] **Step 9: Voller Frontend-Testlauf**

Run: `cd frontend && npm test -- --watch=false`
Expected: PASS (alle neuen Specs grün, keine neuen Regressionen).

- [ ] **Step 10: Commit**

```bash
git add frontend/src/app/administration/stundenuebersicht/ \
        frontend/src/app/app.routes.ts frontend/src/app/app.component.html
git commit -m "feat: Stundenerfassung — Admin-Stundenübersicht (Route, Nav, Bearbeiten/Löschen)"
```

---

## Abschluss

Nach Task 8 ist das Feature vollständig. Empfohlene Endkontrolle:

- [ ] Voller Backend-Lauf: `cd backend && ./mvnw test` — neue Tests grün, keine neuen Regressionen (bekannte rote Baseline in `SecurityFilterTest` ignorieren).
- [ ] Voller Frontend-Lauf: `cd frontend && npm test -- --watch=false`.
- [ ] Manueller Smoke-Test: Als nicht-Admin `/stunden` öffnen → Rolle (inkl. „Kochen") wählen, Datum/HH:MM/Kommentar eingeben, speichern; Liste zeigt `DD.MM.YYYY – Rolle`; Eintrag editieren und löschen. Als Admin `/administration/stundenuebersicht` → Semester wählen, Summen pro Elternteil prüfen, einen fremden Eintrag korrigieren/löschen.
- [ ] `git push` und Merge erst **nach ausdrücklicher Freigabe** des Users.
```
