# Eltern-Übersicht Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eltern sehen unter `/eltern` die Kontaktdaten aller Familien, deren Kinder mit den eigenen Kindern in einer Gruppe des laufenden Semesters sind.

**Architecture:** Ein neuer Backend-Endpoint `GET /api/v1/parent-directory` aggregiert serverseitig alle Gruppen der eigenen Kinder samt der darin vertretenen Familien; der Client benennt keine Gruppe selbst, weshalb es keinen manipulierbaren Parameter gibt. Das Frontend lädt diese Antwort einmal und schaltet im Dropdown rein clientseitig zwischen den Gruppen um.

**Tech Stack:** Backend Quarkus 3 / MongoDB (Panache + nativer Treiber), JUnit 5 + RestAssured. Frontend Angular 20 (standalone components, `@if`/`@for` control flow), Angular Material, Jasmine/Karma.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-02-eltern-uebersicht-design.md`.
- Branch: `feature/eltern-uebersicht`, abgezweigt von `main` (574a778). Nicht pushen, nicht mergen.
- Alle Benutzertexte auf Deutsch, Umlaute ausgeschrieben (`ä`, `ö`, `ü`) im Code; **Commit-Messages ohne Umlaute** (`ue`, `ae`, `oe`), wie in der bestehenden Historie.
- Sichtbare Felder: Kindername, Elternname, E-Mail, Telefon, Familienadresse. Kein Opt-out, kein Admin-Schalter.
- Zeitbezug: neuestes Semester (`Semester.listAll(Sort.descending("createdAt")).get(0)`). Keine Semesterwahl im UI.
- Fehlende Werte werden als `null` geliefert, nie als Platzhaltertext.
- Keine Personen-IDs in der API-Antwort.
- Backend-Tests laufen mit `quarkus.oidc.enabled=false`; `CurrentUserService` liefert dann die erste Person mit `ADMIN`-Rolle, sonst die zuerst persistierte Person. Tests nutzen das, indem die eigene Person **zuerst** persistiert wird.
- Baseline: `main` hat 13 vorbestehende fehlschlagende Backend-Tests. Nur neue Fehlschläge zählen als Regression.
- Backend-Tests laufen mit `cd backend && ./mvnw test -Dtest=<Klasse>`; Frontend mit `cd frontend && npm test -- --watch=false`.

---

### Task 1: Geteilte Personen- und Semester-Helfer

`isChild` und die Semester-Auflösung liegen heute privat in `PersonResource`. Beide werden in einen Service gezogen, damit der neue Verzeichnis-Service dieselbe Logik nutzt statt sie zu duplizieren. `PersonResource` delegiert danach; sein Verhalten ändert sich nicht.

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/PersonLookupService.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/PersonResource.java` (private `isChild` ab Zeile 632 und `resolveSemesterId` ab Zeile 61)
- Test: `backend/src/test/java/at/kigruapp/service/PersonLookupServiceTest.java`

