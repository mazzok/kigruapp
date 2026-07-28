# Mail-Accounts (Multi-Account) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single SMTP `mail_settings` singleton with a real collection of mail accounts (full CRUD), rename the "SMTP" tab to "Mail-Accounts" with a list/detail layout, and remove the test-email feature.

**Architecture:** A new `MailAccount` Panache entity + `mail_accounts` collection backs an admin CRUD REST resource. A startup migration imports the old singleton config as one account and drops `mail_settings`. The send path and job-sender validation resolve the account by the job's `senderAccountId`. The frontend gains a `mail-account-editor` reusing the Jobs/Vorlagen list-detail shell; the old singleton form, test section, and their service/model are removed.

**Tech Stack:** Quarkus (RESTEasy Reactive, Panache MongoDB, `@Startup` migration), MongoDB raw driver for migration, JUnit 5 + RestAssured (backend); Angular standalone components, Angular Material, Karma/Jasmine (frontend).

## Global Constraints

- Admin-only endpoints: resources are NOT whitelisted in `SecurityFilter` (default-deny makes every method admin-only). Do not add whitelist entries.
- Passwords: never returned to the client; stored encrypted via `EncryptionService.encrypt(...)`; response DTOs expose only a `passwordSet` boolean.
- Reuse the existing `MailEncryption` enum (`NONE` / `STARTTLS` / `SSL_TLS`) and `EncryptionService` unchanged.
- Email validation regex (copied verbatim from `MailSettingsResource`): `^[^@\s]+@[^@\s]+\.[^@\s]+$`.
- Port range: 1–65535.
- `MailJob.senderAccountId` is a `String` holding an account id hex; account ids are ordinary `ObjectId`s (no singleton / no pinned id).
- Validation error messages are surfaced to the UI by the existing `WebApplicationExceptionMapper` — throw `BadRequestException` / `WebApplicationException(message, status)` with human-readable German-friendly text.
- German UI copy throughout the frontend.
- Commit after every task (frequent commits). Do not push.

---

## File Structure

**Backend — create**
- `backend/src/main/java/at/kigruapp/entity/MailAccount.java` — the account entity.
- `backend/src/main/java/at/kigruapp/dto/MailAccountDto.java` — read view (no password).
- `backend/src/main/java/at/kigruapp/dto/MailAccountUpdateDto.java` — write payload.
- `backend/src/main/java/at/kigruapp/migration/MailAccountsFromSettingsMigration.java` — one-time import.
- `backend/src/test/java/at/kigruapp/resource/MailAccountResourceTest.java`
- `backend/src/test/java/at/kigruapp/migration/MailAccountsFromSettingsMigrationTest.java`

**Backend — modify**
- `backend/src/main/java/at/kigruapp/resource/MailAccountResource.java` — replace the singleton-wrapper body with CRUD.
- `backend/src/main/java/at/kigruapp/service/MailService.java` — send via a `MailAccount`.
- `backend/src/main/java/at/kigruapp/scheduler/MailJobScheduler.java` — resolve + guard the account.
- `backend/src/main/java/at/kigruapp/resource/MailJobResource.java` — sender validation against accounts.
- `backend/src/test/java/at/kigruapp/scheduler/MailJobRunTest.java` — persist an account for the send path.
- `backend/src/test/java/at/kigruapp/resource/MailJobResourceTest.java` — sender validation tests.

**Backend — delete** (Task 5)
- `backend/src/main/java/at/kigruapp/resource/MailSettingsResource.java`
- `backend/src/main/java/at/kigruapp/entity/MailSettings.java`
- `backend/src/main/java/at/kigruapp/dto/MailSettingsDto.java`
- `backend/src/main/java/at/kigruapp/dto/MailSettingsUpdateDto.java`
- `backend/src/test/java/at/kigruapp/resource/MailSettingsResourceTest.java` (if present)

**Frontend — create**
- `frontend/src/app/settings/mail/mail-account-editor/mail-account-editor.component.ts`
- `frontend/src/app/settings/mail/mail-account-editor/mail-account-editor.component.html`
- `frontend/src/app/settings/mail/mail-account-editor/mail-account-editor.component.scss`
- `frontend/src/app/settings/mail/mail-account-editor/mail-account-editor.component.spec.ts`

**Frontend — modify**
- `frontend/src/app/shared/models/mail-account.model.ts` — extend model + add request type.
- `frontend/src/app/shared/services/mail-account.service.ts` — add CRUD.
- `frontend/src/app/settings/mail/mail.component.ts` / `.html` / `.spec.ts` — host the editor, rename tab, drop test/form.
- `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts` — fake account fixtures gain new fields.

**Frontend — delete** (Task 8)
- `frontend/src/app/shared/services/mail-settings.service.ts`
- `frontend/src/app/shared/models/mail-settings.model.ts`

---

## Task 1: MailAccount entity, DTOs, and CRUD resource

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/MailAccount.java`
- Create: `backend/src/main/java/at/kigruapp/dto/MailAccountDto.java`
- Create: `backend/src/main/java/at/kigruapp/dto/MailAccountUpdateDto.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/MailAccountResource.java` (replace entire body)
- Test: `backend/src/test/java/at/kigruapp/resource/MailAccountResourceTest.java`

**Interfaces:**
- Consumes: `MailEncryption` enum, `EncryptionService.encrypt(String)`, `MailJob` entity (`count`).
- Produces:
  - `at.kigruapp.entity.MailAccount` with public fields `String name, host, username, encryptedPassword, fromAddress, fromName; int port; MailEncryption encryption; boolean enabled` (extends `PanacheMongoEntity`, collection `mail_accounts`).
  - `GET /api/v1/mail-accounts` → `List<MailAccountDto>`; `GET /api/v1/mail-accounts/{id}` → `MailAccountDto`; `POST` (201) and `PUT /{id}` accept `MailAccountUpdateDto`; `DELETE /{id}` → 204 or 409.
  - `MailAccountDto` fields: `String id, name, host, username, fromAddress, fromName; int port; MailEncryption encryption; boolean enabled, passwordSet`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/resource/MailAccountResourceTest.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.MailAccount;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class MailAccountResourceTest {

    @BeforeEach
    void cleanup() {
        MailAccount.deleteAll();
    }

    private String payload(String name) {
        return "{\"name\":\"" + name + "\",\"host\":\"smtp.example.test\",\"port\":587,"
                + "\"encryption\":\"STARTTLS\",\"username\":\"\",\"fromAddress\":\"kita@example.test\","
                + "\"fromName\":\"Kita\",\"enabled\":true}";
    }

    @Test
    void createListGetAndDelete() {
        String id = given().contentType(ContentType.JSON).body(payload("Haupt"))
                .when().post("/api/v1/mail-accounts")
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("name", equalTo("Haupt"))
                .body("passwordSet", is(false))
                .extract().path("id");

        given().when().get("/api/v1/mail-accounts")
                .then().statusCode(200).body("$", hasSize(1));

        given().when().get("/api/v1/mail-accounts/" + id)
                .then().statusCode(200).body("fromAddress", equalTo("kita@example.test"));

        given().when().delete("/api/v1/mail-accounts/" + id)
                .then().statusCode(204);

        given().when().get("/api/v1/mail-accounts").then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void rejectsBlankNameBadPortAndBadEmail() {
        given().contentType(ContentType.JSON)
                .body(payload("Haupt").replace("\"name\":\"Haupt\"", "\"name\":\"\""))
                .when().post("/api/v1/mail-accounts").then().statusCode(400);

        given().contentType(ContentType.JSON)
                .body(payload("Haupt").replace("\"port\":587", "\"port\":0"))
                .when().post("/api/v1/mail-accounts").then().statusCode(400);

        given().contentType(ContentType.JSON)
                .body(payload("Haupt").replace("kita@example.test", "not-an-email"))
                .when().post("/api/v1/mail-accounts").then().statusCode(400);
    }

    @Test
    void rejectsUsernameWithoutPassword() {
        given().contentType(ContentType.JSON)
                .body(payload("Haupt").replace("\"username\":\"\"", "\"username\":\"user\""))
                .when().post("/api/v1/mail-accounts").then().statusCode(400);
    }

    @Test
    void passwordIsStoredEncryptedNeverReturnedAndFlaggedSet() {
        String withPw = payload("Haupt")
                .replace("\"username\":\"\"", "\"username\":\"user\",\"password\":\"secret\"");

        String id = given().contentType(ContentType.JSON).body(withPw)
                .when().post("/api/v1/mail-accounts")
                .then().statusCode(201)
                .body("passwordSet", is(true))
                .body("password", nullValue())
                .extract().path("id");

        MailAccount stored = MailAccount.findById(new org.bson.types.ObjectId(id));
        org.junit.jupiter.api.Assertions.assertNotNull(stored.encryptedPassword);
        org.junit.jupiter.api.Assertions.assertNotEquals("secret", stored.encryptedPassword);
    }

    @Test
    void updateChangesFieldsAndKeepsPasswordWhenOmitted() {
        String withPw = payload("Haupt")
                .replace("\"username\":\"\"", "\"username\":\"user\",\"password\":\"secret\"");
        String id = given().contentType(ContentType.JSON).body(withPw)
                .when().post("/api/v1/mail-accounts").then().statusCode(201).extract().path("id");

        // update without a password -> stays set
        given().contentType(ContentType.JSON)
                .body(payload("Umbenannt").replace("\"username\":\"\"", "\"username\":\"user\""))
                .when().put("/api/v1/mail-accounts/" + id)
                .then().statusCode(200)
                .body("name", equalTo("Umbenannt"))
                .body("passwordSet", is(true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd test -Dtest=MailAccountResourceTest` (PowerShell: `.\mvnw.cmd test "-Dtest=MailAccountResourceTest"`)
