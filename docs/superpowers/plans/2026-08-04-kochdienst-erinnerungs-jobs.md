# Kochdienst-Erinnerungs-Jobs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kochdienst-Erinnerungen werden zu mehrfach anlegbaren Jobs mit je einer fest verdrahteten Mail-Vorlage, die direkt in den Kochdienst-Einstellungen mit derselben Maske wie in den Mail-Einstellungen bearbeitet wird.

**Architecture:** `MailTemplate` und `MailJob` bekommen ein Feld `kind` (`GENERAL`/`COOKING`); Kochdienst-Vorlagen und -Jobs bleiben damit gewöhnliche Datensätze in den bestehenden Collections und erscheinen in den Mail-Einstellungen (dort mit Chip und gesperrt). Ein neuer Endpunkt `/api/v1/cooking-reminder-jobs` legt Job und Vorlage atomar zusammen an, damit die 1:1-Bindung im Backend liegt und nicht in der UI. Der `CookingReminderScheduler` registriert je aktivem Kochdienst-Job einen eigenen täglichen Quartz-Trigger. Im Frontend wird die Vorlagen-Maske als `MailTemplateFormComponent` aus dem bestehenden Editor herausgelöst und von beiden Stellen genutzt.

**Tech Stack:** Quarkus (Panache MongoDB, `io.quarkus.scheduler.Scheduler`), JAX-RS, JUnit 5 + RestAssured, Angular 18 (standalone components, Angular Material, ngx-quill), Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-08-04-kochdienst-erinnerungs-jobs-design.md`

## Global Constraints

- Backend-Verzeichnis: `backend/`. Testbefehl: `.\mvnw.cmd test -Dtest=<TestKlasse>` (aus `backend/`, PowerShell). Voller Lauf: `.\mvnw.cmd test`.
- Frontend-Verzeichnis: `frontend/`. Testbefehl: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/<datei>.spec.ts` (aus `frontend/`). Voller Lauf ohne `--include`.
- MongoDB für Backend-Tests muss laufen (Container `kigru-mongo-test` auf Port 27017).
- Zeitzone für alle Cron-Registrierungen: `Europe/Vienna`.
- Versandzeit-Format: `HH:mm` (String), Regex `^([01]\d|2[0-3]):[0-5]\d$`, Vorbelegung `07:00`.
- Kind-Werte als String-Konstanten, nicht als Enum: `"GENERAL"` und `"COOKING"`. Bestandsdatensätze ohne Feld gelten als `GENERAL`.
- Kochdienst-Tokens (genau diese fünf, in dieser Reihenfolge): `{{duty.date}}`, `{{duty.groups}}`, `{{duty.description}}`, `{{duty.daysBefore}}`, `{{duty.personName}}`.
- Person-Tokens in Kochdienst-Vorlagen: ausschließlich `{{person.firstName}}` und `{{person.lastName}}`.
- Alle Benutzertexte auf Deutsch, ohne Umlaut-Ersatzschreibung in neuen Dateien (bestehende Dateien behalten ihren Stil).
- Bestehende Tests dürfen nicht gelöscht oder abgeschwächt werden — sie sind der Regressionsnachweis. Wo eine Signaturänderung sie bricht, wird der Aufruf angepasst, nie die Zusicherung.

## Dateiübersicht

**Backend — neu**
- `backend/src/main/java/at/kigruapp/service/CookingDutyTokens.java` — einzige Quelle der Kochdienst-Tokens (Name + Label), von Scheduler und Vorlagen-Endpunkt genutzt.
- `backend/src/main/java/at/kigruapp/resource/CookingReminderJobResource.java` — CRUD für Kochdienst-Job + zugehörige Vorlage in einem Request.
- `backend/src/main/java/at/kigruapp/migration/CookingReminderSettingsToJobMigration.java` — überführt das Singleton in einen Kochdienst-Job.

**Backend — geändert**
- `entity/MailTemplate.java`, `entity/MailJob.java` — `kind`; `MailJob` zusätzlich `sendTime`.
- `entity/CookingReminder.java` — `jobId`.
- `resource/MailTemplateResource.java` — `kind`-Filter, Schreibschutz für `COOKING`, Platzhalter-Gruppen.
- `resource/MailJobResource.java` — Schreibschutz für `COOKING`-Jobs.
- `resource/CookingReminderSettingsResource.java` — schrumpft auf `GET {active}`.
- `scheduler/CookingReminderScheduler.java` — ein Trigger je aktivem Job.
- `scheduler/MailJobScheduler.java` — ignoriert `COOKING`-Jobs.
- `migration/CookingReminderIndexMigration.java` — Index auf `(dutyId, dueDate, jobId)`.

**Frontend — neu**
- `settings/mail/mail-template-editor/mail-template-form.component.{ts,html,scss,spec.ts}` — gekapselte Vorlagen-Maske.
- `settings/organisation/cooking-reminder-jobs/cooking-reminder-jobs.component.{ts,html,scss,spec.ts}` — Master-Detail der Kochdienst-Jobs.
- `shared/services/cooking-reminder-job.service.ts` + `shared/models/cooking-reminder-job.model.ts`.

**Frontend — geändert**
- `mail-template-editor.component.{ts,html}` — nutzt die neue Maske, zeigt Chip und sperrt `COOKING`.
- `mail-template-editor/mail-token.util.ts` — `duty`-Namespace.
- `mail-job-editor.component.{ts,html}` — Dropdown nur `GENERAL`, Chip und Sperre für `COOKING`-Jobs.
- `organisation.component.{ts,html}` — altes Erinnerungs-Formular raus, neue Komponente rein.
- `shared/services/mail-template.service.ts`, `shared/models/mail-template.model.ts`, `shared/models/mail-job.model.ts`, `shared/models/cooking-reminder-settings.model.ts`, `shared/services/cooking-reminder-settings.service.ts`.

---

### Task 1: `kind` auf Vorlagen und Jobs, Filter und Schreibschutz

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/MailTemplate.java`
- Modify: `backend/src/main/java/at/kigruapp/entity/MailJob.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java`

**Interfaces:**
- Consumes: nichts.
- Produces: `MailTemplate.KIND_GENERAL`, `MailTemplate.KIND_COOKING`, `MailTemplate.kind`, `MailTemplate.isCooking()`; `MailJob.KIND_GENERAL`, `MailJob.KIND_COOKING`, `MailJob.kind`, `MailJob.sendTime`, `MailJob.isCooking()`; `MailTemplateResource.sanitizeBody(String)` (jetzt package-private static); `GET /api/v1/mail-templates?kind=`.

- [ ] **Step 1: Write the failing tests**

An `backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java` anhängen (die Hilfsmethoden `persistTemplate`/`persistDefinition` existieren dort bereits):

```java
    private MailTemplate persistCookingTemplate(String name) {
        MailTemplate t = persistTemplate(name, "<p>Kochdienst</p>");
        t.kind = MailTemplate.KIND_COOKING;
        t.update();
        return t;
    }

    @Test
    void listFiltersByKind() {
        persistTemplate("Allgemein", "<p>a</p>");
        persistCookingTemplate("Kochdienst");

        given()
                .when().get("/api/v1/mail-templates?kind=GENERAL")
                .then().statusCode(200)
                .body("name", hasItem("Allgemein"))
                .body("name", not(hasItem("Kochdienst")));

        given()
                .when().get("/api/v1/mail-templates?kind=COOKING")
                .then().statusCode(200)
                .body("name", hasItem("Kochdienst"))
                .body("name", not(hasItem("Allgemein")));
    }

    @Test
    void listWithoutKindReturnsEverything() {
        persistTemplate("Allgemein", "<p>a</p>");
        persistCookingTemplate("Kochdienst");

        given()
                .when().get("/api/v1/mail-templates")
                .then().statusCode(200)
                .body("name", hasItem("Allgemein"))
                .body("name", hasItem("Kochdienst"));
    }

    @Test
    void createAlwaysProducesGeneralKind() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"Neu\",\"bodyHtml\":\"<p>x</p>\",\"kind\":\"COOKING\"}")
                .when().post("/api/v1/mail-templates")
                .then().statusCode(201)
                .body("kind", is("GENERAL"));
    }

    @Test
    void cookingTemplatesCannotBeChangedOnGeneralEndpoint() {
        MailTemplate cooking = persistCookingTemplate("Kochdienst");
        String id = cooking.id.toHexString();

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"Geaendert\",\"bodyHtml\":\"<p>y</p>\"}")
                .when().put("/api/v1/mail-templates/" + id)
                .then().statusCode(409);

        given()
                .when().delete("/api/v1/mail-templates/" + id)
                .then().statusCode(409);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run (aus `backend/`): `.\mvnw.cmd test -Dtest=MailTemplateResourceTest`
Expected: FAIL — `kind` existiert nicht (Kompilierfehler in `persistCookingTemplate`).

- [ ] **Step 3: Felder auf die Entities legen**

In `backend/src/main/java/at/kigruapp/entity/MailTemplate.java` ergänzen:

```java
    public static final String KIND_GENERAL = "GENERAL";
    public static final String KIND_COOKING = "COOKING";

    /** GENERAL oder COOKING. Bestandsdaten ohne Feld gelten als GENERAL. */
    public String kind;

    public boolean isCooking() {
        return KIND_COOKING.equals(kind);
    }

    /** Nie null — für Filter und Ausgabe. */
    public String effectiveKind() {
        return kind == null || kind.isBlank() ? KIND_GENERAL : kind;
    }
```

In `backend/src/main/java/at/kigruapp/entity/MailJob.java` ergänzen:

```java
    public static final String KIND_GENERAL = "GENERAL";
    public static final String KIND_COOKING = "COOKING";

    /** GENERAL oder COOKING. Bestandsdaten ohne Feld gelten als GENERAL. */
    public String kind;

    /** Nur bei kind=COOKING gesetzt: Versandzeit HH:mm, Europe/Vienna. Ersetzt dort den Cron. */
    public String sendTime;

    public boolean isCooking() {
        return KIND_COOKING.equals(kind);
    }

    public String effectiveKind() {
        return kind == null || kind.isBlank() ? KIND_GENERAL : kind;
    }
```

- [ ] **Step 4: Filter und Schreibschutz im Vorlagen-Endpunkt**

In `backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java`:

`sanitizeBody` von `private static` auf `static` ändern (Task 4 nutzt sie mit), dann:

```java
    @GET
    public List<MailTemplate> list(@QueryParam("kind") String kind) {
        List<MailTemplate> all = MailTemplate.listAll(Sort.descending("updatedAt"));
        if (kind == null || kind.isBlank()) {
            return all;
        }
        return all.stream()
                .filter(t -> kind.equals(t.effectiveKind()))
                .collect(Collectors.toList());
    }
```

In `create` direkt nach `template.bodyHtml = ...` einfügen:

```java
        template.kind = MailTemplate.KIND_GENERAL;
```

In `update` und `delete` jeweils direkt nach der Null-Prüfung der geladenen Vorlage einfügen:

```java
        if (template.isCooking()) {
            throw new WebApplicationException(
                    "Kochdienst-Vorlagen werden in den Kochdienst-Einstellungen gepflegt", 409);
        }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\mvnw.cmd test -Dtest=MailTemplateResourceTest`
Expected: PASS, alle bisherigen Tests der Klasse weiterhin grün.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/MailTemplate.java backend/src/main/java/at/kigruapp/entity/MailJob.java backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java
git commit -m "feat(be): kind auf Mail-Vorlagen und -Jobs, Filter und Schreibschutz"
```

---

### Task 2: Platzhalter-Gruppen mit Kochdienst-Tokens

**Files:**
- Create: `backend/src/main/java/at/kigruapp/service/CookingDutyTokens.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java`
- Modify: `backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java` (nur Sichtbarkeit von `buildDutyProperties`)
- Test: `backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java`
- Test: `backend/src/test/java/at/kigruapp/scheduler/CookingDutyTokensSyncTest.java`

**Interfaces:**
- Consumes: `MailTemplate.KIND_COOKING` (Task 1).
- Produces: `CookingDutyTokens.Token(String fieldName, String label)`, `CookingDutyTokens.TOKENS`, `CookingDutyTokens.fieldNames()`, `CookingDutyTokens.GROUP`, `CookingDutyTokens.GROUP_LABEL`; erweitertes `MailTemplateResource.PlaceholderTile(String token, String fieldName, Map<String,String> label, String group, String groupLabel)`; `GET /api/v1/mail-templates/placeholders?kind=`.

- [ ] **Step 1: Write the failing tests**

Neue Datei `backend/src/test/java/at/kigruapp/scheduler/CookingDutyTokensSyncTest.java`:

```java
package at.kigruapp.scheduler;

import at.kigruapp.service.CookingDutyTokens;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Haelt die angebotenen Chips und die tatsaechlich befuellten Properties
 * zusammen: ein neues duty-Property ohne Chip (oder umgekehrt) faellt hier auf.
 */
@QuarkusTest
class CookingDutyTokensSyncTest {

    @Inject
    CookingReminderScheduler scheduler;

