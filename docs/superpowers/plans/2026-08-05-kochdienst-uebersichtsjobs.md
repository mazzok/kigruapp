# Kochdienst-Übersichtsjobs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the existing "Kochdienst — Erinnerungen" section into two tabs — "Erinnerungen" (unchanged) and a new "Übersichtsjobs" — by renaming the `kind` value `COOKING` to `COOKING_REMINDER` and introducing a sibling `COOKING_OVERVIEW` kind with its own cron-triggered, 1:1-template job endpoint and UI.

**Architecture:** Backend: rename the existing `kind` constant/value, add a new constant, and build a new `/api/v1/cooking-overview-jobs` REST resource that mirrors `/api/v1/cooking-reminder-jobs`'s "template + job in one request" pattern but with cron and configurable recipients (copied from `/api/v1/mail-jobs`'s validation) instead of `sendTime`. The existing `MailJobScheduler` picks up `COOKING_OVERVIEW` jobs through its normal cron path with zero special-casing. Frontend: a new `CookingOverviewJobsComponent` duplicates `MailJobEditorComponent`'s cron/recipient-picker UI, wires it to an embedded `MailTemplateFormComponent`, and a thin `CookingJobsComponent` wrapper hosts both existing and new components as two `mat-tab`s.

**Tech Stack:** Quarkus (Java 21, MongoDB via Panache), Angular (standalone components, Reactive Forms, Angular Material), Jasmine/Karma, JUnit 5 + RestAssured + `@QuarkusTest`.

## Global Constraints

- Every write to `/cooking-overview-jobs` must keep the job/template 1:1 and roll back the template insert if the job insert fails (spec: "Vorlagen-Insert erfolgreich, Job-Insert scheitert → Vorlage wird zurueckgerollt").
- `active=true` is only accepted when the referenced mail account exists and is `enabled` (400 otherwise) — same rule as the existing `/cooking-reminder-jobs` endpoint.
- `COOKING_OVERVIEW` templates get the same placeholder allowlist as `GENERAL` (person fields only, no `duty.*` tokens) — the Kochdienst-content itself comes from the separate, not-yet-merged Mail-Template-Bausteine feature.
- All renames of the `kind` value `COOKING` → `COOKING_REMINDER` must keep every existing test green — this is a pure rename, not a behavior change for `COOKING_REMINDER`.
- German UI strings/labels throughout (existing convention in this codebase).
- Existing specs for `CookingReminderJobsComponent`, `MailJobEditorComponent`, `MailTemplateEditorComponent`, `MailTemplateResource`, `MailJobResource`, `CookingReminderJobResource` must stay green — they are the regression guard that the rename and new endpoint didn't move anything.

---

## Task 1: Backend — rename `kind=COOKING` to `COOKING_REMINDER`, add `COOKING_OVERVIEW`

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/MailJob.java`
- Modify: `backend/src/main/java/at/kigruapp/entity/MailTemplate.java`
- Modify: `backend/src/main/java/at/kigruapp/scheduler/MailJobScheduler.java:57-61`
- Modify: `backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java:125`
- Modify: `backend/src/main/java/at/kigruapp/resource/CookingReminderJobResource.java:44,57,64,88,122`
- Modify: `backend/src/main/java/at/kigruapp/resource/CookingReminderSettingsResource.java:26`
- Modify: `backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java:69`
- Modify: `backend/src/main/java/at/kigruapp/migration/CookingReminderSettingsToJobMigration.java:30,47,70,80`
- Modify (mechanical symbol rename only, no behavior change): `backend/src/test/java/at/kigruapp/scheduler/MailJobSchedulerTest.java:69`, `backend/src/test/java/at/kigruapp/scheduler/CookingReminderSchedulerTest.java:33,58,66,114`, `backend/src/test/java/at/kigruapp/scheduler/CookingReminderRunTest.java:82,87,315`, `backend/src/test/java/at/kigruapp/scheduler/CookingReminderMultiJobRunTest.java:130,136`, `backend/src/test/java/at/kigruapp/migration/CookingReminderSettingsToJobMigrationTest.java:68,75,98,105,116`, `backend/src/test/java/at/kigruapp/resource/CookingReminderSettingsResourceTest.java:37,46`, `backend/src/test/java/at/kigruapp/resource/CookingReminderJobResourceTest.java:58`, `backend/src/test/java/at/kigruapp/resource/MailJobResourceTest.java:384`, `backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java:50,175,220,242`
- Test: `backend/src/test/java/at/kigruapp/scheduler/MailJobSchedulerTest.java` (new test added)
- Test: `backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java` (new test added)

**Interfaces:**
- Produces: `MailJob.KIND_COOKING_REMINDER`, `MailJob.KIND_COOKING_OVERVIEW`, `MailJob.isCookingReminder()`, `MailJob.isCookingOverview()`, `MailJob.isCooking()` (now true for either), and the equivalent four members on `MailTemplate`. Task 2, 3 and 4 rely on `KIND_COOKING_OVERVIEW`, `isCookingOverview()` and the narrowed `MailJobScheduler` skip guard.

This task is a mechanical rename (the string value `"COOKING"` becomes `"COOKING_REMINDER"`, the constant/method names follow) plus one new constant/pair of methods, plus one narrowed behavior in `MailJobScheduler`. Every file listed under "mechanical symbol rename" only needs the token `KIND_COOKING` replaced with `KIND_COOKING_REMINDER` — do this as a project-wide find-and-replace of the whole word `KIND_COOKING` (careful: NOT a prefix match that would also touch a future `KIND_COOKING_OVERVIEW`, but since that constant doesn't exist until this same task adds it, a plain whole-word replace of `KIND_COOKING` is safe) across `backend/src/main` and `backend/src/test`. `MailJobResource.java`'s four `job.isCooking()` guards need **no changes** — `isCooking()` keeps its name and becomes true for both new kinds, which is exactly the desired behavior (both `COOKING_REMINDER` and `COOKING_OVERVIEW` jobs stay locked out of the general `/mail-jobs` endpoint). Same for `MailTemplateResource.update()`/`delete()`'s `template.isCooking()` guards.

- [ ] **Step 1: Run the existing scheduler and template-resource test suites to confirm the baseline is green**

Run: `cd backend && ./mvnw test -Dtest=MailJobSchedulerTest,MailTemplateResourceTest,CookingReminderSchedulerTest,CookingReminderJobResourceTest,CookingReminderSettingsResourceTest,MailJobResourceTest,CookingReminderSettingsToJobMigrationTest,CookingReminderRunTest,CookingReminderMultiJobRunTest -q`
Expected: all PASS (this is the regression baseline the rest of this task must not break).

- [ ] **Step 2: Write the new failing test proving `COOKING_OVERVIEW` jobs are NOT skipped by the scheduler**

Add to `backend/src/test/java/at/kigruapp/scheduler/MailJobSchedulerTest.java`, after `scheduleSkipsCookingJobsInsteadOfCrashingOnNullCron`:

```java
    /**
     * Only COOKING_REMINDER jobs are driven by CookingReminderScheduler and
     * therefore skipped here. COOKING_OVERVIEW jobs have a real cron and must
     * schedule exactly like GENERAL jobs.
     */
    @Test
    void scheduleRegistersCookingOverviewJobsNormally() {
        MailJob job = newJob("0 0 8 * * ?");
        job.kind = MailJob.KIND_COOKING_OVERVIEW;

        mailJobScheduler.schedule(job);

        assertNotNull(scheduler.getScheduledJob(job.id.toHexString()));
    }
```

This will not compile yet (`KIND_COOKING_OVERVIEW` does not exist) — that failure to compile is the RED signal for this step.

- [ ] **Step 3: Write the new failing test proving `placeholders?kind=COOKING_OVERVIEW` behaves like `GENERAL`**

Add to `backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java`, after `placeholdersForCookingReturnDutyTokensAndOnlyNameFields`:

```java
    @Test
    void placeholdersForCookingOverviewStayGeneral() {
        persistDefinition("firstName", "Vorname");
        persistDefinition("email", "E-Mail");

        given()
                .when().get("/api/v1/mail-templates/placeholders?kind=COOKING_OVERVIEW")
                .then().statusCode(200)
                .body("token", hasItem("{{person.email}}"))
                .body("token", not(hasItem("{{duty.date}}")))
                .body("group", everyItem(is("PERSON")));
    }
```

This also will not compile until Step 5 renames the placeholders check to use the new constant (the endpoint currently checks `MailTemplate.KIND_COOKING.equals(kind)`, which after rename becomes `KIND_COOKING_REMINDER`, and `"COOKING_OVERVIEW"` no longer matches it — but until the rename lands, `KIND_COOKING` still exists and this test would technically compile and pass against old code by coincidence for the wrong reason; treat this step as RED by inspection and proceed directly to Step 4/5 together).

Also add, right after `cookingTemplatesCannotBeChangedOnGeneralEndpoint` in the same file, a variant proving the lockout extends to the new kind (the spec requires both `COOKING_*` kinds to be locked out of the general template endpoint):

```java
    @Test
    void cookingOverviewTemplatesCannotBeChangedOnGeneralEndpoint() {
        MailTemplate overview = persistTemplate("Uebersicht", "<p>x</p>");
        overview.kind = MailTemplate.KIND_COOKING_OVERVIEW;
        overview.update();

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"Neu\",\"bodyHtml\":\"<p>neu</p>\"}")
                .when().put("/api/v1/mail-templates/" + overview.id)
                .then().statusCode(409);

        given()
                .when().delete("/api/v1/mail-templates/" + overview.id)
                .then().statusCode(409);
    }