Expected: FAIL — compilation error (`MailAccount` missing) or 404/405 responses.

- [ ] **Step 3: Create the entity**

`backend/src/main/java/at/kigruapp/entity/MailAccount.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

/** One SMTP sender account. Ordinary id — there is no singleton. */
@MongoEntity(collection = "mail_accounts")
public class MailAccount extends PanacheMongoEntity {
    public String name;
    public String host;
    public int port;
    public MailEncryption encryption;
    public String username;
    public String encryptedPassword;
    public String fromAddress;
    public String fromName;
    public boolean enabled;
}
```

- [ ] **Step 4: Create the DTOs**

`backend/src/main/java/at/kigruapp/dto/MailAccountDto.java`:

```java
package at.kigruapp.dto;

import at.kigruapp.entity.MailEncryption;

/** Read view of a mail account. Omits the password; {@link #passwordSet} tells whether one is stored. */
public class MailAccountDto {
    public String id;
    public String name;
    public String host;
    public int port;
    public MailEncryption encryption;
    public String username;
    public String fromAddress;
    public String fromName;
    public boolean enabled;
    public boolean passwordSet;
}
```

`backend/src/main/java/at/kigruapp/dto/MailAccountUpdateDto.java`:

```java
package at.kigruapp.dto;

import at.kigruapp.entity.MailEncryption;

/** Write payload. {@code password} null/blank keeps the stored one; {@code clearPassword} removes it. */
public class MailAccountUpdateDto {
    public String name;
    public String host;
    public int port;
    public MailEncryption encryption;
    public String username;
    public String password;
    public Boolean clearPassword;
    public String fromAddress;
    public String fromName;
    public boolean enabled;
}
```

- [ ] **Step 5: Replace the resource with CRUD**

Overwrite `backend/src/main/java/at/kigruapp/resource/MailAccountResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.dto.MailAccountDto;
import at.kigruapp.dto.MailAccountUpdateDto;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.service.EncryptionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Admin-only mail account CRUD. Not whitelisted in SecurityFilter, so the
 * default-deny rule makes every method admin-only.
 */
@Path("/api/v1/mail-accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MailAccountResource {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Inject
    EncryptionService encryptionService;

    @GET
    public List<MailAccountDto> list() {
        return MailAccount.<MailAccount>listAll().stream().map(MailAccountResource::toDto).toList();
    }

    @GET
    @Path("/{id}")
    public MailAccountDto get(@PathParam("id") String id) {
        MailAccount account = MailAccount.findById(new ObjectId(id));
        if (account == null) {
            throw new NotFoundException();
        }
        return toDto(account);
    }

    @POST
    public Response create(MailAccountUpdateDto in) {
        MailAccount account = new MailAccount();
        validate(in, account);
        apply(account, in);
        account.persist();
        return Response.status(201).entity(toDto(account)).build();
    }

    @PUT
    @Path("/{id}")
    public MailAccountDto update(@PathParam("id") String id, MailAccountUpdateDto in) {
        MailAccount account = MailAccount.findById(new ObjectId(id));
        if (account == null) {
            throw new NotFoundException();
        }
        validate(in, account);
        apply(account, in);
        account.update();
        return toDto(account);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        MailAccount account = MailAccount.findById(new ObjectId(id));
        if (account == null) {
            throw new NotFoundException();
        }
        if (MailJob.count("senderAccountId", id) > 0) {
            throw new WebApplicationException(
                    "Konto wird von einem Job verwendet und kann nicht gelöscht werden", 409);
        }
        account.delete();
        return Response.noContent().build();
    }

    /** {@code existing} carries an already-stored password so username-without-password is allowed on update. */
    private void validate(MailAccountUpdateDto in, MailAccount existing) {
        if (in.name == null || in.name.isBlank()) {
            throw new BadRequestException("name must not be empty");
        }
        if (in.host == null || in.host.isBlank()) {
            throw new BadRequestException("host must not be empty");
        }
        if (in.port < 1 || in.port > 65535) {
            throw new BadRequestException("port must be between 1 and 65535");
        }
        if (in.encryption == null) {
            throw new BadRequestException("encryption must be set");
        }
        if (in.fromAddress == null || !EMAIL.matcher(in.fromAddress).matches()) {
            throw new BadRequestException("fromAddress must be a valid email address");
        }
        boolean providesPassword = in.password != null && !in.password.isBlank();
        boolean hasStoredPassword = existing.encryptedPassword != null && !existing.encryptedPassword.isBlank()
                && !Boolean.TRUE.equals(in.clearPassword);
        if (in.username != null && !in.username.isBlank() && !providesPassword && !hasStoredPassword) {
            throw new BadRequestException("password is required when a username is set");
        }
    }

    private void apply(MailAccount account, MailAccountUpdateDto in) {
        account.name = in.name;
        account.host = in.host;
        account.port = in.port;
        account.encryption = in.encryption;
        account.username = in.username;
        account.fromAddress = in.fromAddress;
        account.fromName = in.fromName;
        account.enabled = in.enabled;
        if (in.password != null && !in.password.isBlank()) {
            account.encryptedPassword = encryptionService.encrypt(in.password);
        } else if (Boolean.TRUE.equals(in.clearPassword)) {
            account.encryptedPassword = null;
        }
    }

    static MailAccountDto toDto(MailAccount a) {
        MailAccountDto dto = new MailAccountDto();
        dto.id = a.id.toHexString();
        dto.name = a.name;
        dto.host = a.host;
        dto.port = a.port;
        dto.encryption = a.encryption;
        dto.username = a.username;
        dto.fromAddress = a.fromAddress;
        dto.fromName = a.fromName;
        dto.enabled = a.enabled;
        dto.passwordSet = a.encryptedPassword != null && !a.encryptedPassword.isBlank();
        return dto;
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=MailAccountResourceTest"`
Expected: PASS (all 5 tests).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/MailAccount.java \
        backend/src/main/java/at/kigruapp/dto/MailAccountDto.java \
        backend/src/main/java/at/kigruapp/dto/MailAccountUpdateDto.java \
        backend/src/main/java/at/kigruapp/resource/MailAccountResource.java \
        backend/src/test/java/at/kigruapp/resource/MailAccountResourceTest.java