    @Test
    void everyDutyPropertyHasAToken() {
        CookingReminderScheduler.DueDuty duty = new CookingReminderScheduler.DueDuty(
                new ObjectId(), new ObjectId(), "2026-08-10", "2026-08-08", "Suppe", 2, List.of());

        Map<String, String> properties = scheduler.buildDutyProperties(duty);

        assertEquals(new HashSet<>(CookingDutyTokens.fieldNames()), properties.keySet());
    }
}
```

An `MailTemplateResourceTest` anhängen:

```java
    @Test
    void placeholdersForCookingReturnDutyTokensAndOnlyNameFields() {
        persistDefinition("firstName", "Vorname");
        persistDefinition("lastName", "Nachname");
        persistDefinition("email", "E-Mail");

        given()
                .when().get("/api/v1/mail-templates/placeholders?kind=COOKING")
                .then().statusCode(200)
                .body("token", hasItem("{{duty.date}}"))
                .body("token", hasItem("{{duty.personName}}"))
                .body("token", hasItem("{{person.firstName}}"))
                .body("token", hasItem("{{person.lastName}}"))
                .body("token", not(hasItem("{{person.email}}")))
                .body("group", hasItem("KOCHDIENST"))
                .body("group", hasItem("PERSON"));
    }

    @Test
    void placeholdersWithoutKindStayGeneral() {
        persistDefinition("firstName", "Vorname");
        persistDefinition("email", "E-Mail");

        given()
                .when().get("/api/v1/mail-templates/placeholders")
                .then().statusCode(200)
                .body("token", hasItem("{{person.email}}"))
                .body("token", not(hasItem("{{duty.date}}")))
                .body("group", everyItem(is("PERSON")));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd test -Dtest=CookingDutyTokensSyncTest`
Expected: FAIL — `CookingDutyTokens` existiert nicht.

- [ ] **Step 3: Token-Quelle anlegen**

Neue Datei `backend/src/main/java/at/kigruapp/service/CookingDutyTokens.java`:

```java
package at.kigruapp.service;

import java.util.List;

/**
 * Einzige Quelle der Kochdienst-Platzhalter. Der Scheduler befuellt genau diese
 * Felder, der Vorlagen-Editor bietet genau diese Chips an.
 */
public final class CookingDutyTokens {

    public static final String GROUP = "KOCHDIENST";
    public static final String GROUP_LABEL = "Kochdienst";

    public record Token(String fieldName, String label) {}

    /** Reihenfolge = Reihenfolge der Chips in der Maske. */
    public static final List<Token> TOKENS = List.of(
            new Token("date", "Datum"),
            new Token("groups", "Gruppen"),
            new Token("description", "Was wird gekocht"),
            new Token("daysBefore", "Tage vorher"),
            new Token("personName", "Wer kocht"));

    public static List<String> fieldNames() {
        return TOKENS.stream().map(Token::fieldName).toList();
    }

    private CookingDutyTokens() {}
}
```

- [ ] **Step 4: `buildDutyProperties` testbar machen**

In `backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java` die Signatur von

```java
    private Map<String, String> buildDutyProperties(DueDuty duty) {
```

ändern zu (package-private, damit der Test im selben Package sie aufrufen kann):

```java
    Map<String, String> buildDutyProperties(DueDuty duty) {
```

- [ ] **Step 5: Run the sync test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=CookingDutyTokensSyncTest`
Expected: PASS.

- [ ] **Step 6: Platzhalter-Endpunkt um Gruppen erweitern**

In `backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java`:

```java
    private static final Set<String> COOKING_PERSON_FIELD_ALLOWLIST = Set.of("firstName", "lastName");

    private static final String GROUP_PERSON = "PERSON";
    private static final String GROUP_PERSON_LABEL = "Person";

    public record PlaceholderTile(String token, String fieldName, Map<String, String> label,
                                  String group, String groupLabel) {}

    @GET
    @Path("/placeholders")
    public List<PlaceholderTile> placeholders(@QueryParam("kind") String kind) {
        boolean cooking = MailTemplate.KIND_COOKING.equals(kind);
        List<PlaceholderTile> tiles = new ArrayList<>();
        if (cooking) {
            for (CookingDutyTokens.Token token : CookingDutyTokens.TOKENS) {
                tiles.add(new PlaceholderTile(
                        "{{duty." + token.fieldName() + "}}", token.fieldName(),
                        Map.of("de", token.label()),
                        CookingDutyTokens.GROUP, CookingDutyTokens.GROUP_LABEL));
            }
        }
        Set<String> personFields = cooking ? COOKING_PERSON_FIELD_ALLOWLIST : SCALAR_PERSON_FIELD_ALLOWLIST;
        tiles.addAll(FieldDefinition.findActive().stream()
                .filter(def -> personFields.contains(def.fieldName))
                .map(def -> new PlaceholderTile("{{person." + def.fieldName + "}}", def.fieldName, def.label,
                        GROUP_PERSON, GROUP_PERSON_LABEL))
                .sorted(Comparator.comparing(t -> labelSortKey(t.label())))
                .toList());
        return tiles;
    }
```

Importe ergänzen: `at.kigruapp.service.CookingDutyTokens`, `java.util.ArrayList`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `.\mvnw.cmd test -Dtest=MailTemplateResourceTest`
Expected: PASS, inklusive des bestehenden `placeholders`-Tests.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/CookingDutyTokens.java backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java backend/src/test/java/at/kigruapp/scheduler/CookingDutyTokensSyncTest.java backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java
git commit -m "feat(be): Platzhalter-Gruppen mit Kochdienst-Tokens"
```

---

### Task 3: Sende-Log je Job

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/CookingReminder.java`
- Modify: `backend/src/main/java/at/kigruapp/migration/CookingReminderIndexMigration.java`
- Test: `backend/src/test/java/at/kigruapp/migration/CookingReminderIndexMigrationTest.java`

**Interfaces:**
- Consumes: nichts.
- Produces: `CookingReminder.jobId`, `CookingReminder.existsFor(ObjectId dutyId, String dueDate, ObjectId jobId)`; Unique-Index `dutyId_1_dueDate_1_jobId_1`.

- [ ] **Step 1: Write the failing test**

Neue Datei `backend/src/test/java/at/kigruapp/migration/CookingReminderIndexMigrationTest.java`:

```java
package at.kigruapp.migration;

import at.kigruapp.entity.CookingReminder;
import at.kigruapp.entity.CookingReminderStatus;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CookingReminderIndexMigrationTest {

    @Inject
    MongoClient mongoClient;

    @Inject
    CookingReminderIndexMigration migration;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        CookingReminder.deleteAll();
    }

    private List<String> indexNames() {
        List<String> names = new ArrayList<>();
        for (Document index : mongoClient.getDatabase(databaseName)
                .getCollection("cooking_reminders").listIndexes()) {
            names.add(index.getString("name"));
        }
        return names;
    }

    private void persistReminder(ObjectId dutyId, String dueDate, ObjectId jobId) {
        CookingReminder reminder = new CookingReminder();
        reminder.dutyId = dutyId;
        reminder.dueDate = dueDate;
        reminder.dutyDate = "2026-08-10";
        reminder.jobId = jobId;
        reminder.sentAt = Instant.now();
        reminder.status = CookingReminderStatus.SENT;
        reminder.recipientCount = 1;
        reminder.persist();
    }

    @Test
    void indexCoversJobId() {
        migration.run();

        assertTrue(indexNames().contains("dutyId_1_dueDate_1_jobId_1"));
        assertTrue(!indexNames().contains("dutyId_1_dueDate_1"));
    }

    @Test
    void twoJobsMayLogTheSameDutyOnTheSameDay() {
        migration.run();
        ObjectId dutyId = new ObjectId();

        persistReminder(dutyId, "2026-08-08", new ObjectId());
        persistReminder(dutyId, "2026-08-08", new ObjectId());

        assertTrue(CookingReminder.count() == 2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=CookingReminderIndexMigrationTest`
Expected: FAIL — `jobId` existiert nicht (Kompilierfehler).

- [ ] **Step 3: Feld und Abfrage auf der Entity**

In `backend/src/main/java/at/kigruapp/entity/CookingReminder.java` ergänzen bzw. ersetzen:

```java
    /** Id des Kochdienst-Jobs, der diese Erinnerung verschickt hat. */
    public ObjectId jobId;

    public static boolean existsFor(ObjectId dutyId, String dueDate, ObjectId jobId) {
        return count("dutyId = ?1 and dueDate = ?2 and jobId = ?3", dutyId, dueDate, jobId) > 0;
    }
```

Die bisherige Zwei-Parameter-Variante von `existsFor` wird gelöscht; der einzige Aufrufer (`CookingReminderScheduler`) wird in Task 5 angepasst. Den Klassenkommentar auf den neuen Index umschreiben.

- [ ] **Step 4: Index-Migration umstellen**

In `backend/src/main/java/at/kigruapp/migration/CookingReminderIndexMigration.java` die Methode `run` ersetzen:

```java
    public void run() {
        MongoCollection<Document> collection = mongoClient.getDatabase(databaseName)
                .getCollection("cooking_reminders");
        try {
            collection.dropIndex("dutyId_1_dueDate_1");
        } catch (RuntimeException e) {
            // Index existiert nicht (Neuinstallation oder bereits migriert) — kein Fehlerfall.
        }
        collection.createIndex(new Document("dutyId", 1).append("dueDate", 1).append("jobId", 1),
                new IndexOptions().unique(true));
    }
```

Import `com.mongodb.client.MongoCollection` ergänzen und den Klassenkommentar auf `(dutyId, dueDate, jobId)` aktualisieren.

- [ ] **Step 5: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=CookingReminderIndexMigrationTest`
Expected: PASS. `CookingReminderTest` und `CookingReminderRunTest` schlagen an dieser Stelle wegen der geänderten `existsFor`-Signatur noch fehl — das ist erwartet und wird in Task 5 aufgelöst.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/CookingReminder.java backend/src/main/java/at/kigruapp/migration/CookingReminderIndexMigration.java backend/src/test/java/at/kigruapp/migration/CookingReminderIndexMigrationTest.java
git commit -m "feat(be): Sende-Log der Kochdienst-Erinnerungen je Job"
```

---

### Task 4: Endpunkt für Kochdienst-Jobs samt Vorlage

**Files:**
- Create: `backend/src/main/java/at/kigruapp/resource/CookingReminderJobResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/CookingReminderJobResourceTest.java`

**Interfaces:**
- Consumes: `MailTemplate.KIND_COOKING`, `MailJob.KIND_COOKING`, `MailJob.sendTime` (Task 1), `MailTemplateResource.sanitizeBody` (Task 1).
- Produces: `CookingReminderJobResource.JobDto(String id, String name, String senderAccountId, String subject, String sendTime, boolean active, String templateId, String templateName, String templateBodyHtml)`; `CookingReminderJobResource.SaveRequest(String name, String senderAccountId, String subject, String sendTime, boolean active, String templateName, String templateBodyHtml)`; `GET/POST /api/v1/cooking-reminder-jobs`, `PUT/DELETE /api/v1/cooking-reminder-jobs/{id}`.

Hinweis: Der Aufruf `cookingReminderScheduler.reschedule()` wird in diesem Task noch **nicht** eingebaut — die Signatur ändert sich in Task 5. Task 5 ergänzt ihn.

- [ ] **Step 1: Write the failing test**

Neue Datei `backend/src/test/java/at/kigruapp/resource/CookingReminderJobResourceTest.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class CookingReminderJobResourceTest {

    @BeforeEach
    void cleanup() {
        MailJob.deleteAll();
        MailTemplate.deleteAll();
        MailAccount.deleteAll();
    }

    private MailAccount persistAccount(boolean enabled) {
        MailAccount account = new MailAccount();
        account.name = "Kindergarten";
        account.enabled = enabled;
        account.persist();
        return account;
    }

    private String saveBody(String accountId, boolean active) {
        return "{\"name\":\"Erinnerung\",\"senderAccountId\":\"" + accountId
                + "\",\"subject\":\"Dein Kochdienst\",\"sendTime\":\"07:30\",\"active\":" + active
                + ",\"templateName\":\"Kochdienst-Vorlage\""
                + ",\"templateBodyHtml\":\"<p>Am {{duty.date}} kochst du.</p>\"}";
    }

    @Test
    void createStoresJobAndTemplateTogether() {
        MailAccount account = persistAccount(true);

        String templateId = given()
                .contentType(ContentType.JSON)
                .body(saveBody(account.id.toHexString(), true))
                .when().post("/api/v1/cooking-reminder-jobs")
                .then().statusCode(201)
                .body("name", is("Erinnerung"))
                .body("sendTime", is("07:30"))
                .body("active", is(true))
                .body("templateName", is("Kochdienst-Vorlage"))
                .body("templateId", notNullValue())
                .extract().path("templateId");

        MailTemplate template = MailTemplate.findById(new org.bson.types.ObjectId(templateId));
        org.junit.jupiter.api.Assertions.assertEquals(MailTemplate.KIND_COOKING, template.kind);

        given()
                .when().get("/api/v1/cooking-reminder-jobs")
                .then().statusCode(200)
                .body("name", hasItem("Erinnerung"))
                .body("templateBodyHtml", hasItem("<p>Am {{duty.date}} kochst du.</p>"));
    }

    @Test
    void updateChangesJobAndTemplate() {
        MailAccount account = persistAccount(true);
        String id = given()
                .contentType(ContentType.JSON)
                .body(saveBody(account.id.toHexString(), true))
                .when().post("/api/v1/cooking-reminder-jobs")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"Neu\",\"senderAccountId\":\"" + account.id.toHexString()
                        + "\",\"subject\":\"Neuer Betreff\",\"sendTime\":\"08:00\",\"active\":false"
                        + ",\"templateName\":\"Neue Vorlage\",\"templateBodyHtml\":\"<p>Hallo</p>\"}")
                .when().put("/api/v1/cooking-reminder-jobs/" + id)
                .then().statusCode(200)
                .body("name", is("Neu"))
                .body("sendTime", is("08:00"))
                .body("active", is(false))
                .body("templateName", is("Neue Vorlage"));

        org.junit.jupiter.api.Assertions.assertEquals(1, MailTemplate.count());
    }

    @Test
    void deleteRemovesJobAndTemplate() {
        MailAccount account = persistAccount(true);
        String id = given()
                .contentType(ContentType.JSON)
                .body(saveBody(account.id.toHexString(), true))
                .when().post("/api/v1/cooking-reminder-jobs")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .when().delete("/api/v1/cooking-reminder-jobs/" + id)
                .then().statusCode(204);

        org.junit.jupiter.api.Assertions.assertEquals(0, MailJob.count());
        org.junit.jupiter.api.Assertions.assertEquals(0, MailTemplate.count());
    }

    @Test
    void invalidSendTimeIsRejectedAndLeavesNoTemplateBehind() {
        MailAccount account = persistAccount(true);

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"Erinnerung\",\"senderAccountId\":\"" + account.id.toHexString()
                        + "\",\"subject\":\"x\",\"sendTime\":\"25:00\",\"active\":false"
                        + ",\"templateName\":\"V\",\"templateBodyHtml\":\"<p>x</p>\"}")
                .when().post("/api/v1/cooking-reminder-jobs")
                .then().statusCode(400);

        org.junit.jupiter.api.Assertions.assertEquals(0, MailTemplate.count());
    }

    @Test
    void activeJobNeedsAnEnabledAccount() {
        MailAccount account = persistAccount(false);

        given()
                .contentType(ContentType.JSON)
                .body(saveBody(account.id.toHexString(), true))
                .when().post("/api/v1/cooking-reminder-jobs")
                .then().statusCode(400);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=CookingReminderJobResourceTest`
Expected: FAIL — 404, der Endpunkt existiert nicht.

- [ ] **Step 3: Endpunkt implementieren**

Neue Datei `backend/src/main/java/at/kigruapp/resource/CookingReminderJobResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import io.quarkus.panache.common.Sort;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Kochdienst-Erinnerungen: Job und die fest zugeordnete Vorlage werden hier
 * gemeinsam gepflegt. Die 1:1-Bindung liegt bewusst im Backend — die UI kann
 * sie nicht durch einen halb fehlgeschlagenen Doppel-Request zerreissen.
 * Admin-only (nicht im SecurityFilter freigeschaltet).
 */
@Path("/api/v1/cooking-reminder-jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CookingReminderJobResource {

    private static final Pattern SEND_TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    public record JobDto(String id, String name, String senderAccountId, String subject,
                         String sendTime, boolean active, String templateId,
                         String templateName, String templateBodyHtml) {}

    public record SaveRequest(String name, String senderAccountId, String subject, String sendTime,
                              boolean active, String templateName, String templateBodyHtml) {}

    @GET
    public List<JobDto> list() {
        List<JobDto> result = new ArrayList<>();
        for (MailJob job : MailJob.<MailJob>listAll(Sort.descending("updatedAt"))) {
            if (job.isCooking()) {
                result.add(toDto(job, loadTemplate(job)));
            }
        }
        return result;
    }

    @POST
    public Response create(SaveRequest request) {
        validate(request);
        MailTemplate template = new MailTemplate();
        template.name = request.templateName().trim();
        template.bodyHtml = MailTemplateResource.sanitizeBody(request.templateBodyHtml());
        template.kind = MailTemplate.KIND_COOKING;
        template.createdAt = Instant.now();
        template.updatedAt = template.createdAt;
        template.persist();

        MailJob job = new MailJob();
        try {
            job.kind = MailJob.KIND_COOKING;
            job.templateId = template.id;
            applyFields(job, request);
            job.createdAt = Instant.now();
            job.updatedAt = job.createdAt;
            job.persist();
        } catch (RuntimeException e) {
            // Ohne Rollback bliebe eine Vorlage ohne Job zurueck, die nirgends auftaucht.
            template.delete();
            throw e;
        }
        return Response.status(201).entity(toDto(job, template)).build();
    }

    @PUT
    @Path("/{id}")
    public JobDto update(@PathParam("id") String id, SaveRequest request) {
        MailJob job = findCookingJob(id);
        validate(request);

        MailTemplate template = loadTemplate(job);
        if (template == null) {
            template = new MailTemplate();
            template.kind = MailTemplate.KIND_COOKING;
            template.createdAt = Instant.now();
        }
        template.name = request.templateName().trim();
        template.bodyHtml = MailTemplateResource.sanitizeBody(request.templateBodyHtml());
        template.updatedAt = Instant.now();
        template.persistOrUpdate();

        job.templateId = template.id;
        applyFields(job, request);
        job.updatedAt = Instant.now();
        job.update();
        return toDto(job, template);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        MailJob job = findCookingJob(id);
        MailTemplate template = loadTemplate(job);
        job.delete();
        if (template != null) {
            template.delete();
        }
        return Response.noContent().build();
    }

    private MailJob findCookingJob(String id) {
        if (!ObjectId.isValid(id)) {
            throw new NotFoundException();
        }
        MailJob job = MailJob.findById(new ObjectId(id));
        if (job == null || !job.isCooking()) {
            throw new NotFoundException();
        }
        return job;
    }

    private MailTemplate loadTemplate(MailJob job) {
        return job.templateId == null ? null : MailTemplate.findById(job.templateId);
    }

    private void applyFields(MailJob job, SaveRequest request) {
        job.name = request.name().trim();
        job.senderAccountId = request.senderAccountId();
        job.subject = request.subject().trim();
        job.sendTime = request.sendTime().trim();
        job.active = request.active();
    }

    private void validate(SaveRequest request) {
        if (request == null) {
            throw new BadRequestException("Anfrage ist leer");
        }
        requireText(request.name(), "Name ist erforderlich");
        requireText(request.subject(), "Betreff ist erforderlich");
        requireText(request.templateName(), "Name der Vorlage ist erforderlich");
        requireText(request.templateBodyHtml(), "Inhalt der Vorlage ist erforderlich");
        if (request.sendTime() == null || !SEND_TIME_PATTERN.matcher(request.sendTime().trim()).matches()) {
            throw new BadRequestException("Versandzeit muss im Format HH:mm vorliegen");
        }
        MailAccount account = request.senderAccountId() != null && ObjectId.isValid(request.senderAccountId())
                ? MailAccount.<MailAccount>findById(new ObjectId(request.senderAccountId()))
                : null;
        if (account == null) {
            throw new BadRequestException("Mailkonto existiert nicht");
        }
        if (request.active() && !account.enabled) {
            throw new BadRequestException("Ein aktiver Job braucht ein freigeschaltetes Mailkonto");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(message);
        }
    }

    private JobDto toDto(MailJob job, MailTemplate template) {
        return new JobDto(job.id.toHexString(), job.name, job.senderAccountId, job.subject,
                job.sendTime, job.active,
                template == null ? null : template.id.toHexString(),
                template == null ? null : template.name,
                template == null ? null : template.bodyHtml);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=CookingReminderJobResourceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/CookingReminderJobResource.java backend/src/test/java/at/kigruapp/resource/CookingReminderJobResourceTest.java
git commit -m "feat(be): Endpunkt fuer Kochdienst-Jobs samt Vorlage"
```

---

### Task 5: Scheduler verschickt je aktivem Job

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/CookingReminderJobResource.java`
- Modify: `backend/src/test/java/at/kigruapp/scheduler/CookingReminderSchedulerTest.java`
- Modify: `backend/src/test/java/at/kigruapp/scheduler/CookingReminderRunTest.java`
- Test: `backend/src/test/java/at/kigruapp/scheduler/CookingReminderMultiJobRunTest.java`

**Interfaces:**
- Consumes: `MailJob.KIND_COOKING`, `MailJob.sendTime` (Task 1), `CookingReminder.jobId` und die neue `existsFor`-Signatur (Task 3), `CookingReminderJobResource` (Task 4).
- Produces: `CookingReminderScheduler.reschedule()` (unverändert benannt, registriert jetzt je Job), `CookingReminderScheduler.runFor(LocalDate today, MailJob job)`, `CookingReminderScheduler.jobId(ObjectId)`.

- [ ] **Step 1: Write the failing test**

Neue Datei `backend/src/test/java/at/kigruapp/scheduler/CookingReminderMultiJobRunTest.java`:

```java
package at.kigruapp.scheduler;

import at.kigruapp.entity.*;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zwei aktive Kochdienst-Jobs muessen fuer denselben faelligen Kochdienst
 * beide senden und beide protokollieren.
 */
@QuarkusTest
class CookingReminderMultiJobRunTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Inject
    CookingReminderScheduler scheduler;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    FieldDefinition cookingDutyDef;
    FieldDefinition personTypeDef;
    FieldDefinition emailDef;
    FieldDefinition firstNameDef;
    MailAccount account;
    ObjectId familyId;

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    @BeforeEach
    void setup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        MailAccount.deleteAll();
        MailTemplate.deleteAll();
        MailJob.deleteAll();
        CookingReminder.deleteAll();
        fieldInstances().deleteMany(new Document());

        cookingDutyDef = persistDefinition("cookingDuty");
        personTypeDef = persistDefinition("personType");
        emailDef = persistDefinition("email");
        firstNameDef = persistDefinition("firstName");

        account = new MailAccount();
        account.name = "Kiga";
        account.host = "localhost";
        account.port = greenMail.getSmtp().getPort();
        account.encryption = MailEncryption.NONE;
        account.fromAddress = "kiga@example.org";
        account.fromName = "Kindergruppe";
        account.enabled = true;
        account.persist();

        familyId = new ObjectId();
    }

    private FieldDefinition persistDefinition(String fieldName) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.createdAt = Instant.now();
        def.persist();
        return def;
    }

    private ObjectId persistFieldInstance(ObjectId definitionId, Object value) {
        ObjectId id = new ObjectId();
        fieldInstances().insertOne(new Document("_id", id)
                .append("definitionId", definitionId).append("value", value));
        return id;
    }

    private FieldRef ref(ObjectId definitionId, ObjectId instanceId) {
        FieldRef fieldRef = new FieldRef();
        fieldRef.definitionId = definitionId;
        fieldRef.fieldInstanceId = instanceId;
        return fieldRef;
    }

    private Person persistParent(String firstName, String email) {
        Person person = new Person();
        person.familyId = familyId;
        person.basicProperties = new ArrayList<>();
        person.basicProperties.add(ref(personTypeDef.id, persistFieldInstance(personTypeDef.id, "PARENT")));
        person.basicProperties.add(ref(firstNameDef.id, persistFieldInstance(firstNameDef.id, firstName)));
        person.basicProperties.add(ref(emailDef.id, persistFieldInstance(emailDef.id, email)));
        person.schedules = new ArrayList<>();
        person.persist();
        return person;
    }

    private void persistDuty(Person person, String date, int daysBefore) {
        Document value = new Document("date", date).append("groups", List.of("g1"))
                .append("description", "Suppe")
                .append("reminderEnabled", true).append("reminderDaysBefore", daysBefore);
        ObjectId instanceId = persistFieldInstance(cookingDutyDef.id, value);
        person.schedules.add(ref(cookingDutyDef.id, instanceId));
        person.update();
    }

    private MailJob persistCookingJob(String name, String bodyHtml, boolean active) {
        MailTemplate template = new MailTemplate();
        template.name = name;
        template.bodyHtml = bodyHtml;
        template.kind = MailTemplate.KIND_COOKING;
        template.createdAt = Instant.now();
        template.updatedAt = template.createdAt;
        template.persist();

        MailJob job = new MailJob();
        job.kind = MailJob.KIND_COOKING;
        job.name = name;
        job.templateId = template.id;
        job.subject = "Dein Kochdienst — " + name;
        job.senderAccountId = account.id.toHexString();
        job.sendTime = "07:00";
        job.active = active;
        job.createdAt = Instant.now();
        job.updatedAt = job.createdAt;
        job.persist();
        return job;
    }

    @Test
    void everyActiveJobSendsItsOwnReminder() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", 3);
        LocalDate today = LocalDate.of(2026, 9, 12);

        MailJob first = persistCookingJob("Kurz", "<p>Kurz {{duty.date}}</p>", true);
        MailJob second = persistCookingJob("Lang", "<p>Lang {{duty.date}}</p>", true);
        MailJob inactive = persistCookingJob("Aus", "<p>Aus</p>", false);

        scheduler.runFor(today, first);
        scheduler.runFor(today, second);

        assertTrue(greenMail.waitForIncomingEmail(5000, 2));
        assertEquals(2, greenMail.getReceivedMessages().length);
        assertEquals(2, CookingReminder.count());
        assertEquals(1, CookingReminder.count("jobId", first.id));
        assertEquals(1, CookingReminder.count("jobId", second.id));
        assertEquals(0, CookingReminder.count("jobId", inactive.id));
    }

    @Test
    void aJobDoesNotSendTwiceForTheSameDay() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", 3);
        LocalDate today = LocalDate.of(2026, 9, 12);
        MailJob job = persistCookingJob("Kurz", "<p>Kurz</p>", true);

        scheduler.runFor(today, job);
        scheduler.runFor(today, job);

        assertEquals(1, CookingReminder.count());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=CookingReminderMultiJobRunTest`
Expected: FAIL — `runFor(LocalDate, MailJob)` existiert nicht.

- [ ] **Step 3: Scheduler auf Jobs umstellen**

In `backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java`:

Konstante und Überlappungsschutz ersetzen:

```java
    /** Praefix der Quartz-Job-Ids; je Kochdienst-Job eine eigene Registrierung. */
    public static final String JOB_ID_PREFIX = "cooking-reminder-";

    /** Ueberlappungsschutz je Kochdienst-Job: ein langsamer Job darf keinen anderen ueberspringen lassen. */
    private final Map<ObjectId, AtomicBoolean> running = new ConcurrentHashMap<>();

    public static String jobId(ObjectId cookingJobId) {
        return JOB_ID_PREFIX + cookingJobId.toHexString();
    }