**Interfaces:**
- Consumes: `Person`, `FieldDefinition`, `FieldRef`, `Semester` (bestehend)
- Produces:
  - `boolean PersonLookupService.isChild(Person person)`
  - `boolean PersonLookupService.isParent(Person person)`
  - `ObjectId PersonLookupService.resolveNewestSemesterId()` — `null`, wenn kein Semester existiert

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/service/PersonLookupServiceTest.java`:

```java
package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
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

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PersonLookupServiceTest {

    @Inject
    PersonLookupService lookup;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    ObjectId personTypeDefId;

    @BeforeEach
    void cleanup() {
        Person.deleteAll();
        Semester.deleteAll();
        FieldDefinition.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();

        FieldDefinition def = new FieldDefinition();
        def.fieldName = "personType";
        def.createdAt = Instant.now();
        def.persist();
        personTypeDefId = def.id;
    }

    private Person persistPerson(String personType) {
        ObjectId instanceId = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", instanceId)
                        .append("definitionId", personTypeDefId)
                        .append("value", personType));
        Person p = new Person();
        p.familyId = new ObjectId();
        p.basicProperties.add(new FieldRef(personTypeDefId, instanceId));
        p.createdAt = Instant.now();
        p.persist();
        return p;
    }

    @Test
    void isChildRecognisesChildAndRejectsParent() {
        assertTrue(lookup.isChild(persistPerson("CHILD")));
        assertFalse(lookup.isChild(persistPerson("PARENT")));
    }

    @Test
    void isParentRecognisesParentAndRejectsChild() {
        assertTrue(lookup.isParent(persistPerson("PARENT")));
        assertFalse(lookup.isParent(persistPerson("CHILD")));
    }

    @Test
    void personWithoutPropertiesIsNeitherChildNorParent() {
        Person p = new Person();
        p.createdAt = Instant.now();
        p.persist();
        assertFalse(lookup.isChild(p));
        assertFalse(lookup.isParent(p));
    }

    @Test
    void resolveNewestSemesterIdReturnsNullWithoutSemester() {
        assertNull(lookup.resolveNewestSemesterId());
    }

    @Test
    void resolveNewestSemesterIdReturnsMostRecentlyCreated() {
        Semester older = new Semester();
        older.start = Instant.parse("2025-09-01T00:00:00Z");
        older.end = Instant.parse("2026-02-28T00:00:00Z");
        older.createdAt = Instant.parse("2025-08-01T00:00:00Z");
        older.persist();

        Semester newer = new Semester();
        newer.start = Instant.parse("2026-09-01T00:00:00Z");
        newer.end = Instant.parse("2027-02-28T00:00:00Z");
        newer.createdAt = Instant.parse("2026-08-01T00:00:00Z");
        newer.persist();

        assertEquals(newer.id, lookup.resolveNewestSemesterId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=PersonLookupServiceTest`
Expected: Compile-Fehler, `PersonLookupService` existiert nicht.

- [ ] **Step 3: Write the service**

`backend/src/main/java/at/kigruapp/service/PersonLookupService.java`:

```java
package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Gemeinsame Personen- und Semester-Auflösung für Resources und Services.
 * Die Logik lag zuvor privat in PersonResource; sie wird hier geteilt, damit
 * es nur eine Definition von "Kind" bzw. "Elternteil" gibt.
 */
@ApplicationScoped
public class PersonLookupService {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    public boolean isChild(Person person) {
        return hasPersonType(person, "CHILD");
    }

    public boolean isParent(Person person) {
        return hasPersonType(person, "PARENT");
    }

    /** Die id des zuletzt angelegten Semesters, oder null wenn keines existiert. */
    public ObjectId resolveNewestSemesterId() {
        List<Semester> latest = Semester.listAll(Sort.descending("createdAt"));
        return latest.isEmpty() ? null : latest.get(0).id;
    }

    private boolean hasPersonType(Person person, String expected) {
        if (person == null || person.basicProperties == null) return false;
        MongoCollection<Document> instances = mongoClient.getDatabase(databaseName)
                .getCollection("field_instances");
        for (FieldRef ref : person.basicProperties) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def == null || !"personType".equals(def.fieldName)) continue;
            Document instance = instances.find(new Document("_id", ref.fieldInstanceId)).first();
            if (instance != null && expected.equals(instance.get("value"))) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=PersonLookupServiceTest`
Expected: PASS, 5 Tests.

- [ ] **Step 5: PersonResource delegieren lassen**

In `PersonResource` das Feld ergänzen:

```java
    @Inject
    PersonLookupService personLookup;
```

(Import `at.kigruapp.service.PersonLookupService` hinzufügen.)

Den Rumpf von `isChild` (Zeile 632–645) ersetzen durch:

```java
    private boolean isChild(Person person) {
        return personLookup.isChild(person);
    }
```

In `resolveSemesterId` (Zeile 61–70) den Fallback-Zweig ersetzen:

```java
    private ObjectId resolveSemesterId(String semesterIdParam) {
        if (semesterIdParam != null && !semesterIdParam.isBlank()) {
            return new ObjectId(semesterIdParam);
        }
        return personLookup.resolveNewestSemesterId();
    }
```

Nicht mehr benötigte Importe (`io.quarkus.panache.common.Sort`, `at.kigruapp.entity.Semester`) nur entfernen, wenn sie nirgends sonst in der Datei verwendet werden — vorher prüfen.

- [ ] **Step 6: Run the affected existing tests**

Run: `cd backend && ./mvnw test -Dtest=PersonLookupServiceTest+PersonResourceTest`
Expected: `PersonLookupServiceTest` grün; `PersonResourceTest` mit demselben Ergebnis wie vor der Änderung (Baseline vorher mit `git stash` prüfen, falls dort etwas rot ist).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/PersonLookupService.java backend/src/main/java/at/kigruapp/resource/PersonResource.java backend/src/test/java/at/kigruapp/service/PersonLookupServiceTest.java
git commit -m "refactor(be): Personen- und Semester-Aufloesung in PersonLookupService teilen"
```

---

### Task 2: DTOs und Aggregation im ParentDirectoryService

**Files:**
- Create: `backend/src/main/java/at/kigruapp/dto/ParentDirectoryDTO.java`
- Create: `backend/src/main/java/at/kigruapp/service/ParentDirectoryService.java`
- Test: `backend/src/test/java/at/kigruapp/service/ParentDirectoryServiceTest.java`

**Interfaces:**
- Consumes: `PersonLookupService.isChild`, `PersonLookupService.isParent`, `PersonLookupService.resolveNewestSemesterId` (Task 1); `PersonPropertyResolver.resolve(List<Person>) → Map<ObjectId, Map<String,String>>` mit den Schlüsseln `firstName`, `lastName`, `email`, `phone`
- Produces:
  - `ParentDirectoryDTO ParentDirectoryService.buildForFamily(ObjectId ownFamilyId)`
  - Records `ParentDirectoryDTO(String semesterId, List<GroupEntry> groups)`, `ParentDirectoryDTO.GroupEntry(String groupInstanceId, String groupName, List<FamilyEntry> families)`, `ParentDirectoryDTO.FamilyEntry(String familyId, boolean isOwnFamily, List<String> children, List<ParentEntry> parents, String address)`, `ParentDirectoryDTO.ParentEntry(String firstName, String lastName, String email, String phone)`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/service/ParentDirectoryServiceTest.java`:

```java
package at.kigruapp.service;

import at.kigruapp.dto.ParentDirectoryDTO;
import at.kigruapp.entity.Family;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ParentDirectoryServiceTest {

    @Inject
    ParentDirectoryService service;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    ObjectId personTypeDefId;
    ObjectId firstNameDefId;
    ObjectId lastNameDefId;
    ObjectId emailDefId;
    ObjectId phoneDefId;
    ObjectId groupDefId;
    ObjectId semesterId;

    @BeforeEach
    void setUp() {
        Person.deleteAll();
        Family.deleteAll();
        Semester.deleteAll();
        FieldDefinition.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments").drop();

        personTypeDefId = persistDefinition("personType");
        firstNameDefId = persistDefinition("firstName");
        lastNameDefId = persistDefinition("lastName");
        emailDefId = persistDefinition("email");
        phoneDefId = persistDefinition("phone");
        groupDefId = persistDefinition("group");

        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        s.createdAt = Instant.parse("2026-08-01T00:00:00Z");
        s.persist();
        semesterId = s.id;
    }

    private ObjectId persistDefinition(String fieldName) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.createdAt = Instant.now();
        def.persist();
        return def.id;
    }

    private ObjectId persistInstance(ObjectId definitionId, String value) {
        ObjectId id = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", id)
                        .append("definitionId", definitionId)
                        .append("value", value));
        return id;
    }

    private ObjectId persistFamily(String name, String street, String zip, String city) {
        Family f = new Family();
        f.name = name;
        if (street != null) {
            f.address = Map.of("street", street, "zip", zip, "city", city);
        }
        f.createdAt = Instant.now();
        f.persist();
        return f.id;
    }

    /** props: firstName, lastName, email, phone — null-Werte werden ausgelassen. */
    private Person persistPerson(ObjectId familyId, String personType,
                                 String firstName, String lastName, String email, String phone) {
        Person p = new Person();
        p.familyId = familyId;
        p.basicProperties.add(new FieldRef(personTypeDefId, persistInstance(personTypeDefId, personType)));
        if (firstName != null) p.basicProperties.add(new FieldRef(firstNameDefId, persistInstance(firstNameDefId, firstName)));
        if (lastName != null) p.basicProperties.add(new FieldRef(lastNameDefId, persistInstance(lastNameDefId, lastName)));
        if (email != null) p.basicProperties.add(new FieldRef(emailDefId, persistInstance(emailDefId, email)));
        if (phone != null) p.basicProperties.add(new FieldRef(phoneDefId, persistInstance(phoneDefId, phone)));
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p;
    }

    private ObjectId persistGroup(String name) {
        return persistInstance(groupDefId, name);
    }

    private void assign(ObjectId childId, ObjectId groupInstanceId, ObjectId inSemester) {
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments")
                .insertOne(new Document("_id", new ObjectId())
                        .append("personId", childId)
                        .append("semesterId", inSemester)
                        .append("section", "group")
                        .append("definitionId", groupDefId)
                        .append("fieldInstanceId", groupInstanceId));
    }

    @Test
    void ownGroupContainsOtherFamiliesButNotForeignGroups() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);
        persistPerson(ownFamily, "PARENT", "Anna", "Muster", "anna@x.at", "0660 111");

        ObjectId otherFamily = persistFamily("Sommer", "Gasse 7", "1020", "Wien");
        Person otherChild = persistPerson(otherFamily, "CHILD", "Tim", "Sommer", null, null);
        persistPerson(otherFamily, "PARENT", "Clara", "Sommer", "clara@y.at", "0664 333");

        ObjectId strangerFamily = persistFamily("Fremd", "Weg 3", "1030", "Wien");
        Person strangerChild = persistPerson(strangerFamily, "CHILD", "Max", "Fremd", null, null);
        persistPerson(strangerFamily, "PARENT", "Doris", "Fremd", "doris@z.at", null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        ObjectId biene = persistGroup("Bienengruppe");
        assign(ownChild.id, kaefer, semesterId);
        assign(otherChild.id, kaefer, semesterId);
        assign(strangerChild.id, biene, semesterId);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals(semesterId.toHexString(), result.semesterId());
        assertEquals(1, result.groups().size());
        ParentDirectoryDTO.GroupEntry group = result.groups().get(0);
        assertEquals("Kaefergruppe", group.groupName());
        assertEquals(2, group.families().size());

        ParentDirectoryDTO.FamilyEntry own = group.families().get(0);
        assertTrue(own.isOwnFamily());
        assertEquals(List.of("Lena"), own.children());
        assertEquals("Hauptstrasse 1, 1010 Wien", own.address());
        assertEquals("anna@x.at", own.parents().get(0).email());

        ParentDirectoryDTO.FamilyEntry other = group.families().get(1);
        assertFalse(other.isOwnFamily());
        assertEquals(List.of("Tim"), other.children());
        assertEquals("Clara", other.parents().get(0).firstName());
    }

    @Test
    void familyWithTwoChildrenInSameGroupAppearsOnce() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId otherFamily = persistFamily("Sommer", "Gasse 7", "1020", "Wien");
        Person twinA = persistPerson(otherFamily, "CHILD", "Tim", "Sommer", null, null);
        Person twinB = persistPerson(otherFamily, "CHILD", "Nina", "Sommer", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);
        assign(twinA.id, kaefer, semesterId);
        assign(twinB.id, kaefer, semesterId);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        List<ParentDirectoryDTO.FamilyEntry> families = result.groups().get(0).families();
        assertEquals(2, families.size());
        assertEquals(List.of("Nina", "Tim"), families.get(1).children());
    }

    @Test
    void onlyChildrenOfTheSameGroupAreListedForAFamily() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId otherFamily = persistFamily("Sommer", "Gasse 7", "1020", "Wien");
        Person inGroup = persistPerson(otherFamily, "CHILD", "Tim", "Sommer", null, null);
        Person elsewhere = persistPerson(otherFamily, "CHILD", "Nina", "Sommer", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        ObjectId biene = persistGroup("Bienengruppe");
        assign(ownChild.id, kaefer, semesterId);
        assign(inGroup.id, kaefer, semesterId);
        assign(elsewhere.id, biene, semesterId);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals(List.of("Tim"), result.groups().get(0).families().get(1).children());
    }

    @Test
    void parentWithoutEmailIsListedWithNullEmail() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId otherFamily = persistFamily("Sommer", null, null, null);
        Person otherChild = persistPerson(otherFamily, "CHILD", "Tim", "Sommer", null, null);
        persistPerson(otherFamily, "PARENT", "Clara", "Sommer", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);
        assign(otherChild.id, kaefer, semesterId);

        ParentDirectoryDTO.FamilyEntry other = service.buildForFamily(ownFamily).groups().get(0).families().get(1);
        assertEquals(1, other.parents().size());
        assertNull(other.parents().get(0).email());
        assertNull(other.parents().get(0).phone());
        assertNull(other.address());
    }

    @Test
    void assignmentsOfOtherSemestersAreIgnored() {
        Semester older = new Semester();
        older.start = Instant.parse("2025-09-01T00:00:00Z");
        older.end = Instant.parse("2026-02-28T00:00:00Z");
        older.createdAt = Instant.parse("2025-08-01T00:00:00Z");
        older.persist();

        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId otherFamily = persistFamily("Sommer", "Gasse 7", "1020", "Wien");
        Person otherChild = persistPerson(otherFamily, "CHILD", "Tim", "Sommer", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);
        assign(otherChild.id, kaefer, older.id);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals(1, result.groups().get(0).families().size());
        assertTrue(result.groups().get(0).families().get(0).isOwnFamily());
    }

    @Test
    void childWithoutGroupYieldsEmptyGroupList() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals(semesterId.toHexString(), result.semesterId());
        assertTrue(result.groups().isEmpty());
    }

    @Test
    void withoutSemesterResultIsEmpty() {
        Semester.deleteAll();
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertNull(result.semesterId());
        assertTrue(result.groups().isEmpty());
    }

    @Test
    void groupsAreSortedByName() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person childA = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);
        Person childB = persistPerson(ownFamily, "CHILD", "Paul", "Muster", null, null);

        ObjectId zebra = persistGroup("Zebragruppe");
        ObjectId ameise = persistGroup("Ameisengruppe");
        assign(childA.id, zebra, semesterId);
        assign(childB.id, ameise, semesterId);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals(List.of("Ameisengruppe", "Zebragruppe"),
                result.groups().stream().map(ParentDirectoryDTO.GroupEntry::groupName).toList());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ParentDirectoryServiceTest`
Expected: Compile-Fehler, `ParentDirectoryDTO` und `ParentDirectoryService` existieren nicht.

- [ ] **Step 3: Write the DTO**

`backend/src/main/java/at/kigruapp/dto/ParentDirectoryDTO.java`:

```java
package at.kigruapp.dto;