git commit -m "feat: mail-account CRUD resource (entity, DTOs, validation, delete guard)"
```

---

## Task 2: Startup migration — import singleton into mail_accounts

**Files:**
- Create: `backend/src/main/java/at/kigruapp/migration/MailAccountsFromSettingsMigration.java`
- Test: `backend/src/test/java/at/kigruapp/migration/MailAccountsFromSettingsMigrationTest.java`

**Interfaces:**
- Consumes: `MongoClient`, config `quarkus.mongodb.database`. Reads collection `mail_settings`, writes `mail_accounts`, records in `migrations`.
- Produces: bean `MailAccountsFromSettingsMigration` with package-private `void run()` callable from tests (also invoked on `StartupEvent`).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/migration/MailAccountsFromSettingsMigrationTest.java`:

```java
package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class MailAccountsFromSettingsMigrationTest {

    private static final String MIGRATION_ID = "mail-accounts-from-settings-v1";

    @Inject
    MailAccountsFromSettingsMigration migration;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoDatabase db() {
        return mongoClient.getDatabase(databaseName);
    }

    @BeforeEach
    void reset() {
        db().getCollection("mail_accounts").deleteMany(new Document());
        db().getCollection("mail_settings").deleteMany(new Document());
        db().getCollection("migrations").deleteMany(new Document("_id", MIGRATION_ID));
    }

    @Test
    void importsSingletonAsAccountAndDropsSettings() {
        db().getCollection("mail_settings").insertOne(new Document()
                .append("host", "smtp.example.test").append("port", 587)
                .append("encryption", "STARTTLS").append("username", "user")
                .append("encryptedPassword", "enc").append("fromAddress", "kita@example.test")
                .append("fromName", "Kita").append("enabled", true));

        migration.run();

        Document account = db().getCollection("mail_accounts").find().first();
        assertEquals("kita@example.test", account.getString("name"));
        assertEquals("smtp.example.test", account.getString("host"));
        assertEquals("enc", account.getString("encryptedPassword"));
        assertEquals(0, db().getCollection("mail_settings").countDocuments());
    }

    @Test
    void isIdempotentAndSkipsWhenAccountsExist() {
        db().getCollection("mail_settings").insertOne(new Document()
                .append("host", "h").append("port", 25).append("encryption", "NONE")
                .append("fromAddress", "a@b.test").append("enabled", false));

        migration.run();
        migration.run(); // second run must not duplicate

        assertEquals(1, db().getCollection("mail_accounts").countDocuments());
    }

    @Test
    void noSettingsProducesNoAccount() {
        migration.run();
        assertNull(db().getCollection("mail_accounts").find().first());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=MailAccountsFromSettingsMigrationTest"`
Expected: FAIL — `MailAccountsFromSettingsMigration` does not exist (compilation error).

- [ ] **Step 3: Create the migration**

`backend/src/main/java/at/kigruapp/migration/MailAccountsFromSettingsMigration.java`:

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

import java.time.Instant;
import java.util.Date;

/**
 * One-time: import the legacy {@code mail_settings} singleton as an ordinary
 * {@code mail_accounts} document, then drop {@code mail_settings}. Idempotent.
 */
@ApplicationScoped
@Startup
public class MailAccountsFromSettingsMigration {

    private static final String MIGRATION_ID = "mail-accounts-from-settings-v1";

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

        MongoCollection<Document> accounts = db.getCollection("mail_accounts");
        MongoCollection<Document> settings = db.getCollection("mail_settings");

        Document s = settings.find().first();
        if (s != null && accounts.countDocuments() == 0) {
            String from = s.getString("fromAddress");
            String name = (from != null && !from.isBlank()) ? from : "Standard";
            accounts.insertOne(new Document("_id", new ObjectId())
                    .append("name", name)
                    .append("host", s.getString("host"))
                    .append("port", s.get("port"))
                    .append("encryption", s.get("encryption"))
                    .append("username", s.getString("username"))
                    .append("encryptedPassword", s.getString("encryptedPassword"))
                    .append("fromAddress", from)
                    .append("fromName", s.getString("fromName"))
                    .append("enabled", s.get("enabled")));
        }

        settings.drop();
        migrations.insertOne(new Document("_id", MIGRATION_ID).append("executedAt", Date.from(Instant.now())));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\mvnw.cmd test "-Dtest=MailAccountsFromSettingsMigrationTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/migration/MailAccountsFromSettingsMigration.java \
        backend/src/test/java/at/kigruapp/migration/MailAccountsFromSettingsMigrationTest.java
git commit -m "feat: migrate legacy mail_settings singleton into mail_accounts"
```

---

## Task 3: Send path — send via the job's MailAccount

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/MailService.java`
- Modify: `backend/src/main/java/at/kigruapp/scheduler/MailJobScheduler.java:118-152`
- Modify: `backend/src/main/java/at/kigruapp/resource/MailSettingsResource.java` (remove the test endpoint + its MailService use)
- Modify: `backend/src/test/java/at/kigruapp/scheduler/MailJobRunTest.java`

**Interfaces:**
- Consumes: `MailAccount` (Task 1).
- Produces: `MailService.sendHtml(MailAccount account, String recipient, String subject, String htmlBody)`. The old `send(...)` and the singleton-based `prepareMessage/buildProperties/isIncomplete` are removed; `MailSettings` is no longer referenced by `MailService`.

- [ ] **Step 1: Update the send-path test first**

In `backend/src/test/java/at/kigruapp/scheduler/MailJobRunTest.java`, persist a `MailAccount` in setup and point the job at it. Add near the other `@Inject`/setup a helper and use it in `runJobSendsToAllRecipientsAndRecordsSuccess`:

```java
// add import: import at.kigruapp.entity.MailAccount;

private MailAccount persistEnabledAccount() {
    MailAccount a = new MailAccount();
    a.name = "Test";
    a.host = "localhost";
    a.port = greenMail.getSmtp().getPort();
    a.encryption = at.kigruapp.entity.MailEncryption.NONE;
    a.fromAddress = "kita@example.test";
    a.fromName = "Kita";
    a.enabled = true;
    a.persist();
    return a;
}
```

Then in `runJobSendsToAllRecipientsAndRecordsSuccess`, after building the job, set its sender:

```java
MailAccount account = persistEnabledAccount();
job.senderAccountId = account.id.toHexString();
```

(Place this before `job.persist();`.) Add a `MailAccount.deleteAll();` line to the existing `@BeforeEach` cleanup.

Also add a guard test:

```java
@Test
void runJobFailsWhenSenderAccountMissing() {
    MailTemplate template = persistTemplate();
    MailJob job = new MailJob();
    job.templateId = template.id;
    job.subject = "Willkommen";
    job.recipientMode = RecipientMode.ALL_PARENTS;
    job.senderAccountId = new ObjectId().toHexString(); // no such account
    job.persist();

    mailJobScheduler.runJob(job, template);

    assertEquals("FAILED", job.lastRunStatus);
    assertEquals(0, greenMail.getReceivedMessages().length);
}
```

> Note: if the existing GreenMail helper exposes the SMTP port differently, use that accessor. The point is an enabled `NONE`-encryption account pointing at the in-test SMTP server.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test "-Dtest=MailJobRunTest"`
Expected: FAIL — `MailJob` has no send account wired through; `sendHtml(MailAccount, ...)` does not exist (compile error).

- [ ] **Step 3: Rewrite MailService to use MailAccount**

In `backend/src/main/java/at/kigruapp/service/MailService.java`:
1. Replace `import at.kigruapp.entity.MailSettings;` with `import at.kigruapp.entity.MailAccount;`.
2. Delete the `send(...)` method entirely (its only caller, the test endpoint, is removed in Step 5).
3. Change `sendHtml` and the private helpers to take a `MailAccount`:

```java
    /** Send an HTML mail using the given account's SMTP config. */
    public void sendHtml(MailAccount account, String recipient, String subject, String htmlBody) {
        try {
            MimeMessage msg = prepareMessage(account, recipient, subject);
            msg.setContent(htmlBody, "text/html; charset=UTF-8");
            Transport.send(msg);
        } catch (AuthenticationFailedException e) {
            throw new MailException(MailException.Category.AUTH_FAILED,
                    "Authentifizierung am Mailserver fehlgeschlagen", e);
        } catch (MessagingException e) {
            throw new MailException(MailException.Category.CONNECTION_FAILED,
                    "Verbindung zum Mailserver fehlgeschlagen", e);
        } catch (MailException e) {
            throw e;
        } catch (Exception e) {
            throw new MailException(MailException.Category.UNKNOWN,
                    "Unbekannter Fehler beim Mailversand", e);
        }
    }