```

Die Testhaken `markRunningForTest`/`clearRunningForTest` auf die Map umstellen:

```java
    void markRunningForTest(ObjectId cookingJobId) {
        running.computeIfAbsent(cookingJobId, id -> new AtomicBoolean()).set(true);
    }

    void clearRunningForTest(ObjectId cookingJobId) {
        running.computeIfAbsent(cookingJobId, id -> new AtomicBoolean()).set(false);
    }
```

`reschedule()` ersetzen:

```java
    /** Registriert je aktivem Kochdienst-Job einen taeglichen Lauf. Idempotent. */
    public void reschedule() {
        for (String existing : new ArrayList<>(registeredJobIds)) {
            if (scheduler.getScheduledJob(existing) != null) {
                scheduler.unscheduleJob(existing);
            }
        }
        registeredJobIds.clear();

        List<MailJob> jobs = MailJob.list("kind = ?1 and active = ?2", MailJob.KIND_COOKING, true);
        for (MailJob job : jobs) {
            String quartzId = jobId(job.id);
            ObjectId cookingJobId = job.id;
            scheduler.newJob(quartzId)
                    .setCron(toCron(job.sendTime))
                    .setTimeZone(TIMEZONE)
                    .setTask(ctx -> runForJobId(LocalDate.now(ZoneId.of(TIMEZONE)), cookingJobId))
                    .schedule();
            registeredJobIds.add(quartzId);
            Log.infof("Kochdienst-Erinnerung: Lauf fuer Job %s registriert (%s, %s)",
                    job.name, toCron(job.sendTime), TIMEZONE);
        }
    }

    /** Merkt sich die eigenen Registrierungen, damit reschedule() sie wieder aufheben kann. */
    private final Set<String> registeredJobIds = ConcurrentHashMap.newKeySet();

    /** Laedt den Job frisch, damit eine zwischenzeitliche Aenderung sofort greift. */
    void runForJobId(LocalDate today, ObjectId cookingJobId) {
        MailJob job = MailJob.findById(cookingJobId);
        if (job == null || !job.isCooking() || !job.active) {
            return;
        }
        runFor(today, job);
    }