import java.util.List;

/**
 * Antwort von GET /api/v1/parent-directory: alle Gruppen der eigenen Kinder im
 * laufenden Semester, je Gruppe die dort vertretenen Familien. Enthält bewusst
 * keine Personen-IDs — der Client soll damit nichts nachladen können.
 */
public record ParentDirectoryDTO(String semesterId, List<GroupEntry> groups) {

    public record GroupEntry(String groupInstanceId, String groupName, List<FamilyEntry> families) {}

    public record FamilyEntry(
            String familyId,
            boolean isOwnFamily,
            List<String> children,
            List<ParentEntry> parents,
            String address) {}

    public record ParentEntry(String firstName, String lastName, String email, String phone) {}
}
```

- [ ] **Step 4: Write the service**

`backend/src/main/java/at/kigruapp/service/ParentDirectoryService.java`:

```java
package at.kigruapp.service;

import at.kigruapp.dto.ParentDirectoryDTO;
import at.kigruapp.entity.Family;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.SemesterAssignment;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Baut das Eltern-Verzeichnis auf: ausgehend von den eigenen Kindern werden die
 * Gruppen des laufenden Semesters bestimmt und je Gruppe die dort vertretenen
 * Familien aufgelöst. Die Gruppenmenge stammt immer aus den eigenen Kindern —
 * es gibt keinen Parameter, über den fremde Gruppen angefragt werden könnten.
 */
@ApplicationScoped
public class ParentDirectoryService {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    PersonLookupService personLookup;