    private MimeMessage prepareMessage(MailAccount account, String recipient, String subject)
            throws MessagingException, java.io.UnsupportedEncodingException {
        if (!encryptionService.isConfigured()) {
            throw new MailException(MailException.Category.CONFIG_MISSING,
                    "Verschlüsselung ist nicht konfiguriert");
        }
        if (account == null || !account.enabled) {
            throw new MailException(MailException.Category.CONFIG_MISSING,
                    "Mailversand ist deaktiviert");
        }
        if (isIncomplete(account)) {
            throw new MailException(MailException.Category.CONFIG_MISSING,
                    "Mail-Einstellungen sind unvollständig");
        }
        Properties props = buildProperties(account);
        String password = (account.encryptedPassword != null && !account.encryptedPassword.isBlank())
                ? encryptionService.decrypt(account.encryptedPassword)
                : null;

        Session session;
        if (account.username != null && !account.username.isBlank() && password != null) {
            final String user = account.username;
            final String pw = password;
            session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, pw);
                }
            });
        } else {
            session = Session.getInstance(props);
        }

        MimeMessage msg = new MimeMessage(session);
        if (account.fromName != null && !account.fromName.isBlank()) {
            msg.setFrom(new InternetAddress(account.fromAddress, account.fromName));
        } else {
            msg.setFrom(new InternetAddress(account.fromAddress));
        }
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
        msg.setSubject(subject);
        return msg;
    }

    private boolean isIncomplete(MailAccount a) {
        if (a.host == null || a.host.isBlank()) {
            return true;
        }
        if (a.port < 1 || a.port > 65535) {
            return true;
        }
        if (a.encryption == null) {
            return true;
        }
        if (a.fromAddress == null || a.fromAddress.isBlank()) {
            return true;
        }
        return a.username != null && !a.username.isBlank()
                && (a.encryptedPassword == null || a.encryptedPassword.isBlank());
    }

    /** Build jakarta.mail SMTP properties from the account (timeouts + transport hardening). */
    Properties buildProperties(MailAccount a) {
        Properties p = new Properties();
        p.put("mail.smtp.host", a.host);
        p.put("mail.smtp.port", String.valueOf(a.port));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.ssl.checkserveridentity", "true");
        if (a.username != null && !a.username.isBlank()) {
            p.put("mail.smtp.auth", "true");
        }
        switch (a.encryption) {
            case STARTTLS -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
            }
            case SSL_TLS -> p.put("mail.smtp.ssl.enable", "true");
            case NONE -> { /* no transport security */ }
        }
        return p;
    }