```

`runFor` ersetzen (die Hilfsmethoden `findDueDuties`, `sendOne`, `writeLogSafely` bleiben, bekommen aber den Job durchgereicht):

```java
    public void runFor(LocalDate today, MailJob job) {
        AtomicBoolean guard = running.computeIfAbsent(job.id, id -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            Log.warnf("Kochdienst-Erinnerung: Lauf fuer %s/%s uebersprungen, vorheriger Lauf noch aktiv",
                    job.name, today);
            return;
        }
        try {
            List<DueDuty> due = findDueDuties(today);
            if (due.isEmpty()) {
                return;
            }

            MailAccount account = findAccount(job.senderAccountId);
            MailTemplate template = job.templateId == null ? null : MailTemplate.findById(job.templateId);
            if (account == null || !account.enabled || template == null) {
                String reason = account == null || !account.enabled
                        ? "Mailkonto fehlt oder ist deaktiviert"
                        : "Mailvorlage fehlt";
                Log.warnf("Kochdienst-Erinnerung (%s): %s, %d faellige Erinnerung(en) entfallen",
                        job.name, reason, due.size());
                for (DueDuty duty : due) {
                    writeLogSafely(duty, job.id, CookingReminderStatus.ACCOUNT_UNAVAILABLE, 0, reason);
                }
                return;
            }

            for (DueDuty duty : due) {
                sendOne(duty, job, account, template);
            }
        } finally {
            guard.set(false);
        }
    }

    /** Ersetzt die frueher in CookingReminderSettingsResource liegende Aufloesung. */
    private static MailAccount findAccount(String hexId) {
        if (hexId == null || !ObjectId.isValid(hexId)) {
            return null;
        }
        return MailAccount.findById(new ObjectId(hexId));
    }
```

`sendOne`, `writeLogSafely` und `writeLog` bekommen den Job bzw. dessen Id als Parameter; `sendOne` nutzt `job.subject`, `writeLog` setzt `reminder.jobId = jobId;`. Die Duplikatsprüfung in `findDueDuties`/`sendOne`, die bisher `CookingReminder.existsFor(dutyId, dueDate)` aufruft, wird auf `CookingReminder.existsFor(dutyId, dueDate, job.id)` umgestellt. Die Importe `at.kigruapp.entity.MailJob`, `java.util.ArrayList`, `java.util.Set`, `java.util.concurrent.ConcurrentHashMap` ergänzen; die Importe von `CookingReminderSettings` und `CookingReminderSettingsResource` entfallen, ebenso `inactiveReason`.

- [ ] **Step 4: Reschedule aus dem Endpunkt aufrufen**

In `backend/src/main/java/at/kigruapp/resource/CookingReminderJobResource.java` injizieren und am Ende von `create`, `update` und `delete` (vor dem Return) aufrufen:

```java
    @jakarta.inject.Inject
    at.kigruapp.scheduler.CookingReminderScheduler cookingReminderScheduler;
```

```java
        cookingReminderScheduler.reschedule();
```

- [ ] **Step 5: Bestehende Scheduler-Tests anpassen**

In `CookingReminderRunTest`:

1. Im `@BeforeEach` den Block, der `CookingReminderSettings` anlegt, ersetzen durch einen Kochdienst-Job (`MailJob.deleteAll()` beim Aufräumen ergänzen, `CookingReminderSettings.deleteAll()` entfernen):

```java
        template = new MailTemplate();
        template.name = "Erinnerung";
        template.bodyHtml = "<p>Hallo {{person.firstName}}, am {{duty.date}} kochst du.</p>";
        template.kind = MailTemplate.KIND_COOKING;
        template.createdAt = Instant.now();
        template.persist();

        job = new MailJob();
        job.kind = MailJob.KIND_COOKING;
        job.name = "Kochdienst-Erinnerung";
        job.templateId = template.id;
        job.subject = "Dein Kochdienst";
        job.senderAccountId = account.id.toHexString();
        job.sendTime = "07:00";
        job.active = true;
        job.createdAt = Instant.now();
        job.updatedAt = job.createdAt;
        job.persist();
```

mit einem neuen Feld `MailJob job;` neben `template`.

2. Jedes `scheduler.runFor(LocalDate.of(...))` wird zu `scheduler.runFor(LocalDate.of(...), job)`.
3. `scheduler.markRunningForTest()` / `clearRunningForTest()` werden zu `scheduler.markRunningForTest(job.id)` / `scheduler.clearRunningForTest(job.id)`.
4. In `loestGruppenLabelsUeberFieldDefinitionAuf` wird statt der Settings der Job umgehängt:

```java
        job.templateId = groupsTemplate.id;
        job.update();
```

und `groupsTemplate.kind = MailTemplate.KIND_COOKING;` gesetzt.

5. `ohneKonfigurationPassiertNichts` prüft jetzt den Job ohne Vorlage — der Rumpf wird zu:

```java
    @Test
    void ohneVorlagePassiertNichts() {
        job.templateId = null;
        job.update();
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12), job);

        assertEquals(0, greenMail.getReceivedMessages().length);
        CookingReminder log = CookingReminder.findAll().firstResult();
        assertNotNull(log);
        assertEquals(CookingReminderStatus.ACCOUNT_UNAVAILABLE, log.status);
    }
```

In `CookingReminderSchedulerTest` dieselbe Umstellung: wo dort `CookingReminderSettings` angelegt und `reschedule()` bzw. `runFor` geprüft wird, tritt ein `MailJob` mit `kind=COOKING` an ihre Stelle; die Zusicherung über die abgeleitete Cron-Zeit (`toCron`) bleibt unverändert, die Zusicherung über die registrierte Job-Id wird von `JOB_ID` auf `CookingReminderScheduler.jobId(job.id)` umgestellt.

- [ ] **Step 6: Run tests to verify they pass**

Run: `.\mvnw.cmd test -Dtest=CookingReminder*Test`
Expected: PASS für `CookingReminderMultiJobRunTest`, `CookingReminderSchedulerTest`, `CookingReminderRunTest`, `CookingReminderTest`, `CookingReminderIndexMigrationTest`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java backend/src/main/java/at/kigruapp/resource/CookingReminderJobResource.java backend/src/test/java/at/kigruapp/scheduler/
git commit -m "feat(be): Kochdienst-Erinnerungen je aktivem Job verschicken"
```

---

### Task 6: Migration des Singletons in einen Kochdienst-Job

**Files:**
- Create: `backend/src/main/java/at/kigruapp/migration/CookingReminderSettingsToJobMigration.java`
- Test: `backend/src/test/java/at/kigruapp/migration/CookingReminderSettingsToJobMigrationTest.java`

**Interfaces:**
- Consumes: `MailJob.KIND_COOKING`, `MailTemplate.KIND_COOKING` (Task 1).
- Produces: `CookingReminderSettingsToJobMigration.run()`.

- [ ] **Step 1: Write the failing test**

Neue Datei `backend/src/test/java/at/kigruapp/migration/CookingReminderSettingsToJobMigrationTest.java`:

```java
package at.kigruapp.migration;

import at.kigruapp.entity.CookingReminderSettings;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CookingReminderSettingsToJobMigrationTest {

    @Inject
    CookingReminderSettingsToJobMigration migration;

    @BeforeEach
    void cleanup() {
        MailJob.deleteAll();
        MailTemplate.deleteAll();
        MailAccount.deleteAll();
        CookingReminderSettings.deleteAll();
    }

    private MailAccount persistAccount() {
        MailAccount account = new MailAccount();
        account.name = "Kindergarten";
        account.enabled = true;
        account.persist();
        return account;
    }

    private MailTemplate persistTemplate(String name) {
        MailTemplate template = new MailTemplate();
        template.name = name;
        template.bodyHtml = "<p>Am {{duty.date}} kochst du.</p>";
        template.createdAt = Instant.now();
        template.updatedAt = template.createdAt;
        template.persist();
        return template;
    }

    private void persistSettings(MailAccount account, MailTemplate template) {
        CookingReminderSettings settings = new CookingReminderSettings();
        settings.senderAccountId = account.id.toHexString();
        settings.templateId = template.id.toHexString();
        settings.subject = "Dein Kochdienst";
        settings.sendTime = "07:30";
        settings.updatedAt = Instant.now();
        settings.persist();
    }

    @Test
    void adoptsTheTemplateWhenNoGeneralJobUsesIt() {
        MailAccount account = persistAccount();
        MailTemplate template = persistTemplate("Erinnerung");
        persistSettings(account, template);

        migration.run();

        MailJob job = MailJob.find("kind", MailJob.KIND_COOKING).firstResult();
        assertNotNull(job);
        assertEquals("Kochdienst-Erinnerung", job.name);
        assertEquals("Dein Kochdienst", job.subject);
        assertEquals("07:30", job.sendTime);
        assertTrue(job.active);
        assertEquals(template.id, job.templateId);
        assertEquals(MailTemplate.KIND_COOKING,
                MailTemplate.<MailTemplate>findById(template.id).kind);
        assertEquals(1, MailTemplate.count());
    }

    @Test
    void copiesTheTemplateWhenAGeneralJobUsesIt() {
        MailAccount account = persistAccount();
        MailTemplate template = persistTemplate("Geteilt");
        persistSettings(account, template);

        MailJob general = new MailJob();
        general.name = "Newsletter";
        general.templateId = template.id;
        general.subject = "News";
        general.senderAccountId = account.id.toHexString();
        general.cron = "0 0 8 * * ?";
        general.createdAt = Instant.now();
        general.updatedAt = general.createdAt;
        general.persist();

        migration.run();

        MailJob job = MailJob.find("kind", MailJob.KIND_COOKING).firstResult();
        assertNotNull(job);
        assertEquals(2, MailTemplate.count());
        assertEquals(MailTemplate.KIND_GENERAL,
                MailTemplate.<MailTemplate>findById(template.id).effectiveKind());
        MailTemplate copy = MailTemplate.findById(job.templateId);
        assertEquals("Geteilt (Kochdienst)", copy.name);
        assertEquals(MailTemplate.KIND_COOKING, copy.kind);
    }

    @Test
    void isIdempotent() {
        MailAccount account = persistAccount();
        persistSettings(account, persistTemplate("Erinnerung"));

        migration.run();
        migration.run();

        assertEquals(1, MailJob.count("kind", MailJob.KIND_COOKING));
    }

    @Test
    void doesNothingWithoutSettings() {
        migration.run();

        assertEquals(0, MailJob.count());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=CookingReminderSettingsToJobMigrationTest`
Expected: FAIL — die Migrationsklasse existiert nicht.

- [ ] **Step 3: Migration implementieren**

Neue Datei `backend/src/main/java/at/kigruapp/migration/CookingReminderSettingsToJobMigration.java`:

```java
package at.kigruapp.migration;

import at.kigruapp.entity.CookingReminderSettings;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * Ueberfuehrt die alte Singleton-Konfiguration in einen Kochdienst-Job.
 * Das Singleton bleibt als Datensatz liegen, damit ein Rueckbau moeglich ist;
 * gelesen wird es nach dieser Migration nicht mehr.
 */
@ApplicationScoped
@Startup
public class CookingReminderSettingsToJobMigration {

    void onStart(@Observes StartupEvent ev) {
        run();
    }

    public void run() {
        if (MailJob.count("kind", MailJob.KIND_COOKING) > 0) {
            return;
        }
        CookingReminderSettings settings = CookingReminderSettings.findSingleton();
        if (settings == null || settings.senderAccountId == null || settings.templateId == null) {
            return;
        }
        MailTemplate source = ObjectId.isValid(settings.templateId)
                ? MailTemplate.findById(new ObjectId(settings.templateId))
                : null;
        if (source == null) {
            return;
        }

        MailTemplate target = usedByGeneralJob(source) ? copyOf(source) : adopt(source);

        MailJob job = new MailJob();
        job.kind = MailJob.KIND_COOKING;
        job.name = "Kochdienst-Erinnerung";
        job.templateId = target.id;
        job.subject = settings.subject;
        job.senderAccountId = settings.senderAccountId;
        job.sendTime = settings.sendTime == null ? "07:00" : settings.sendTime;
        job.active = isSendable(settings);
        job.createdAt = Instant.now();
        job.updatedAt = job.createdAt;
        job.persist();
        Log.infof("Kochdienst-Erinnerung: Einstellungen in Job %s ueberfuehrt", job.id.toHexString());
    }

    private boolean usedByGeneralJob(MailTemplate template) {
        for (MailJob job : MailJob.<MailJob>list("templateId", template.id)) {
            if (!job.isCooking()) {
                return true;
            }
        }
        return false;
    }

    private MailTemplate adopt(MailTemplate template) {
        template.kind = MailTemplate.KIND_COOKING;
        template.updatedAt = Instant.now();
        template.update();
        return template;
    }

    private MailTemplate copyOf(MailTemplate source) {
        MailTemplate copy = new MailTemplate();
        copy.name = source.name + " (Kochdienst)";
        copy.bodyHtml = source.bodyHtml;
        copy.kind = MailTemplate.KIND_COOKING;
        copy.createdAt = Instant.now();
        copy.updatedAt = copy.createdAt;
        copy.persist();
        return copy;
    }

    /** Aktiv nur, wenn das Konto existiert und freigeschaltet ist — sonst haette der Job nie gesendet. */
    private boolean isSendable(CookingReminderSettings settings) {
        if (!ObjectId.isValid(settings.senderAccountId)) {
            return false;
        }
        MailAccount account = MailAccount.findById(new ObjectId(settings.senderAccountId));
        return account != null && account.enabled;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=CookingReminderSettingsToJobMigrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/migration/CookingReminderSettingsToJobMigration.java backend/src/test/java/at/kigruapp/migration/CookingReminderSettingsToJobMigrationTest.java
git commit -m "feat(be): Migration der Kochdienst-Einstellungen in einen Job"
```

---

### Task 7: Status-Endpunkt und Sperre allgemeiner Job-Änderungen

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/CookingReminderSettingsResource.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/MailJobResource.java`
- Modify: `backend/src/main/java/at/kigruapp/scheduler/MailJobScheduler.java`
- Modify: `backend/src/main/java/at/kigruapp/scheduler/CookingReminderStartupRearmer.java` (nur prüfen, ob der Aufruf noch passt)
- Modify: `backend/src/test/java/at/kigruapp/resource/CookingReminderSettingsResourceTest.java`
- Test: `backend/src/test/java/at/kigruapp/resource/MailJobResourceTest.java`

**Interfaces:**
- Consumes: `MailJob.KIND_COOKING` (Task 1).
- Produces: `GET /api/v1/cooking-reminder-settings` liefert `{ "active": boolean }`.

- [ ] **Step 1: Write the failing tests**

`CookingReminderSettingsResourceTest` wird ersetzt durch Tests gegen das neue Verhalten (Kochdienst-Jobs statt Singleton). Kern:

```java
    @Test
    void inactiveWithoutAnyJob() {
        given()
                .when().get("/api/v1/cooking-reminder-settings")
                .then().statusCode(200)
                .body("active", is(false));
    }

    @Test
    void activeWithOneSendableJob() {
        MailAccount account = persistAccount(true);
        MailTemplate template = persistCookingTemplate();
        persistCookingJob(account, template, true);

        given()
                .when().get("/api/v1/cooking-reminder-settings")
                .then().statusCode(200)
                .body("active", is(true));
    }

    @Test
    void inactiveWhenTheOnlyJobIsInactiveOrItsAccountIsDisabled() {
        MailAccount disabled = persistAccount(false);
        persistCookingJob(disabled, persistCookingTemplate(), true);

        given()
                .when().get("/api/v1/cooking-reminder-settings")
                .then().statusCode(200)
                .body("active", is(false));
    }
```

An `MailJobResourceTest` anhängen:

```java
    @Test
    void cookingJobsCannotBeChangedOnTheGeneralEndpoint() {
        MailJob cooking = new MailJob();
        cooking.kind = MailJob.KIND_COOKING;
        cooking.name = "Erinnerung";
        cooking.subject = "x";
        cooking.sendTime = "07:00";
        cooking.createdAt = java.time.Instant.now();
        cooking.updatedAt = cooking.createdAt;
        cooking.persist();
        String id = cooking.id.toHexString();

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"Neu\",\"templateId\":null,\"subject\":\"y\",\"senderAccountId\":null,"
                        + "\"cron\":\"0 0 8 * * ?\",\"allParents\":true,\"recipientSelections\":[]}")
                .when().put("/api/v1/mail-jobs/" + id)
                .then().statusCode(409);

        given()
                .when().delete("/api/v1/mail-jobs/" + id)
                .then().statusCode(409);

        given()
                .when().post("/api/v1/mail-jobs/" + id + "/activate")
                .then().statusCode(409);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd test -Dtest=MailJobResourceTest`
Expected: FAIL — der allgemeine Endpunkt ändert Kochdienst-Jobs noch.

- [ ] **Step 3: Status-Endpunkt umbauen**

`backend/src/main/java/at/kigruapp/resource/CookingReminderSettingsResource.java` vollständig ersetzen:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;

/**
 * Statusabfrage der Kochdienst-Erinnerungen. Fuer alle Angemeldeten lesbar
 * (siehe SecurityFilter), damit der Kochdienst-Dialog weiss, ob er die
 * Erinnerungs-Option anbieten darf. Konfiguriert wird ueber
 * /api/v1/cooking-reminder-jobs.
 */
@Path("/api/v1/cooking-reminder-settings")
@Produces(MediaType.APPLICATION_JSON)
public class CookingReminderSettingsResource {

    public record StatusDto(boolean active) {}

    @GET
    public StatusDto get() {
        for (MailJob job : MailJob.<MailJob>list("kind = ?1 and active = ?2", MailJob.KIND_COOKING, true)) {
            if (isSendable(job)) {
                return new StatusDto(true);
            }
        }
        return new StatusDto(false);
    }

    private boolean isSendable(MailJob job) {
        if (job.templateId == null || MailTemplate.findById(job.templateId) == null) {
            return false;
        }
        if (job.senderAccountId == null || !ObjectId.isValid(job.senderAccountId)) {
            return false;
        }
        MailAccount account = MailAccount.findById(new ObjectId(job.senderAccountId));
        return account != null && account.enabled;
    }
}
```

- [ ] **Step 4: Kochdienst-Jobs im allgemeinen Endpunkt sperren**

In `backend/src/main/java/at/kigruapp/resource/MailJobResource.java` in `update`, `delete`, `activate` und `deactivate` jeweils direkt nach der Null-Prüfung des geladenen Jobs einfügen:

```java
        if (job.isCooking()) {
            throw new WebApplicationException(
                    "Kochdienst-Jobs werden in den Kochdienst-Einstellungen gepflegt", 409);
        }
```

In `create` nach `applyFields(job, request)` ergänzen, damit über diesen Weg nie ein Kochdienst-Job entsteht:

```java
        job.kind = MailJob.KIND_GENERAL;
```

- [ ] **Step 5: Kochdienst-Jobs aus dem allgemeinen Scheduler halten**

In `backend/src/main/java/at/kigruapp/scheduler/MailJobScheduler.java` am Anfang von `schedule(MailJob job)` einfügen (deckt auch den `MailJobStartupRearmer` ab, der über diese Methode geht):

```java
        if (job.isCooking()) {
            // Kochdienst-Jobs haben keinen Cron; sie laufen im CookingReminderScheduler.
            return;
        }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `.\mvnw.cmd test -Dtest=MailJobResourceTest,CookingReminderSettingsResourceTest,MailJobSchedulerTest,MailJobStartupRearmerTest`
Expected: PASS.

- [ ] **Step 7: Voller Backend-Lauf**

Run: `.\mvnw.cmd test`
Expected: keine neuen Fehlschläge gegenüber dem Stand vor Task 1 (der Baseline-Vergleich zählt, nicht die absolute Null).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/CookingReminderSettingsResource.java backend/src/main/java/at/kigruapp/resource/MailJobResource.java backend/src/main/java/at/kigruapp/scheduler/MailJobScheduler.java backend/src/test/java/at/kigruapp/resource/
git commit -m "feat(be): Status-Endpunkt der Kochdienst-Erinnerungen und Sperre im Job-Endpunkt"
```

---

### Task 8: Token-Utility für den duty-Namensraum