    @Inject
    PersonPropertyResolver personPropertyResolver;

    public ParentDirectoryDTO buildForFamily(ObjectId ownFamilyId) {
        ObjectId semesterId = personLookup.resolveNewestSemesterId();
        if (ownFamilyId == null || semesterId == null) {
            return new ParentDirectoryDTO(semesterId != null ? semesterId.toHexString() : null, List.of());
        }

        List<ObjectId> ownChildIds = new ArrayList<>();
        for (Person person : Person.findByFamilyId(ownFamilyId)) {
            if (personLookup.isChild(person)) {
                ownChildIds.add(person.id);
            }
        }
        if (ownChildIds.isEmpty()) {
            return new ParentDirectoryDTO(semesterId.toHexString(), List.of());
        }

        Set<ObjectId> ownGroupIds = new LinkedHashSet<>();
        for (Document doc : groupAssignments(semesterId, Filters.in("personId", ownChildIds))) {
            ObjectId instanceId = SemesterAssignment.fromDocument(doc).fieldInstanceId;
            if (instanceId != null) {
                ownGroupIds.add(instanceId);
            }
        }
        if (ownGroupIds.isEmpty()) {
            return new ParentDirectoryDTO(semesterId.toHexString(), List.of());
        }

        // Alle Kinder dieser Gruppen, gruppiert nach Gruppe.
        Map<ObjectId, List<ObjectId>> childIdsByGroup = new LinkedHashMap<>();
        Set<ObjectId> allChildIds = new LinkedHashSet<>();
        for (Document doc : groupAssignments(semesterId, Filters.in("fieldInstanceId", ownGroupIds))) {
            SemesterAssignment sa = SemesterAssignment.fromDocument(doc);
            if (sa.personId == null || sa.fieldInstanceId == null) continue;
            childIdsByGroup.computeIfAbsent(sa.fieldInstanceId, k -> new ArrayList<>()).add(sa.personId);
            allChildIds.add(sa.personId);
        }

        Map<ObjectId, Person> childrenById = new LinkedHashMap<>();
        for (Person child : Person.<Person>list("_id in ?1", new ArrayList<>(allChildIds))) {
            childrenById.put(child.id, child);
        }

        Set<ObjectId> familyIds = new LinkedHashSet<>();
        for (Person child : childrenById.values()) {
            if (child.familyId != null) {
                familyIds.add(child.familyId);
            }
        }

        Map<ObjectId, List<Person>> parentsByFamily = new LinkedHashMap<>();
        List<Person> allParents = new ArrayList<>();
        for (ObjectId familyId : familyIds) {
            List<Person> parents = new ArrayList<>();
            for (Person candidate : Person.findByFamilyId(familyId)) {
                if (personLookup.isParent(candidate)) {
                    parents.add(candidate);
                }
            }
            parentsByFamily.put(familyId, parents);
            allParents.addAll(parents);
        }
        Map<ObjectId, Map<String, String>> parentProperties = personPropertyResolver.resolve(allParents);
        Map<ObjectId, Map<String, String>> childProperties =
                personPropertyResolver.resolve(new ArrayList<>(childrenById.values()));

        List<ParentDirectoryDTO.GroupEntry> groups = new ArrayList<>();
        for (Map.Entry<ObjectId, List<ObjectId>> entry : childIdsByGroup.entrySet()) {
            ObjectId groupInstanceId = entry.getKey();

            Map<ObjectId, List<String>> childNamesByFamily = new LinkedHashMap<>();
            for (ObjectId childId : entry.getValue()) {
                Person child = childrenById.get(childId);
                if (child == null || child.familyId == null) continue;
                String name = childProperties.getOrDefault(child.id, Map.of()).get("firstName");
                childNamesByFamily.computeIfAbsent(child.familyId, k -> new ArrayList<>())
                        .add(name != null ? name : "");
            }

            List<ParentDirectoryDTO.FamilyEntry> families = new ArrayList<>();
            for (Map.Entry<ObjectId, List<String>> famEntry : childNamesByFamily.entrySet()) {
                ObjectId familyId = famEntry.getKey();
                List<String> childNames = new ArrayList<>(famEntry.getValue());
                childNames.sort(Comparator.naturalOrder());

                List<ParentDirectoryDTO.ParentEntry> parents = new ArrayList<>();
                for (Person parent : parentsByFamily.getOrDefault(familyId, List.of())) {
                    Map<String, String> props = parentProperties.getOrDefault(parent.id, Map.of());
                    parents.add(new ParentDirectoryDTO.ParentEntry(
                            props.get("firstName"), props.get("lastName"),
                            props.get("email"), props.get("phone")));
                }

                families.add(new ParentDirectoryDTO.FamilyEntry(
                        familyId.toHexString(),
                        familyId.equals(ownFamilyId),
                        childNames,
                        parents,
                        formatAddress(Family.<Family>findById(familyId))));
            }

            // Eigene Familie zuerst, danach nach dem ersten Kindernamen.
            families.sort(Comparator
                    .comparing(ParentDirectoryDTO.FamilyEntry::isOwnFamily).reversed()
                    .thenComparing(f -> f.children().isEmpty() ? "" : f.children().get(0)));

            groups.add(new ParentDirectoryDTO.GroupEntry(
                    groupInstanceId.toHexString(), resolveGroupName(groupInstanceId), families));
        }

        groups.sort(Comparator.comparing(g -> g.groupName() != null ? g.groupName() : ""));
        return new ParentDirectoryDTO(semesterId.toHexString(), groups);
    }

    private Iterable<Document> groupAssignments(ObjectId semesterId, org.bson.conversions.Bson extraFilter) {
        MongoCollection<Document> collection = mongoClient.getDatabase(databaseName)
                .getCollection("semester_assignments");
        return collection.find(Filters.and(
                Filters.eq("section", "group"),
                Filters.eq("semesterId", semesterId),
                extraFilter));
    }

    private String resolveGroupName(ObjectId groupInstanceId) {
        Document instance = mongoClient.getDatabase(databaseName)
                .getCollection("field_instances")
                .find(Filters.eq("_id", groupInstanceId))
                .first();
        Object value = instance != null ? instance.get("value") : null;
        return value != null ? value.toString() : null;
    }