```

Keep the class-level Javadoc but replace "stored `MailSettings`" wording with "the account's SMTP config".

- [ ] **Step 4: Resolve + guard the account in the scheduler**

In `backend/src/main/java/at/kigruapp/scheduler/MailJobScheduler.java`, add `import at.kigruapp.entity.MailAccount;`. Inside `runJob`, at the very top of the `try` block (before `resolveCurrentSemesterId()`), insert the guard, and pass the account to `sendHtml`:

```java
        try {
            MailAccount account = null;
            try {
                account = MailAccount.findById(new ObjectId(job.senderAccountId));
            } catch (IllegalArgumentException ignored) {
                // malformed id -> treated as missing below
            }
            if (account == null || !account.enabled) {
                Log.warnf("MailJob '%s' (%s): sender account missing or disabled", job.name, job.id.toHexString());
                job.lastRunAt = Instant.now();
                job.lastRunStatus = "FAILED";
                job.lastRunError = "sender account missing or disabled";
                job.update();
                return;
            }

            ObjectId semesterId = resolveCurrentSemesterId();
            // ... existing recipient resolution unchanged ...
```

Change the send call inside the loop:

```java
                    mailService.sendHtml(account, recipient.email(), job.subject, renderedHtml);
```

(`ObjectId` is already imported in this file.)

- [ ] **Step 5: Remove the test endpoint from MailSettingsResource**

In `backend/src/main/java/at/kigruapp/resource/MailSettingsResource.java`, delete: the `@POST @Path("/test") sendTest(...)` method, the `TestRequest` and `TestResult` classes, the `TEST_SUBJECT`/`TEST_BODY` constants, the `@Inject MailService mailService;` field, and the now-unused `MailService`/`MailException` imports. Leave `GET`/`PUT` intact for now (removed in Task 5).

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\mvnw.cmd test "-Dtest=MailJobRunTest"`
Expected: PASS — success path sends via the account; `runJobFailsWhenSenderAccountMissing` records FAILED.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/MailService.java \
        backend/src/main/java/at/kigruapp/scheduler/MailJobScheduler.java \
        backend/src/main/java/at/kigruapp/resource/MailSettingsResource.java \
        backend/src/test/java/at/kigruapp/scheduler/MailJobRunTest.java
git commit -m "feat: send mail via the job's account; drop the test-mail endpoint"
```

---

## Task 4: Job sender validation against accounts

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/resource/MailJobResource.java:178-184` (`validateSenderAccountId`)
- Modify: `backend/src/test/java/at/kigruapp/resource/MailJobResourceTest.java`

**Interfaces:**
- Consumes: `MailAccount` (Task 1).
- Produces: `senderAccountId` must reference an existing, enabled `MailAccount` or the create/update is rejected 400.

- [ ] **Step 1: Update the tests first**

In `backend/src/test/java/at/kigruapp/resource/MailJobResourceTest.java`:
1. Add `import at.kigruapp.entity.MailAccount;`.
2. In `@BeforeEach cleanup`, add `MailAccount.deleteAll();` and persist one enabled account, storing its id:

```java
    private String enabledAccountId;

    // inside cleanup(), after MailSettings setup:
    MailAccount.deleteAll();
    MailAccount acc = new MailAccount();
    acc.name = "Haupt";
    acc.host = "smtp.example.test";
    acc.port = 587;
    acc.encryption = at.kigruapp.entity.MailEncryption.STARTTLS;
    acc.fromAddress = "kita@example.test";
    acc.enabled = true;
    acc.persist();
    enabledAccountId = acc.id.toHexString();
```

3. Change `validPayload` to use `enabledAccountId` as the sender:

```java
    private String validPayload(ObjectId templateId) {
        return "{\"name\":\"Willkommen-Job\",\"templateId\":\"" + templateId
                + "\",\"subject\":\"Willkommen\",\"cron\":\"0 0 8 * * ?\",\"recipientMode\":\"ALL_PARENTS\","
                + "\"senderAccountId\":\"" + enabledAccountId + "\"}";
    }
```

4. Replace any `senderAccountId` assertion tied to the singleton. Add:

```java
    @Test
    void rejectsUnknownSenderAccount() {
        String payload = validPayload(new ObjectId())
                .replace(enabledAccountId, new ObjectId().toHexString());
        given().contentType(ContentType.JSON).body(payload)
                .when().post("/api/v1/mail-jobs").then().statusCode(400);
    }

    @Test
    void rejectsDisabledSenderAccount() {
        MailAccount disabled = new MailAccount();
        disabled.name = "Aus";
        disabled.host = "smtp.example.test";
        disabled.port = 587;
        disabled.encryption = at.kigruapp.entity.MailEncryption.STARTTLS;
        disabled.fromAddress = "aus@example.test";
        disabled.enabled = false;
        disabled.persist();

        String payload = validPayload(new ObjectId())
                .replace(enabledAccountId, disabled.id.toHexString());
        given().contentType(ContentType.JSON).body(payload)
                .when().post("/api/v1/mail-jobs").then().statusCode(400);
    }
```

Also update `persistJob(...)` if it sets `senderAccountId = MailSettings.SINGLETON_ID...` — set it to `enabledAccountId` instead. (Search the file for `SINGLETON_ID` and replace usages with `enabledAccountId`.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd test "-Dtest=MailJobResourceTest"`
Expected: FAIL — current validation compares against `MailSettings.SINGLETON_ID`, so a real account id is rejected and disabled/unknown aren't distinguished.

- [ ] **Step 3: Rewrite the validation**

In `backend/src/main/java/at/kigruapp/resource/MailJobResource.java`, replace `validateSenderAccountId`:

```java
    private void validateSenderAccountId(String senderAccountId) {
        at.kigruapp.entity.MailAccount account = null;
        if (senderAccountId != null) {
            try {
                account = at.kigruapp.entity.MailAccount.findById(new ObjectId(senderAccountId));
            } catch (IllegalArgumentException ignored) {
                // malformed id -> treated as unknown below
            }
        }
        if (account == null) {
            throw new BadRequestException("senderAccountId does not reference a known mail account");
        }
        if (!account.enabled) {
            throw new BadRequestException("senderAccountId references a disabled mail account");
        }
    }
```

Remove the now-unused `MailSettings` import from `MailJobResource` if present.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\mvnw.cmd test "-Dtest=MailJobResourceTest"`
Expected: PASS (including the two new rejection tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/resource/MailJobResource.java \
        backend/src/test/java/at/kigruapp/resource/MailJobResourceTest.java
git commit -m "feat: validate job sender against existing enabled mail accounts"
```

---

## Task 5: Remove the legacy singleton surface

**Files:**
- Delete: `backend/src/main/java/at/kigruapp/resource/MailSettingsResource.java`
- Delete: `backend/src/main/java/at/kigruapp/entity/MailSettings.java`
- Delete: `backend/src/main/java/at/kigruapp/dto/MailSettingsDto.java`
- Delete: `backend/src/main/java/at/kigruapp/dto/MailSettingsUpdateDto.java`
- Delete: `backend/src/test/java/at/kigruapp/resource/MailSettingsResourceTest.java` (if it exists)

**Interfaces:**
- Consumes: nothing new.
- Produces: `/api/v1/mail-settings` no longer exists; `MailSettings` type is gone.

- [ ] **Step 1: Find remaining references**

Run: `grep -rn "MailSettings" backend/src/`
Expected: only the files listed for deletion (and their tests). If any other file references `MailSettings`, it is a leftover from an earlier task — fix it before deleting (should not happen if Tasks 3–4 were completed).

- [ ] **Step 2: Delete the files**

```bash
git rm backend/src/main/java/at/kigruapp/resource/MailSettingsResource.java \
       backend/src/main/java/at/kigruapp/entity/MailSettings.java \
       backend/src/main/java/at/kigruapp/dto/MailSettingsDto.java \
       backend/src/main/java/at/kigruapp/dto/MailSettingsUpdateDto.java
# only if it exists:
git rm backend/src/test/java/at/kigruapp/resource/MailSettingsResourceTest.java
```

- [ ] **Step 3: Run the full backend test suite**

Run: `.\mvnw.cmd test`
Expected: BUILD SUCCESS — no compilation errors, mail tests green. (Pre-existing unrelated failures from the broken baseline may remain; confirm no *new* failures appear versus the baseline.)

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: remove legacy mail_settings singleton (resource, entity, DTOs)"
```

---

## Task 6: Frontend model + service CRUD

**Files:**
- Modify: `frontend/src/app/shared/models/mail-account.model.ts`
- Modify: `frontend/src/app/shared/services/mail-account.service.ts`
- Modify: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts` (fixtures)

**Interfaces:**
- Produces:
  - `MailEncryption = 'NONE' | 'STARTTLS' | 'SSL_TLS'`.
  - `MailAccount { id, name, host, port, encryption, username, fromAddress, fromName, enabled, passwordSet }`.
  - `SaveMailAccountRequest { name, host, port, encryption, username, password?, clearPassword?, fromAddress, fromName, enabled }`.
  - `MailAccountService.list()/get(id)/create(req)/update(id, req)/delete(id)`.

- [ ] **Step 1: Extend the model**

Overwrite `frontend/src/app/shared/models/mail-account.model.ts`:

```ts
export type MailEncryption = 'NONE' | 'STARTTLS' | 'SSL_TLS';

export interface MailAccount {
  id: string;
  name: string;
  host: string;
  port: number;
  encryption: MailEncryption;
  username: string;
  fromAddress: string;
  fromName: string;
  enabled: boolean;
  passwordSet: boolean;
}

export interface SaveMailAccountRequest {
  name: string;
  host: string;
  port: number;
  encryption: MailEncryption;
  username: string;
  password?: string;
  clearPassword?: boolean;
  fromAddress: string;
  fromName: string;
  enabled: boolean;
}
```

- [ ] **Step 2: Extend the service**

Overwrite `frontend/src/app/shared/services/mail-account.service.ts`:

```ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { MailAccount, SaveMailAccountRequest } from '../models/mail-account.model';

@Injectable({ providedIn: 'root' })
export class MailAccountService {
  constructor(private api: ApiService) {}

  list(): Observable<MailAccount[]> {
    return this.api.get<MailAccount[]>('/mail-accounts');
  }

  get(id: string): Observable<MailAccount> {
    return this.api.get<MailAccount>(`/mail-accounts/${id}`);
  }

  create(request: SaveMailAccountRequest): Observable<MailAccount> {
    return this.api.post<MailAccount>('/mail-accounts', request);
  }

  update(id: string, request: SaveMailAccountRequest): Observable<MailAccount> {
    return this.api.put<MailAccount>(`/mail-accounts/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/mail-accounts/${id}`);
  }
}
```

- [ ] **Step 3: Fix the jobs-editor spec fixtures**

The Jobs editor's `FakeMailAccountService` builds `MailAccount[]` literals that now need the new required fields. In `mail-job-editor.component.spec.ts`, update the fake accounts to:

```ts
  accounts: MailAccount[] = [{
    id: 'acc1', name: 'Haupt', host: 'smtp.example.test', port: 587,
    encryption: 'STARTTLS', username: '', fromAddress: 'kita@example.test',
    fromName: 'Kita', enabled: true, passwordSet: false,
  }];
```

- [ ] **Step 4: Run the jobs-editor tests**

Run (in `frontend/`): `npx ng test --include='**/mail-job-editor/mail-job-editor.component.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: PASS — fixtures compile with the extended model; dropdown still reads `fromAddress`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/models/mail-account.model.ts \
        frontend/src/app/shared/services/mail-account.service.ts \
        frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.spec.ts
git commit -m "feat: mail-account model + service CRUD (frontend)"
```

---

## Task 7: mail-account-editor component (list/detail)

**Files:**
- Create: `frontend/src/app/settings/mail/mail-account-editor/mail-account-editor.component.ts`
- Create: `frontend/src/app/settings/mail/mail-account-editor/mail-account-editor.component.html`
- Create: `frontend/src/app/settings/mail/mail-account-editor/mail-account-editor.component.scss`
- Test: `frontend/src/app/settings/mail/mail-account-editor/mail-account-editor.component.spec.ts`

**Interfaces:**
- Consumes: `MailAccountService`, `MailAccount`, `SaveMailAccountRequest`, `NotificationService`.
- Produces: `<app-mail-account-editor>` standalone component with `editing` gate, `selectForEdit`, `newAccount`, `closeEditor`, `save`, `delete`, `passwordSet` handling.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/settings/mail/mail-account-editor/mail-account-editor.component.spec.ts`:

```ts
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { MailAccountEditorComponent } from './mail-account-editor.component';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { MailAccount, SaveMailAccountRequest } from '../../../shared/models/mail-account.model';
import { NotificationService } from '../../../shared/services/notification.service';

class FakeMailAccountService {
  accounts: MailAccount[] = [{
    id: 'a1', name: 'Haupt', host: 'smtp.example.test', port: 587, encryption: 'STARTTLS',
    username: 'user', fromAddress: 'kita@example.test', fromName: 'Kita', enabled: true, passwordSet: true,
  }];
  createCalls: SaveMailAccountRequest[] = [];
  updateCalls: { id: string; request: SaveMailAccountRequest }[] = [];
  deleteCalls: string[] = [];

  list() { return of(this.accounts); }
  create(request: SaveMailAccountRequest) {
    this.createCalls.push(request);
    return of({ id: 'a2', ...request, passwordSet: !!request.password } as MailAccount);
  }
  update(id: string, request: SaveMailAccountRequest) {
    this.updateCalls.push({ id, request });
    return of({ id, ...request, passwordSet: true } as MailAccount);
  }
  delete(id: string) { this.deleteCalls.push(id); return of(undefined); }
}

class FakeNotificationService {
  successCalls: string[] = [];
  errorCalls: string[] = [];
  success(m: string) { this.successCalls.push(m); }
  error(m: string) { this.errorCalls.push(m); }
  extractError(err: unknown) { return err instanceof HttpErrorResponse ? String(err.error) : 'error'; }
}

describe('MailAccountEditorComponent', () => {
  let component: MailAccountEditorComponent;
  let service: FakeMailAccountService;
  let notify: FakeNotificationService;

  beforeEach(() => {
    service = new FakeMailAccountService();
    notify = new FakeNotificationService();
    component = new MailAccountEditorComponent(
      service as unknown as MailAccountService,
      notify as unknown as NotificationService,
    );
    component.ngOnInit();
  });

  it('lists accounts and starts with the editor closed', () => {
    expect(component.accounts.length).toBe(1);
    expect(component.editing).toBe(false);
  });

  it('opens the editor via newAccount and selectForEdit; closeEditor hides it', () => {
    component.newAccount();
    expect(component.editing).toBe(true);
    expect(component.selectedId).toBeNull();

    component.closeEditor();
    expect(component.editing).toBe(false);

    component.selectForEdit(service.accounts[0]);
    expect(component.editing).toBe(true);
    expect(component.selectedId).toBe('a1');
    expect(component.form.value.name).toBe('Haupt');
  });

  it('creates a new account and closes the editor', () => {
    component.newAccount();
    component.form.patchValue({
      name: 'Neu', host: 'h', port: 25, encryption: 'NONE',
      username: '', fromAddress: 'a@b.test', fromName: '', enabled: true,
    });

    component.save();

    expect(service.createCalls.length).toBe(1);
    expect(service.createCalls[0].name).toBe('Neu');
    expect(component.editing).toBe(false);
    expect(notify.successCalls).toEqual(['Konto gespeichert']);
  });

  it('only sends the password when the field has a value', () => {
    component.selectForEdit(service.accounts[0]);
    component.save();
    expect(service.updateCalls[0].request.password).toBeUndefined();

    component.selectForEdit(service.accounts[0]);
    component.form.patchValue({ password: 'newpw' });
    component.save();
    expect(service.updateCalls[1].request.password).toBe('newpw');
  });

  it('surfaces the backend reason on save failure', () => {
    service.create = () => throwError(() => new HttpErrorResponse({ status: 400, error: 'host must not be empty' }));
    component.newAccount();
    component.save();
    expect(notify.errorCalls).toEqual(['host must not be empty']);
  });

  it('delete calls the service and returns to the placeholder when the open account is deleted', () => {
    component.selectForEdit(service.accounts[0]);
    component.delete(service.accounts[0]);
    expect(service.deleteCalls).toEqual(['a1']);
    expect(component.editing).toBe(false);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx ng test --include='**/mail-account-editor/mail-account-editor.component.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Create the component TS**

`frontend/src/app/settings/mail/mail-account-editor/mail-account-editor.component.ts`:

```ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { MailAccount, MailEncryption, SaveMailAccountRequest } from '../../../shared/models/mail-account.model';
import { NotificationService } from '../../../shared/services/notification.service';

@Component({
  selector: 'app-mail-account-editor',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatSlideToggleModule, MatIconModule, MatTooltipModule,
  ],
  templateUrl: './mail-account-editor.component.html',
  styleUrl: './mail-account-editor.component.scss',
})
export class MailAccountEditorComponent implements OnInit {
  accounts: MailAccount[] = [];
  selectedId: string | null = null;
  /** When false the form is hidden and a placeholder is shown instead. */
  editing = false;
  /** Whether the selected account has a stored password (drives the hint). */
  passwordSet = false;

  readonly encryptionOptions: MailEncryption[] = ['NONE', 'STARTTLS', 'SSL_TLS'];

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    host: new FormControl('', Validators.required),
    port: new FormControl<number>(587, Validators.required),
    encryption: new FormControl<MailEncryption>('STARTTLS', { nonNullable: true }),
    username: new FormControl(''),
    password: new FormControl(''),
    fromAddress: new FormControl('', Validators.required),
    fromName: new FormControl(''),
    enabled: new FormControl(false, { nonNullable: true }),
  });

  constructor(
    private mailAccountService: MailAccountService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.mailAccountService.list().subscribe((accounts) => (this.accounts = accounts));
  }

  selectForEdit(account: MailAccount): void {
    this.selectedId = account.id;
    this.editing = true;
    this.passwordSet = account.passwordSet;
    this.form.reset({
      name: account.name, host: account.host, port: account.port,
      encryption: account.encryption, username: account.username, password: '',
      fromAddress: account.fromAddress, fromName: account.fromName, enabled: account.enabled,
    });
  }

  newAccount(): void {
    this.selectedId = null;
    this.editing = true;
    this.passwordSet = false;
    this.form.reset({
      name: '', host: '', port: 587, encryption: 'STARTTLS', username: '',
      password: '', fromAddress: '', fromName: '', enabled: false,
    });
  }

  closeEditor(): void {
    this.selectedId = null;
    this.editing = false;
    this.passwordSet = false;
    this.form.reset({
      name: '', host: '', port: 587, encryption: 'STARTTLS', username: '',
      password: '', fromAddress: '', fromName: '', enabled: false,
    });
  }

  save(): void {
    const v = this.form.value;
    const request: SaveMailAccountRequest = {
      name: v.name ?? '',
      host: v.host ?? '',
      port: v.port ?? 0,
      encryption: v.encryption ?? 'NONE',
      username: v.username ?? '',
      fromAddress: v.fromAddress ?? '',
      fromName: v.fromName ?? '',
      enabled: v.enabled ?? false,
    };
    if (v.password && v.password.trim().length > 0) {
      request.password = v.password;
    }
    const isUpdate = this.selectedId !== null;
    const save$ = this.selectedId
      ? this.mailAccountService.update(this.selectedId, request)
      : this.mailAccountService.create(request);
    save$.subscribe({
      next: () => {
        this.notify.success(isUpdate ? 'Konto aktualisiert' : 'Konto gespeichert');
        this.closeEditor();
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  delete(account: MailAccount): void {
    this.mailAccountService.delete(account.id).subscribe({
      next: () => {
        this.notify.success('Konto gelöscht');
        if (this.selectedId === account.id) {
          this.closeEditor();
        }
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
```

- [ ] **Step 4: Create the component HTML**

`frontend/src/app/settings/mail/mail-account-editor/mail-account-editor.component.html`:

```html
<div class="mail-account-editor">
  <aside class="account-list">
    <div class="list-head">
      <h2>Konten</h2>
      <button mat-stroked-button color="primary" type="button" (click)="newAccount()">
        <mat-icon>add</mat-icon> Neues Konto
      </button>
    </div>

    <nav class="accounts" *ngIf="accounts.length; else emptyList">
      <div class="account-item" *ngFor="let a of accounts" [class.selected]="a.id === selectedId">
        <button class="account-main" type="button" (click)="selectForEdit(a)">
          <span class="account-name">{{ a.name }}</span>
          <span class="account-meta">
            <span class="from">{{ a.fromAddress }}</span>
            <span *ngIf="a.enabled" class="badge">aktiv</span>
          </span>
        </button>
        <button mat-icon-button type="button" class="delete-btn"
                (click)="$event.stopPropagation(); delete(a)" matTooltip="Löschen">
          <mat-icon>delete_outline</mat-icon>
        </button>
      </div>
    </nav>

    <ng-template #emptyList>
      <p class="empty">Noch keine Konten angelegt.</p>
    </ng-template>
  </aside>

  <div class="account-placeholder" *ngIf="!editing">
    <mat-icon>alternate_email</mat-icon>
    <p class="placeholder-title">Kein Konto ausgewählt</p>
    <p class="placeholder-sub">Wähle links ein bestehendes Konto zum Bearbeiten oder lege über
      „Neues Konto“ ein neues an.</p>
  </div>

  <form *ngIf="editing" [formGroup]="form" class="account-form" (ngSubmit)="save()">
    <header class="form-head">
      <h2>{{ selectedId ? 'Konto bearbeiten' : 'Neues Konto' }}</h2>
    </header>

    <mat-slide-toggle formControlName="enabled">Konto aktiviert</mat-slide-toggle>

    <mat-form-field appearance="outline">
      <mat-label>Name</mat-label>
      <input matInput formControlName="name" />
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>SMTP-Host</mat-label>
      <input matInput formControlName="host" />
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Port</mat-label>
      <input matInput type="number" formControlName="port" />
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Verschlüsselung</mat-label>
      <mat-select formControlName="encryption">
        <mat-option *ngFor="let opt of encryptionOptions" [value]="opt">{{ opt }}</mat-option>
      </mat-select>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Benutzername</mat-label>
      <input matInput formControlName="username" autocomplete="off" />
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Passwort</mat-label>
      <input matInput type="password" formControlName="password" autocomplete="new-password"
             [placeholder]="passwordSet ? '•••••••• (gesetzt)' : ''" />
      <mat-hint *ngIf="passwordSet">Ein Passwort ist gespeichert. Leer lassen, um es unverändert zu übernehmen.</mat-hint>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Absender-Adresse</mat-label>
      <input matInput formControlName="fromAddress" />
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Absender-Name</mat-label>
      <input matInput formControlName="fromName" />
    </mat-form-field>

    <footer class="actions">
      <button mat-button type="button" (click)="closeEditor()">Verwerfen</button>
      <span class="spacer"></span>
      <button mat-flat-button color="primary" type="submit" [disabled]="form.invalid">Speichern</button>
    </footer>
  </form>
</div>
```

- [ ] **Step 5: Create the component SCSS**

`frontend/src/app/settings/mail/mail-account-editor/mail-account-editor.component.scss` (mirrors the jobs editor shell):

```scss
$border: #e6e8ee;
$border-strong: #c7ccd8;
$surface: #ffffff;
$muted: #6b7280;
$heading: #1f2430;
$accent: #3f51b5;

.mail-account-editor {
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr);
  gap: 1.5rem;
  align-items: start;
}

.account-list {
  position: sticky;
  top: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;

  .list-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;

    h2 { margin: 0; font-size: 1.05rem; font-weight: 600; color: $heading; }
    button mat-icon { font-size: 1.1rem; height: 1.1rem; width: 1.1rem; margin-right: 0.15rem; }
  }

  .accounts { display: flex; flex-direction: column; gap: 0.5rem; }

  .account-item {
    display: flex;
    align-items: center;
    gap: 0.25rem;
    border: 1px solid $border;
    border-radius: 10px;
    background: $surface;
    padding: 0.35rem 0.35rem 0.35rem 0.25rem;
    transition: border-color 0.15s ease, box-shadow 0.15s ease;

    &:hover { border-color: $border-strong; }
    &.selected { border-color: $accent; box-shadow: inset 0 0 0 1px $accent; }

    .account-main {
      flex: 1; min-width: 0;
      display: flex; flex-direction: column; gap: 0.3rem;
      padding: 0.4rem 0.5rem;
      border: none; background: none; cursor: pointer; text-align: left; font: inherit;
    }

    .account-name {
      font-weight: 500; color: $heading;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }

    .account-meta {
      display: flex; flex-wrap: wrap; align-items: center; gap: 0.35rem; min-height: 1.1rem;
      .from { font-size: 0.8rem; color: $muted; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    }

    .delete-btn {
      color: $muted; opacity: 0;
      transition: opacity 0.15s ease, color 0.15s ease;
      &:hover { color: #c62828; }
    }

    &:hover .delete-btn, &.selected .delete-btn { opacity: 1; }
  }

  .empty {
    margin: 0; padding: 0.75rem; color: $muted; font-size: 0.9rem;
    border: 1px dashed $border; border-radius: 10px;
  }
}

.badge {
  font-size: 0.7rem; font-weight: 600; letter-spacing: 0.02em;
  color: #1e7e34; background: #e6f4ea; padding: 0.05rem 0.4rem; border-radius: 999px;
}

.account-placeholder {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  text-align: center; gap: 0.35rem; min-height: 320px; padding: 2rem;
  border: 1px dashed $border-strong; border-radius: 14px; background: $surface; color: $muted;

  mat-icon { font-size: 2.5rem; height: 2.5rem; width: 2.5rem; opacity: 0.5; }
  .placeholder-title { margin: 0.25rem 0 0; font-size: 1.05rem; font-weight: 600; color: $heading; }
  .placeholder-sub { margin: 0; max-width: 360px; font-size: 0.9rem; }
}

.account-form {
  display: flex; flex-direction: column; gap: 0.75rem; min-width: 0; max-width: 560px;

  .form-head h2 { margin: 0 0 0.25rem; font-size: 1.35rem; font-weight: 600; color: $heading; }
  mat-form-field { width: 100%; }
}

.actions {
  display: flex; align-items: center; gap: 0.5rem; padding-top: 0.25rem;
  .spacer { flex: 1; }
}

@media (max-width: 860px) {
  .mail-account-editor { grid-template-columns: 1fr; }
  .account-list { position: static; }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `npx ng test --include='**/mail-account-editor/mail-account-editor.component.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: PASS (all specs).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/settings/mail/mail-account-editor/
git commit -m "feat: mail-account-editor component (list/detail, no test-mail)"
```

---

## Task 8: Host the editor in the Mail tab; remove the old form/test/service

**Files:**
- Modify: `frontend/src/app/settings/mail/mail.component.ts`
- Modify: `frontend/src/app/settings/mail/mail.component.html`
- Modify: `frontend/src/app/settings/mail/mail.component.spec.ts`
- Delete: `frontend/src/app/shared/services/mail-settings.service.ts`
- Delete: `frontend/src/app/shared/models/mail-settings.model.ts`

**Interfaces:**
- Consumes: `MailAccountEditorComponent` (Task 7).
- Produces: the settings page renders three tabs — **Mail-Accounts**, **Vorlagen**, **Jobs** — with no SMTP form or test-mail controls.

- [ ] **Step 1: Rewrite the tab-host component**

Overwrite `frontend/src/app/settings/mail/mail.component.ts`:

```ts
import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTabsModule } from '@angular/material/tabs';
import { MailAccountEditorComponent } from './mail-account-editor/mail-account-editor.component';
import { MailTemplateEditorComponent } from './mail-template-editor/mail-template-editor.component';
import { MailJobEditorComponent } from './mail-job-editor/mail-job-editor.component';

@Component({
  selector: 'app-mail',
  standalone: true,
  imports: [
    CommonModule, MatTabsModule,
    MailAccountEditorComponent, MailTemplateEditorComponent, MailJobEditorComponent,
  ],
  templateUrl: './mail.component.html',
  styleUrl: './mail.component.scss',
})
export class MailComponent {}
```

- [ ] **Step 2: Rewrite the tab-host template**

Overwrite `frontend/src/app/settings/mail/mail.component.html`:

```html
<div class="mail-settings">
  <h1>Mail-Einstellungen</h1>

  <mat-tab-group>
    <mat-tab label="Mail-Accounts">
      <div class="tab-content">
        <app-mail-account-editor></app-mail-account-editor>
      </div>
    </mat-tab>

    <mat-tab label="Vorlagen">
      <div class="tab-content">
        <app-mail-template-editor></app-mail-template-editor>
      </div>
    </mat-tab>

    <mat-tab label="Jobs">
      <div class="tab-content">
        <app-mail-job-editor></app-mail-job-editor>
      </div>
    </mat-tab>
  </mat-tab-group>
</div>
```

- [ ] **Step 3: Replace the tab-host spec**

Overwrite `frontend/src/app/settings/mail/mail.component.spec.ts`:

```ts
import { MailComponent } from './mail.component';

describe('MailComponent', () => {
  it('constructs (tab host with no SMTP form or test-mail state)', () => {
    const component = new MailComponent();
    expect(component).toBeTruthy();
    // Regression guard: the removed test-mail API must not reappear here.
    expect((component as unknown as { sendTest?: unknown }).sendTest).toBeUndefined();
  });
});
```

- [ ] **Step 4: Delete the now-unused settings service + model**

```bash
git rm frontend/src/app/shared/services/mail-settings.service.ts \
       frontend/src/app/shared/models/mail-settings.model.ts
```

Then run: `grep -rn "mail-settings.model\|MailSettingsService\|mail-settings.service" frontend/src/`
Expected: no matches. If any remain (e.g. a stray `MailEncryption` import from `mail-settings.model`), repoint it to `../models/mail-account.model`.

- [ ] **Step 5: Run the mail settings tests**

Run: `npx ng test --include='**/settings/mail/**/*.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: PASS — account editor, jobs, templates, and the trimmed `MailComponent` spec all green.

- [ ] **Step 6: Build the frontend to catch dangling references**

Run: `npx ng build --configuration development`
Expected: build succeeds with no unresolved imports.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: rename SMTP tab to Mail-Accounts; host account editor; drop test-mail UI"
```

---

## Self-Review

**Spec coverage**

- Multi-account data model → Task 1 (`MailAccount` entity).
- No singleton / ordinary ids → Task 1 (entity), Task 5 (delete `MailSettings`).
- Migration (import config, drop `mail_settings`, idempotent) → Task 2.
- CRUD endpoints + password handling + `passwordSet` → Task 1.
- Send path resolves account by `senderAccountId` → Task 3.
- Job sender validation (exists + enabled) → Task 4.
- Delete safety (409 when referenced) → Task 1 (DELETE) with the guard verified conceptually; add a delete-conflict test if desired (see note below).
- Remove test endpoint server-side → Task 3 (Step 5).
- Frontend list/detail editor + placeholder → Task 7.
- Rename tab, remove test section, remove settings service/model → Task 8.
- Testing across both stacks → each task's test steps.

**Gap fix applied:** the delete-safety path in Task 1 ships the code but its test lives most naturally after `MailJob` sender ids are real (Task 4). Add this test to `MailAccountResourceTest` during Task 4 (it already persists jobs there), or append it in Task 1 using a raw `MailJob` with `senderAccountId` set to the account id:

```java
@Test
void blocksDeleteWhenReferencedByAJob() {
    String id = given().contentType(ContentType.JSON).body(payload("Haupt"))
            .when().post("/api/v1/mail-accounts").then().statusCode(201).extract().path("id");
    at.kigruapp.entity.MailJob job = new at.kigruapp.entity.MailJob();
    job.name = "J"; job.templateId = new org.bson.types.ObjectId(); job.subject = "s";
    job.cron = "0 0 8 * * ?"; job.senderAccountId = id;
    job.recipientMode = at.kigruapp.entity.RecipientMode.ALL_PARENTS;
    job.persist();

    given().when().delete("/api/v1/mail-accounts/" + id).then().statusCode(409);
}
```
Add `at.kigruapp.entity.MailJob.deleteAll();` to that test class's `@BeforeEach`. Put this test in Task 1 (Step 1) so the DELETE guard is covered where it is written.

**Placeholder scan:** no TBD/TODO; every code step carries full code; every test step carries runnable assertions.

**Type consistency:** `sendHtml(MailAccount, String, String, String)` (Task 3) matches the scheduler call (Task 3 Step 4). `SaveMailAccountRequest` fields (Task 6) match the form `save()` payload (Task 7) and the backend `MailAccountUpdateDto` (Task 1). `MailAccountDto`/`MailAccount` (frontend) field names align across model, service, editor, and the jobs-editor fixture update (Task 6 Step 3). `senderAccountId` treated as a hex `String` consistently in entity, validation (Task 4), scheduler (Task 3), and delete guard (Task 1).

---

## Notes for the Executor

- Backend tests run with `.\mvnw.cmd test "-Dtest=..."` in PowerShell (a Bash hook intercepts `./mvnw`). The full suite is `.\mvnw.cmd test`.
- Frontend tests run from `frontend/` with `npx ng test --include='<glob>' --watch=false --browsers=ChromeHeadless`.
- A known broken baseline exists (pre-existing unrelated failing tests). Judge each task by "no *new* failures," not by a fully-green global suite.
- Do not push; commit only.