**Files:**
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-token.util.ts`
- Test: `frontend/src/app/settings/mail/mail-template-editor/mail-token.util.spec.ts`

**Interfaces:**
- Consumes: nichts.
- Produces: `TOKEN_RE` matcht `person` und `duty`; `tokensToPills(html, placeholders)` und `renderPreview(html, samples)` arbeiten über den vollen Token-String; `SAMPLE_VALUES` enthält Beispielwerte für beide Namensräume, Schlüssel ist der volle Token.

- [ ] **Step 1: Write the failing test**

An `frontend/src/app/settings/mail/mail-template-editor/mail-token.util.spec.ts` anhängen:

```typescript
  it('wandelt duty-Tokens in Pills mit Label', () => {
    const html = tokensToPills('<p>Am {{duty.date}} kochst du.</p>', [
      { token: '{{duty.date}}', fieldName: 'date', label: { de: 'Datum' }, group: 'KOCHDIENST', groupLabel: 'Kochdienst' },
    ]);

    expect(html).toContain('data-token="{{duty.date}}"');
    expect(html).toContain('>Datum<');
  });

  it('rendert die Vorschau fuer beide Namensraeume', () => {
    const preview = renderPreview('<p>{{person.firstName}} am {{duty.date}}</p>', SAMPLE_VALUES);

    expect(preview).toContain('Anna');
    expect(preview).toContain('10.08.2026');
  });

  it('laesst unbekannte Tokens leer', () => {
    expect(renderPreview('<p>{{duty.unbekannt}}</p>', SAMPLE_VALUES)).toBe('<p></p>');
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run (aus `frontend/`): `npm test -- --watch=false --browsers=ChromeHeadless --include=**/mail-token.util.spec.ts`
Expected: FAIL — `duty`-Tokens werden nicht ersetzt.

- [ ] **Step 3: Utility umstellen**

`frontend/src/app/settings/mail/mail-template-editor/mail-token.util.ts` anpassen:

```typescript
/** Matches a stored placeholder token of either namespace, capturing the whole token. */
export const TOKEN_RE = /\{\{(?:person|duty)\.[A-Za-z0-9_]+\}\}/g;

/** Stored HTML (raw {{tokens}}) -> editor HTML (pill spans). */
export function tokensToPills(html: string, placeholders: PlaceholderTile[]): string {
  const labels = new Map<string, string>();
  placeholders.forEach((p) => labels.set(p.token, p.label['de'] || p.fieldName));
  return html.replace(TOKEN_RE, (token) => pillSpan(token, labels.get(token) ?? token));
}

/** Fixed sample values used only for the client-side preview. Key is the full token. */
export const SAMPLE_VALUES: Record<string, string> = {
  '{{person.firstName}}': 'Anna',
  '{{person.lastName}}': 'Muster',
  '{{person.email}}': 'anna.muster@example.org',
  '{{person.phone}}': '+43 660 1234567',
  '{{person.dateOfBirth}}': '15.03.2015',
  '{{person.gender}}': 'weiblich',
  '{{person.notes}}': 'Allergien beachten',
  '{{duty.date}}': '10.08.2026',
  '{{duty.groups}}': 'Baeren, Fuechse',
  '{{duty.description}}': 'Gemuesesuppe',
  '{{duty.daysBefore}}': '2',
  '{{duty.personName}}': 'Anna Muster',
};

/** Stored HTML -> preview HTML with sample data (unknown tokens blanked). */
export function renderPreview(storedHtml: string, samples: Record<string, string>): string {
  return storedHtml.replace(TOKEN_RE, (token) => samples[token] ?? '');
}
```

`pillSpan` und `pillsToTokens` bleiben unverändert.

- [ ] **Step 4: Bestehende Spec-Aufrufe anpassen**

Die bereits vorhandenen Tests in `mail-token.util.spec.ts`, die `SAMPLE_VALUES` oder `tokensToPills` mit `fieldName`-Schlüsseln aufrufen, werden auf die Token-Schlüssel umgestellt. Die Zusicherungen bleiben inhaltlich gleich.

- [ ] **Step 5: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/mail-token.util.spec.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/mail/mail-template-editor/mail-token.util.ts frontend/src/app/settings/mail/mail-template-editor/mail-token.util.spec.ts
git commit -m "feat(fe): Token-Utility kennt den duty-Namensraum"
```

---

### Task 9: Modelle und Services für kind, Platzhalter und Kochdienst-Jobs

**Files:**
- Modify: `frontend/src/app/shared/models/mail-template.model.ts`
- Modify: `frontend/src/app/shared/models/mail-job.model.ts`
- Modify: `frontend/src/app/shared/models/cooking-reminder-settings.model.ts`
- Modify: `frontend/src/app/shared/services/mail-template.service.ts`
- Modify: `frontend/src/app/shared/services/cooking-reminder-settings.service.ts`
- Create: `frontend/src/app/shared/models/cooking-reminder-job.model.ts`
- Create: `frontend/src/app/shared/services/cooking-reminder-job.service.ts`
- Test: `frontend/src/app/shared/services/cooking-reminder-job.service.spec.ts`

**Interfaces:**
- Consumes: die Endpunkte aus Task 1, 2, 4, 7.
- Produces: `MailTemplateKind = 'GENERAL' | 'COOKING'`; `MailTemplate.kind`, `PlaceholderTile.group`, `PlaceholderTile.groupLabel`; `MailJob.kind`, `MailJob.sendTime`; `CookingReminderStatus { active: boolean }`; `CookingReminderJob`, `SaveCookingReminderJobRequest`; `MailTemplateService.list(kind?)`, `MailTemplateService.placeholders(kind?)`; `CookingReminderJobService.list/create/update/delete`.

- [ ] **Step 1: Write the failing test**

Neue Datei `frontend/src/app/shared/services/cooking-reminder-job.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CookingReminderJobService } from './cooking-reminder-job.service';
import { environment } from '../../../environments/environment';

describe('CookingReminderJobService', () => {
  let service: CookingReminderJobService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(CookingReminderJobService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('laedt die Kochdienst-Jobs', () => {
    service.list().subscribe((jobs) => expect(jobs.length).toBe(1));

    const req = http.expectOne(`${environment.apiUrl}/cooking-reminder-jobs`);
    expect(req.request.method).toBe('GET');
    req.flush([{ id: '1', name: 'Erinnerung', senderAccountId: 'a', subject: 's',
                 sendTime: '07:00', active: true, templateId: 't',
                 templateName: 'V', templateBodyHtml: '<p>x</p>' }]);
  });

  it('legt einen Job samt Vorlage an', () => {
    service.create({
      name: 'Erinnerung', senderAccountId: 'a', subject: 's', sendTime: '07:00',
      active: false, templateName: 'V', templateBodyHtml: '<p>x</p>',
    }).subscribe();

    const req = http.expectOne(`${environment.apiUrl}/cooking-reminder-jobs`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.templateName).toBe('V');
    req.flush({});
  });
});
```

Falls die vorhandenen Service-Specs im Projekt einen anderen Weg gehen, um an die Basis-URL zu kommen (`ApiService`), wird dieser Test an das dort etablierte Muster angeglichen — maßgeblich ist eine bestehende Spec wie `cooking-reminder-settings.service.spec.ts`.

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/cooking-reminder-job.service.spec.ts`
Expected: FAIL — Service existiert nicht.

- [ ] **Step 3: Modelle erweitern**

`frontend/src/app/shared/models/mail-template.model.ts`:

```typescript
export type MailTemplateKind = 'GENERAL' | 'COOKING';

export interface MailTemplate {
  id: string;
  name: string;
  bodyHtml: string;
  kind: MailTemplateKind;
  createdAt: string;
  updatedAt: string;
}

export interface SaveMailTemplateRequest {
  name: string;
  bodyHtml: string;
}

export interface PlaceholderTile {
  token: string;
  fieldName: string;
  label: Record<string, string>;
  group: string;
  groupLabel: string;
}
```

`frontend/src/app/shared/models/mail-job.model.ts` — `MailJob` um `kind: MailTemplateKind;` und `sendTime: string | null;` ergänzen (Import des Typs aus `mail-template.model`). `SaveMailJobRequest` bleibt unverändert.

`frontend/src/app/shared/models/cooking-reminder-settings.model.ts` vollständig ersetzen:

```typescript
export interface CookingReminderStatus {
  active: boolean;
}
```

Neue Datei `frontend/src/app/shared/models/cooking-reminder-job.model.ts`:

```typescript
export interface CookingReminderJob {
  id: string;
  name: string;
  senderAccountId: string;
  subject: string;
  sendTime: string;
  active: boolean;
  templateId: string;
  templateName: string;
  templateBodyHtml: string;
}

export interface SaveCookingReminderJobRequest {
  name: string;
  senderAccountId: string;
  subject: string;
  sendTime: string;
  active: boolean;
  templateName: string;
  templateBodyHtml: string;
}
```

- [ ] **Step 4: Services erweitern und anlegen**

`frontend/src/app/shared/services/mail-template.service.ts`:

```typescript
  list(kind?: MailTemplateKind): Observable<MailTemplate[]> {
    const query = kind ? `?kind=${kind}` : '';
    return this.api.get<MailTemplate[]>(`/mail-templates${query}`);
  }

  placeholders(kind?: MailTemplateKind): Observable<PlaceholderTile[]> {
    const query = kind ? `?kind=${kind}` : '';
    return this.api.get<PlaceholderTile[]>(`/mail-templates/placeholders${query}`);
  }
```

`frontend/src/app/shared/services/cooking-reminder-settings.service.ts` — `save()` entfernen, `get()` auf `CookingReminderStatus` umstellen.

Neue Datei `frontend/src/app/shared/services/cooking-reminder-job.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { CookingReminderJob, SaveCookingReminderJobRequest } from '../models/cooking-reminder-job.model';

@Injectable({ providedIn: 'root' })
export class CookingReminderJobService {
  constructor(private api: ApiService) {}

  list(): Observable<CookingReminderJob[]> {
    return this.api.get<CookingReminderJob[]>('/cooking-reminder-jobs');
  }

  create(request: SaveCookingReminderJobRequest): Observable<CookingReminderJob> {
    return this.api.post<CookingReminderJob>('/cooking-reminder-jobs', request);
  }

  update(id: string, request: SaveCookingReminderJobRequest): Observable<CookingReminderJob> {
    return this.api.put<CookingReminderJob>(`/cooking-reminder-jobs/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/cooking-reminder-jobs/${id}`);
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/cooking-reminder-job.service.spec.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/models/ frontend/src/app/shared/services/
git commit -m "feat(fe): Modelle und Services fuer Kochdienst-Jobs und Vorlagen-Arten"
```

---

### Task 10: Vorlagen-Maske als eigene Komponente

**Files:**
- Create: `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.ts`
- Create: `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.html`
- Create: `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.scss`
- Create: `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.html`
- Test: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts` (bestehend, bleibt grün)

**Interfaces:**
- Consumes: `MailTemplateService.placeholders(kind)` (Task 9), Token-Utility (Task 8).
- Produces: `MailTemplateFormComponent` mit `@Input() kind: MailTemplateKind`, `@Input() set value(v: { name: string; bodyHtml: string })` (bodyHtml in Token-Form), `@Output() valueChange: EventEmitter<{ name: string; bodyHtml: string }>` (bodyHtml in Token-Form), `@Input() nameLabel = 'Name'`, `get valid(): boolean`.

- [ ] **Step 1: Write the failing test**

Neue Datei `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { MailTemplateFormComponent } from './mail-template-form.component';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { PlaceholderTile } from '../../../shared/models/mail-template.model';

const TILES: PlaceholderTile[] = [
  { token: '{{duty.date}}', fieldName: 'date', label: { de: 'Datum' }, group: 'KOCHDIENST', groupLabel: 'Kochdienst' },
  { token: '{{person.firstName}}', fieldName: 'firstName', label: { de: 'Vorname' }, group: 'PERSON', groupLabel: 'Person' },
];

describe('MailTemplateFormComponent', () => {
  let fixture: ComponentFixture<MailTemplateFormComponent>;
  let component: MailTemplateFormComponent;
  let service: jasmine.SpyObj<MailTemplateService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj('MailTemplateService', ['placeholders']);
    service.placeholders.and.returnValue(of(TILES));

    await TestBed.configureTestingModule({
      imports: [MailTemplateFormComponent, HttpClientTestingModule, NoopAnimationsModule],
      providers: [{ provide: MailTemplateService, useValue: service }],
    }).compileComponents();

    fixture = TestBed.createComponent(MailTemplateFormComponent);
    component = fixture.componentInstance;
  });

  it('laedt die Platzhalter fuer die uebergebene Art', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();

    expect(service.placeholders).toHaveBeenCalledWith('COOKING');
  });

  it('gruppiert die Chips nach Gruppe', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();

    expect(component.groups.map((g) => g.label)).toEqual(['Kochdienst', 'Person']);
    expect(component.groups[0].tiles.length).toBe(1);
  });

  it('meldet Aenderungen in Token-Form nach aussen', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();
    const emitted: { name: string; bodyHtml: string }[] = [];
    component.valueChange.subscribe((v) => emitted.push(v));

    component.form.patchValue({ name: 'Vorlage', bodyHtml: '<p>Hallo</p>' });

    expect(emitted[emitted.length - 1]).toEqual({ name: 'Vorlage', bodyHtml: '<p>Hallo</p>' });
  });

  it('ist erst mit Name und Inhalt gueltig', () => {
    component.kind = 'COOKING';
    fixture.detectChanges();

    expect(component.valid).toBeFalse();
    component.form.patchValue({ name: 'Vorlage', bodyHtml: '<p>Hallo</p>' });
    expect(component.valid).toBeTrue();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/mail-template-form.component.spec.ts`
Expected: FAIL — Komponente existiert nicht.

- [ ] **Step 3: Maske herauslösen**

Neue Datei `mail-template-form.component.ts`. Der Inhalt entsteht aus dem heutigen `MailTemplateEditorComponent`: Formular, Quill, Chips, Drag-Handling und Vorschau wandern hierher, Liste/Speichern/Löschen bleiben zurück.

```typescript
import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import Quill from 'quill';
import { QuillModule } from 'ngx-quill';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplateKind, PlaceholderTile } from '../../../shared/models/mail-template.model';
import { configureQuillForEmailSafeOutput, EMAIL_SAFE_QUILL_TOOLBAR } from './quill-email-safe.config';
import { tokensToPills, pillsToTokens, pillSpan, renderPreview, SAMPLE_VALUES } from './mail-token.util';

const DRAG_MIME = 'application/x-mail-token';

/** Eine Chip-Gruppe mit ihrer Ueberschrift. */
export interface PlaceholderGroup {
  label: string;
  tiles: PlaceholderTile[];
}

/**
 * Die reine Vorlagen-Maske: Name, gruppierte Platzhalter-Chips, Editor und
 * Vorschau. Speichert nicht selbst — der einbettende Bereich entscheidet, wohin
 * der Wert geht (allgemeine Vorlagen oder Kochdienst-Job).
 */
@Component({
  selector: 'app-mail-template-form',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatIconModule,
    QuillModule,
  ],
  templateUrl: './mail-template-form.component.html',
  styleUrl: './mail-template-form.component.scss',
})
export class MailTemplateFormComponent implements OnInit {
  readonly quillModules = { toolbar: EMAIL_SAFE_QUILL_TOOLBAR };

  @Input() kind: MailTemplateKind = 'GENERAL';
  @Input() nameLabel = 'Name';

  /** Wert in Token-Form; wird beim Setzen in Pill-Form uebersetzt. */
  @Input() set value(v: { name: string; bodyHtml: string }) {
    this.form.patchValue(
      { name: v.name, bodyHtml: tokensToPills(v.bodyHtml, this.placeholders) },
      { emitEvent: false },
    );
    this.updatePreview(this.form.value.bodyHtml ?? '');
  }

  @Output() valueChange = new EventEmitter<{ name: string; bodyHtml: string }>();