```

And add, in `backend/src/test/java/at/kigruapp/resource/MailJobResourceTest.java` right after the existing COOKING-lockout test (the one at line 384 renamed to `KIND_COOKING_REMINDER` in this same task), the equivalent for the new kind:

```java
    @Test
    void cookingOverviewJobsCannotBeChangedOnGeneralEndpoint() {
        MailJob overview = new MailJob();
        overview.kind = MailJob.KIND_COOKING_OVERVIEW;
        overview.name = "Uebersicht";
        overview.cron = "0 0 8 * * ?";
        overview.persist();

        given()
                .contentType(ContentType.JSON)
                .body(validPayload(templateId))
                .when().put("/api/v1/mail-jobs/" + overview.id)
                .then().statusCode(409);

        given()
                .when().delete("/api/v1/mail-jobs/" + overview.id)
                .then().statusCode(409);
    }
```

(this reuses the existing `validPayload(ObjectId templateId)` helper and `templateId` field already set up in that test class's `@BeforeEach`, per the file's existing conventions).

- [ ] **Step 4: Apply the entity rename and add the new kind**

Replace the constants and methods block in `backend/src/main/java/at/kigruapp/entity/MailJob.java`:

```java
    public static final String KIND_GENERAL = "GENERAL";
    public static final String KIND_COOKING_REMINDER = "COOKING_REMINDER";
    public static final String KIND_COOKING_OVERVIEW = "COOKING_OVERVIEW";
```

and replace the two methods:

```java
    /** True for either Kochdienst-Job-Art — beide werden ausserhalb des allgemeinen Job-Endpunkts gepflegt. */
    public boolean isCooking() {
        return isCookingReminder() || isCookingOverview();
    }

    public boolean isCookingReminder() {
        return KIND_COOKING_REMINDER.equals(kind);
    }

    public boolean isCookingOverview() {
        return KIND_COOKING_OVERVIEW.equals(kind);
    }
