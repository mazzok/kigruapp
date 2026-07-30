# Mailjob-Empfänger aus Teams und Rollen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Mailjob adressiert eine beliebige, deduplizierte Kombination aus Gruppen, Elternteams, Vorstand, Team-Rollen und Vorstandsrollen statt genau eines Empfängermodus.

**Architecture:** `MailJob.recipientMode` + `recipientGroupDefinitionIds` werden durch `allParents: boolean` + `recipientSelections: List<RecipientSelection>` ersetzt. Eine `RecipientSelection` ist ein Paar aus `RecipientKind` (`GROUP` | `TEAM` | `ROLE`) und der `fieldInstanceId` des konkreten Teams/der Rolle/Gruppe. Der Resolver bündelt die Selektionen nach Art, löst `GROUP` weiter über Kind → Familie → Eltern auf und `TEAM`/`ROLE` direkt über `semester_assignments`, und vereinigt die Personen dedupliziert. Bestehende Jobs stellt eine Startup-Migration um.

**Tech Stack:** Quarkus 3 / MongoDB Panache / JUnit 5 + RestAssured (Backend), Angular 18 standalone components / Angular Material / Karma + Jasmine (Frontend).

## Global Constraints

- Backend-Tests laufen gegen die Datenbank `kigruapp_test`; niemals gegen die Dev-DB.
- Nested-POJOs in Panache-Entities brauchen einen öffentlichen No-Arg-Konstruktor und öffentliche Felder — Vorbild: `at.kigruapp.entity.FieldRef`.
- Migrationen sind `@ApplicationScoped @Startup`, tragen ihre ID in die Collection `migrations` ein und stellen darüber Idempotenz sicher. Die Arbeit steckt in einer paketprivaten Methode `run()`, die `onStart(@Observes StartupEvent)` aufruft, damit Tests sie direkt aufrufen können.
- Alle vier Zielgruppen-Töpfe folgen demselben Muster: Org-Tag → genau eine aktive Template-Definition (`outdatedAt == null`) → deren Field-Instances sind die einzelnen Teams/Rollen/Gruppen.
- Zuordnung Kind → `section` → erlaubte `fieldName`s:
  - `GROUP` → `section "group"` → `group`
  - `TEAM` → `section "team"` → `parent-team`, `board`
  - `ROLE` → `section "role"` → `parent-team-role`, `board-role`
- UI-Texte sind deutsch.
- Bekannter Baseline-Bruch: `AppComponent should create the app` schlägt im Frontend schon vor diesen Änderungen fehl (`No provider for HttpClient`). Nicht reparieren, aber auch nicht als eigenen Fehler verbuchen.

### Abweichung von der Spec

Die Spec sagt, Instance-IDs würden beim Speichern nicht auf Existenz geprüft. `MailJobResource.validateRecipientGroupDefinitionIds` prüft das für Gruppen heute aber bereits. Der Plan behält diese Prüfung und zieht sie symmetrisch auf `TEAM` und `ROLE` hoch (Task 2). Der Resolver bleibt davon unabhängig tolerant gegenüber Instances, die zwischen Speichern und Lauf verschwinden (Task 1) — das ist der Fall, den die Spec eigentlich meinte.

---

## File Structure

**Backend — neu:**
- `backend/src/main/java/at/kigruapp/entity/RecipientKind.java` — Enum der drei Empfängerarten.
- `backend/src/main/java/at/kigruapp/entity/RecipientSelection.java` — Paar aus Art und `fieldInstanceId`.
- `backend/src/main/java/at/kigruapp/migration/MailJobRecipientSelectionMigration.java` — Umstellung bestehender Jobs.
- `backend/src/test/java/at/kigruapp/migration/MailJobRecipientSelectionMigrationTest.java`

**Backend — geändert:**
- `backend/src/main/java/at/kigruapp/entity/MailJob.java` — neue Felder, alte raus.
- `backend/src/main/java/at/kigruapp/entity/RecipientMode.java` — **gelöscht**.
- `backend/src/main/java/at/kigruapp/service/RecipientResolverService.java` — Union-Auflösung.
- `backend/src/main/java/at/kigruapp/resource/MailJobResource.java` — Feldübernahme und Validierung.
- `backend/src/test/java/at/kigruapp/service/RecipientResolverServiceTest.java`
- `backend/src/test/java/at/kigruapp/resource/MailJobResourceTest.java`
- `backend/src/test/java/at/kigruapp/scheduler/MailJobRunTest.java`, `MailJobStartupRearmerTest.java`, `backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java`, `MailAccountResourceTest.java` — nur so weit, dass sie kompilieren.

**Frontend — geändert:**
- `frontend/src/app/shared/models/mail-job.model.ts` — Typen.
- `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.ts` — Laden der fünf Töpfe, Kodierung/Dekodierung der Optionswerte.
- `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.html` — Checkbox + ein `mat-select` mit fünf `mat-optgroup`.
- `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts`

---

### Task 1: Datenmodell und Resolver-Union

Ersetzt den Empfängermodus durch die Selektionsliste und baut die Auflösung auf Vereinigung um. `MailJobResource` wird hier nur so weit angefasst, dass der Code kompiliert — die eigentliche Validierung folgt in Task 2.

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/RecipientKind.java`
- Create: `backend/src/main/java/at/kigruapp/entity/RecipientSelection.java`
- Modify: `backend/src/main/java/at/kigruapp/entity/MailJob.java`
- Delete: `backend/src/main/java/at/kigruapp/entity/RecipientMode.java`
- Modify: `backend/src/main/java/at/kigruapp/service/RecipientResolverService.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/MailJobResource.java:139-189`
- Test: `backend/src/test/java/at/kigruapp/service/RecipientResolverServiceTest.java`

**Interfaces:**
- Produces:
  - `enum RecipientKind { GROUP, TEAM, ROLE }`
  - `class RecipientSelection { public RecipientKind kind; public ObjectId fieldInstanceId; RecipientSelection(); RecipientSelection(RecipientKind, ObjectId); }`
  - `MailJob.allParents: boolean`, `MailJob.recipientSelections: List<RecipientSelection>`
  - `RecipientResolverService.resolveAssignedParents(String section, List<ObjectId> instanceIds, ObjectId semesterId): List<Person>`
- Consumes: nichts.

- [ ] **Step 1: Write the failing test**

An `backend/src/test/java/at/kigruapp/service/RecipientResolverServiceTest.java`: den Import `at.kigruapp.entity.RecipientMode` entfernen, `at.kigruapp.entity.RecipientKind` und `at.kigruapp.entity.RecipientSelection` importieren. Die bestehende Methode `resolveDispatchesByRecipientModeAndAttachesProperties` **ersetzen** durch die Tests unten; `resolveGroupParentsReturnsDedupedParentsWithEmail` und `resolveAllParentsReturnsEveryParentWithEmail` bleiben unverändert.

Zusätzlich im `cleanup()`-Block zwei Definitionen ergänzen, direkt nach `emailDef = persistDefinition("email");`:

```java
        teamDef = persistDefinition("parent-team");
        roleDef = persistDefinition("board-role");