  placeholders: PlaceholderTile[] = [];
  groups: PlaceholderGroup[] = [];
  previewHtml: SafeHtml;
  quillInstance: any = null;

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    bodyHtml: new FormControl('', Validators.required),
  });

  constructor(
    private mailTemplateService: MailTemplateService,
    private sanitizer: DomSanitizer,
  ) {
    configureQuillForEmailSafeOutput();
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml('');
  }

  ngOnInit(): void {
    this.mailTemplateService.placeholders(this.kind).subscribe((tiles) => {
      this.placeholders = tiles;
      this.groups = this.buildGroups(tiles);
    });
    this.form.valueChanges.subscribe(() => {
      this.updatePreview(this.form.value.bodyHtml ?? '');
      this.valueChange.emit(this.currentValue());
    });
  }

  get valid(): boolean {
    return this.form.valid;
  }

  currentValue(): { name: string; bodyHtml: string } {
    return {
      name: this.form.value.name ?? '',
      bodyHtml: pillsToTokens(this.form.value.bodyHtml ?? ''),
    };
  }

  /** Erhaelt die Reihenfolge, in der der Server die Kacheln liefert. */
  private buildGroups(tiles: PlaceholderTile[]): PlaceholderGroup[] {
    const groups: PlaceholderGroup[] = [];
    tiles.forEach((tile) => {
      const existing = groups.find((g) => g.label === tile.groupLabel);
      if (existing) {
        existing.tiles.push(tile);
      } else {
        groups.push({ label: tile.groupLabel, tiles: [tile] });
      }
    });
    return groups;
  }

  onEditorCreated(editor: any): void {
    this.quillInstance = editor;
  }

  private labelFor(tile: PlaceholderTile): string {
    return tile.label['de'] || tile.fieldName;
  }

  private syncBodyFromQuill(): void {
    this.form.patchValue({ bodyHtml: this.quillInstance.root?.innerHTML ?? '' });
  }

  private insertPillAt(index: number, tile: PlaceholderTile): void {
    this.quillInstance.insertEmbed(index, 'mail-token', { token: tile.token, label: this.labelFor(tile) });
    this.quillInstance.setSelection(index + 1, 0);
    this.syncBodyFromQuill();
  }

  insertPlaceholder(tile: PlaceholderTile): void {
    if (this.quillInstance) {
      const selection = this.quillInstance.getSelection?.();
      const index = selection ? selection.index : this.quillInstance.getLength();
      this.insertPillAt(index, tile);
    } else {
      const current = this.form.value.bodyHtml ?? '';
      this.form.patchValue({ bodyHtml: current + pillSpan(tile.token, this.labelFor(tile)) });
    }
  }

  onChipDragStart(event: DragEvent, tile: PlaceholderTile): void {
    event.dataTransfer?.setData(DRAG_MIME, tile.token);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'copy';
    }
  }

  onEditorDragOver(event: DragEvent): void {
    event.preventDefault();
  }

  onEditorDrop(event: DragEvent): void {
    const token = event.dataTransfer?.getData(DRAG_MIME);
    if (!token || !this.quillInstance) {
      return;
    }
    event.preventDefault();
    const tile = this.placeholders.find((p) => p.token === token);
    if (!tile) {
      return;
    }
    this.insertPillAt(this.dropIndex(event), tile);
  }

  /** Best-effort caret index from the drop point; falls back to the document end. */
  private dropIndex(event: DragEvent): number {
    const end = Math.max(0, this.quillInstance.getLength() - 1);
    try {
      const doc: any = document;
      const range = doc.caretRangeFromPoint?.(event.clientX, event.clientY);
      if (!range) {
        return end;
      }
      const blot = Quill.find(range.startContainer, true);
      if (!blot) {
        return end;
      }
      return this.quillInstance.getIndex(blot) + range.startOffset;
    } catch {
      return end;
    }
  }

  private updatePreview(editorHtml: string): void {
    const rendered = renderPreview(pillsToTokens(editorHtml), SAMPLE_VALUES);
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml(rendered);
  }
}
```

`mail-template-form.component.html` — aus dem heutigen Editor-Template übernommen, Chips jetzt gruppiert:

```html
<mat-form-field appearance="outline" [formGroup]="form">
  <mat-label>{{ nameLabel }}</mat-label>
  <input matInput formControlName="name" />
</mat-form-field>

<div class="chip-bar" *ngFor="let group of groups">
  <span class="chip-hint">{{ group.label }} (klicken oder in den Text ziehen):</span>
  <button class="chip" type="button"
          *ngFor="let tile of group.tiles"
          draggable="true"
          (dragstart)="onChipDragStart($event, tile)"
          (click)="insertPlaceholder(tile)">
    {{ tile.label['de'] || tile.fieldName }}
  </button>
</div>

<label class="field-label">Inhalt</label>
<div class="editor-wrap" [formGroup]="form"
     (dragover)="onEditorDragOver($event)" (drop)="onEditorDrop($event)">
  <quill-editor formControlName="bodyHtml" [modules]="quillModules"
                (onEditorCreated)="onEditorCreated($event)"></quill-editor>
</div>

<div class="preview">
  <label class="field-label">Vorschau <span class="muted">(mit Beispiel-Daten)</span></label>
  <div class="preview-box" [innerHTML]="previewHtml"></div>
</div>
```

`mail-template-form.component.scss` — die Regeln für `.chip-bar`, `.chip`, `.chip-hint`, `.field-label`, `.editor-wrap`, `.preview`, `.preview-box`, `.muted` aus `mail-template-editor.component.scss` hierher verschieben.

- [ ] **Step 4: Editor auf die Maske umstellen**

`mail-template-editor.component.ts`: Chips, Quill-Handling, Vorschau und Token-Konvertierung entfernen; stattdessen:

```typescript
  /** Aktueller Wert der eingebetteten Maske, in Token-Form. */
  formValue = { name: '', bodyHtml: '' };
  editorValue = { name: '', bodyHtml: '' };
  formValid = false;

  onFormValueChange(value: { name: string; bodyHtml: string }): void {
    this.formValue = value;
    this.formValid = value.name.trim().length > 0 && value.bodyHtml.trim().length > 0;
  }

  onSelectTemplate(id: string): void {
    const template = this.templates.find((t) => t.id === id);
    if (!template || template.kind === 'COOKING') {
      return;
    }
    this.selectedId = template.id;
    this.editing = true;
    this.editorValue = { name: template.name, bodyHtml: template.bodyHtml };
  }

  newTemplate(): void {
    this.selectedId = null;
    this.editing = true;
    this.editorValue = { name: '', bodyHtml: '' };
  }

  save(): void {
    const request = { name: this.formValue.name, bodyHtml: this.formValue.bodyHtml };
    // Rest unveraendert (create/update, Notification, closeEditor, load)
  }

  isCooking(template: MailTemplate): boolean {
    return template.kind === 'COOKING';
  }
```

`mail-template-editor.component.html`: Der Block von `<mat-form-field>` „Name" bis `</div>` der Vorschau wird ersetzt durch

```html
    <app-mail-template-form [kind]="'GENERAL'" [value]="editorValue"
                            (valueChange)="onFormValueChange($event)"></app-mail-template-form>
```

Der Speichern-Button prüft `[disabled]="!formValid"`. In der Liste bekommt jeder Eintrag:

```html
        <span class="kind-chip" *ngIf="isCooking(t)"
              matTooltip="Wird in Organisation → Dienst-Einstellungen gepflegt">Kochdienst</span>
```

und sowohl der Bearbeiten-Button (`.tpl-main`) als auch der Löschen-Button erhalten `[disabled]="isCooking(t)"`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/mail-template-*.spec.ts`
Expected: PASS — sowohl die neue Spec als auch die bestehende `mail-template-editor.component.spec.ts`. Wo diese auf entfernte Methoden (`insertPlaceholder`, `onEditorDrop`, …) zugreift, werden die betroffenen Fälle auf `MailTemplateFormComponent` verschoben; ihre Zusicherungen bleiben inhaltlich unverändert.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/mail/mail-template-editor/
git commit -m "refactor(fe): Vorlagen-Maske als eigene Komponente"
```

---

### Task 11: Kochdienst-Jobs-Komponente

**Files:**
- Create: `frontend/src/app/settings/organisation/cooking-reminder-jobs/cooking-reminder-jobs.component.ts`
- Create: `frontend/src/app/settings/organisation/cooking-reminder-jobs/cooking-reminder-jobs.component.html`
- Create: `frontend/src/app/settings/organisation/cooking-reminder-jobs/cooking-reminder-jobs.component.scss`
- Test: `frontend/src/app/settings/organisation/cooking-reminder-jobs/cooking-reminder-jobs.component.spec.ts`

**Interfaces:**
- Consumes: `CookingReminderJobService` (Task 9), `MailAccountService`, `NotificationService`, `MailTemplateFormComponent` (Task 10).
- Produces: `CookingReminderJobsComponent` mit Selector `app-cooking-reminder-jobs`.

- [ ] **Step 1: Write the failing test**

Neue Datei `cooking-reminder-jobs.component.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { CookingReminderJobsComponent } from './cooking-reminder-jobs.component';
import { CookingReminderJobService } from '../../../shared/services/cooking-reminder-job.service';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { CookingReminderJob } from '../../../shared/models/cooking-reminder-job.model';

const JOB: CookingReminderJob = {
  id: '1', name: 'Erinnerung', senderAccountId: 'a', subject: 'Dein Kochdienst',
  sendTime: '07:30', active: true, templateId: 't', templateName: 'Vorlage',
  templateBodyHtml: '<p>Am {{duty.date}} kochst du.</p>',
};