    /** "Strasse, PLZ Ort" — fehlende Teile werden weggelassen, leeres Ergebnis wird null. */
    private String formatAddress(Family family) {
        if (family == null || family.address == null) return null;
        String street = trimToNull(family.address.get("street"));
        String zip = trimToNull(family.address.get("zip"));
        String city = trimToNull(family.address.get("city"));

        StringBuilder sb = new StringBuilder();
        if (street != null) sb.append(street);
        String place = zip != null && city != null ? zip + " " + city : (zip != null ? zip : city);
        if (place != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(place);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ParentDirectoryServiceTest`
Expected: PASS, 8 Tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/ParentDirectoryDTO.java backend/src/main/java/at/kigruapp/service/ParentDirectoryService.java backend/src/test/java/at/kigruapp/service/ParentDirectoryServiceTest.java
git commit -m "feat(be): ParentDirectoryService aggregiert Gruppen und Familien der Eltern-Uebersicht"
```

---

### Task 3: REST-Endpoint

**Files:**
- Create: `backend/src/main/java/at/kigruapp/resource/ParentDirectoryResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/ParentDirectoryResourceTest.java`

**Interfaces:**
- Consumes: `ParentDirectoryService.buildForFamily(ObjectId)` (Task 2), `CurrentUserService.getCurrentPerson()`
- Produces: `GET /api/v1/parent-directory` → JSON gemäß `ParentDirectoryDTO`; 403 ohne auflösbare Person

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/resource/ParentDirectoryResourceTest.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.Family;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
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
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ParentDirectoryResourceTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    ObjectId personTypeDefId;
    ObjectId firstNameDefId;
    ObjectId emailDefId;
    ObjectId groupDefId;
    ObjectId semesterId;

    @BeforeEach
    void setUp() {
        Person.deleteAll();
        Family.deleteAll();
        Semester.deleteAll();
        FieldDefinition.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments").drop();

        personTypeDefId = persistDefinition("personType");
        firstNameDefId = persistDefinition("firstName");
        emailDefId = persistDefinition("email");
        groupDefId = persistDefinition("group");

        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        s.createdAt = Instant.parse("2026-08-01T00:00:00Z");
        s.persist();
        semesterId = s.id;
    }

    private ObjectId persistDefinition(String fieldName) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.createdAt = Instant.now();
        def.persist();
        return def.id;
    }

    private ObjectId persistInstance(ObjectId definitionId, String value) {
        ObjectId id = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", id)
                        .append("definitionId", definitionId)
                        .append("value", value));
        return id;
    }

    private ObjectId persistFamily(String name) {
        Family f = new Family();
        f.name = name;
        f.address = Map.of("street", "Hauptstrasse 1", "zip", "1010", "city", "Wien");
        f.createdAt = Instant.now();
        f.persist();
        return f.id;
    }

    private Person persistPerson(ObjectId familyId, String personType, String firstName, String email) {
        Person p = new Person();
        p.familyId = familyId;
        p.basicProperties.add(new FieldRef(personTypeDefId, persistInstance(personTypeDefId, personType)));
        if (firstName != null) p.basicProperties.add(new FieldRef(firstNameDefId, persistInstance(firstNameDefId, firstName)));
        if (email != null) p.basicProperties.add(new FieldRef(emailDefId, persistInstance(emailDefId, email)));
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p;
    }

    private void assign(ObjectId childId, ObjectId groupInstanceId) {
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments")
                .insertOne(new Document("_id", new ObjectId())
                        .append("personId", childId)
                        .append("semesterId", semesterId)
                        .append("section", "group")
                        .append("definitionId", groupDefId)
                        .append("fieldInstanceId", groupInstanceId));
    }

    @Test
    void returnsOwnGroupWithOwnFamilyFirst() {
        ObjectId ownFamily = persistFamily("Muster");
        // Zuerst persistierte Person = aktueller Benutzer im Dev-Modus.
        persistPerson(ownFamily, "PARENT", "Anna", "anna@x.at");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", null);

        ObjectId otherFamily = persistFamily("Sommer");
        Person otherChild = persistPerson(otherFamily, "CHILD", "Tim", null);
        persistPerson(otherFamily, "PARENT", "Clara", "clara@y.at");

        ObjectId kaefer = persistInstance(groupDefId, "Kaefergruppe");
        assign(ownChild.id, kaefer);
        assign(otherChild.id, kaefer);

        given().when().get("/api/v1/parent-directory")
            .then().statusCode(200)
            .body("semesterId", is(semesterId.toHexString()))
            .body("groups.size()", is(1))
            .body("groups[0].groupName", is("Kaefergruppe"))
            .body("groups[0].families.size()", is(2))
            .body("groups[0].families[0].isOwnFamily", is(true))
            .body("groups[0].families[0].children", contains("Lena"))
            .body("groups[0].families[0].address", is("Hauptstrasse 1, 1010 Wien"))
            .body("groups[0].families[1].isOwnFamily", is(false))
            .body("groups[0].families[1].parents[0].firstName", is("Clara"))
            .body("groups[0].families[1].parents[0].email", is("clara@y.at"));
    }