```

Apply the identical change to `backend/src/main/java/at/kigruapp/entity/MailTemplate.java` (same constants, same three methods, `template.` instead of `job.` in the javadoc).

- [ ] **Step 5: Rename every remaining `KIND_COOKING` symbol reference, narrow the scheduler guard, and narrow the placeholders check**

In `backend/src/main/java/at/kigruapp/scheduler/MailJobScheduler.java:58`, change:

```java
        if (job.isCooking()) {
```
to:
```java
        if (job.isCookingReminder()) {
```

(keep the comment on the next line unchanged — it is still accurate for reminder jobs).

In `backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java:69`, change:

```java
        boolean cooking = MailTemplate.KIND_COOKING.equals(kind);
```
to:
```java
        boolean cooking = MailTemplate.KIND_COOKING_REMINDER.equals(kind);
```

In `backend/src/main/java/at/kigruapp/resource/CookingReminderJobResource.java`, change line 44 and 122 from `job.isCooking()` to `job.isCookingReminder()`, and replace every `MailTemplate.KIND_COOKING`/`MailJob.KIND_COOKING` at lines 57, 64, 88 with `MailTemplate.KIND_COOKING_REMINDER`/`MailJob.KIND_COOKING_REMINDER`.

In `backend/src/main/java/at/kigruapp/resource/CookingReminderSettingsResource.java:26` and `backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java:125`, replace `MailJob.KIND_COOKING` with `MailJob.KIND_COOKING_REMINDER`.

In `backend/src/main/java/at/kigruapp/migration/CookingReminderSettingsToJobMigration.java`, replace all four occurrences (lines 30, 47, 70, 80) of `MailJob.KIND_COOKING`/`MailTemplate.KIND_COOKING` with the `_REMINDER` variants.

In every test file listed under "Files" above, replace `MailJob.KIND_COOKING`/`MailTemplate.KIND_COOKING` with the `_REMINDER` variants (mechanical, one-for-one). Additionally in `backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java`, change the two query-string literals at lines 175 and 220 from `?kind=COOKING` to `?kind=COOKING_REMINDER`, and the JSON body literal at line 242 from `\"kind\":\"COOKING\"` to `\"kind\":\"COOKING_REMINDER\"`.

- [ ] **Step 6: Run the full affected test suite to verify everything passes**

Run: `cd backend && ./mvnw test -Dtest=MailJobSchedulerTest,MailTemplateResourceTest,CookingReminderSchedulerTest,CookingReminderJobResourceTest,CookingReminderSettingsResourceTest,MailJobResourceTest,CookingReminderSettingsToJobMigrationTest,CookingReminderRunTest,CookingReminderMultiJobRunTest -q`
Expected: all PASS, including the four new tests from Steps 2 and 3 (scheduler skip-narrowing, placeholders parity, and the two general-endpoint lockout variants for `COOKING_OVERVIEW`).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/MailJob.java backend/src/main/java/at/kigruapp/entity/MailTemplate.java backend/src/main/java/at/kigruapp/scheduler/MailJobScheduler.java backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java backend/src/main/java/at/kigruapp/resource/CookingReminderJobResource.java backend/src/main/java/at/kigruapp/resource/CookingReminderSettingsResource.java backend/src/main/java/at/kigruapp/resource/MailTemplateResource.java backend/src/main/java/at/kigruapp/migration/CookingReminderSettingsToJobMigration.java backend/src/test/java/at/kigruapp/scheduler/MailJobSchedulerTest.java backend/src/test/java/at/kigruapp/scheduler/CookingReminderSchedulerTest.java backend/src/test/java/at/kigruapp/scheduler/CookingReminderRunTest.java backend/src/test/java/at/kigruapp/scheduler/CookingReminderMultiJobRunTest.java backend/src/test/java/at/kigruapp/migration/CookingReminderSettingsToJobMigrationTest.java backend/src/test/java/at/kigruapp/resource/CookingReminderSettingsResourceTest.java backend/src/test/java/at/kigruapp/resource/CookingReminderJobResourceTest.java backend/src/test/java/at/kigruapp/resource/MailJobResourceTest.java backend/src/test/java/at/kigruapp/resource/MailTemplateResourceTest.java
git commit -m "refactor(be): kind=COOKING zu COOKING_REMINDER umbenannt, COOKING_OVERVIEW ergaenzt"
```

---

## Task 2: Backend — startup migration renaming persisted `kind="COOKING"` documents

**Files:**
- Create: `backend/src/main/java/at/kigruapp/migration/CookingKindRenameMigration.java`
- Test: `backend/src/test/java/at/kigruapp/migration/CookingKindRenameMigrationTest.java`

**Interfaces:**
- Consumes: `MailJob.KIND_COOKING_REMINDER`, `MailTemplate.KIND_COOKING_REMINDER` (from Task 1).
- Produces: nothing consumed by later tasks — this is a standalone startup migration, following the same `@ApplicationScoped @Startup` + `migrations` idempotency-collection pattern as `GenderEnumMigration` (`backend/src/main/java/at/kigruapp/migration/GenderEnumMigration.java`).

Because this feature branch has not yet been deployed, no real environment has `mail_templates`/`mail_jobs` documents with the raw string `kind="COOKING"` on disk today — but the migration is still required for correctness (any environment that ran the previous `kochdienst-erinnerungs-jobs` feature before this rename lands needs it) and is cheap to add.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/migration/CookingKindRenameMigrationTest.java`:

```java
package at.kigruapp.migration;

import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CookingKindRenameMigrationTest {

    @Inject
    CookingKindRenameMigration migration;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        MailJob.deleteAll();
        MailTemplate.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("migrations")
                .deleteOne(new Document("_id", "cooking-kind-rename-v1"));
    }

    private MongoCollection<Document> mailJobs() {
        return mongoClient.getDatabase(databaseName).getCollection("mail_jobs");
    }

    private MongoCollection<Document> mailTemplates() {
        return mongoClient.getDatabase(databaseName).getCollection("mail_templates");
    }

    @Test
    void renamesPersistedCookingKindOnJobsAndTemplates() {
        mailJobs().insertOne(new Document("name", "Alt").append("kind", "COOKING"));
        mailTemplates().insertOne(new Document("name", "Alt").append("kind", "COOKING"));

        migration.run();

        assertEquals(0, mailJobs().countDocuments(new Document("kind", "COOKING")));
        assertEquals(1, mailJobs().countDocuments(new Document("kind", MailJob.KIND_COOKING_REMINDER)));
        assertEquals(0, mailTemplates().countDocuments(new Document("kind", "COOKING")));
        assertEquals(1, mailTemplates().countDocuments(new Document("kind", MailTemplate.KIND_COOKING_REMINDER)));
    }

    @Test
    void isIdempotent() {
        mailJobs().insertOne(new Document("name", "Alt").append("kind", "COOKING"));

        migration.run();
        migration.run();

        assertEquals(1, mailJobs().countDocuments(new Document("kind", MailJob.KIND_COOKING_REMINDER)));
    }

    @Test
    void leavesUnrelatedKindsUntouched() {
        mailJobs().insertOne(new Document("name", "General").append("kind", "GENERAL"));

        migration.run();

        assertEquals(1, mailJobs().countDocuments(new Document("kind", "GENERAL")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=CookingKindRenameMigrationTest -q`
Expected: FAIL to compile — `CookingKindRenameMigration` does not exist yet.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/java/at/kigruapp/migration/CookingKindRenameMigration.java`:

```java
package at.kigruapp.migration;

import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Date;

/**
 * Benennt bereits persistierte Dokumente mit kind="COOKING" auf
 * kind="COOKING_REMINDER" um (Umstellung auf zwei Kochdienst-Job-Arten).
 * Idempotent ueber die migrations-Collection, wie GenderEnumMigration.
 */
@ApplicationScoped
@Startup
public class CookingKindRenameMigration {

    private static final String MIGRATION_ID = "cooking-kind-rename-v1";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@Observes StartupEvent ev) {
        run();
    }

    public void run() {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> migrations = db.getCollection("migrations");
        if (migrations.find(new Document("_id", MIGRATION_ID)).first() != null) {
            return;
        }

        db.getCollection("mail_jobs").updateMany(
                new Document("kind", "COOKING"),
                new Document("$set", new Document("kind", MailJob.KIND_COOKING_REMINDER)));
        db.getCollection("mail_templates").updateMany(
                new Document("kind", "COOKING"),
                new Document("$set", new Document("kind", MailTemplate.KIND_COOKING_REMINDER)));

        migrations.insertOne(new Document("_id", MIGRATION_ID).append("executedAt", Date.from(Instant.now())));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=CookingKindRenameMigrationTest -q`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/migration/CookingKindRenameMigration.java backend/src/test/java/at/kigruapp/migration/CookingKindRenameMigrationTest.java
git commit -m "feat(be): Migration fuer bereits persistiertes kind=COOKING auf COOKING_REMINDER"
```

---

## Task 3: Backend — `/api/v1/cooking-overview-jobs` CRUD endpoint

**Files:**
- Create: `backend/src/main/java/at/kigruapp/resource/CookingOverviewJobResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/CookingOverviewJobResourceTest.java`

**Interfaces:**
- Consumes: `MailJob.KIND_COOKING_OVERVIEW`, `MailJob.isCookingOverview()`, `MailTemplate.KIND_COOKING_OVERVIEW` (Task 1); `MailTemplateResource.sanitizeBody(String)` (existing, package-visible static); `MailJobScheduler.schedule(MailJob)` / `.unschedule(ObjectId)` (existing).
- Produces: `GET/POST/PUT/DELETE /api/v1/cooking-overview-jobs[/{id}]`, consumed by Task 6's `CookingOverviewJobService`. Response/request shape: `CookingOverviewJobResource.JobDto(String id, String name, String senderAccountId, String subject, String cron, boolean allParents, List<RecipientSelection> recipientSelections, boolean active, String templateId, String templateName, String templateBodyHtml)` and `CookingOverviewJobResource.SaveRequest(String name, String senderAccountId, String subject, String cron, boolean allParents, List<RecipientSelection> recipientSelections, boolean active, String templateName, String templateBodyHtml)`.

This resource mirrors `CookingReminderJobResource` (template+job in one request, rollback on failed job insert) but validates `cron` instead of `sendTime`, and validates `recipientSelections` the same way `MailJobResource` does (duplicated — `CookingReminderJobResource` already duplicates its own validation instead of reusing `MailJobResource`'s, so this follows the codebase's existing precedent instead of introducing a shared validation service).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/at/kigruapp/resource/CookingOverviewJobResourceTest.java`:

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
class CookingOverviewJobResourceTest {

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
        return "{\"name\":\"Wochenuebersicht\",\"senderAccountId\":\"" + accountId
                + "\",\"subject\":\"Kochdienste diese Woche\",\"cron\":\"0 0 7 * * MON\",\"allParents\":true,"
                + "\"recipientSelections\":[],\"active\":" + active
                + ",\"templateName\":\"Uebersicht-Vorlage\""
                + ",\"templateBodyHtml\":\"<p>Diese Woche kochen ...</p>\"}";
    }

    @Test
    void createStoresJobAndTemplateTogether() {
        MailAccount account = persistAccount(true);

        String templateId = given()
                .contentType(ContentType.JSON)
                .body(saveBody(account.id.toHexString(), true))
                .when().post("/api/v1/cooking-overview-jobs")
                .then().statusCode(201)
                .body("name", is("Wochenuebersicht"))
                .body("cron", is("0 0 7 * * MON"))
                .body("active", is(true))
                .body("templateName", is("Uebersicht-Vorlage"))
                .body("templateId", notNullValue())
                .extract().path("templateId");

        MailTemplate template = MailTemplate.findById(new org.bson.types.ObjectId(templateId));
        org.junit.jupiter.api.Assertions.assertEquals(MailTemplate.KIND_COOKING_OVERVIEW, template.kind);

        given()
                .when().get("/api/v1/cooking-overview-jobs")
                .then().statusCode(200)
                .body("name", hasItem("Wochenuebersicht"))
                .body("templateBodyHtml", hasItem("<p>Diese Woche kochen ...</p>"));
    }

    @Test
    void updateChangesJobAndTemplate() {
        MailAccount account = persistAccount(true);
        String id = given()
                .contentType(ContentType.JSON)
                .body(saveBody(account.id.toHexString(), true))
                .when().post("/api/v1/cooking-overview-jobs")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"Neu\",\"senderAccountId\":\"" + account.id.toHexString()
                        + "\",\"subject\":\"Neuer Betreff\",\"cron\":\"0 0 8 * * MON\",\"allParents\":true,"
                        + "\"recipientSelections\":[],\"active\":false"
                        + ",\"templateName\":\"Neue Vorlage\",\"templateBodyHtml\":\"<p>Hallo</p>\"}")
                .when().put("/api/v1/cooking-overview-jobs/" + id)
                .then().statusCode(200)
                .body("name", is("Neu"))
                .body("cron", is("0 0 8 * * MON"))
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
                .when().post("/api/v1/cooking-overview-jobs")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .when().delete("/api/v1/cooking-overview-jobs/" + id)
                .then().statusCode(204);

        org.junit.jupiter.api.Assertions.assertEquals(0, MailJob.count());
        org.junit.jupiter.api.Assertions.assertEquals(0, MailTemplate.count());
    }

    @Test
    void invalidCronIsRejectedAndLeavesNoTemplateBehind() {
        MailAccount account = persistAccount(true);

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"X\",\"senderAccountId\":\"" + account.id.toHexString()
                        + "\",\"subject\":\"x\",\"cron\":\"not a cron\",\"allParents\":true,"
                        + "\"recipientSelections\":[],\"active\":false"
                        + ",\"templateName\":\"V\",\"templateBodyHtml\":\"<p>x</p>\"}")
                .when().post("/api/v1/cooking-overview-jobs")
                .then().statusCode(400);

        org.junit.jupiter.api.Assertions.assertEquals(0, MailTemplate.count());
    }

    @Test
    void activeJobNeedsAnEnabledAccount() {
        MailAccount account = persistAccount(false);

        given()
                .contentType(ContentType.JSON)
                .body(saveBody(account.id.toHexString(), true))
                .when().post("/api/v1/cooking-overview-jobs")
                .then().statusCode(400);
    }

    @Test
    void listOnlyReturnsCookingOverviewJobs() {
        MailAccount account = persistAccount(true);
        given()
                .contentType(ContentType.JSON)
                .body(saveBody(account.id.toHexString(), false))
                .when().post("/api/v1/cooking-overview-jobs")
                .then().statusCode(201);

        MailJob reminder = new MailJob();
        reminder.kind = MailJob.KIND_COOKING_REMINDER;
        reminder.name = "Erinnerung";
        reminder.persist();

        given()
                .when().get("/api/v1/cooking-overview-jobs")
                .then().statusCode(200)
                .body("name", hasItem("Wochenuebersicht"))
                .body("name", org.hamcrest.Matchers.not(hasItem("Erinnerung")));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=CookingOverviewJobResourceTest -q`
Expected: FAIL to compile — `CookingOverviewJobResource` does not exist yet, so `/api/v1/cooking-overview-jobs` returns 404 once the class exists but before that the module won't build the test target meaningfully; treat any non-2xx/non-400 mismatch as RED.

- [ ] **Step 3: Write the resource**

Create `backend/src/main/java/at/kigruapp/resource/CookingOverviewJobResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import at.kigruapp.entity.RecipientKind;
import at.kigruapp.entity.RecipientSelection;
import at.kigruapp.scheduler.MailJobScheduler;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.mongodb.client.MongoClient;
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
import java.util.Map;
import java.util.Set;

/**
 * Kochdienst-Uebersichtsjobs: Job und die fest zugeordnete Vorlage werden hier
 * gemeinsam gepflegt, analog zu CookingReminderJobResource, aber mit echtem
 * Cron und konfigurierbaren Empfaengern statt sendTime. Admin-only (nicht im
 * SecurityFilter freigeschaltet).
 */
@Path("/api/v1/cooking-overview-jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CookingOverviewJobResource {

    /** fieldNames a selection of the given kind may legitimately point at. Mirrors MailJobResource. */
    private static final Map<RecipientKind, Set<String>> ALLOWED_FIELD_NAMES = Map.of(
            RecipientKind.GROUP, Set.of("group"),
            RecipientKind.TEAM, Set.of("parent-team", "board"),
            RecipientKind.ROLE, Set.of("parent-team-role", "board-role"));

    private final CronParser cronParser =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    @Inject
    MailJobScheduler mailJobScheduler;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    public record JobDto(String id, String name, String senderAccountId, String subject,
                         String cron, boolean allParents, List<RecipientSelection> recipientSelections,
                         boolean active, String templateId, String templateName, String templateBodyHtml) {}

    public record SaveRequest(String name, String senderAccountId, String subject, String cron,
                              boolean allParents, List<RecipientSelection> recipientSelections,
                              boolean active, String templateName, String templateBodyHtml) {}

    @GET
    public List<JobDto> list() {
        List<JobDto> result = new ArrayList<>();
        for (MailJob job : MailJob.<MailJob>listAll(Sort.descending("updatedAt"))) {
            if (job.isCookingOverview()) {
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
        template.kind = MailTemplate.KIND_COOKING_OVERVIEW;
        template.createdAt = Instant.now();
        template.updatedAt = template.createdAt;
        template.persist();

        MailJob job = new MailJob();
        try {
            job.kind = MailJob.KIND_COOKING_OVERVIEW;
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
        if (job.active) {
            mailJobScheduler.schedule(job);
        }
        return Response.status(201).entity(toDto(job, template)).build();
    }

    @PUT
    @Path("/{id}")
    public JobDto update(@PathParam("id") String id, SaveRequest request) {
        MailJob job = findOverviewJob(id);
        validate(request);

        MailTemplate template = loadTemplate(job);
        if (template == null) {
            template = new MailTemplate();
            template.kind = MailTemplate.KIND_COOKING_OVERVIEW;
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
        if (job.active) {
            mailJobScheduler.schedule(job);
        } else {
            mailJobScheduler.unschedule(job.id);
        }
        return toDto(job, template);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        MailJob job = findOverviewJob(id);
        MailTemplate template = loadTemplate(job);
        if (job.active) {
            mailJobScheduler.unschedule(job.id);
        }
        job.delete();
        if (template != null) {
            template.delete();
        }
        return Response.noContent().build();
    }

    private MailJob findOverviewJob(String id) {
        if (!ObjectId.isValid(id)) {
            throw new NotFoundException();
        }
        MailJob job = MailJob.findById(new ObjectId(id));
        if (job == null || !job.isCookingOverview()) {
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
        job.cron = request.cron().trim();
        job.allParents = request.allParents();
        job.recipientSelections = request.recipientSelections() == null
                ? new ArrayList<>()
                : request.recipientSelections();
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
        requireText(request.cron(), "Zeitplan ist erforderlich");
        try {
            cronParser.parse(request.cron()).validate();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Zeitplan ist ungueltig: " + e.getMessage());
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
        validateRecipientSelections(request);
    }

    private void validateRecipientSelections(SaveRequest request) {
        if (request.allParents() || request.recipientSelections() == null) {
            return;
        }
        for (RecipientSelection sel : request.recipientSelections()) {
            if (sel == null || sel.kind == null || sel.fieldInstanceId == null) {
                throw new BadRequestException("recipientSelections enthaelt einen Eintrag ohne kind oder fieldInstanceId");
            }
            if (!matchesKind(sel)) {
                throw new BadRequestException("recipientSelections enthaelt eine unbekannte oder veraltete "
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

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(message);
        }
    }

    private JobDto toDto(MailJob job, MailTemplate template) {
        return new JobDto(job.id.toHexString(), job.name, job.senderAccountId, job.subject,
                job.cron, job.allParents, job.recipientSelections, job.active,
                template == null ? null : template.id.toHexString(),
                template == null ? null : template.name,
                template == null ? null : template.bodyHtml);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=CookingOverviewJobResourceTest -q`
Expected: PASS (all six tests).

- [ ] **Step 5: Run the broader regression suite (MailJobResource/MailTemplateResource/CookingReminderJobResource must be unaffected)**

Run: `cd backend && ./mvnw test -Dtest=MailJobResourceTest,MailTemplateResourceTest,CookingReminderJobResourceTest -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/CookingOverviewJobResource.java backend/src/test/java/at/kigruapp/resource/CookingOverviewJobResourceTest.java
git commit -m "feat(be): /api/v1/cooking-overview-jobs CRUD-Endpunkt"
```

---

## Task 4: Frontend — `MailTemplateKind` rename and chip differentiation

**Files:**
- Modify: `frontend/src/app/shared/models/mail-template.model.ts`
- Modify: `frontend/src/app/settings/organisation/cooking-reminder-jobs/cooking-reminder-jobs.component.html:70`
- Modify: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.ts:252-254`
- Modify: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.html:20-21`
- Modify: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts:500-509`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts:55-63,99-101`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.html:15-16`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts:87-101`
- Modify: `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts` (all `'COOKING'` literals)

**Interfaces:**
- Produces: `MailTemplateKind = 'GENERAL' | 'COOKING_REMINDER' | 'COOKING_OVERVIEW'`, consumed by Task 5 (`CookingOverviewJob` model), Task 7 (`CookingOverviewJobsComponent`'s `MailTemplateFormComponent` binding).

- [ ] **Step 1: Write/update the failing tests**

In `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts`, replace lines 500-509:

```ts
  it('erkennt Kochdienst-Jobs', () => {
    expect(component.isCooking({ ...JOB, kind: 'COOKING_REMINDER' })).toBeTrue();
    expect(component.isCooking({ ...JOB, kind: 'COOKING_OVERVIEW' })).toBeTrue();
    expect(component.isCooking({ ...JOB, kind: 'GENERAL' })).toBeFalse();
  });

  it('unterscheidet die Chip-Beschriftung nach Job-Art', () => {
    expect(component.kindChipLabel({ ...JOB, kind: 'COOKING_REMINDER' })).toBe('Kochdienst-Erinnerung');
    expect(component.kindChipLabel({ ...JOB, kind: 'COOKING_OVERVIEW' })).toBe('Kochdienst-Übersicht');
  });

  it('oeffnet einen Kochdienst-Job nicht zum Bearbeiten', () => {
    component.selectForEdit({ ...JOB, kind: 'COOKING_REMINDER' });

    expect(component.editing).toBeFalse();
```

(the remainder of that `it` block after line 509 is unchanged — only the `kind` literal on the line calling `selectForEdit` changes).

In `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts`, replace lines 87-101:

```ts
  it('does not open cooking templates for editing', () => {
    service.templates.push({
      id: 't-cooking', name: 'Kochdienst', bodyHtml: '<p>x</p>', kind: 'COOKING_REMINDER',
      createdAt: '2026-01-01', updatedAt: '2026-01-01',
    });
```

(keep the rest of that `it` block as-is), and replace lines 99-101:

```ts
  it('isCooking reflects the template kind', () => {
    expect(component.isCooking(service.templates[0])).toBe(false);
    expect(component.isCooking({ ...service.templates[0], kind: 'COOKING_REMINDER' })).toBe(true);
    expect(component.isCooking({ ...service.templates[0], kind: 'COOKING_OVERVIEW' })).toBe(true);
  });

  it('unterscheidet die Chip-Beschriftung nach Vorlagen-Art', () => {
    expect(component.kindChipLabel({ ...service.templates[0], kind: 'COOKING_REMINDER' })).toBe('Kochdienst-Erinnerung');
    expect(component.kindChipLabel({ ...service.templates[0], kind: 'COOKING_OVERVIEW' })).toBe('Kochdienst-Übersicht');
  });
```

In `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts`, replace every occurrence of `component.kind = 'COOKING';` and `toHaveBeenCalledWith('COOKING')` with `component.kind = 'COOKING_REMINDER';` and `toHaveBeenCalledWith('COOKING_REMINDER')` respectively (14 occurrences, purely mechanical — the component is agnostic to which non-`GENERAL` kind it receives).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-job-editor.component.spec.ts' --include='**/mail-template-editor.component.spec.ts' --include='**/mail-template-form.component.spec.ts'`
Expected: FAIL to compile — `'COOKING'`/`'COOKING_REMINDER'`/`'COOKING_OVERVIEW'` are not yet valid `MailTemplateKind` values, and `kindChipLabel` does not exist yet.

- [ ] **Step 3: Update the type and implementation**

In `frontend/src/app/shared/models/mail-template.model.ts`, change:

```ts
export type MailTemplateKind = 'GENERAL' | 'COOKING';
```
to:
```ts
export type MailTemplateKind = 'GENERAL' | 'COOKING_REMINDER' | 'COOKING_OVERVIEW';
```

In `frontend/src/app/settings/organisation/cooking-reminder-jobs/cooking-reminder-jobs.component.html:70`, change:

```html
    <app-mail-template-form [kind]="'COOKING'" nameLabel="Name der Vorlage"
```
to:
```html
    <app-mail-template-form [kind]="'COOKING_REMINDER'" nameLabel="Name der Vorlage"
```

In `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.ts`, replace lines 252-254:

```ts
  isCooking(job: MailJob): boolean {
    return job.kind === 'COOKING';
  }
```
with:
```ts
  isCooking(job: MailJob): boolean {
    return job.kind === 'COOKING_REMINDER' || job.kind === 'COOKING_OVERVIEW';
  }

  kindChipLabel(job: MailJob): string {
    return job.kind === 'COOKING_REMINDER' ? 'Kochdienst-Erinnerung' : 'Kochdienst-Übersicht';
  }

  kindChipTooltip(job: MailJob): string {
    return job.kind === 'COOKING_REMINDER'
      ? 'Wird in Organisation → Dienst-Einstellungen → Erinnerungen gepflegt'
      : 'Wird in Organisation → Dienst-Einstellungen → Übersichtsjobs gepflegt';
  }
```

In `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.html:20-21`, replace:

```html
            <span class="kind-chip" *ngIf="isCooking(j)"
                  matTooltip="Wird in Organisation → Dienst-Einstellungen gepflegt">Kochdienst</span>
```
with:
```html
            <span class="kind-chip" *ngIf="isCooking(j)"
                  [matTooltip]="kindChipTooltip(j)">{{ kindChipLabel(j) }}</span>
```

In `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts`, replace the `onSelectTemplate` body's condition (line 57):

```ts
    if (!template || template.kind === 'COOKING') {
```
with:
```ts
    if (!template || this.isCooking(template)) {
```

and replace lines 99-101:

```ts
  isCooking(template: MailTemplate): boolean {
    return template.kind === 'COOKING';
  }
```
with:
```ts
  isCooking(template: MailTemplate): boolean {
    return template.kind === 'COOKING_REMINDER' || template.kind === 'COOKING_OVERVIEW';
  }

  kindChipLabel(template: MailTemplate): string {
    return template.kind === 'COOKING_REMINDER' ? 'Kochdienst-Erinnerung' : 'Kochdienst-Übersicht';
  }

  kindChipTooltip(template: MailTemplate): string {
    return template.kind === 'COOKING_REMINDER'
      ? 'Wird in Organisation → Dienst-Einstellungen → Erinnerungen gepflegt'
      : 'Wird in Organisation → Dienst-Einstellungen → Übersichtsjobs gepflegt';
  }
```

In `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.html:15-16`, replace:

```html
        <span class="kind-chip" *ngIf="isCooking(t)"
              matTooltip="Wird in Organisation → Dienst-Einstellungen gepflegt">Kochdienst</span>
```
with:
```html
        <span class="kind-chip" *ngIf="isCooking(t)"
              [matTooltip]="kindChipTooltip(t)">{{ kindChipLabel(t) }}</span>
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx ng test --watch=false --include='**/mail-job-editor.component.spec.ts' --include='**/mail-template-editor.component.spec.ts' --include='**/mail-template-form.component.spec.ts' --include='**/cooking-reminder-jobs.component.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/models/mail-template.model.ts frontend/src/app/settings/organisation/cooking-reminder-jobs/cooking-reminder-jobs.component.html frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.ts frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.html frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.ts frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.html frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.spec.ts frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.spec.ts
git commit -m "refactor(fe): MailTemplateKind auf COOKING_REMINDER/COOKING_OVERVIEW umgestellt, Chips unterscheiden Job-Art"
```

---

## Task 5: Frontend — `CookingOverviewJob` model and service

**Files:**
- Create: `frontend/src/app/shared/models/cooking-overview-job.model.ts`
- Create: `frontend/src/app/shared/services/cooking-overview-job.service.ts`
- Test: `frontend/src/app/shared/services/cooking-overview-job.service.spec.ts`

**Interfaces:**
- Consumes: `RecipientSelection` from `frontend/src/app/shared/models/mail-job.model.ts`.
- Produces: `CookingOverviewJob`, `SaveCookingOverviewJobRequest` interfaces and `CookingOverviewJobService.list/create/update/delete`, consumed by Task 6 (`CookingOverviewJobsComponent`).

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/shared/services/cooking-overview-job.service.spec.ts`:

```ts
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { CookingOverviewJobService } from './cooking-overview-job.service';
import { ApiService } from '../../core/services/api.service';

describe('CookingOverviewJobService', () => {
  let service: CookingOverviewJobService;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj('ApiService', ['get', 'post', 'put', 'delete']);
    TestBed.configureTestingModule({
      providers: [CookingOverviewJobService, { provide: ApiService, useValue: api }],
    });
    service = TestBed.inject(CookingOverviewJobService);
  });

  it('laedt die Uebersichtsjobs', () => {
    const jobs = [{ id: '1', name: 'Wochenuebersicht', senderAccountId: 'a', subject: 's',
                    cron: '0 0 7 * * MON', allParents: true, recipientSelections: [], active: true,
                    templateId: 't', templateName: 'V', templateBodyHtml: '<p>x</p>' }];
    api.get.and.returnValue(of(jobs));

    service.list().subscribe((result: typeof jobs) => expect(result.length).toBe(1));

    expect(api.get).toHaveBeenCalledWith('/cooking-overview-jobs');
  });

  it('legt einen Job samt Vorlage an', () => {
    const request = {
      name: 'Wochenuebersicht', senderAccountId: 'a', subject: 's', cron: '0 0 7 * * MON',
      allParents: true, recipientSelections: [], active: false, templateName: 'V', templateBodyHtml: '<p>x</p>',
    };
    api.post.and.returnValue(of({}));

    service.create(request).subscribe();

    expect(api.post).toHaveBeenCalledWith('/cooking-overview-jobs', request);
  });

  it('aktualisiert einen Job', () => {
    const request = {
      name: 'Wochenuebersicht', senderAccountId: 'a', subject: 's', cron: '0 0 8 * * MON',
      allParents: false, recipientSelections: [{ kind: 'TEAM' as const, fieldInstanceId: 't1' }],
      active: true, templateName: 'V', templateBodyHtml: '<p>x</p>',
    };
    api.put.and.returnValue(of({}));

    service.update('1', request).subscribe();

    expect(api.put).toHaveBeenCalledWith('/cooking-overview-jobs/1', request);
  });

  it('loescht einen Job', () => {
    api.delete.and.returnValue(of(undefined));

    service.delete('1').subscribe();

    expect(api.delete).toHaveBeenCalledWith('/cooking-overview-jobs/1');
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/cooking-overview-job.service.spec.ts'`
Expected: FAIL to compile — `./cooking-overview-job.service` does not exist yet.

- [ ] **Step 3: Write the model and service**

Create `frontend/src/app/shared/models/cooking-overview-job.model.ts`:

```ts
import { RecipientSelection } from './mail-job.model';

export interface CookingOverviewJob {
  id: string;
  name: string;
  senderAccountId: string;
  subject: string;
  cron: string;
  allParents: boolean;
  recipientSelections: RecipientSelection[];
  active: boolean;
  templateId: string;
  templateName: string;
  templateBodyHtml: string;
}

export interface SaveCookingOverviewJobRequest {
  name: string;
  senderAccountId: string;
  subject: string;
  cron: string;
  allParents: boolean;
  recipientSelections: RecipientSelection[];
  active: boolean;
  templateName: string;
  templateBodyHtml: string;
}
```

Create `frontend/src/app/shared/services/cooking-overview-job.service.ts`:

```ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { CookingOverviewJob, SaveCookingOverviewJobRequest } from '../models/cooking-overview-job.model';

@Injectable({ providedIn: 'root' })
export class CookingOverviewJobService {
  constructor(private api: ApiService) {}

  list(): Observable<CookingOverviewJob[]> {
    return this.api.get<CookingOverviewJob[]>('/cooking-overview-jobs');
  }

  create(request: SaveCookingOverviewJobRequest): Observable<CookingOverviewJob> {
    return this.api.post<CookingOverviewJob>('/cooking-overview-jobs', request);
  }

  update(id: string, request: SaveCookingOverviewJobRequest): Observable<CookingOverviewJob> {
    return this.api.put<CookingOverviewJob>(`/cooking-overview-jobs/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/cooking-overview-jobs/${id}`);
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/cooking-overview-job.service.spec.ts'`
Expected: PASS (all four tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/models/cooking-overview-job.model.ts frontend/src/app/shared/services/cooking-overview-job.service.ts frontend/src/app/shared/services/cooking-overview-job.service.spec.ts
git commit -m "feat(fe): CookingOverviewJob-Modell und -Service"
```

---

## Task 6: Frontend — `CookingOverviewJobsComponent`

**Files:**
- Create: `frontend/src/app/settings/organisation/cooking-overview-jobs/cooking-overview-jobs.component.ts`
- Create: `frontend/src/app/settings/organisation/cooking-overview-jobs/cooking-overview-jobs.component.html`
- Create: `frontend/src/app/settings/organisation/cooking-overview-jobs/cooking-overview-jobs.component.scss`
- Test: `frontend/src/app/settings/organisation/cooking-overview-jobs/cooking-overview-jobs.component.spec.ts`

**Interfaces:**
- Consumes: `CookingOverviewJobService` (Task 5), `MailTemplateFormComponent` (existing, `frontend/src/app/settings/mail/mail-template-editor/mail-template-form.component.ts` — `@Input() kind`, `@Input() nameLabel`, `@Input() set value`, `@Output() valueChange`), `MailAccountService`/`MailAccount` (existing), `CronScheduleBuilderComponent` (existing, `frontend/src/app/settings/mail/mail-job-editor/cron-schedule-builder.component.ts`, selector `app-cron-schedule-builder`, binds a Quartz cron string via `ControlValueAccessor`), `OrganisationService`/`FieldInstanceService`/`FieldInstanceDTO` (existing, same pool-loading pattern as `MailJobEditorComponent`), `RecipientKind`/`RecipientSelection` (existing, `frontend/src/app/shared/models/mail-job.model.ts`), `NotificationService` (existing).
- Produces: `app-cooking-overview-jobs` selector, consumed by Task 8 (`CookingJobsComponent`).

The recipient-picker and pool-loading logic (fields `groups`, `parentTeams`, `boardTeams`, `teamRoles`, `boardRoles`, `parentTeamGroups`, `boardTeamGroups`, `recipientOptionValues`, methods `loadPool`, `onPoolLoaded`, `buildTeamGroups`, `roleTeamInstanceId`, `pruneStaleRecipientSelections`, `onRecipientSelectionChange`, `optionValue`, `toSelections`, `instanceLabel`) is copied verbatim from `MailJobEditorComponent` (`frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.ts:28-31,49-69,93-101,108-199`) — this mirrors how `CookingReminderJobsComponent` already duplicates rather than shares UI with its sibling editor, so `MailJobEditorComponent` stays untouched and its extensive existing test suite (which asserts directly on these same internals) carries zero regression risk.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/settings/organisation/cooking-overview-jobs/cooking-overview-jobs.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { CookingOverviewJobsComponent } from './cooking-overview-jobs.component';
import { CookingOverviewJobService } from '../../../shared/services/cooking-overview-job.service';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { OrganisationService } from '../../../shared/services/organisation.service';
import { FieldInstanceService } from '../../../shared/services/field-instance.service';
import { CookingOverviewJob } from '../../../shared/models/cooking-overview-job.model';

const JOB: CookingOverviewJob = {
  id: '1', name: 'Wochenuebersicht', senderAccountId: 'a', subject: 'Kochdienste diese Woche',
  cron: '0 0 7 * * MON', allParents: true, recipientSelections: [], active: true,
  templateId: 't', templateName: 'Vorlage', templateBodyHtml: '<p>Diese Woche kochen ...</p>',
};

describe('CookingOverviewJobsComponent', () => {
  let fixture: ComponentFixture<CookingOverviewJobsComponent>;
  let component: CookingOverviewJobsComponent;
  let jobService: jasmine.SpyObj<CookingOverviewJobService>;

  beforeEach(async () => {
    jobService = jasmine.createSpyObj('CookingOverviewJobService', ['list', 'create', 'update', 'delete']);
    jobService.list.and.returnValue(of([JOB]));
    jobService.create.and.returnValue(of(JOB));
    jobService.update.and.returnValue(of(JOB));
    jobService.delete.and.returnValue(of(void 0));

    const accountService = jasmine.createSpyObj('MailAccountService', ['list']);
    accountService.list.and.returnValue(of([{ id: 'a', name: 'Kindergarten', enabled: true }]));

    const organisationService = jasmine.createSpyObj('OrganisationService', ['getByTag']);
    organisationService.getByTag.and.returnValue(of({ id: 'o1', tag: 'groups', definitions: [], entries: [] }));

    const fieldInstanceService = jasmine.createSpyObj('FieldInstanceService', ['listByDefinitionId']);
    fieldInstanceService.listByDefinitionId.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [CookingOverviewJobsComponent, HttpClientTestingModule, NoopAnimationsModule],
      providers: [
        { provide: CookingOverviewJobService, useValue: jobService },
        { provide: MailAccountService, useValue: accountService },
        { provide: OrganisationService, useValue: organisationService },
        { provide: FieldInstanceService, useValue: fieldInstanceService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CookingOverviewJobsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('laedt die Jobs', () => {
    expect(component.jobs.length).toBe(1);
  });

  it('zeigt auch nicht aktivierte Mailkonten zur Auswahl an', () => {
    expect(component.accounts.length).toBe(1);
  });

  it('uebernimmt Job und Vorlage in die Maske', () => {
    component.selectForEdit(JOB);

    expect(component.form.value.subject).toBe('Kochdienste diese Woche');
    expect(component.form.value.cron).toBe('0 0 7 * * MON');
    expect(component.templateValue).toEqual({ name: 'Vorlage', bodyHtml: '<p>Diese Woche kochen ...</p>' });
  });

  it('sendet Job und Vorlage gemeinsam beim Anlegen', () => {
    component.newJob();
    component.form.patchValue({
      name: 'Neu', senderAccountId: 'a', subject: 'Betreff', cron: '0 0 8 * * MON', allParents: true, active: false,
    });
    component.onTemplateChange({ name: 'V', bodyHtml: '<p>x</p>' });

    component.save();

    expect(jobService.create).toHaveBeenCalledWith(jasmine.objectContaining({
      name: 'Neu', senderAccountId: 'a', subject: 'Betreff', cron: '0 0 8 * * MON',
      allParents: true, active: false, templateName: 'V', templateBodyHtml: '<p>x</p>',
    }));
  });

  it('schaltet einen Job aktiv/inaktiv', () => {
    component.toggleActive(JOB);

    expect(jobService.update).toHaveBeenCalledWith('1', jasmine.objectContaining({ active: false }));
  });

  it('loescht einen Job', () => {
    component.delete(JOB);

    expect(jobService.delete).toHaveBeenCalledWith('1');
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/cooking-overview-jobs.component.spec.ts'`
Expected: FAIL to compile — `./cooking-overview-jobs.component` does not exist yet.

- [ ] **Step 3: Write the component**

Create `frontend/src/app/settings/organisation/cooking-overview-jobs/cooking-overview-jobs.component.ts`:

```ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CookingOverviewJobService } from '../../../shared/services/cooking-overview-job.service';
import { CookingOverviewJob, SaveCookingOverviewJobRequest } from '../../../shared/models/cooking-overview-job.model';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { MailAccount } from '../../../shared/models/mail-account.model';
import { OrganisationService } from '../../../shared/services/organisation.service';
import { FieldInstanceService } from '../../../shared/services/field-instance.service';
import { FieldInstanceDTO } from '../../../shared/models/field-instance.model';
import { RecipientKind, RecipientSelection } from '../../../shared/models/mail-job.model';
import { NotificationService } from '../../../shared/services/notification.service';
import { MailTemplateFormComponent } from '../../mail/mail-template-editor/mail-template-form.component';
import { CronScheduleBuilderComponent } from '../../mail/mail-job-editor/cron-schedule-builder.component';

const DEFAULT_CRON = '0 0 8 * * ?';

export interface TeamWithRoles {
  team: FieldInstanceDTO;
  roles: FieldInstanceDTO[];
}

/**
 * Kochdienst-Uebersichtsjobs: links die Jobs, rechts Job-Formular (Cron +
 * Empfaenger, wie MailJobEditorComponent) und die fest zugeordnete Vorlage.
 * Beides geht in einem Request an den Server.
 */
@Component({
  selector: 'app-cooking-overview-jobs',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatIconModule, MatCheckboxModule, MatSlideToggleModule, MatTooltipModule,
    MailTemplateFormComponent, CronScheduleBuilderComponent,
  ],
  templateUrl: './cooking-overview-jobs.component.html',
  styleUrl: './cooking-overview-jobs.component.scss',
})
export class CookingOverviewJobsComponent implements OnInit {
  jobs: CookingOverviewJob[] = [];
  accounts: MailAccount[] = [];

  /** Selectable pools; each is the field instances of that pool's single template definition. */
  groups: FieldInstanceDTO[] = [];
  parentTeams: FieldInstanceDTO[] = [];
  boardTeams: FieldInstanceDTO[] = [];
  teamRoles: FieldInstanceDTO[] = [];
  boardRoles: FieldInstanceDTO[] = [];

  /** Jedes Team, zusammen mit den Rollen, die zu ihm gehoeren (siehe {@link buildTeamGroups}). */
  parentTeamGroups: TeamWithRoles[] = [];
  boardTeamGroups: TeamWithRoles[] = [];

  /**
   * Currently picked options, encoded as "<KIND>:<fieldInstanceId>". The kind
   * travels in the value because board and parent teams sit in separate
   * optgroups but resolve to the same kind.
   */
  recipientOptionValues: string[] = [];

  /** Counts pool loads so we know when it is safe to prune stale selections (see {@link pruneStaleRecipientSelections}). */
  private poolsLoadedCount = 0;
  private static readonly POOL_COUNT = 5;

  selectedId: string | null = null;
  editing = false;

  /** Wert fuer die Maske (Eingang) und der zuletzt gemeldete Wert (Ausgang). */
  templateValue = { name: '', bodyHtml: '' };
  private currentTemplate = { name: '', bodyHtml: '' };

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    senderAccountId: new FormControl('', Validators.required),
    subject: new FormControl('', Validators.required),
    cron: new FormControl(DEFAULT_CRON, Validators.required),
    allParents: new FormControl<boolean>(true, { nonNullable: true }),
    active: new FormControl<boolean>(false, { nonNullable: true }),
  });

  constructor(
    private jobService: CookingOverviewJobService,
    private mailAccountService: MailAccountService,
    private organisationService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
    this.mailAccountService.list().subscribe((accounts) => (this.accounts = accounts));
    this.loadPool('groups', 'group', (i) => (this.groups = i));
    this.loadPool('parent-teams', 'parent-team', (i) => (this.parentTeams = i));
    this.loadPool('board', 'board', (i) => (this.boardTeams = i));
    this.loadPool('parent-team-roles', 'parent-team-role', (i) => (this.teamRoles = i));
    this.loadPool('board-roles', 'board-role', (i) => (this.boardRoles = i));
  }

  load(): void {
    this.jobService.list().subscribe((jobs) => (this.jobs = jobs));
  }

  /**
   * Each pool is an organisation tag holding exactly one active template
   * definition; the pickable entries are that definition's field instances.
   */
  private loadPool(tag: string, fieldName: string, assign: (instances: FieldInstanceDTO[]) => void): void {
    this.organisationService.getByTag(tag).subscribe({
      next: (org) => {
        const templateDef = org?.definitions?.find((d) => d.fieldName === fieldName && !d.outdatedAt);
        if (!templateDef?.id) {
          this.onPoolLoaded();
          return;
        }
        this.fieldInstanceService.listByDefinitionId(templateDef.id).subscribe((instances) => {
          assign(instances);
          this.onPoolLoaded();
        });
      },
      error: () => {
        assign([]);
        this.onPoolLoaded();
      },
    });
  }

  /** Once every pool has loaded, drop any selection whose instance no longer exists in its pool. */
  private onPoolLoaded(): void {
    this.buildTeamGroups();
    this.poolsLoadedCount++;
    if (this.poolsLoadedCount === CookingOverviewJobsComponent.POOL_COUNT) {
      this.pruneStaleRecipientSelections();
    }
  }

  private buildTeamGroups(): void {
    this.parentTeamGroups = this.parentTeams.map((team) => ({
      team,
      roles: this.teamRoles.filter((r) => this.roleTeamInstanceId(r) === team.id),
    }));
    this.boardTeamGroups = this.boardTeams.map((team) => ({
      team,
      roles: this.boardRoles,
    }));
  }

  private roleTeamInstanceId(role: FieldInstanceDTO): string | undefined {
    return (role.value as { teamInstanceId?: string } | null)?.teamInstanceId;
  }

  /**
   * Removes recipientOptionValues entries whose "<KIND>:<id>" no longer resolves
   * to an instance in the corresponding loaded pool.
   */
  private pruneStaleRecipientSelections(): void {
    const validValues = new Set<string>([
      ...this.groups.map((i) => this.optionValue('GROUP', i.id ?? '')),
      ...this.parentTeams.map((i) => this.optionValue('TEAM', i.id ?? '')),
      ...this.boardTeams.map((i) => this.optionValue('TEAM', i.id ?? '')),
      ...this.teamRoles.map((i) => this.optionValue('ROLE', i.id ?? '')),
      ...this.boardRoles.map((i) => this.optionValue('ROLE', i.id ?? '')),
    ]);
    this.recipientOptionValues = this.recipientOptionValues.filter((v) => validValues.has(v));
  }

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

  /** Display name of a pickable entry (its value's label), with a safe fallback. */
  instanceLabel(i: FieldInstanceDTO): string {
    const label = (i.value as { label?: string } | null)?.label;
    return label || i.label?.['de'] || i.fieldName;
  }

  onTemplateChange(value: { name: string; bodyHtml: string }): void {
    this.currentTemplate = value;
  }

  get canSave(): boolean {
    return this.form.valid
      && this.currentTemplate.name.trim().length > 0
      && this.currentTemplate.bodyHtml.trim().length > 0;
  }

  selectForEdit(job: CookingOverviewJob): void {
    this.selectedId = job.id;
    this.editing = true;
    this.form.patchValue({
      name: job.name,
      senderAccountId: job.senderAccountId,
      subject: job.subject,
      cron: job.cron,
      allParents: job.allParents,
      active: job.active,
    });
    this.recipientOptionValues = (job.recipientSelections ?? [])
      .map((s) => this.optionValue(s.kind, s.fieldInstanceId));
    if (this.poolsLoadedCount === CookingOverviewJobsComponent.POOL_COUNT) {
      this.pruneStaleRecipientSelections();
    }
    this.templateValue = { name: job.templateName, bodyHtml: job.templateBodyHtml };
    this.currentTemplate = { ...this.templateValue };
  }

  newJob(): void {
    this.selectedId = null;
    this.editing = true;
    this.form.reset({
      name: '', senderAccountId: '', subject: '', cron: DEFAULT_CRON, allParents: true, active: false,
    });
    this.recipientOptionValues = [];
    this.templateValue = { name: '', bodyHtml: '' };
    this.currentTemplate = { name: '', bodyHtml: '' };
  }

  closeEditor(): void {
    this.selectedId = null;
    this.editing = false;
    this.form.reset({
      name: '', senderAccountId: '', subject: '', cron: DEFAULT_CRON, allParents: true, active: false,
    });
    this.recipientOptionValues = [];
    this.templateValue = { name: '', bodyHtml: '' };
    this.currentTemplate = { name: '', bodyHtml: '' };
  }

  private toRequest(): SaveCookingOverviewJobRequest {
    const v = this.form.value;
    return {
      name: v.name ?? '',
      senderAccountId: v.senderAccountId ?? '',
      subject: v.subject ?? '',
      cron: v.cron ?? DEFAULT_CRON,
      allParents: v.allParents ?? true,
      recipientSelections: v.allParents ? [] : this.toSelections(this.recipientOptionValues),
      active: v.active ?? false,
      templateName: this.currentTemplate.name,
      templateBodyHtml: this.currentTemplate.bodyHtml,
    };
  }

  save(): void {
    const request = this.toRequest();
    const isUpdate = this.selectedId !== null;
    const save$ = this.selectedId
      ? this.jobService.update(this.selectedId, request)
      : this.jobService.create(request);
    save$.subscribe({
      next: () => {
        this.notify.success(isUpdate ? 'Übersichtsjob aktualisiert' : 'Übersichtsjob gespeichert');
        this.closeEditor();
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  /** Der Schalter in der Liste speichert den Job unveraendert mit gekipptem active. */
  toggleActive(job: CookingOverviewJob): void {
    const activating = !job.active;
    this.jobService.update(job.id, {
      name: job.name,
      senderAccountId: job.senderAccountId,
      subject: job.subject,
      cron: job.cron,
      allParents: job.allParents,
      recipientSelections: job.recipientSelections,
      active: activating,
      templateName: job.templateName,
      templateBodyHtml: job.templateBodyHtml,
    }).subscribe({
      next: () => {
        this.notify.success(activating ? 'Übersichtsjob aktiviert' : 'Übersichtsjob deaktiviert');
        this.load();
      },
      error: (err) => {
        this.notify.error(this.notify.extractError(err));
        this.load();
      },
    });
  }

  delete(job: CookingOverviewJob): void {
    this.jobService.delete(job.id).subscribe({
      next: () => {
        this.notify.success('Übersichtsjob gelöscht');
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

Create `frontend/src/app/settings/organisation/cooking-overview-jobs/cooking-overview-jobs.component.html`:

```html
<div class="overview-jobs">
  <aside class="job-list">
    <div class="list-head">
      <h3>Übersichtsjobs</h3>
      <button mat-stroked-button color="primary" type="button" (click)="newJob()">
        <mat-icon>add</mat-icon> Neuer Übersichtsjob
      </button>
    </div>

    <nav class="jobs" *ngIf="jobs.length; else emptyList">
      <div class="job-item" *ngFor="let job of jobs" [class.selected]="job.id === selectedId">
        <button class="job-main" type="button" (click)="selectForEdit(job)">
          <span class="job-name">{{ job.name }}</span>
        </button>
        <mat-slide-toggle [checked]="job.active" (change)="toggleActive(job)"
                          matTooltip="Aktiv"></mat-slide-toggle>
        <button mat-icon-button type="button" (click)="delete(job)" matTooltip="Löschen">
          <mat-icon>delete_outline</mat-icon>
        </button>
      </div>
    </nav>

    <ng-template #emptyList>
      <p class="empty">Noch kein Übersichtsjob angelegt.</p>
    </ng-template>
  </aside>

  <div class="job-placeholder" *ngIf="!editing">
    <mat-icon>summarize</mat-icon>
    <p class="placeholder-title">Kein Übersichtsjob ausgewählt</p>
    <p class="placeholder-sub">Wähle links einen bestehenden Übersichtsjob oder lege über
      „Neuer Übersichtsjob“ einen neuen an.</p>
  </div>

  <form *ngIf="editing" [formGroup]="form" class="job-form" (ngSubmit)="save()">
    <header class="form-head">
      <h3>{{ selectedId ? 'Übersichtsjob bearbeiten' : 'Neuer Übersichtsjob' }}</h3>
    </header>

    <mat-form-field appearance="outline">
      <mat-label>Name</mat-label>
      <input matInput formControlName="name" />
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Mailkonto</mat-label>
      <mat-select formControlName="senderAccountId">
        <mat-option *ngFor="let account of accounts" [value]="account.id">{{ account.fromAddress }}</mat-option>
      </mat-select>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Betreff</mat-label>
      <input matInput formControlName="subject" />
    </mat-form-field>

    <section class="card">
      <p class="eyebrow">Zeitplan</p>
      <app-cron-schedule-builder formControlName="cron"></app-cron-schedule-builder>
    </section>

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
              <mat-option *ngFor="let g of groups" [value]="optionValue('GROUP', g.id!)">{{ instanceLabel(g) }}</mat-option>
            </mat-optgroup>
            <mat-optgroup label="Elternteams" *ngIf="parentTeams.length">
              <ng-container *ngFor="let group of parentTeamGroups">
                <mat-option class="recipient-team-option" [value]="optionValue('TEAM', group.team.id!)">{{ instanceLabel(group.team) }}</mat-option>
                <mat-option *ngFor="let r of group.roles" class="recipient-role-option" [value]="optionValue('ROLE', r.id!)">{{ instanceLabel(r) }}</mat-option>
              </ng-container>
            </mat-optgroup>
            <mat-optgroup label="Vorstand" *ngIf="boardTeams.length">
              <ng-container *ngFor="let group of boardTeamGroups">
                <mat-option class="recipient-team-option" [value]="optionValue('TEAM', group.team.id!)">{{ instanceLabel(group.team) }}</mat-option>
                <mat-option *ngFor="let r of group.roles" class="recipient-role-option" [value]="optionValue('ROLE', r.id!)">{{ instanceLabel(r) }}</mat-option>
              </ng-container>
            </mat-optgroup>
          </mat-select>
        </mat-form-field>
      </div>
    </section>

    <mat-slide-toggle formControlName="active">Aktiv</mat-slide-toggle>

    <app-mail-template-form [kind]="'COOKING_OVERVIEW'" nameLabel="Name der Vorlage"
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

Create `frontend/src/app/settings/organisation/cooking-overview-jobs/cooking-overview-jobs.component.scss`:

```scss
.overview-jobs {
  display: flex;
  gap: 1.5rem;
}

.job-list {
  flex: 0 0 260px;
}

.job-form {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.job-placeholder {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: rgba(0, 0, 0, 0.54);
  text-align: center;
}

.card {
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 4px;
  padding: 1rem;
}

.eyebrow {
  margin: 0 0 0.5rem;
  font-size: 0.75rem;
  text-transform: uppercase;
  color: rgba(0, 0, 0, 0.54);
}

.actions {
  display: flex;
  align-items: center;
}

.spacer {
  flex: 1;
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/cooking-overview-jobs.component.spec.ts'`
Expected: PASS (all seven tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/settings/organisation/cooking-overview-jobs/
git commit -m "feat(fe): CookingOverviewJobsComponent (Cron- und Empfaenger-Maske mit 1:1-Vorlage)"
```

---

## Task 7: Frontend — `CookingJobsComponent` tab wrapper

**Files:**
- Create: `frontend/src/app/settings/organisation/cooking-jobs/cooking-jobs.component.ts`
- Create: `frontend/src/app/settings/organisation/cooking-jobs/cooking-jobs.component.html`
- Test: `frontend/src/app/settings/organisation/cooking-jobs/cooking-jobs.component.spec.ts`

**Interfaces:**
- Consumes: `CookingReminderJobsComponent` (existing, selector `app-cooking-reminder-jobs`), `CookingOverviewJobsComponent` (Task 6, selector `app-cooking-overview-jobs`).
- Produces: `app-cooking-jobs` selector, consumed by Task 8 (`organisation.component.html`).

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/settings/organisation/cooking-jobs/cooking-jobs.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { CookingJobsComponent } from './cooking-jobs.component';

describe('CookingJobsComponent', () => {
  let fixture: ComponentFixture<CookingJobsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CookingJobsComponent, NoopAnimationsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA],
    }).compileComponents();

    fixture = TestBed.createComponent(CookingJobsComponent);
    fixture.detectChanges();
  });

  it('rendert zwei Reiter: Erinnerungen und Übersichtsjobs', () => {
    const labels = Array.from(fixture.nativeElement.querySelectorAll('.mdc-tab__text-label'))
      .map((el: Element) => el.textContent?.trim());

    expect(labels).toContain('Erinnerungen');
    expect(labels).toContain('Übersichtsjobs');
  });
});
```

This spec deliberately uses `CUSTOM_ELEMENTS_SCHEMA` and skips stubbing `CookingReminderJobsComponent`/`CookingOverviewJobsComponent`'s own dependencies — `CookingJobsComponent` is a dumb layout wrapper, and both child components already have their own full test coverage (existing `cooking-reminder-jobs.component.spec.ts` and Task 6's `cooking-overview-jobs.component.spec.ts`).

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --include='**/cooking-jobs.component.spec.ts'`
Expected: FAIL to compile — `./cooking-jobs.component` does not exist yet.

- [ ] **Step 3: Write the component**

Create `frontend/src/app/settings/organisation/cooking-jobs/cooking-jobs.component.ts`:

```ts
import { Component } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { CookingReminderJobsComponent } from '../cooking-reminder-jobs/cooking-reminder-jobs.component';
import { CookingOverviewJobsComponent } from '../cooking-overview-jobs/cooking-overview-jobs.component';

/** Duenner Wrapper: hostet die Kochdienst-Erinnerungen und -Uebersichtsjobs als zwei Reiter. */
@Component({
  selector: 'app-cooking-jobs',
  standalone: true,
  imports: [MatTabsModule, CookingReminderJobsComponent, CookingOverviewJobsComponent],
  templateUrl: './cooking-jobs.component.html',
})
export class CookingJobsComponent {}
```

Create `frontend/src/app/settings/organisation/cooking-jobs/cooking-jobs.component.html`:

```html
<mat-tab-group animationDuration="0ms">
  <mat-tab label="Erinnerungen">
    <app-cooking-reminder-jobs></app-cooking-reminder-jobs>
  </mat-tab>
  <mat-tab label="Übersichtsjobs">
    <app-cooking-overview-jobs></app-cooking-overview-jobs>
  </mat-tab>
</mat-tab-group>
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --include='**/cooking-jobs.component.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/settings/organisation/cooking-jobs/
git commit -m "feat(fe): CookingJobsComponent haengt Erinnerungen und Uebersichtsjobs als Reiter zusammen"
```

---

## Task 8: Frontend — wire `CookingJobsComponent` into Organisation

**Files:**
- Modify: `frontend/src/app/settings/organisation/organisation.component.html:100-101`
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts:39,60`

**Interfaces:**
- Consumes: `CookingJobsComponent` (Task 7, selector `app-cooking-jobs`).

`organisation.component.spec.ts` instantiates `OrganisationComponent` directly (`new OrganisationComponent(...)` with fake services, no `TestBed`/no template compilation — confirmed by inspection, the spec file has no `TestBed.configureTestingModule` call at all), so this change carries no risk to that spec and needs no spec update.

- [ ] **Step 1: Confirm the current template still renders `CookingReminderJobsComponent` (manual baseline check, no automated test exists for the template wiring)**

Run: `cd frontend && npx ng build --configuration production 2>&1 | tail -20`
Expected: build succeeds (this is the closest thing to a regression check for a template-only change with no dedicated spec — a broken selector reference fails the Angular template compiler at build time).

- [ ] **Step 2: Swap the component in the template**

In `frontend/src/app/settings/organisation/organisation.component.html`, replace lines 100-101:

```html
        <h3>Kochdienst — Erinnerungen</h3>
        <app-cooking-reminder-jobs></app-cooking-reminder-jobs>
```
with:
```html
        <h3>Kochdienst</h3>
        <app-cooking-jobs></app-cooking-jobs>
```

- [ ] **Step 3: Update the import and standalone `imports` array**

In `frontend/src/app/settings/organisation/organisation.component.ts:39`, replace:

```ts
import { CookingReminderJobsComponent } from './cooking-reminder-jobs/cooking-reminder-jobs.component';
```
with:
```ts
import { CookingJobsComponent } from './cooking-jobs/cooking-jobs.component';
```

At line 60 (inside the `@Component({ imports: [...] })` array), replace:

```ts
    CookingReminderJobsComponent,
```
with:
```ts
    CookingJobsComponent,
```

- [ ] **Step 4: Run the production build again to verify the new wiring compiles**

Run: `cd frontend && npx ng build --configuration production 2>&1 | tail -20`
Expected: build succeeds, no unresolved-selector errors.

- [ ] **Step 5: Run the full frontend test suite as a broad regression check**

Run: `cd frontend && npx ng test --watch=false`
Expected: all suites PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/organisation/organisation.component.html frontend/src/app/settings/organisation/organisation.component.ts
git commit -m "feat(fe): Organisation Dienst-Einstellungen nutzt CookingJobsComponent mit zwei Reitern"
```

---

## Task 9: Full regression run (backend + frontend)

**Files:**
- None (verification-only task).

**Interfaces:**
- Consumes: everything built in Tasks 1-8.

- [ ] **Step 1: Run the full backend test suite**

Run: `cd backend && ./mvnw test -q`
Expected: all PASS. If anything unrelated to this feature was already red before this plan started (check `docs/superpowers/plans/` / memory for a known pre-existing-failures baseline), confirm the failure count matches that known baseline exactly — do not treat pre-existing unrelated failures as caused by this plan, but do not silently accept any failure in a file this plan touched.

- [ ] **Step 2: Run the full frontend test suite**

Run: `cd frontend && npx ng test --watch=false`
Expected: all PASS, same caveat as Step 1.

- [ ] **Step 3: Run the frontend production build one more time**

Run: `cd frontend && npx ng build --configuration production 2>&1 | tail -30`
Expected: build succeeds within the configured bundle-size budget (Task 14 of the prior `kochdienst-erinnerungs-jobs` plan hit and fixed a budget overflow here once already — watch for the same class of failure if `cooking-overview-jobs.component.scss` grows).

- [ ] **Step 4: No commit for this task** — it is verification-only. If Steps 1-3 surface a regression, fix it as an amendment to the task that introduced it (re-open that task's commit with a follow-up fix commit) rather than committing anything here.