```

und die Felder oben bei `groupDef` deklarieren:

```java
    private FieldDefinition teamDef;
    private FieldDefinition roleDef;
```

Dazu ein Zuweisungs-Helfer neben `assignToGroup`:

```java
    private void assignToSection(ObjectId personId, ObjectId semesterId, String section,
                                 ObjectId definitionId, ObjectId instanceId) {
        SemesterAssignment sa = new SemesterAssignment();
        sa.personId = personId;
        sa.semesterId = semesterId;
        sa.section = section;
        sa.definitionId = definitionId;
        sa.fieldInstanceId = instanceId;
        semesterAssignments().insertOne(sa.toDocument());
    }
```

Und die neuen Tests:

```java
    @Test
    void resolveAssignedParentsReturnsParentsOfTeamWithEmail() {
        ObjectId semesterId = new ObjectId();
        ObjectId teamInstanceId = new ObjectId();

        Person parentWithEmail = persistPerson(new ObjectId(), "PARENT", "team@example.test");
        Person parentWithoutEmail = persistPerson(new ObjectId(), "PARENT", null);
        Person childInTeam = persistPerson(new ObjectId(), "CHILD", "child@example.test");
        Person unrelatedParent = persistPerson(new ObjectId(), "PARENT", "other@example.test");

        assignToSection(parentWithEmail.id, semesterId, "team", teamDef.id, teamInstanceId);
        assignToSection(parentWithoutEmail.id, semesterId, "team", teamDef.id, teamInstanceId);
        assignToSection(childInTeam.id, semesterId, "team", teamDef.id, teamInstanceId);
        assignToSection(unrelatedParent.id, semesterId, "team", teamDef.id, new ObjectId());

        List<Person> result = resolver.resolveAssignedParents("team", List.of(teamInstanceId), semesterId);

        assertEquals(1, result.size(), "only the parent with an email, not the child, not the other team");
        assertEquals(parentWithEmail.id, result.get(0).id);
    }

    @Test
    void resolveAssignedParentsIgnoresOtherSemesters() {
        ObjectId semesterId = new ObjectId();
        ObjectId otherSemesterId = new ObjectId();
        ObjectId roleInstanceId = new ObjectId();

        Person parent = persistPerson(new ObjectId(), "PARENT", "role@example.test");
        assignToSection(parent.id, otherSemesterId, "role", roleDef.id, roleInstanceId);

        List<Person> result = resolver.resolveAssignedParents("role", List.of(roleInstanceId), semesterId);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolveAssignedParentsReturnsEmptyForUnknownInstance() {
        assertTrue(resolver.resolveAssignedParents("team", List.of(new ObjectId()), new ObjectId()).isEmpty());
        assertTrue(resolver.resolveAssignedParents("team", List.of(), new ObjectId()).isEmpty());
    }

    @Test
    void resolveUnionsKindsAndDedupesByPerson() {
        FieldDefinition firstNameDef = persistDefinition("firstName");
        ObjectId semesterId = new ObjectId();
        ObjectId familyId = new ObjectId();
        ObjectId groupInstanceId = new ObjectId();
        ObjectId teamInstanceId = new ObjectId();
        ObjectId roleInstanceId = new ObjectId();

        // Parent A is reachable twice: via the group (through their child) and via the team.
        Person parentA = persistPerson(familyId, "PARENT", "a@example.test");
        parentA.basicProperties = new java.util.ArrayList<>(parentA.basicProperties);
        parentA.basicProperties.add(new FieldRef(firstNameDef.id, persistFieldInstance(firstNameDef.id, "Anna")));
        parentA.update();
        Person child = persistPerson(familyId, "CHILD", null);
        assignToGroup(child.id, semesterId, groupInstanceId);
        assignToSection(parentA.id, semesterId, "team", teamDef.id, teamInstanceId);

        // Parent B only via the role.
        Person parentB = persistPerson(new ObjectId(), "PARENT", "b@example.test");
        assignToSection(parentB.id, semesterId, "role", roleDef.id, roleInstanceId);

        MailJob job = new MailJob();
        job.allParents = false;
        job.recipientSelections = List.of(
                new RecipientSelection(RecipientKind.GROUP, groupInstanceId),
                new RecipientSelection(RecipientKind.TEAM, teamInstanceId),
                new RecipientSelection(RecipientKind.ROLE, roleInstanceId));

        List<RecipientResolverService.ResolvedRecipient> result = resolver.resolve(job, semesterId);

        assertEquals(2, result.size(), "parentA must appear exactly once despite two matching selections");
        List<String> emails = result.stream().map(RecipientResolverService.ResolvedRecipient::email).toList();
        assertTrue(emails.contains("a@example.test"));
        assertTrue(emails.contains("b@example.test"));
        RecipientResolverService.ResolvedRecipient a = result.stream()
                .filter(r -> r.email().equals("a@example.test")).findFirst().orElseThrow();
        assertEquals("Anna", a.properties().get("firstName"));
    }

    @Test
    void resolveWithAllParentsIgnoresSelections() {
        ObjectId semesterId = new ObjectId();
        Person parent = persistPerson(new ObjectId(), "PARENT", "all@example.test");
        assertNotNull(parent.id);

        MailJob job = new MailJob();
        job.allParents = true;
        job.recipientSelections = List.of(new RecipientSelection(RecipientKind.TEAM, new ObjectId()));

        List<RecipientResolverService.ResolvedRecipient> result = resolver.resolve(job, semesterId);

        assertEquals(1, result.size());
        assertEquals("all@example.test", result.get(0).email());
    }

    @Test
    void resolveWithoutAllParentsAndWithoutSelectionsReturnsNothing() {
        persistPerson(new ObjectId(), "PARENT", "nobody@example.test");

        MailJob job = new MailJob();
        job.allParents = false;
        job.recipientSelections = List.of();

        assertTrue(resolver.resolve(job, new ObjectId()).isEmpty());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=RecipientResolverServiceTest`
Expected: Compile-Fehler — `RecipientKind`, `RecipientSelection`, `MailJob.allParents`, `MailJob.recipientSelections` und `resolveAssignedParents` existieren nicht.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/at/kigruapp/entity/RecipientKind.java`:

```java
package at.kigruapp.entity;

/** What a {@link RecipientSelection} points at. Determines how it is resolved to parents. */
public enum RecipientKind {
    /** A group field instance. Resolved via the assigned children to their families' parents. */
    GROUP,
    /** A parent team or the board. Resolved directly from the parents' team assignments. */
    TEAM,
    /** A team role or a board role. Resolved directly from the parents' role assignments. */
    ROLE
}
```

`backend/src/main/java/at/kigruapp/entity/RecipientSelection.java`:

```java
package at.kigruapp.entity;

import org.bson.types.ObjectId;

/**
 * One recipient source of a MailJob: the field instance of a concrete group,
 * team or role. Mirrors {@link FieldRef}'s shape so the POJO codec can map it.
 */
public class RecipientSelection {
    public RecipientKind kind;
    public ObjectId fieldInstanceId;

    public RecipientSelection() {}

    public RecipientSelection(RecipientKind kind, ObjectId fieldInstanceId) {
        this.kind = kind;
        this.fieldInstanceId = fieldInstanceId;
    }
}
```

`MailJob.java` — die beiden Zeilen `recipientMode` und `recipientGroupDefinitionIds` ersetzen durch:

```java
    /** When true every parent is addressed and {@link #recipientSelections} is ignored. */
    public boolean allParents = false;
    public List<RecipientSelection> recipientSelections = new ArrayList<>();
```

`RecipientMode.java` löschen.

`RecipientResolverService.java` — `resolve` ersetzen und `resolveAssignedParents` ergänzen; `resolveGroupParents`, `resolveAllParents`, `isParent`, `resolveEmail` bleiben unverändert:

```java
    /**
     * Given a MailJob, produces the final (email, propertyMap) list. Selections
     * are resolved per kind and unioned, deduplicated by person id.
     */
    public List<ResolvedRecipient> resolve(MailJob job, ObjectId semesterId) {
        List<Person> parents = job.allParents
                ? resolveAllParents()
                : resolveSelections(job.recipientSelections, semesterId);

        Map<ObjectId, Map<String, String>> propertiesByPersonId = personPropertyResolver.resolve(parents);

        List<ResolvedRecipient> result = new ArrayList<>();
        for (Person parent : parents) {
            String email = resolveEmail(parent);
            if (email == null) continue;
            result.add(new ResolvedRecipient(email, propertiesByPersonId.getOrDefault(parent.id, Map.of())));
        }
        return result;
    }

    /** Buckets the selections by kind, resolves each bucket and unions the result. */
    private List<Person> resolveSelections(List<RecipientSelection> selections, ObjectId semesterId) {
        if (selections == null || selections.isEmpty()) {
            return List.of();
        }
        Map<RecipientKind, List<ObjectId>> byKind = new EnumMap<>(RecipientKind.class);
        for (RecipientSelection sel : selections) {
            if (sel == null || sel.kind == null || sel.fieldInstanceId == null) continue;
            byKind.computeIfAbsent(sel.kind, k -> new ArrayList<>()).add(sel.fieldInstanceId);
        }

        Map<ObjectId, Person> union = new LinkedHashMap<>();
        addAll(union, resolveGroupParents(byKind.get(RecipientKind.GROUP), semesterId));
        addAll(union, resolveAssignedParents("team", byKind.get(RecipientKind.TEAM), semesterId));
        addAll(union, resolveAssignedParents("role", byKind.get(RecipientKind.ROLE), semesterId));
        return new ArrayList<>(union.values());
    }

    private void addAll(Map<ObjectId, Person> union, List<Person> people) {
        for (Person p : people) {
            union.putIfAbsent(p.id, p);
        }
    }

    /**
     * Resolves the parents directly assigned to any of the given field instances
     * in the given semester section ("team" or "role"). Unlike groups, these
     * assignments already point at parents, so no family detour is needed.
     * Instances that no longer exist simply match nothing.
     */
    public List<Person> resolveAssignedParents(String section, List<ObjectId> instanceIds, ObjectId semesterId) {
        if (instanceIds == null || instanceIds.isEmpty() || semesterId == null) {
            return List.of();
        }
        Map<ObjectId, Person> deduped = new LinkedHashMap<>();
        for (Document doc : semesterAssignments().find(Filters.and(
                Filters.eq("section", section),
                Filters.eq("semesterId", semesterId),
                Filters.in("fieldInstanceId", instanceIds)))) {
            SemesterAssignment sa = SemesterAssignment.fromDocument(doc);
            if (sa.personId == null || deduped.containsKey(sa.personId)) {
                continue;
            }
            Person person = Person.findById(sa.personId);
            if (person != null && isParent(person) && hasNonBlankEmail(person)) {
                deduped.put(person.id, person);
            }
        }
        return new ArrayList<>(deduped.values());
    }
```

Importe in `RecipientResolverService.java` anpassen: `at.kigruapp.entity.RecipientMode` entfernen, `at.kigruapp.entity.RecipientKind`, `at.kigruapp.entity.RecipientSelection` und `java.util.EnumMap` ergänzen.

`MailJobResource.java` — in `applyFields` die beiden alten Zuweisungen ersetzen:

```java
        job.allParents = request.allParents;
        job.recipientSelections = request.recipientSelections == null
                ? new java.util.ArrayList<>()
                : request.recipientSelections;
```

In `validate(...)` den Aufruf `validateRecipientGroupDefinitionIds(request);` vorerst **entfernen**, ebenso die Methoden `validateRecipientGroupDefinitionIds` und `isGroupInstance` sowie den Import `at.kigruapp.entity.RecipientMode`. Die Validierung kommt in Task 2 zurück. `isGroupInstance` nutzt `mongoClient`/`databaseName`/`FieldDefinition` — diese Felder und Importe bleiben stehen, weil Task 2 sie wieder braucht; falls der Build über ungenutzte Importe stolpert, `FieldDefinition` und `Filters` vorübergehend entfernen und in Task 2 wieder ergänzen.

- [ ] **Step 4: Fix the remaining compile errors in other tests**

`RecipientMode` und `recipientGroupDefinitionIds` werden in weiteren Testklassen referenziert. Betroffene Stellen finden:

Run: `cd backend && grep -rln "RecipientMode\|recipientGroupDefinitionIds" src/test/java`

In jeder Fundstelle die Job-Konstruktion umschreiben: `recipientMode = RecipientMode.ALL_PARENTS` → `allParents = true`; `recipientMode = RecipientMode.GROUPS` mit `recipientGroupDefinitionIds = List.of(x)` → `allParents = false` plus `recipientSelections = List.of(new RecipientSelection(RecipientKind.GROUP, x))`. In JSON-Bodies von RestAssured-Tests (`MailJobResourceTest`) `"recipientMode": "ALL_PARENTS"` → `"allParents": true` und `"recipientGroupDefinitionIds": [...]` → `"recipientSelections": [{"kind":"GROUP","fieldInstanceId":"..."}]`. Assertions auf die alten Feldnamen entsprechend umstellen.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest='RecipientResolverServiceTest+MailJobResourceTest+MailJobRunTest+MailJobStartupRearmerTest'`
Expected: PASS

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && ./mvnw test`
Expected: Keine neuen Fehler gegenüber dem Baseline-Stand des Branches. Vor dem Weitermachen den Baseline-Stand kennen: `git stash && ./mvnw test; git stash pop`, falls unklar.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity backend/src/main/java/at/kigruapp/service/RecipientResolverService.java backend/src/main/java/at/kigruapp/resource/MailJobResource.java backend/src/test/java/at/kigruapp
git commit -m "feat(be): Mailjob-Empfaenger als Union aus Gruppen, Teams und Rollen"
```

---

### Task 2: Validierung der Selektionen

Stellt die in Task 1 entfernte Prüfung wieder her und zieht sie auf alle drei Arten hoch: jede `fieldInstanceId` muss auf eine existierende, nicht-veraltete Definition zeigen, deren `fieldName` zur gewählten Art passt.

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/MailJobResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/MailJobResourceTest.java`

**Interfaces:**
- Consumes: `RecipientKind`, `RecipientSelection`, `MailJob.recipientSelections` aus Task 1.
- Produces: HTTP 400 mit Meldung `recipientSelections contains an unknown or outdated <kind>: <id>` bzw. `recipientSelections entry is missing kind or fieldInstanceId`.

- [ ] **Step 1: Write the failing test**

In `backend/src/test/java/at/kigruapp/resource/MailJobResourceTest.java` ergänzen. Der bestehende Test, der eine gültige Gruppen-Instance anlegt, zeigt das Muster für `insertGroupInstance` — falls dort ein Helfer existiert, wiederverwenden, sonst diese beiden Helfer ergänzen:

```java
    private ObjectId persistDefinitionAndInstance(String fieldName, boolean outdated) {
        at.kigruapp.entity.FieldDefinition def = new at.kigruapp.entity.FieldDefinition();
        def.fieldName = fieldName;
        def.createdAt = java.time.Instant.now();
        if (outdated) {
            def.outdatedAt = java.time.Instant.now();
        }
        def.persist();
        ObjectId instanceId = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", instanceId).append("definitionId", def.id));
        return instanceId;
    }
```

```java
    @Test
    void acceptsSelectionsForGroupTeamAndRole() {
        ObjectId group = persistDefinitionAndInstance("group", false);
        ObjectId team = persistDefinitionAndInstance("parent-team", false);
        ObjectId board = persistDefinitionAndInstance("board", false);
        ObjectId teamRole = persistDefinitionAndInstance("parent-team-role", false);
        ObjectId boardRole = persistDefinitionAndInstance("board-role", false);

        given().contentType(ContentType.JSON)
                .body(jobBody("""
                        "allParents": false,
                        "recipientSelections": [
                          {"kind":"GROUP","fieldInstanceId":"%s"},
                          {"kind":"TEAM","fieldInstanceId":"%s"},
                          {"kind":"TEAM","fieldInstanceId":"%s"},
                          {"kind":"ROLE","fieldInstanceId":"%s"},
                          {"kind":"ROLE","fieldInstanceId":"%s"}
                        ]
                        """.formatted(group.toHexString(), team.toHexString(), board.toHexString(),
                        teamRole.toHexString(), boardRole.toHexString())))
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(201)
                .body("recipientSelections.size()", equalTo(5));
    }

    @Test
    void rejectsTeamSelectionPointingAtAGroupInstance() {
        ObjectId group = persistDefinitionAndInstance("group", false);

        given().contentType(ContentType.JSON)
                .body(jobBody("""
                        "allParents": false,
                        "recipientSelections": [{"kind":"TEAM","fieldInstanceId":"%s"}]
                        """.formatted(group.toHexString())))
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(400);
    }

    @Test
    void rejectsSelectionWithOutdatedDefinition() {
        ObjectId outdatedTeam = persistDefinitionAndInstance("parent-team", true);

        given().contentType(ContentType.JSON)
                .body(jobBody("""
                        "allParents": false,
                        "recipientSelections": [{"kind":"TEAM","fieldInstanceId":"%s"}]
                        """.formatted(outdatedTeam.toHexString())))
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(400);
    }

    @Test
    void skipsSelectionValidationWhenAllParentsIsSet() {
        given().contentType(ContentType.JSON)
                .body(jobBody("""
                        "allParents": true,
                        "recipientSelections": [{"kind":"TEAM","fieldInstanceId":"%s"}]
                        """.formatted(new ObjectId().toHexString())))
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(201);
    }
```

`jobBody(String recipientJson)` ist ein Helfer, der die Pflichtfelder um den übergebenen Empfänger-Teil ergänzt. Falls die Testklasse noch keinen hat, ergänzen — `senderAccountId` muss auf ein aktiviertes `MailAccount` zeigen, wie in den bestehenden Tests der Klasse:

```java
    private String jobBody(String recipientJson) {
        return """
                {
                  "name": "Job",
                  "templateId": "%s",
                  "subject": "Betreff",
                  "senderAccountId": "%s",
                  "cron": "0 0 8 * * ?",
                  %s
                }
                """.formatted(templateId.toHexString(), accountId, recipientJson);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MailJobResourceTest`
Expected: `rejectsTeamSelectionPointingAtAGroupInstance` und `rejectsSelectionWithOutdatedDefinition` schlagen fehl — es kommt 201 statt 400, weil noch nichts validiert wird.

- [ ] **Step 3: Write minimal implementation**

In `MailJobResource.java` in `validate(...)` nach `validateSenderAccountId(request.senderAccountId);` wieder aufnehmen:

```java
        validateRecipientSelections(request);
```

Und die entfernten Methoden durch diese ersetzen:

```java
    /** fieldNames a selection of the given kind may legitimately point at. */
    private static final Map<RecipientKind, Set<String>> ALLOWED_FIELD_NAMES = Map.of(
            RecipientKind.GROUP, Set.of("group"),
            RecipientKind.TEAM, Set.of("parent-team", "board"),
            RecipientKind.ROLE, Set.of("parent-team-role", "board-role"));

    /**
     * Each selection must point at an existing field instance whose definition is
     * not outdated and matches the selection's kind. Skipped entirely when the job
     * addresses all parents, because the selections are ignored at run time then.
     */
    private void validateRecipientSelections(MailJob request) {
        if (request.allParents || request.recipientSelections == null) {
            return;
        }
        for (RecipientSelection sel : request.recipientSelections) {
            if (sel == null || sel.kind == null || sel.fieldInstanceId == null) {
                throw new BadRequestException("recipientSelections entry is missing kind or fieldInstanceId");
            }
            if (!matchesKind(sel)) {
                throw new BadRequestException("recipientSelections contains an unknown or outdated "
                        + sel.kind + ": " + sel.fieldInstanceId);
            }
        }
    }

    private boolean matchesKind(RecipientSelection sel) {
        Document inst = mongoClient.getDatabase(databaseName)
                .getCollection("field_instances")
                .find(Filters.eq("_id", sel.fieldInstanceId))
                .first();
        if (inst == null) {
            return false;
        }
        ObjectId definitionId = inst.getObjectId("definitionId");
        if (definitionId == null) {
            return false;
        }
        FieldDefinition def = FieldDefinition.findById(definitionId);
        return def != null && def.outdatedAt == null
                && ALLOWED_FIELD_NAMES.get(sel.kind).contains(def.fieldName);
    }
```

Importe ergänzen: `at.kigruapp.entity.RecipientKind`, `at.kigruapp.entity.RecipientSelection`, `java.util.Map`, `java.util.Set`; `at.kigruapp.entity.FieldDefinition` und `com.mongodb.client.model.Filters` müssen (wieder) vorhanden sein.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=MailJobResourceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/MailJobResource.java backend/src/test/java/at/kigruapp/resource/MailJobResourceTest.java
git commit -m "feat(be): Validierung der Mailjob-Empfaengerselektionen je Art"
```

---

### Task 3: Startup-Migration bestehender Jobs

Stellt in der DB liegende Jobs vom alten Modus auf die Selektionsliste um und entfernt die Altfelder.

**Files:**
- Create: `backend/src/main/java/at/kigruapp/migration/MailJobRecipientSelectionMigration.java`
- Test: `backend/src/test/java/at/kigruapp/migration/MailJobRecipientSelectionMigrationTest.java`

**Interfaces:**
- Consumes: die Feldnamen `allParents` / `recipientSelections` aus Task 1.
- Produces: `MailJobRecipientSelectionMigration.run()` (paketprivat, für Tests aufrufbar), Migrations-ID `mailjob-recipient-selections-v1`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/at/kigruapp/migration/MailJobRecipientSelectionMigrationTest.java`:

```java
package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MailJobRecipientSelectionMigrationTest {

    private static final String MIGRATION_ID = "mailjob-recipient-selections-v1";

    @Inject
    MailJobRecipientSelectionMigration migration;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoDatabase db() {
        return mongoClient.getDatabase(databaseName);
    }

    @BeforeEach
    void reset() {
        db().getCollection("mail_jobs").deleteMany(new Document());
        db().getCollection("migrations").deleteMany(new Document("_id", MIGRATION_ID));
    }

    private Document jobById(ObjectId id) {
        return db().getCollection("mail_jobs").find(new Document("_id", id)).first();
    }

    @Test
    void allParentsModeBecomesAllParentsFlag() {
        ObjectId id = new ObjectId();
        db().getCollection("mail_jobs").insertOne(new Document("_id", id)
                .append("name", "Alle").append("recipientMode", "ALL_PARENTS")
                .append("recipientGroupDefinitionIds", List.of()));

        migration.run();

        Document job = jobById(id);
        assertTrue(job.getBoolean("allParents"));
        assertEquals(List.of(), job.get("recipientSelections"));
        assertFalse(job.containsKey("recipientMode"));
        assertFalse(job.containsKey("recipientGroupDefinitionIds"));
    }

    @Test
    void groupsModeBecomesGroupSelections() {
        ObjectId id = new ObjectId();
        ObjectId g1 = new ObjectId();
        ObjectId g2 = new ObjectId();
        db().getCollection("mail_jobs").insertOne(new Document("_id", id)
                .append("name", "Gruppen").append("recipientMode", "GROUPS")
                .append("recipientGroupDefinitionIds", List.of(g1, g2)));

        migration.run();

        Document job = jobById(id);
        assertFalse(job.getBoolean("allParents"));
        List<Document> selections = job.getList("recipientSelections", Document.class);
        assertEquals(2, selections.size());
        assertEquals("GROUP", selections.get(0).getString("kind"));
        assertEquals(g1, selections.get(0).getObjectId("fieldInstanceId"));
        assertEquals(g2, selections.get(1).getObjectId("fieldInstanceId"));
        assertFalse(job.containsKey("recipientMode"));
    }

    @Test
    void missingModeDefaultsToAllParents() {
        ObjectId id = new ObjectId();
        db().getCollection("mail_jobs").insertOne(new Document("_id", id).append("name", "Ohne Modus"));

        migration.run();

        assertTrue(jobById(id).getBoolean("allParents"));
    }

    @Test
    void secondRunIsANoop() {
        ObjectId id = new ObjectId();
        ObjectId g1 = new ObjectId();
        db().getCollection("mail_jobs").insertOne(new Document("_id", id)
                .append("name", "Gruppen").append("recipientMode", "GROUPS")
                .append("recipientGroupDefinitionIds", List.of(g1)));

        migration.run();
        // Simulate a job saved after the migration; a second run must not touch it.
        db().getCollection("mail_jobs").updateOne(new Document("_id", id),
                new Document("$set", new Document("allParents", true)));
        migration.run();

        assertTrue(jobById(id).getBoolean("allParents"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MailJobRecipientSelectionMigrationTest`
Expected: Compile-Fehler — `MailJobRecipientSelectionMigration` existiert nicht.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/at/kigruapp/migration/MailJobRecipientSelectionMigration.java`:

```java
package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time: replace the mutually exclusive {@code recipientMode} /
 * {@code recipientGroupDefinitionIds} pair with {@code allParents} plus the
 * combinable {@code recipientSelections} list. Idempotent via the migrations
 * collection.
 */
@ApplicationScoped
@Startup
public class MailJobRecipientSelectionMigration {

    private static final String MIGRATION_ID = "mailjob-recipient-selections-v1";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@Observes StartupEvent ev) {
        run();
    }

    void run() {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> migrations = db.getCollection("migrations");
        if (migrations.find(new Document("_id", MIGRATION_ID)).first() != null) {
            return;
        }

        MongoCollection<Document> jobs = db.getCollection("mail_jobs");
        for (Document job : jobs.find()) {
            String mode = job.getString("recipientMode");
            boolean allParents = !"GROUPS".equals(mode);

            List<Document> selections = new ArrayList<>();
            if (!allParents) {
                for (ObjectId instanceId : job.getList("recipientGroupDefinitionIds", ObjectId.class, List.of())) {
                    selections.add(new Document("kind", "GROUP").append("fieldInstanceId", instanceId));
                }
            }

            jobs.updateOne(new Document("_id", job.getObjectId("_id")), new Document()
                    .append("$set", new Document("allParents", allParents)
                            .append("recipientSelections", selections))
                    .append("$unset", new Document("recipientMode", "")
                            .append("recipientGroupDefinitionIds", "")));
        }

        migrations.insertOne(new Document("_id", MIGRATION_ID));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=MailJobRecipientSelectionMigrationTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/migration/MailJobRecipientSelectionMigration.java backend/src/test/java/at/kigruapp/migration/MailJobRecipientSelectionMigrationTest.java
git commit -m "feat(be): Migration bestehender Mailjobs auf recipientSelections"
```

---

### Task 4: Frontend-Modell und Auswahl-Logik

Stellt Typen und Komponentenlogik um: fünf Töpfe laden, Optionswerte kodieren und dekodieren, Selektionen speichern.

**Files:**
- Modify: `frontend/src/app/shared/models/mail-job.model.ts`
- Modify: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.ts`
- Test: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts`

**Interfaces:**
- Consumes: Backend-Felder `allParents`, `recipientSelections` aus Task 1.
- Produces:
  - `type RecipientKind = 'GROUP' | 'TEAM' | 'ROLE'`
  - `interface RecipientSelection { kind: RecipientKind; fieldInstanceId: string }`
  - `MailJobEditorComponent.recipientOptionValues: string[]` — die aktuell gewählten `"<KIND>:<id>"`-Werte
  - `MailJobEditorComponent.onRecipientSelectionChange(values: string[]): void`
  - `MailJobEditorComponent.optionValue(kind: RecipientKind, instanceId: string): string`
  - `MailJobEditorComponent.groups | parentTeams | boardTeams | teamRoles | boardRoles: FieldInstanceDTO[]`
  - `MailJobEditorComponent.instanceLabel(i: FieldInstanceDTO): string` (ersetzt `groupLabel`)

- [ ] **Step 1: Write the failing test**

`frontend/src/app/shared/models/mail-job.model.ts` wird in Step 3 geändert; der Test hier greift auf die neuen Typen zu. In `mail-job-editor.component.spec.ts`:

`FakeOrganisationService` so umbauen, dass es je Tag eine eigene Organisation liefert:

```ts
class FakeOrganisationService {
  orgs: Record<string, OrganisationDTO> = {
    groups: {
      id: 'org-groups', tag: 'groups', entries: [],
      definitions: [{ id: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false }],
    },
    'parent-teams': {
      id: 'org-teams', tag: 'parent-teams', entries: [],
      definitions: [{ id: 'def-team', fieldName: 'parent-team', label: { de: 'Teams' }, jsonSchema: {}, required: false }],
    },
    board: {
      id: 'org-board', tag: 'board', entries: [],
      definitions: [{ id: 'def-board', fieldName: 'board', label: { de: 'Vorstand' }, jsonSchema: {}, required: false }],
    },
    'parent-team-roles': {
      id: 'org-team-roles', tag: 'parent-team-roles', entries: [],
      definitions: [{ id: 'def-team-role', fieldName: 'parent-team-role', label: { de: 'Team-Rollen' }, jsonSchema: {}, required: false }],
    },
    'board-roles': {
      id: 'org-board-roles', tag: 'board-roles', entries: [],
      definitions: [{ id: 'def-board-role', fieldName: 'board-role', label: { de: 'Vorstandsrollen' }, jsonSchema: {}, required: false }],
    },
  };
  getByTag(tag: string) {
    return of(this.orgs[tag]);
  }
}
```

`FakeFieldInstanceService` liefert Instances je Definition:

```ts
class FakeFieldInstanceService {
  byDefinition: Record<string, FieldInstanceDTO[]> = {
    'def-group': [
      { id: 'g1', definitionId: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Rote Gruppe' }, definitionOutdated: false },
      { id: 'g2', definitionId: 'def-group', fieldName: 'group', label: { de: 'Gruppen' }, jsonSchema: {}, required: false, value: { label: 'Blaue Gruppe' }, definitionOutdated: false },
    ],
    'def-team': [
      { id: 't1', definitionId: 'def-team', fieldName: 'parent-team', label: { de: 'Teams' }, jsonSchema: {}, required: false, value: { label: 'Gartenteam' }, definitionOutdated: false },
    ],
    'def-board': [
      { id: 'b1', definitionId: 'def-board', fieldName: 'board', label: { de: 'Vorstand' }, jsonSchema: {}, required: false, value: { label: 'Vorstand' }, definitionOutdated: false },
    ],
    'def-team-role': [
      { id: 'tr1', definitionId: 'def-team-role', fieldName: 'parent-team-role', label: { de: 'Team-Rollen' }, jsonSchema: {}, required: false, value: { label: 'Teamleitung' }, definitionOutdated: false },
    ],
    'def-board-role': [
      { id: 'br1', definitionId: 'def-board-role', fieldName: 'board-role', label: { de: 'Vorstandsrollen' }, jsonSchema: {}, required: false, value: { label: 'Obfrau' }, definitionOutdated: false },
    ],
  };
  listByDefinitionId(definitionId: string) {
    return of(this.byDefinition[definitionId] ?? []);
  }
}
```

Im `FakeMailJobService` den Job auf die neuen Felder umstellen: `recipientMode: 'ALL_PARENTS', recipientGroupDefinitionIds: []` → `allParents: true, recipientSelections: []`.

Neue Tests:

```ts
  it('loads all five recipient pools on init', () => {
    expect(component.groups.map((g) => g.id)).toEqual(['g1', 'g2']);
    expect(component.parentTeams.map((t) => t.id)).toEqual(['t1']);
    expect(component.boardTeams.map((t) => t.id)).toEqual(['b1']);
    expect(component.teamRoles.map((r) => r.id)).toEqual(['tr1']);
    expect(component.boardRoles.map((r) => r.id)).toEqual(['br1']);
  });

  it('maps encoded option values to recipientSelections on save', () => {
    component.newJob();
    component.form.patchValue({
      name: 'Job', templateId: 't1', subject: 'Betreff', senderAccountId: 'acc1', cron: '0 0 8 * * ?',
    });
    component.onRecipientSelectionChange(['GROUP:g1', 'TEAM:b1', 'ROLE:tr1']);

    component.save();

    expect(jobService.createCalls.length).toBe(1);
    expect(jobService.createCalls[0].allParents).toBeFalse();
    expect(jobService.createCalls[0].recipientSelections).toEqual([
      { kind: 'GROUP', fieldInstanceId: 'g1' },
      { kind: 'TEAM', fieldInstanceId: 'b1' },
      { kind: 'ROLE', fieldInstanceId: 'tr1' },
    ]);
  });

  it('decodes an existing job back into option values', () => {
    component.selectForEdit({
      ...jobService.jobs[0],
      allParents: false,
      recipientSelections: [
        { kind: 'TEAM', fieldInstanceId: 't1' },
        { kind: 'ROLE', fieldInstanceId: 'br1' },
      ],
    });

    expect(component.recipientOptionValues).toEqual(['TEAM:t1', 'ROLE:br1']);
    expect(component.form.value.allParents).toBeFalse();
  });

  it('keeps the selection when all-parents is toggled on and off', () => {
    component.newJob();
    component.onRecipientSelectionChange(['TEAM:t1']);

    component.form.patchValue({ allParents: true });
    expect(component.recipientOptionValues).toEqual(['TEAM:t1']);

    component.form.patchValue({ allParents: false });
    expect(component.recipientOptionValues).toEqual(['TEAM:t1']);
  });

  it('sends an empty selection when all parents is set', () => {
    component.newJob();
    component.form.patchValue({
      name: 'Job', templateId: 't1', subject: 'Betreff', senderAccountId: 'acc1', cron: '0 0 8 * * ?',
      allParents: true,
    });
    component.onRecipientSelectionChange(['TEAM:t1']);

    component.save();

    expect(jobService.createCalls[0].allParents).toBeTrue();
    expect(jobService.createCalls[0].recipientSelections).toEqual([]);
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: Kompilierfehler bzw. FAIL — `parentTeams`, `onRecipientSelectionChange`, `recipientOptionValues`, `allParents` existieren nicht.

- [ ] **Step 3: Write minimal implementation**

`frontend/src/app/shared/models/mail-job.model.ts` vollständig ersetzen:

```ts
export type RecipientKind = 'GROUP' | 'TEAM' | 'ROLE';

export interface RecipientSelection {
  kind: RecipientKind;
  fieldInstanceId: string;
}

export interface MailJob {
  id: string;
  name: string;
  templateId: string;
  subject: string;
  senderAccountId: string;
  cron: string;
  allParents: boolean;
  recipientSelections: RecipientSelection[];
  active: boolean;
  lastRunAt: string | null;
  lastRunStatus: string | null;
  lastRunError: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface SaveMailJobRequest {
  name: string;
  templateId: string;
  subject: string;
  senderAccountId: string;
  cron: string;
  allParents: boolean;
  recipientSelections: RecipientSelection[];
}
```

In `mail-job-editor.component.ts`:

Import anpassen:

```ts
import { MailJob, RecipientKind, RecipientSelection, SaveMailJobRequest } from '../../../shared/models/mail-job.model';
```

`MatRadioModule` durch `MatCheckboxModule` ersetzen — im `imports`-Array und im Import-Block:

```ts
import { MatCheckboxModule } from '@angular/material/checkbox';
```

Felder: `groups` beibehalten, vier weitere ergänzen, dazu die gewählten Optionswerte:

```ts
  /** Selectable pools; each is the field instances of that pool's single template definition. */
  groups: FieldInstanceDTO[] = [];
  parentTeams: FieldInstanceDTO[] = [];
  boardTeams: FieldInstanceDTO[] = [];
  teamRoles: FieldInstanceDTO[] = [];
  boardRoles: FieldInstanceDTO[] = [];

  /**
   * Currently picked options, encoded as "<KIND>:<fieldInstanceId>". The kind
   * travels in the value because board and parent teams sit in separate
   * optgroups but resolve to the same kind.
   */
  recipientOptionValues: string[] = [];
```

Formular: `recipientMode` und `recipientGroupDefinitionIds` ersetzen durch:

```ts
    allParents: new FormControl<boolean>(true, { nonNullable: true }),
```

`ngOnInit` — den `getByTag('groups')`-Block ersetzen durch:

```ts
    this.loadPool('groups', 'group', (i) => (this.groups = i));
    this.loadPool('parent-teams', 'parent-team', (i) => (this.parentTeams = i));
    this.loadPool('board', 'board', (i) => (this.boardTeams = i));
    this.loadPool('parent-team-roles', 'parent-team-role', (i) => (this.teamRoles = i));
    this.loadPool('board-roles', 'board-role', (i) => (this.boardRoles = i));
```

Und die Hilfsmethode ergänzen:

```ts
  /**
   * Each pool is an organisation tag holding exactly one active template
   * definition; the pickable entries are that definition's field instances.
   */
  private loadPool(tag: string, fieldName: string, assign: (instances: FieldInstanceDTO[]) => void): void {
    this.organisationService.getByTag(tag).subscribe({
      next: (org) => {
        const templateDef = org?.definitions?.find((d) => d.fieldName === fieldName && !d.outdatedAt);
        if (!templateDef?.id) return;
        this.fieldInstanceService.listByDefinitionId(templateDef.id).subscribe((instances) => assign(instances));
      },
      error: () => assign([]),
    });
  }
```

`onGroupsChange` und `selectAllParents` ersetzen durch:

```ts
  onRecipientSelectionChange(values: string[]): void {
    this.recipientOptionValues = values ?? [];
  }

  /** Encodes one pickable entry as the option's value. */
  optionValue(kind: RecipientKind, instanceId: string): string {
    return `${kind}:${instanceId}`;
  }

  private toSelections(values: string[]): RecipientSelection[] {
    return values.map((v) => {
      const separator = v.indexOf(':');
      return {
        kind: v.slice(0, separator) as RecipientKind,
        fieldInstanceId: v.slice(separator + 1),
      };
    });
  }
```

`groupLabel` in `instanceLabel` umbenennen (Signatur und Rumpf bleiben gleich):

```ts
  /** Display name of a pickable entry (its value's label), with a safe fallback. */
  instanceLabel(i: FieldInstanceDTO): string {
    const label = (i.value as { label?: string } | null)?.label;
    return label || i.label?.['de'] || i.fieldName;
  }
```

`selectForEdit` — die beiden Empfängerzeilen im `patchValue` durch `allParents: job.allParents` ersetzen und danach ergänzen:

```ts
    this.recipientOptionValues = (job.recipientSelections ?? [])
      .map((s) => this.optionValue(s.kind, s.fieldInstanceId));
```

`newJob` und `closeEditor` — im `reset(...)` die beiden Empfängerfelder durch `allParents: true` ersetzen und in beiden Methoden ergänzen:

```ts
    this.recipientOptionValues = [];
```

`save` — die beiden Empfängerzeilen im Request ersetzen durch:

```ts
      allParents: v.allParents ?? true,
      recipientSelections: v.allParents ? [] : this.toSelections(this.recipientOptionValues),
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS bis auf den bekannten `AppComponent should create the app`-Baseline-Fehler.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/models/mail-job.model.ts frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.ts frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts
git commit -m "feat(fe): Mailjob-Empfaengerauswahl als kombinierte Selektionsliste"
```

---

### Task 5: Empfänger-UI mit Optgroups

Ersetzt Radio-Button und Gruppen-Select durch eine Checkbox und ein Multi-Select mit fünf Gruppenüberschriften.

**Files:**
- Modify: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.html:88-107`
- Test: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts`

**Interfaces:**
- Consumes: `recipientOptionValues`, `onRecipientSelectionChange`, `optionValue`, `instanceLabel` und die fünf Pool-Felder aus Task 4.
- Produces: nichts für spätere Tasks.

- [ ] **Step 1: Write the failing test**

Das bestehende `describe('MailJobEditorComponent')` instanziiert die Komponente direkt mit `new MailJobEditorComponent(...)` und rendert kein Template — es gibt dort also weder `TestBed` noch `fixture`. Diese Tests brauchen ein gerendertes Template, kommen deshalb in ein **zweites, eigenes** `describe` am Ende derselben Datei. Die Fake-Klassen aus Task 4 werden wiederverwendet.

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

describe('MailJobEditorComponent (Template)', () => {
  let fixture: ComponentFixture<MailJobEditorComponent>;
  let component: MailJobEditorComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MailJobEditorComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MailJobService, useClass: FakeMailJobService },
        { provide: MailTemplateService, useClass: FakeMailTemplateService },
        { provide: MailAccountService, useClass: FakeMailAccountService },
        { provide: OrganisationService, useClass: FakeOrganisationService },
        { provide: FieldInstanceService, useClass: FakeFieldInstanceService },
        { provide: NotificationService, useClass: FakeNotificationService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(MailJobEditorComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ... die drei Tests unten hier hinein
});
```

```ts
Die Optgroups liegen erst im DOM, wenn das Select-Panel offen ist — und dann im CDK-Overlay, nicht unter `fixture.nativeElement`. Dieser Helfer gehört mit in das neue `describe`:

```ts
  /** Opens the recipient select and returns the optgroup labels from the overlay. */
  function openAndReadOptgroupLabels(): (string | undefined)[] {
    const trigger: HTMLElement = fixture.nativeElement.querySelector('.recipient-field .mat-mdc-select-trigger');
    trigger.click();
    fixture.detectChanges();
    return Array.from(document.querySelectorAll('.mat-mdc-optgroup .mat-mdc-optgroup-label'))
      .map((el) => (el as HTMLElement).textContent?.trim());
  }
```

```ts
  it('renders one optgroup per non-empty pool', () => {
    component.newJob();
    component.form.patchValue({ allParents: false });
    fixture.detectChanges();

    expect(openAndReadOptgroupLabels())
      .toEqual(['Gruppen', 'Elternteams', 'Vorstand', 'Team-Rollen', 'Vorstandsrollen']);
  });

  it('omits the optgroup of an empty pool', () => {
    component.newJob();
    component.form.patchValue({ allParents: false });
    component.boardRoles = [];
    fixture.detectChanges();

    expect(openAndReadOptgroupLabels()).not.toContain('Vorstandsrollen');
  });

  it('hides the recipient select while all parents is checked', () => {
    component.newJob();
    component.form.patchValue({ allParents: true });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.recipient-select')).toBeNull();

    component.form.patchValue({ allParents: false });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.recipient-select')).not.toBeNull();
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — es gibt weder `mat-optgroup` noch `.recipient-select`.

- [ ] **Step 3: Write minimal implementation**

In `mail-job-editor.component.html` den Block von `<section class="card">` mit `<p class="eyebrow">Empfänger</p>` bis zum schließenden `</section>` (Zeilen 88–107) ersetzen durch:

```html
    <section class="card">
      <p class="eyebrow">Empfänger</p>
      <mat-checkbox formControlName="allParents">Alle Eltern</mat-checkbox>

      <div class="recipient-select" *ngIf="!form.value.allParents">
        <p class="sub-label">Zielgruppen auswählen</p>
        <mat-form-field appearance="outline" class="recipient-field">
          <mat-label>Gruppen, Teams und Rollen</mat-label>
          <mat-select multiple
                      [value]="recipientOptionValues"
                      (selectionChange)="onRecipientSelectionChange($event.value)">
            <mat-optgroup label="Gruppen" *ngIf="groups.length">
              <mat-option *ngFor="let g of groups" [value]="optionValue('GROUP', g.id)">{{ instanceLabel(g) }}</mat-option>
            </mat-optgroup>
            <mat-optgroup label="Elternteams" *ngIf="parentTeams.length">
              <mat-option *ngFor="let t of parentTeams" [value]="optionValue('TEAM', t.id)">{{ instanceLabel(t) }}</mat-option>
            </mat-optgroup>
            <mat-optgroup label="Vorstand" *ngIf="boardTeams.length">
              <mat-option *ngFor="let b of boardTeams" [value]="optionValue('TEAM', b.id)">{{ instanceLabel(b) }}</mat-option>
            </mat-optgroup>
            <mat-optgroup label="Team-Rollen" *ngIf="teamRoles.length">
              <mat-option *ngFor="let r of teamRoles" [value]="optionValue('ROLE', r.id)">{{ instanceLabel(r) }}</mat-option>
            </mat-optgroup>
            <mat-optgroup label="Vorstandsrollen" *ngIf="boardRoles.length">
              <mat-option *ngFor="let r of boardRoles" [value]="optionValue('ROLE', r.id)">{{ instanceLabel(r) }}</mat-option>
            </mat-optgroup>
          </mat-select>
        </mat-form-field>
      </div>
    </section>
```

In `mail-job-editor.component.scss` die Regel für `.group-select` in `.recipient-field` umbenennen; falls es eine Regel `.groups` gibt, in `.recipient-select` umbenennen.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS bis auf den bekannten `AppComponent`-Baseline-Fehler.

- [ ] **Step 5: Verify the build**

Run: `cd frontend && npm run build`
Expected: Erfolgreich, keine Template-Fehler.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.html frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.scss frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts
git commit -m "feat(fe): Empfaengerauswahl als Optgroup-Select mit Alle-Eltern-Schalter"
```

---

## Manuelle Abnahme

Nach Task 5, im laufenden Dev-Setup:

1. Bestehenden Mailjob mit Gruppen-Empfängern öffnen — die Gruppen müssen nach der Migration weiterhin ausgewählt sein.
2. Job auf „Alle Eltern" stellen, speichern, wieder öffnen — Checkbox gesetzt, Select ausgeblendet.
3. Neuen Job mit einer Gruppe, einem Elternteam und einer Vorstandsrolle anlegen und speichern.
4. Job aktivieren und den nächsten Lauf abwarten (oder Cron kurzfristig setzen): im Log muss die Empfängerzahl der Vereinigung entsprechen, ein Elternteil, das über zwei Selektionen erreichbar ist, darf nur eine Mail bekommen.