describe('CookingReminderJobsComponent', () => {
  let fixture: ComponentFixture<CookingReminderJobsComponent>;
  let component: CookingReminderJobsComponent;
  let jobService: jasmine.SpyObj<CookingReminderJobService>;

  beforeEach(async () => {
    jobService = jasmine.createSpyObj('CookingReminderJobService', ['list', 'create', 'update', 'delete']);
    jobService.list.and.returnValue(of([JOB]));
    jobService.create.and.returnValue(of(JOB));
    jobService.update.and.returnValue(of(JOB));
    jobService.delete.and.returnValue(of(void 0));

    const accountService = jasmine.createSpyObj('MailAccountService', ['list']);
    accountService.list.and.returnValue(of([{ id: 'a', name: 'Kindergarten', enabled: true }]));

    await TestBed.configureTestingModule({
      imports: [CookingReminderJobsComponent, HttpClientTestingModule, NoopAnimationsModule],
      providers: [
        { provide: CookingReminderJobService, useValue: jobService },
        { provide: MailAccountService, useValue: accountService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CookingReminderJobsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('laedt die Jobs', () => {
    expect(component.jobs.length).toBe(1);
  });

  it('uebernimmt Job und Vorlage in die Maske', () => {
    component.selectForEdit(JOB);

    expect(component.form.value.subject).toBe('Dein Kochdienst');
    expect(component.templateValue).toEqual({ name: 'Vorlage', bodyHtml: '<p>Am {{duty.date}} kochst du.</p>' });
  });

  it('sendet Job und Vorlage gemeinsam beim Anlegen', () => {
    component.newJob();
    component.form.patchValue({
      name: 'Neu', senderAccountId: 'a', subject: 'Betreff', sendTime: '08:00', active: false,
    });
    component.onTemplateChange({ name: 'V', bodyHtml: '<p>x</p>' });

    component.save();

    expect(jobService.create).toHaveBeenCalledWith({
      name: 'Neu', senderAccountId: 'a', subject: 'Betreff', sendTime: '08:00', active: false,
      templateName: 'V', templateBodyHtml: '<p>x</p>',
    });
  });

  it('schaltet einen Job ueber die Liste um', () => {
    component.toggleActive(JOB);

    expect(jobService.update).toHaveBeenCalledWith('1', jasmine.objectContaining({ active: false }));
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/cooking-reminder-jobs.component.spec.ts`
Expected: FAIL — Komponente existiert nicht.

- [ ] **Step 3: Komponente implementieren**

`cooking-reminder-jobs.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CookingReminderJobService } from '../../../shared/services/cooking-reminder-job.service';
import { CookingReminderJob, SaveCookingReminderJobRequest } from '../../../shared/models/cooking-reminder-job.model';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { MailAccount } from '../../../shared/models/mail-account.model';
import { NotificationService } from '../../../shared/services/notification.service';
import { MailTemplateFormComponent } from '../../mail/mail-template-editor/mail-template-form.component';

const DEFAULT_SEND_TIME = '07:00';

/**
 * Kochdienst-Erinnerungen: links die Jobs, rechts Job-Formular und die fest
 * zugeordnete Vorlage. Beides geht in einem Request an den Server.
 */
@Component({
  selector: 'app-cooking-reminder-jobs',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatIconModule, MatSlideToggleModule, MatTooltipModule,
    MailTemplateFormComponent,
  ],
  templateUrl: './cooking-reminder-jobs.component.html',
  styleUrl: './cooking-reminder-jobs.component.scss',
})
export class CookingReminderJobsComponent implements OnInit {
  jobs: CookingReminderJob[] = [];
  accounts: MailAccount[] = [];

  selectedId: string | null = null;
  editing = false;

  /** Wert fuer die Maske (Eingang) und der zuletzt gemeldete Wert (Ausgang). */
  templateValue = { name: '', bodyHtml: '' };
  private currentTemplate = { name: '', bodyHtml: '' };

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    senderAccountId: new FormControl('', Validators.required),
    subject: new FormControl('', Validators.required),
    sendTime: new FormControl(DEFAULT_SEND_TIME, Validators.required),
    active: new FormControl<boolean>(false, { nonNullable: true }),
  });

  constructor(
    private jobService: CookingReminderJobService,
    private mailAccountService: MailAccountService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
    this.mailAccountService.list().subscribe((accounts) => (this.accounts = accounts.filter((a) => a.enabled)));
  }

  load(): void {
    this.jobService.list().subscribe((jobs) => (this.jobs = jobs));
  }

  onTemplateChange(value: { name: string; bodyHtml: string }): void {
    this.currentTemplate = value;
  }

  get canSave(): boolean {
    return this.form.valid
      && this.currentTemplate.name.trim().length > 0
      && this.currentTemplate.bodyHtml.trim().length > 0;
  }

  selectForEdit(job: CookingReminderJob): void {
    this.selectedId = job.id;
    this.editing = true;
    this.form.patchValue({
      name: job.name,
      senderAccountId: job.senderAccountId,
      subject: job.subject,
      sendTime: job.sendTime,
      active: job.active,
    });
    this.templateValue = { name: job.templateName, bodyHtml: job.templateBodyHtml };
    this.currentTemplate = { ...this.templateValue };
  }

  newJob(): void {
    this.selectedId = null;
    this.editing = true;
    this.form.reset({ name: '', senderAccountId: '', subject: '', sendTime: DEFAULT_SEND_TIME, active: false });
    this.templateValue = { name: '', bodyHtml: '' };
    this.currentTemplate = { name: '', bodyHtml: '' };
  }

  closeEditor(): void {
    this.selectedId = null;
    this.editing = false;
    this.form.reset({ name: '', senderAccountId: '', subject: '', sendTime: DEFAULT_SEND_TIME, active: false });
    this.templateValue = { name: '', bodyHtml: '' };
    this.currentTemplate = { name: '', bodyHtml: '' };
  }

  private toRequest(active: boolean): SaveCookingReminderJobRequest {
    const v = this.form.value;
    return {
      name: v.name ?? '',
      senderAccountId: v.senderAccountId ?? '',
      subject: v.subject ?? '',
      sendTime: v.sendTime ?? DEFAULT_SEND_TIME,
      active,
      templateName: this.currentTemplate.name,
      templateBodyHtml: this.currentTemplate.bodyHtml,
    };
  }

  save(): void {
    const request = this.toRequest(this.form.value.active ?? false);
    const isUpdate = this.selectedId !== null;
    const save$ = this.selectedId
      ? this.jobService.update(this.selectedId, request)
      : this.jobService.create(request);
    save$.subscribe({
      next: () => {
        this.notify.success(isUpdate ? 'Erinnerung aktualisiert' : 'Erinnerung gespeichert');
        this.closeEditor();
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  /** Der Schalter in der Liste speichert den Job unveraendert mit gekipptem active. */
  toggleActive(job: CookingReminderJob): void {
    const activating = !job.active;
    this.jobService.update(job.id, {
      name: job.name,
      senderAccountId: job.senderAccountId,
      subject: job.subject,
      sendTime: job.sendTime,
      active: activating,
      templateName: job.templateName,
      templateBodyHtml: job.templateBodyHtml,
    }).subscribe({
      next: () => {
        this.notify.success(activating ? 'Erinnerung aktiviert' : 'Erinnerung deaktiviert');
        this.load();
      },
      error: (err) => {
        this.notify.error(this.notify.extractError(err));
        this.load();
      },
    });
  }

  delete(job: CookingReminderJob): void {
    this.jobService.delete(job.id).subscribe({
      next: () => {
        this.notify.success('Erinnerung geloescht');
        if (this.selectedId === job.id) {
          this.closeEditor();
        }
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
```

`cooking-reminder-jobs.component.html`:

```html
<div class="reminder-jobs">
  <aside class="job-list">
    <div class="list-head">
      <h3>Erinnerungen</h3>
      <button mat-stroked-button color="primary" type="button" (click)="newJob()">
        <mat-icon>add</mat-icon> Neue Erinnerung
      </button>
    </div>

    <nav class="jobs" *ngIf="jobs.length; else emptyList">
      <div class="job-item" *ngFor="let job of jobs" [class.selected]="job.id === selectedId">
        <button class="job-main" type="button" (click)="selectForEdit(job)">
          <span class="job-name">{{ job.name }}</span>
          <span class="job-time">{{ job.sendTime }}</span>
        </button>
        <mat-slide-toggle [checked]="job.active" (change)="toggleActive(job)"
                          matTooltip="Aktiv"></mat-slide-toggle>
        <button mat-icon-button type="button" (click)="delete(job)" matTooltip="Loeschen">
          <mat-icon>delete_outline</mat-icon>
        </button>
      </div>
    </nav>

    <ng-template #emptyList>
      <p class="empty">Noch keine Erinnerung angelegt.</p>
    </ng-template>
  </aside>

  <div class="job-placeholder" *ngIf="!editing">
    <mat-icon>notifications</mat-icon>
    <p class="placeholder-title">Keine Erinnerung ausgewählt</p>
    <p class="placeholder-sub">Wähle links eine bestehende Erinnerung oder lege über
      „Neue Erinnerung“ eine neue an.</p>
  </div>

  <form *ngIf="editing" [formGroup]="form" class="job-form" (ngSubmit)="save()">
    <header class="form-head">
      <h3>{{ selectedId ? 'Erinnerung bearbeiten' : 'Neue Erinnerung' }}</h3>
    </header>

    <mat-form-field appearance="outline">
      <mat-label>Name</mat-label>
      <input matInput formControlName="name" />
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Mailkonto</mat-label>
      <mat-select formControlName="senderAccountId">
        <mat-option *ngFor="let account of accounts" [value]="account.id">{{ account.name }}</mat-option>
      </mat-select>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Betreff</mat-label>
      <input matInput formControlName="subject" />
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Versandzeit</mat-label>
      <input matInput type="time" formControlName="sendTime" />
    </mat-form-field>

    <mat-slide-toggle formControlName="active">Aktiv</mat-slide-toggle>

    <p class="recipients-hint">
      Empfänger sind immer die Eltern der Familie, die den Kochdienst übernommen hat.
      Wie viele Tage vorher erinnert wird, legt das Elternteil beim Kochdienst selbst fest.
    </p>

    <app-mail-template-form [kind]="'COOKING'" nameLabel="Name der Vorlage"
                            [value]="templateValue"
                            (valueChange)="onTemplateChange($event)"></app-mail-template-form>

    <footer class="actions">
      <button mat-button type="button" (click)="closeEditor()">Verwerfen</button>
      <span class="spacer"></span>
      <button mat-flat-button color="primary" type="submit" [disabled]="!canSave">Speichern</button>
    </footer>
  </form>
</div>
```

`cooking-reminder-jobs.component.scss` — Layout analog `mail-template-editor.component.scss`: `.reminder-jobs` als Grid mit schmaler Liste links und Formular rechts, `.job-item` als Zeile mit Flex, `.actions` mit `.spacer { flex: 1; }`.

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/cooking-reminder-jobs.component.spec.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/settings/organisation/cooking-reminder-jobs/
git commit -m "feat(fe): Master-Detail fuer Kochdienst-Erinnerungen"
```

---

### Task 12: Organisation-Tab auf die neue Komponente umstellen

**Files:**
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.html:100-149`
- Modify: `frontend/src/app/settings/organisation/organisation.component.spec.ts`
- Modify: `frontend/src/app/settings/organisation/cooking-reminder-settings.spec.ts`

**Interfaces:**
- Consumes: `CookingReminderJobsComponent` (Task 11).
- Produces: nichts Neues.

- [ ] **Step 1: Bestehende Spec auf das neue Verhalten umschreiben**

`cooking-reminder-settings.spec.ts` prüft heute das alte Formular (Konto/Vorlage/Betreff/Versandzeit auf `organisation.component`). Sie wird ersetzt durch eine Spec, die nachweist, dass der Tab die neue Komponente rendert und das alte Formular nicht mehr existiert:

```typescript
  it('rendert die Kochdienst-Erinnerungen als eigene Komponente', () => {
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('app-cooking-reminder-jobs')).not.toBeNull();
  });

  it('haelt kein eigenes Erinnerungs-Formular mehr', () => {
    expect((component as unknown as Record<string, unknown>)['reminderForm']).toBeUndefined();
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/cooking-reminder-settings.spec.ts`
Expected: FAIL — die Komponente ist nicht eingebunden, `reminderForm` existiert noch.

- [ ] **Step 3: Altes Formular entfernen**

In `organisation.component.ts` streichen: `mailAccounts`, `mailTemplates`, `reminderSettingsActive`, `reminderForm`, `reminderPlaceholders`, `loadReminderSettings()`, `saveReminderSettings()`, der Aufruf `this.loadReminderSettings()` in `ngOnInit`, die Konstruktor-Parameter `cookingReminderSettingsService`, `mailAccountService`, `mailTemplateService` sowie deren Importe. `CookingReminderJobsComponent` importieren und in das `imports`-Array der Komponente aufnehmen.

Bleiben `notificationService` und `dialog` erhalten — sie werden an anderen Stellen der Klasse genutzt.

- [ ] **Step 4: Template ersetzen**

In `organisation.component.html` den Block von `<h3>Kochdienst — Erinnerungen</h3>` (Zeile 100) bis zum schließenden `</p>` des Platzhalter-Hinweises (Zeile 149) vollständig ersetzen durch:

```html
        <h3>Kochdienst — Erinnerungen</h3>
        <app-cooking-reminder-jobs></app-cooking-reminder-jobs>
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/organisation*.spec.ts`
Expected: PASS für `organisation.component.spec.ts` und `cooking-reminder-settings.spec.ts`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/organisation/organisation.component.ts frontend/src/app/settings/organisation/organisation.component.html frontend/src/app/settings/organisation/organisation.component.spec.ts frontend/src/app/settings/organisation/cooking-reminder-settings.spec.ts
git commit -m "feat(fe): Kochdienst-Erinnerungen im Organisation-Tab als Job-Verwaltung"
```

---

### Task 13: Kochdienst-Jobs in den Mail-Einstellungen kennzeichnen und sperren

**Files:**
- Modify: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.ts`
- Modify: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.html`
- Test: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts`

**Interfaces:**
- Consumes: `MailTemplateService.list('GENERAL')` (Task 9), `MailJob.kind` (Task 9).
- Produces: `MailJobEditorComponent.isCooking(job)`.

- [ ] **Step 1: Write the failing test**

An `mail-job-editor.component.spec.ts` anhängen (der bestehende Spy auf `MailTemplateService` wird dafür genutzt):

```typescript
  it('laedt nur allgemeine Vorlagen fuer das Dropdown', () => {
    fixture.detectChanges();

    expect(mailTemplateService.list).toHaveBeenCalledWith('GENERAL');
  });

  it('erkennt Kochdienst-Jobs', () => {
    expect(component.isCooking({ ...JOB, kind: 'COOKING' })).toBeTrue();
    expect(component.isCooking({ ...JOB, kind: 'GENERAL' })).toBeFalse();
  });

  it('oeffnet einen Kochdienst-Job nicht zum Bearbeiten', () => {
    component.selectForEdit({ ...JOB, kind: 'COOKING' });

    expect(component.editing).toBeFalse();
  });
```

`JOB` ist die im Spec bereits vorhandene Beispiel-Job-Konstante; falls keine existiert, wird sie mit allen Feldern aus `MailJob` angelegt (`kind: 'GENERAL'`, `sendTime: null`).

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/mail-job-editor.component.spec.ts`
Expected: FAIL — `isCooking` existiert nicht, `list` wird ohne Argument gerufen.

- [ ] **Step 3: Komponente anpassen**

In `mail-job-editor.component.ts`:

```typescript
    this.mailTemplateService.list('GENERAL').subscribe((templates) => (this.templates = templates));
```

```typescript
  isCooking(job: MailJob): boolean {
    return job.kind === 'COOKING';
  }
```

In `selectForEdit` als erste Zeile:

```typescript
    if (this.isCooking(job)) {
      return;
    }
```

- [ ] **Step 4: Liste kennzeichnen**

In `mail-job-editor.component.html` beim Listeneintrag ergänzen:

```html
        <span class="kind-chip" *ngIf="isCooking(job)"
              matTooltip="Wird in Organisation → Dienst-Einstellungen gepflegt">Kochdienst</span>
```

Der Aktiv-Schalter, der Löschen-Button und der Bearbeiten-Button des Eintrags erhalten `[disabled]="isCooking(job)"`.

Die Klasse `.kind-chip` in `mail-job-editor.component.scss` und `mail-template-editor.component.scss` anlegen: kleiner, abgerundeter Hintergrund-Chip in gedämpfter Farbe, `font-size: 11px`.

- [ ] **Step 5: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/mail-job-editor.component.spec.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/mail/mail-job-editor/
git commit -m "feat(fe): Kochdienst-Jobs in den Mail-Einstellungen gekennzeichnet und gesperrt"
```

---

### Task 14: Vollständiger Lauf und Aufräumen

**Files:**
- Modify: alle in vorherigen Tasks berührten Dateien (nur Korrekturen)

**Interfaces:**
- Consumes: alles Vorherige.
- Produces: nichts Neues.

- [ ] **Step 1: Voller Frontend-Lauf**

Run (aus `frontend/`): `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: keine Fehlschläge außer den in `docs`/Memory dokumentierten Baseline-Fehlern.

- [ ] **Step 2: Voller Backend-Lauf**

Run (aus `backend/`): `.\mvnw.cmd test`
Expected: keine neuen Fehlschläge gegenüber der Baseline.

- [ ] **Step 3: Produktionsbuild des Frontends**

Run (aus `frontend/`): `npm run build`
Expected: erfolgreich — fängt AOT-Fehler, die den Karma-Lauf nicht sieht (z. B. Templates, die auf entfernte Felder zugreifen).

- [ ] **Step 4: Ungenutzte Reste entfernen**

Prüfen und entfernen, falls durch die Umstellung verwaist:
- `frontend/src/app/shared/services/cooking-reminder-settings.service.spec.ts` — auf die reduzierte `get()`-Signatur anpassen.
- Import von `CookingReminderSettings` in `CookingReminderScheduler` (durch Task 5 entfallen).
- `CookingReminderSettingsResource.findAccount`/`findTemplate` — Aufrufer prüfen; werden sie nirgends mehr gebraucht, entfallen sie mit Task 7.

Die Entity `CookingReminderSettings` bleibt bestehen (Migrationsquelle), ebenso ihre Collection.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: Aufraeumen nach Umstellung der Kochdienst-Erinnerungen"
```

- [ ] **Step 6: Manueller Rauchtest**

Nach dem Start von Backend und Frontend prüfen:
1. Organisation → Dienst-Einstellungen: eine Erinnerung anlegen (Name, Konto, Betreff, Versandzeit, Vorlagenname, Text mit `{{duty.date}}`), aktivieren, speichern.
2. Mail-Einstellungen → Vorlagen und Jobs: der neue Eintrag erscheint jeweils mit Chip „Kochdienst" und lässt sich dort nicht bearbeiten oder löschen.
3. Kochdienst-Kalender: beim Anlegen eines Kochdienstes erscheinen „Erinnerung aktivieren" und „Tage vorher".
4. Eine zweite Erinnerung anlegen und aktivieren; beide erscheinen in der Liste.