    @Test
    void doesNotLeakPersonIds() {
        ObjectId ownFamily = persistFamily("Muster");
        persistPerson(ownFamily, "PARENT", "Anna", "anna@x.at");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", null);
        assign(ownChild.id, persistInstance(groupDefId, "Kaefergruppe"));

        String body = given().when().get("/api/v1/parent-directory")
            .then().statusCode(200)
            .extract().asString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains(ownChild.id.toHexString()),
                "Antwort darf keine Personen-IDs enthalten");
    }

    @Test
    void returnsEmptyGroupsWithoutAnyGroupAssignment() {
        ObjectId ownFamily = persistFamily("Muster");
        persistPerson(ownFamily, "PARENT", "Anna", "anna@x.at");
        persistPerson(ownFamily, "CHILD", "Lena", null);

        given().when().get("/api/v1/parent-directory")
            .then().statusCode(200)
            .body("groups.size()", is(0));
    }

    @Test
    void returnsEmptyResultWithoutSemester() {
        Semester.deleteAll();
        ObjectId ownFamily = persistFamily("Muster");
        persistPerson(ownFamily, "PARENT", "Anna", "anna@x.at");
        persistPerson(ownFamily, "CHILD", "Lena", null);

        given().when().get("/api/v1/parent-directory")
            .then().statusCode(200)
            .body("semesterId", nullValue())
            .body("groups.size()", is(0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ParentDirectoryResourceTest`
Expected: FAIL mit 404 — der Endpoint existiert nicht.

- [ ] **Step 3: Write the resource**

`backend/src/main/java/at/kigruapp/resource/ParentDirectoryResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.dto.ParentDirectoryDTO;
import at.kigruapp.entity.Person;
import at.kigruapp.security.CurrentUserService;
import at.kigruapp.service.ParentDirectoryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Kontrollierte Offenlegung der Elternkontakte innerhalb der eigenen Gruppen.
 * Der Endpoint nimmt bewusst keine Parameter entgegen: welche Gruppen sichtbar
 * sind, ergibt sich ausschließlich aus den Kindern der aufrufenden Familie.
 */
@Path("/api/v1/parent-directory")
@Produces(MediaType.APPLICATION_JSON)
public class ParentDirectoryResource {

    @Inject
    CurrentUserService currentUserService;

    @Inject
    ParentDirectoryService parentDirectoryService;

    @GET
    public ParentDirectoryDTO get() {
        Person current = currentUserService.getCurrentPerson();
        if (current == null || current.familyId == null) {
            throw new ForbiddenException();
        }
        return parentDirectoryService.buildForFamily(current.familyId);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ParentDirectoryResourceTest`
Expected: PASS, 4 Tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/ParentDirectoryResource.java backend/src/test/java/at/kigruapp/resource/ParentDirectoryResourceTest.java
git commit -m "feat(be): GET /api/v1/parent-directory liefert die Eltern-Uebersicht"
```

---

### Task 4: SecurityFilter-Whitelist

Ohne diesen Schritt ist der Endpoint in Produktion admin-only (Default-Deny).

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/security/SecurityFilter.java` (`isAllowed`, vor dem `return false;` in Zeile 106)
- Test: `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java` (neue Tests am Ende der Klasse)

**Interfaces:**
- Consumes: bestehende Struktur von `SecurityFilter.isAllowed(String path, String method, Person person)`
- Produces: keine neuen Signaturen

- [ ] **Step 1: Write the failing test**

Am Ende von `SecurityFilterTest` (vor der schließenden Klammer der Klasse) ergänzen:

```java
    // Eltern-Uebersicht: lesend fuer alle angemeldeten Eltern
    @Test
    void getParentDirectory_nonAdmin_allowed() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/parent-directory", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    @Test
    void getParentDirectory_withoutPerson_forbidden() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/parent-directory", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(null);

        filter.filter(ctx);

        assertForbidden();
    }

    @Test
    void postParentDirectory_nonAdmin_forbidden() {
        filter.oidcEnabled = true;
        givenPath("/api/v1/parent-directory", "POST");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SecurityFilterTest`
Expected: `getParentDirectory_nonAdmin_allowed` schlägt fehl (abortWith wurde aufgerufen); die beiden anderen sind bereits grün.

- [ ] **Step 3: Add the whitelist entry**

In `SecurityFilter.isAllowed` direkt vor dem Default-Kommentar in Zeile 105 einfügen:

```java
        // Eltern-Übersicht: Kontakte der eigenen Gruppen, lesend für alle angemeldeten Eltern.
        // Welche Gruppen sichtbar sind, entscheidet ausschließlich der Resource-Code.
        if (path.equals("/api/v1/parent-directory") && "GET".equals(method)) return true;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SecurityFilterTest`
Expected: PASS für alle drei neuen Tests, keine neuen Fehlschläge bei den bestehenden.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/security/SecurityFilter.java backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java
git commit -m "feat(be): parent-directory fuer angemeldete Eltern freischalten"
```

---

### Task 5: Frontend-Modell und Service

**Files:**
- Create: `frontend/src/app/shared/models/parent-directory.model.ts`
- Create: `frontend/src/app/eltern/services/parent-directory.service.ts`
- Test: `frontend/src/app/eltern/services/parent-directory.service.spec.ts`

**Interfaces:**
- Consumes: `ApiService.get<T>(path: string): Observable<T>` aus `core/services/api.service`
- Produces:
  - `interface ParentDirectory { semesterId: string | null; groups: ParentDirectoryGroup[] }`
  - `interface ParentDirectoryGroup { groupInstanceId: string; groupName: string | null; families: ParentDirectoryFamily[] }`
  - `interface ParentDirectoryFamily { familyId: string; isOwnFamily: boolean; children: string[]; parents: ParentDirectoryParent[]; address: string | null }`
  - `interface ParentDirectoryParent { firstName: string | null; lastName: string | null; email: string | null; phone: string | null }`
  - `ParentDirectoryService.load(): Observable<ParentDirectory>`

- [ ] **Step 1: Write the failing test**

`frontend/src/app/eltern/services/parent-directory.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ParentDirectoryService } from './parent-directory.service';
import { ParentDirectory } from '../../shared/models/parent-directory.model';

describe('ParentDirectoryService', () => {
  let service: ParentDirectoryService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ParentDirectoryService],
    });
    service = TestBed.inject(ParentDirectoryService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lädt das Verzeichnis von /parent-directory', () => {
    const payload: ParentDirectory = {
      semesterId: 's1',
      groups: [
        {
          groupInstanceId: 'g1',
          groupName: 'Käfergruppe',
          families: [
            {
              familyId: 'f1',
              isOwnFamily: true,
              children: ['Lena'],
              parents: [{ firstName: 'Anna', lastName: 'Muster', email: 'anna@x.at', phone: null }],
              address: 'Hauptstraße 1, 1010 Wien',
            },
          ],
        },
      ],
    };

    let result: ParentDirectory | undefined;
    service.load().subscribe((r) => (result = r));

    const req = httpMock.expectOne('/api/v1/parent-directory');
    expect(req.request.method).toBe('GET');
    req.flush(payload);

    expect(result).toEqual(payload);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --include='**/parent-directory.service.spec.ts'`
Expected: Compile-Fehler, Modul nicht gefunden.

- [ ] **Step 3: Write the model**

`frontend/src/app/shared/models/parent-directory.model.ts`:

```typescript
export interface ParentDirectoryParent {
  firstName: string | null;
  lastName: string | null;
  email: string | null;
  phone: string | null;
}

export interface ParentDirectoryFamily {
  familyId: string;
  isOwnFamily: boolean;
  children: string[];
  parents: ParentDirectoryParent[];
  address: string | null;
}

export interface ParentDirectoryGroup {
  groupInstanceId: string;
  groupName: string | null;
  families: ParentDirectoryFamily[];
}

export interface ParentDirectory {
  semesterId: string | null;
  groups: ParentDirectoryGroup[];
}
```

- [ ] **Step 4: Write the service**

`frontend/src/app/eltern/services/parent-directory.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ParentDirectory } from '../../shared/models/parent-directory.model';

@Injectable({ providedIn: 'root' })
export class ParentDirectoryService {
  constructor(private api: ApiService) {}

  load(): Observable<ParentDirectory> {
    return this.api.get<ParentDirectory>('/parent-directory');
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --include='**/parent-directory.service.spec.ts'`
Expected: PASS, 1 Test.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/models/parent-directory.model.ts frontend/src/app/eltern/services/parent-directory.service.ts frontend/src/app/eltern/services/parent-directory.service.spec.ts
git commit -m "feat(fe): Service und Modell fuer die Eltern-Uebersicht"
```

---

### Task 6: Eltern-Komponente

**Files:**
- Create: `frontend/src/app/eltern/eltern.component.ts`
- Create: `frontend/src/app/eltern/eltern.component.html`
- Create: `frontend/src/app/eltern/eltern.component.scss`
- Test: `frontend/src/app/eltern/eltern.component.spec.ts`

**Interfaces:**
- Consumes: `ParentDirectoryService.load()` (Task 5), `NotificationService` aus `shared/services/notification.service` mit den Methoden `error(message: string)` und `extractError(err: unknown): string`
- Produces: `ElternComponent` mit `groups: ParentDirectoryGroup[]`, `selectedGroupId: string | null`, `loading: boolean`, `failed: boolean`, `selectedGroup: ParentDirectoryGroup | null` (getter), `selectGroup(groupInstanceId: string): void`, `load(): void`

- [ ] **Step 1: Write the failing test**

`frontend/src/app/eltern/eltern.component.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ElternComponent } from './eltern.component';
import { ParentDirectoryService } from './services/parent-directory.service';
import { NotificationService } from '../shared/services/notification.service';
import { ParentDirectory } from '../shared/models/parent-directory.model';

describe('ElternComponent', () => {
  let fixture: ComponentFixture<ElternComponent>;
  let component: ElternComponent;
  let service: jasmine.SpyObj<ParentDirectoryService>;
  let notify: jasmine.SpyObj<NotificationService>;

  const directory: ParentDirectory = {
    semesterId: 's1',
    groups: [
      {
        groupInstanceId: 'g1',
        groupName: 'Käfergruppe',
        families: [
          {
            familyId: 'f1',
            isOwnFamily: true,
            children: ['Lena'],
            parents: [{ firstName: 'Anna', lastName: 'Muster', email: 'anna@x.at', phone: '0660 111' }],
            address: 'Hauptstraße 1, 1010 Wien',
          },
          {
            familyId: 'f2',
            isOwnFamily: false,
            children: ['Tim'],
            parents: [{ firstName: 'Clara', lastName: 'Sommer', email: null, phone: null }],
            address: null,
          },
        ],
      },
      {
        groupInstanceId: 'g2',
        groupName: 'Bienengruppe',
        families: [
          {
            familyId: 'f1',
            isOwnFamily: true,
            children: ['Paul'],
            parents: [{ firstName: 'Anna', lastName: 'Muster', email: 'anna@x.at', phone: '0660 111' }],
            address: 'Hauptstraße 1, 1010 Wien',
          },
        ],
      },
    ],
  };

  async function setup(response = of(directory)): Promise<void> {
    service = jasmine.createSpyObj<ParentDirectoryService>('ParentDirectoryService', ['load']);
    service.load.and.returnValue(response);
    notify = jasmine.createSpyObj<NotificationService>('NotificationService', ['success', 'error', 'extractError']);
    notify.extractError.and.returnValue('Fehler');

    await TestBed.configureTestingModule({
      imports: [ElternComponent],
      providers: [
        provideNoopAnimations(),
        { provide: ParentDirectoryService, useValue: service },
        { provide: NotificationService, useValue: notify },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ElternComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('wählt beim Laden die erste Gruppe vor und zeigt deren Familien', async () => {
    await setup();

    expect(component.selectedGroupId).toBe('g1');
    expect(component.selectedGroup?.families.length).toBe(2);

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('Lena');
    expect(fixture.nativeElement.textContent).toContain('Clara');
  });

  it('tauscht die Zeilen beim Gruppenwechsel', async () => {
    await setup();

    component.selectGroup('g2');
    fixture.detectChanges();

    expect(component.selectedGroup?.families.length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('Paul');
    expect(fixture.nativeElement.textContent).not.toContain('Tim');
  });

  it('markiert die eigene Familie', async () => {
    await setup();

    expect(fixture.nativeElement.textContent).toContain('(meine Familie)');
  });

  it('zeigt einen Hinweis, wenn keine Gruppen vorhanden sind', async () => {
    await setup(of({ semesterId: 's1', groups: [] }));

    expect(component.selectedGroupId).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('keiner Gruppe');
  });

  it('meldet Ladefehler und zeigt einen Wiederholen-Hinweis', async () => {
    await setup(throwError(() => new Error('boom')));

    expect(notify.error).toHaveBeenCalledWith('Fehler');
    expect(component.failed).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('Erneut laden');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --include='**/eltern.component.spec.ts'`
Expected: Compile-Fehler, `ElternComponent` existiert nicht.

- [ ] **Step 3: Write the component class**

`frontend/src/app/eltern/eltern.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ParentDirectoryService } from './services/parent-directory.service';
import { NotificationService } from '../shared/services/notification.service';
import { ParentDirectoryGroup } from '../shared/models/parent-directory.model';

@Component({
  selector: 'app-eltern',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatFormFieldModule, MatSelectModule, MatButtonModule, MatIconModule,
  ],
  templateUrl: './eltern.component.html',
  styleUrl: './eltern.component.scss',
})
export class ElternComponent implements OnInit {
  groups: ParentDirectoryGroup[] = [];
  selectedGroupId: string | null = null;
  loading = false;
  failed = false;

  constructor(
    private directory: ParentDirectoryService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.failed = false;
    this.directory.load().subscribe({
      next: (result) => {
        this.groups = result.groups;
        this.selectedGroupId = result.groups.length > 0 ? result.groups[0].groupInstanceId : null;
        this.loading = false;
      },
      error: (err) => {
        this.groups = [];
        this.selectedGroupId = null;
        this.loading = false;
        this.failed = true;
        this.notify.error(this.notify.extractError(err));
      },
    });
  }

  get selectedGroup(): ParentDirectoryGroup | null {
    return this.groups.find((g) => g.groupInstanceId === this.selectedGroupId) ?? null;
  }

  selectGroup(groupInstanceId: string): void {
    this.selectedGroupId = groupInstanceId;
  }

  parentName(parent: { firstName: string | null; lastName: string | null }): string {
    return [parent.firstName, parent.lastName].filter((part) => !!part).join(' ');
  }
}
```

- [ ] **Step 4: Write the template**

`frontend/src/app/eltern/eltern.component.html`:

```html
<div class="eltern-page">
  <h2>Eltern in unseren Gruppen</h2>

  @if (failed) {
    <div class="eltern-error">
      <p>Die Übersicht konnte nicht geladen werden.</p>
      <button mat-stroked-button type="button" (click)="load()">Erneut laden</button>
    </div>
  } @else if (!loading && groups.length === 0) {
    <p class="eltern-empty">
      Eure Kinder sind im laufenden Semester keiner Gruppe zugeteilt.
    </p>
  } @else if (groups.length > 0) {
    <mat-form-field appearance="outline" class="eltern-group-select">
      <mat-label>Gruppe</mat-label>
      <mat-select [ngModel]="selectedGroupId" (ngModelChange)="selectGroup($event)">
        @for (group of groups; track group.groupInstanceId) {
          <mat-option [value]="group.groupInstanceId">{{ group.groupName }}</mat-option>
        }
      </mat-select>
    </mat-form-field>

    <div class="eltern-table-scroll">
      <table class="eltern-table">
        <thead>
          <tr>
            <th>Kind(er)</th>
            <th>Eltern</th>
            <th>E-Mail</th>
            <th>Telefon</th>
            <th>Adresse</th>
          </tr>
        </thead>
        <tbody>
          @for (family of selectedGroup?.families ?? []; track family.familyId) {
            <tr [class.own-family]="family.isOwnFamily">
              <td>
                @for (child of family.children; track child) {
                  <div>{{ child }}</div>
                }
                @if (family.isOwnFamily) {
                  <div class="own-family-hint">(meine Familie)</div>
                }
              </td>
              <td>
                @for (parent of family.parents; track $index) {
                  <div>{{ parentName(parent) }}</div>
                }
              </td>
              <td>
                @for (parent of family.parents; track $index) {
                  <div>
                    @if (parent.email) {
                      <a [href]="'mailto:' + parent.email">{{ parent.email }}</a>
                    }
                  </div>
                }
              </td>
              <td>
                @for (parent of family.parents; track $index) {
                  <div>
                    @if (parent.phone) {
                      <a [href]="'tel:' + parent.phone">{{ parent.phone }}</a>
                    }
                  </div>
                }
              </td>
              <td>{{ family.address }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  }
</div>
```

- [ ] **Step 5: Write the styles**

`frontend/src/app/eltern/eltern.component.scss`:

```scss
.eltern-page {
  padding: 16px;
}

.eltern-group-select {
  min-width: 260px;
}

.eltern-table-scroll {
  overflow-x: auto;
}

.eltern-table {
  width: 100%;
  border-collapse: collapse;

  th,
  td {
    text-align: left;
    padding: 8px 12px;
    border-bottom: 1px solid rgba(0, 0, 0, 0.12);
    vertical-align: top;
    white-space: nowrap;
  }

  tr.own-family {
    background: rgba(63, 81, 181, 0.08);
  }
}

.own-family-hint {
  font-size: 12px;
  opacity: 0.7;
}

.eltern-empty,
.eltern-error {
  margin-top: 16px;
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --include='**/eltern.component.spec.ts'`
Expected: PASS, 5 Tests.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/eltern/eltern.component.ts frontend/src/app/eltern/eltern.component.html frontend/src/app/eltern/eltern.component.scss frontend/src/app/eltern/eltern.component.spec.ts
git commit -m "feat(fe): Eltern-Uebersicht mit Gruppen-Dropdown und Familientabelle"
```

---

### Task 7: Route und Menüeintrag

**Files:**
- Modify: `frontend/src/app/app.routes.ts` (nach dem `stunden`-Eintrag, Zeile 17–22)
- Modify: `frontend/src/app/app.component.html` (nach dem `/stunden`-Listeneintrag, Zeile 11–14)

**Interfaces:**
- Consumes: `ElternComponent` aus `./eltern/eltern.component` (Task 6), `authGuard` aus `./core/guards/auth.guard`
- Produces: Route `/eltern`

- [ ] **Step 1: Add the route**

In `frontend/src/app/app.routes.ts` nach dem `stunden`-Block einfügen:

```typescript
  {
    path: 'eltern',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./eltern/eltern.component').then(m => m.ElternComponent),
  },
```

- [ ] **Step 2: Add the menu entry**

In `frontend/src/app/app.component.html` direkt nach dem `/stunden`-Eintrag einfügen:

```html
      <a mat-list-item routerLink="/eltern" routerLinkActive="active">
        <mat-icon matListItemIcon>contacts</mat-icon>
        <span matListItemTitle>Eltern</span>
      </a>
```

- [ ] **Step 3: Run the full frontend suite**

Run: `cd frontend && npm test -- --watch=false`
Expected: PASS, keine neuen Fehlschläge gegenüber dem Baseline-Stand.

- [ ] **Step 4: Verify the production build**

Run: `cd frontend && npm run build`
Expected: Erfolgreicher AOT-Build ohne NG-Fehler.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/app.routes.ts frontend/src/app/app.component.html
git commit -m "feat(fe): Route und Menuepunkt fuer die Eltern-Uebersicht"
```

---

### Task 8: Gesamtlauf und Smoke-Test-Vorbereitung

**Files:**
- Keine Änderungen; reiner Verifikationsschritt.

**Interfaces:**
- Consumes: alle vorherigen Tasks
- Produces: nichts

- [ ] **Step 1: Backend-Gesamtlauf**

Run: `cd backend && ./mvnw test`
Expected: Dieselben 13 vorbestehenden Fehlschläge wie auf `main`, plus keine weiteren. Bei Abweichung: die abweichenden Tests einzeln nachfahren und die Ursache beheben, bevor der Task abgeschlossen wird.

- [ ] **Step 2: Frontend-Gesamtlauf**

Run: `cd frontend && npm test -- --watch=false`
Expected: Grün bis auf den bekannten Baseline-Fehlschlag.

- [ ] **Step 3: Commit-Übersicht prüfen**

```bash
git log --oneline main..HEAD
```
Expected: 7 Commits, einer je Task 1–7.

- [ ] **Step 4: Manuellen Smoke-Test notieren**

Dem Benutzer folgende Schritte zum manuellen Test melden (nicht selbst ausführen):
1. `start-dev.bat` starten.
2. Als Elternteil mit einem Kind in einer Gruppe anmelden.
3. Menüpunkt „Eltern" öffnen: Dropdown zeigt die eigene(n) Gruppe(n), die Tabelle die Familien; die eigene Zeile steht oben und ist markiert.
4. E-Mail- und Telefonlinks prüfen.
5. Bei mehreren Kindern in verschiedenen Gruppen: Dropdown umschalten und die Zeilenänderung prüfen.

---

## Self-Review

**Spec-Abdeckung:**

| Spec-Abschnitt | Task |
|---|---|
| Geteilte Helfer (`isChild`, Semester) | 1 |
| `ParentDirectoryService`-Ablauf inkl. Adressformat, Sortierung, leeres Semester | 2 |
| API-Contract, keine Personen-IDs, 403 ohne Person | 3 |
| SecurityFilter-Whitelist | 4 |
| Frontend-Service/Modell | 5 |
| Komponente: Dropdown, Tabelle, eigene Familie markiert, Leerzustand, Fehlerfall, horizontales Scrollen | 6 |
| Route `/eltern` mit `authGuard`, Menüeintrag | 7 |
| Testabdeckung und Baseline-Vergleich | 1–4, 6, 8 |

Sichtbare Felder (Kind, Elternname, E-Mail, Telefon, Adresse) sind in DTO (Task 2), API-Test (Task 3), Modell (Task 5) und Template (Task 6) durchgängig abgebildet.

**Platzhalter:** keine — jeder Code-Schritt enthält den vollständigen Code, jeder Test-Schritt die vollständigen Assertions.

**Typkonsistenz:** `ParentDirectoryDTO`-Record-Komponenten (`semesterId`, `groups`, `groupInstanceId`, `groupName`, `families`, `familyId`, `isOwnFamily`, `children`, `parents`, `address`, `firstName`, `lastName`, `email`, `phone`) stimmen mit den TypeScript-Interfaces in Task 5, den RestAssured-Pfaden in Task 3 und den Template-Zugriffen in Task 6 überein. `PersonLookupService`-Signaturen aus Task 1 werden in Task 2 unverändert verwendet.
