# Kochdienst-Erinnerungen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eltern lassen sich vor ihrem Kochdienst automatisch per E-Mail erinnern; ein täglicher Backend-Lauf verschickt die fälligen Erinnerungen.

**Architecture:** Die Erinnerungs-Konfiguration (`reminderEnabled`, `reminderDaysBefore`) liegt im `value` der cookingDuty-FieldInstance — dadurch kaskadieren Änderung und Löschung des Kochdienstes ohne Synchronisationscode. Eine neue Singleton-Entity hält Mailkonto, Vorlage, Betreff und Versandzeit. Ein programmatischer Quarkus-Cron (Europe/Vienna, analog `MailJobScheduler`) läuft täglich, filtert auf `dutyDate − daysBefore == heute` und schreibt jeden Versand in die Kollektion `cooking_reminders`, deren Unique-Index (`dutyId`, `dueDate`) den Lauf idempotent macht.

**Tech Stack:** Quarkus (Panache MongoDB, `io.quarkus.scheduler.Scheduler`), JAX-RS, JUnit 5 + RestAssured + GreenMail, Angular 17 (standalone components, Angular Material), Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-08-02-kochdienst-erinnerungen-design.md`

## Global Constraints

- Backend-Verzeichnis: `backend/`. Testbefehl: `.\mvnw.cmd test -Dtest=<TestKlasse>` (aus `backend/`, PowerShell). Voller Lauf: `.\mvnw.cmd test`.
- Frontend-Verzeichnis: `frontend/`. Testbefehl: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/<datei>.spec.ts` (aus `frontend/`). Voller Lauf ohne `--include`.
- MongoDB für Backend-Tests muss laufen (Container `kigru-mongo-test` auf Port 27017).
- Zeitzone für alle Cron-Registrierungen: `Europe/Vienna`.
- Datumsformat in gespeicherten Werten: `yyyy-MM-dd` (String). Anzeige/Token-Format: `dd.MM.yyyy`.
- Vorlaufzeit: ganzzahlig, 1 bis 14, Vorbelegung 3.
- UI-Texte deutsch. Umlaute im Frontend wie im Bestand ausgeschrieben, wo der Bestand das tut (`Loeschen`, `hinzufuegen`), neue Fließtexte dürfen echte Umlaute nutzen wie in `landing-page`-Code.
- Auf `main` bestehen 13 vorab fehlschlagende Backend-Tests und 1 Frontend-Test. Diese sind nicht Teil dieser Arbeit; bewerte nur die in den Tasks genannten Testklassen.
- Kein `git commit` ohne ausdrückliche Freigabe des Nutzers — jede Commit-Anweisung in diesem Plan gilt als vom Nutzer freigegeben, sobald der Plan freigegeben ist.

---

### Task 1: Einstellungs-Entity und Ressource

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/CookingReminderSettings.java`
- Create: `backend/src/main/java/at/kigruapp/resource/CookingReminderSettingsResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/CookingReminderSettingsResourceTest.java`

**Interfaces:**
- Consumes: `MailAccount`, `MailTemplate` (bestehende Entities).
- Produces:
  - `CookingReminderSettings` mit den öffentlichen Feldern `senderAccountId : String`, `templateId : String`, `subject : String`, `sendTime : String`, `updatedAt : Instant` und `static CookingReminderSettings findSingleton()`.
  - `CookingReminderSettingsResource.SettingsDto(String senderAccountId, String templateId, String subject, String sendTime, boolean active)`.
  - `static boolean isActive(CookingReminderSettings settings)` in `CookingReminderSettingsResource` — von Task 7 wiederverwendet.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/resource/CookingReminderSettingsResourceTest.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.CookingReminderSettings;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailTemplate;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CookingReminderSettingsResourceTest {

    MailAccount account;
    MailTemplate template;

    @BeforeEach
    void cleanup() {
        CookingReminderSettings.deleteAll();
        MailAccount.deleteAll();
        MailTemplate.deleteAll();

        account = new MailAccount();
        account.name = "Kiga";
        account.host = "localhost";
        account.port = 3025;
        account.fromAddress = "kiga@example.org";
        account.enabled = true;
        account.persist();

        template = new MailTemplate();
        template.name = "Erinnerung";
        template.bodyHtml = "<p>Hallo</p>";
        template.createdAt = Instant.now();
        template.persist();
    }

    private io.restassured.response.Response put(String senderAccountId, String templateId,
                                                 String subject, String sendTime) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"senderAccountId\":" + json(senderAccountId)
                        + ",\"templateId\":" + json(templateId)
                        + ",\"subject\":" + json(subject)
                        + ",\"sendTime\":" + json(sendTime) + "}")
                .when().put("/api/v1/cooking-reminder-settings");
    }

    private String json(String raw) {
        return raw == null ? "null" : "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void getReturnsInactiveDefaultsWhenNothingSavedYet() {
        given()
                .when().get("/api/v1/cooking-reminder-settings")
                .then().statusCode(200)
                .body("active", is(false))
                .body("sendTime", is("07:00"));
    }

    @Test
    void putStoresSettingsAndReportsActive() {
        put(account.id.toHexString(), template.id.toHexString(), "Dein Kochdienst", "18:30")
                .then().statusCode(200)
                .body("active", is(true))
                .body("sendTime", is("18:30"))
                .body("subject", is("Dein Kochdienst"));

        given()
                .when().get("/api/v1/cooking-reminder-settings")
                .then().statusCode(200)
                .body("senderAccountId", is(account.id.toHexString()))
                .body("templateId", is(template.id.toHexString()));
    }

    @Test
    void putOverwritesSingletonInsteadOfCreatingSecondRow() {
        put(account.id.toHexString(), template.id.toHexString(), "Eins", "07:00");
        put(account.id.toHexString(), template.id.toHexString(), "Zwei", "08:00");

        assertEquals(1, CookingReminderSettings.count());
    }

    @Test
    void putWithoutAccountClearsConfigurationAndIsInactive() {
        put(account.id.toHexString(), template.id.toHexString(), "Eins", "07:00");

        put(null, null, null, "07:00")
                .then().statusCode(200)
                .body("active", is(false));
    }

    @Test
    void putRejectsMalformedSendTime() {
        put(account.id.toHexString(), template.id.toHexString(), "Eins", "25:00")
                .then().statusCode(400);
    }

    @Test
    void putRejectsAccountWithoutTemplate() {
        put(account.id.toHexString(), null, "Eins", "07:00")
                .then().statusCode(400);
    }

    @Test
    void disabledAccountMakesSettingsInactive() {
        put(account.id.toHexString(), template.id.toHexString(), "Eins", "07:00");

        account.enabled = false;
        account.update();

        given()
                .when().get("/api/v1/cooking-reminder-settings")
                .then().statusCode(200)
                .body("active", is(false));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=CookingReminderSettingsResourceTest` (aus `backend/`)
Expected: Kompilierfehler — `CookingReminderSettings` existiert nicht.

- [ ] **Step 3: Write the entity**

Create `backend/src/main/java/at/kigruapp/entity/CookingReminderSettings.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.time.Instant;

/**
 * Konfiguration der Kochdienst-Erinnerungen. Bewusst ein Singleton — es gibt
 * genau eine Einstellung, die erste Zeile der Collection ist maßgeblich
 * (gleiches Muster wie {@link LandingPage}).
 */
@MongoEntity(collection = "cooking_reminder_settings")
public class CookingReminderSettings extends PanacheMongoEntity {

    /** Hex-Id einer MailAccount. Null bedeutet: Erinnerungen sind abgeschaltet. */
    public String senderAccountId;
    /** Hex-Id einer MailTemplate. */
    public String templateId;
    public String subject;
    /** Versandzeit im Format HH:mm, Zeitzone Europe/Vienna. */
    public String sendTime;
    public Instant updatedAt;

    public static CookingReminderSettings findSingleton() {
        return findAll().firstResult();
    }
}
```

- [ ] **Step 4: Write the resource**

Create `backend/src/main/java/at/kigruapp/resource/CookingReminderSettingsResource.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.CookingReminderSettings;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailTemplate;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Einstellungen der Kochdienst-Erinnerungen. GET ist für alle Angemeldeten
 * freigeschaltet (siehe SecurityFilter), damit der Kochdienst-Dialog weiß, ob
 * die Funktion aktiv ist. PUT bleibt durch das Default-Deny admin-only.
 */
@Path("/api/v1/cooking-reminder-settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CookingReminderSettingsResource {

    static final String DEFAULT_SEND_TIME = "07:00";

    private static final Pattern SEND_TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    public record SettingsDto(String senderAccountId, String templateId, String subject,
                              String sendTime, boolean active) {}

    /**
     * Aktiv ist die Funktion genau dann, wenn Konto und Vorlage gesetzt sind,
     * beide existieren und das Konto freigeschaltet ist.
     */
    public static boolean isActive(CookingReminderSettings settings) {
        if (settings == null || settings.senderAccountId == null || settings.templateId == null) {
            return false;
        }
        MailAccount account = findAccount(settings.senderAccountId);
        if (account == null || !account.enabled) {
            return false;
        }
        return findTemplate(settings.templateId) != null;
    }

    /** Öffentlich, weil der Scheduler (anderes Package) beide Referenzen auflöst. */
    public static MailAccount findAccount(String hexId) {
        if (hexId == null || !ObjectId.isValid(hexId)) {
            return null;
        }
        return MailAccount.findById(new ObjectId(hexId));
    }

    public static MailTemplate findTemplate(String hexId) {
        if (hexId == null || !ObjectId.isValid(hexId)) {
            return null;
        }
        return MailTemplate.findById(new ObjectId(hexId));
    }

    @GET
    public SettingsDto get() {
        CookingReminderSettings settings = CookingReminderSettings.findSingleton();
        if (settings == null) {
            return new SettingsDto(null, null, null, DEFAULT_SEND_TIME, false);
        }
        return toDto(settings);
    }

    @PUT
    public SettingsDto save(SettingsDto dto) {
        String sendTime = dto == null || dto.sendTime() == null ? DEFAULT_SEND_TIME : dto.sendTime().trim();
        if (!SEND_TIME_PATTERN.matcher(sendTime).matches()) {
            throw new BadRequestException("sendTime muss im Format HH:mm vorliegen");
        }

        String accountId = blankToNull(dto == null ? null : dto.senderAccountId());
        String templateId = blankToNull(dto == null ? null : dto.templateId());
        String subject = dto == null ? null : dto.subject();

        if (accountId != null) {
            if (findAccount(accountId) == null) {
                throw new BadRequestException("Mailkonto existiert nicht");
            }
            if (templateId == null || findTemplate(templateId) == null) {
                throw new BadRequestException("Zu einem Mailkonto muss eine gültige Vorlage gewählt werden");
            }
            if (subject == null || subject.isBlank()) {
                throw new BadRequestException("Betreff darf nicht leer sein");
            }
        } else {
            templateId = null;
            subject = null;
        }

        CookingReminderSettings settings = CookingReminderSettings.findSingleton();
        if (settings == null) {
            settings = new CookingReminderSettings();
        }
        settings.senderAccountId = accountId;
        settings.templateId = templateId;
        settings.subject = subject;
        settings.sendTime = sendTime;
        settings.updatedAt = Instant.now();
        settings.persistOrUpdate();

        return toDto(settings);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private SettingsDto toDto(CookingReminderSettings settings) {
        return new SettingsDto(settings.senderAccountId, settings.templateId, settings.subject,
                settings.sendTime == null ? DEFAULT_SEND_TIME : settings.sendTime, isActive(settings));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=CookingReminderSettingsResourceTest`
Expected: PASS (7 Tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/CookingReminderSettings.java backend/src/main/java/at/kigruapp/resource/CookingReminderSettingsResource.java backend/src/test/java/at/kigruapp/resource/CookingReminderSettingsResourceTest.java
git commit -m "feat(be): Einstellungen fuer Kochdienst-Erinnerungen"
```

---

### Task 2: GET der Einstellungen für alle Angemeldeten freischalten

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/security/SecurityFilter.java` (Methode `isAllowed`, bei den übrigen Whitelist-Zeilen)
- Test: `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java` (zwei Tests anhängen)

**Interfaces:**
- Consumes: Pfad `/api/v1/cooking-reminder-settings` aus Task 1.
- Produces: nichts Neues.

- [ ] **Step 1: Write the failing test**

An das Ende von `SecurityFilterTest` (vor der schließenden Klammer) anfügen:

```java
    @Test
    void cookingReminderSettingsGet_passesThrough_forNonAdmin() {
        givenPath("/api/v1/cooking-reminder-settings", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(new Person());
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    @Test
    void cookingReminderSettingsPut_isForbidden_forNonAdmin() {
        givenPath("/api/v1/cooking-reminder-settings", "PUT");
        when(currentUserService.getCurrentPerson()).thenReturn(new Person());
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=SecurityFilterTest`
Expected: FAIL — `cookingReminderSettingsGet_passesThrough_forNonAdmin` schlägt fehl, weil das Default-Deny greift.

- [ ] **Step 3: Add the whitelist entry**

In `SecurityFilter.isAllowed`, direkt unter den Startseiten-Zeilen einfügen:

```java
        // Kochdienst-Erinnerungen: Einstellungen lesen alle Angemeldeten, damit der
        // Kochdienst-Dialog weiß, ob die Erinnerungsfunktion angeboten wird.
        // PUT bleibt admin-only (Default-Deny).
        if (path.equals("/api/v1/cooking-reminder-settings") && "GET".equals(method)) return true;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=SecurityFilterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/security/SecurityFilter.java backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java
git commit -m "feat(be): Erinnerungs-Einstellungen fuer alle Angemeldeten lesbar"
```

---

### Task 3: JSON-Schema des Kochdienstes um die Erinnerungsfelder erweitern

Der Seed in `FieldDefinitionSeedMigration` läuft nur bei leerer Datenbank. Bestehende Installationen brauchen zusätzlich eine eigene, über die `migrations`-Kollektion abgesicherte Migration, sonst lehnt `JsonSchemaValidatorService` das Speichern mit 400 ab.

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java:116-125` (Seed der Definition `cookingDuty`)
- Create: `backend/src/main/java/at/kigruapp/migration/CookingDutyReminderSchemaMigration.java`
- Test: `backend/src/test/java/at/kigruapp/migration/CookingDutyReminderSchemaMigrationTest.java`

**Interfaces:**
- Consumes: Kollektionen `field_definitions` und `migrations`.
- Produces: Definition `cookingDuty` mit den zusätzlichen Schema-Properties `reminderEnabled` (`boolean`) und `reminderDaysBefore` (`integer`, `minimum` 1, `maximum` 14). Beide bleiben optional, damit bestehende Kochdienste gültig bleiben.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/migration/CookingDutyReminderSchemaMigrationTest.java`:

```java
package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CookingDutyReminderSchemaMigrationTest {

    private static final String MIGRATION_ID = "cookingduty-reminder-schema-v1";

    @Inject
    CookingDutyReminderSchemaMigration migration;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoCollection<Document> definitions() {
        return mongoClient.getDatabase(databaseName).getCollection("field_definitions");
    }

    private MongoCollection<Document> migrations() {
        return mongoClient.getDatabase(databaseName).getCollection("migrations");
    }

    @BeforeEach
    void setup() {
        definitions().deleteMany(new Document("fieldName", "cookingDuty"));
        migrations().deleteMany(new Document("_id", MIGRATION_ID));

        definitions().insertOne(new Document("fieldName", "cookingDuty")
                .append("jsonSchema", new Document("type", "object")
                        .append("properties", new Document()
                                .append("date", new Document("type", "string").append("format", "date"))
                                .append("groups", new Document("type", "array")))
                        .append("required", List.of("date", "groups"))));
    }

    @Test
    void addsReminderPropertiesToExistingDefinition() {
        migration.run();

        Document def = definitions().find(new Document("fieldName", "cookingDuty")).first();
        assertNotNull(def);
        Document properties = def.get("jsonSchema", Document.class).get("properties", Document.class);

        assertEquals("boolean", properties.get("reminderEnabled", Document.class).getString("type"));
        Document days = properties.get("reminderDaysBefore", Document.class);
        assertEquals("integer", days.getString("type"));
        assertEquals(1, days.getInteger("minimum"));
        assertEquals(14, days.getInteger("maximum"));
    }

    @Test
    void keepsExistingPropertiesAndRequiredList() {
        migration.run();

        Document def = definitions().find(new Document("fieldName", "cookingDuty")).first();
        Document schema = def.get("jsonSchema", Document.class);
        assertNotNull(schema.get("properties", Document.class).get("date"));
        assertEquals(List.of("date", "groups"), schema.get("required"));
    }

    @Test
    void isIdempotentViaMigrationsCollection() {
        migration.run();
        migration.run();

        assertEquals(1, migrations().countDocuments(new Document("_id", MIGRATION_ID)));
        assertTrue(definitions().countDocuments(new Document("fieldName", "cookingDuty")) == 1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=CookingDutyReminderSchemaMigrationTest`
Expected: Kompilierfehler — `CookingDutyReminderSchemaMigration` existiert nicht.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/java/at/kigruapp/migration/CookingDutyReminderSchemaMigration.java`:

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
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

/**
 * One-time: erweitert das JSON-Schema der Definition {@code cookingDuty} um die
 * optionalen Felder {@code reminderEnabled} und {@code reminderDaysBefore}.
 * Ohne diesen Schritt weist der JsonSchemaValidatorService Kochdienste mit
 * Erinnerung ab. Idempotent über die migrations-Kollektion.
 */
@ApplicationScoped
@Startup
public class CookingDutyReminderSchemaMigration {

    private static final String MIGRATION_ID = "cookingduty-reminder-schema-v1";

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

        MongoCollection<Document> definitions = db.getCollection("field_definitions");
        definitions.updateOne(new Document("fieldName", "cookingDuty"),
                new Document("$set", new Document()
                        .append("jsonSchema.properties.reminderEnabled", new Document("type", "boolean"))
                        .append("jsonSchema.properties.reminderDaysBefore", new Document("type", "integer")
                                .append("minimum", 1)
                                .append("maximum", 14))));

        migrations.insertOne(new Document("_id", MIGRATION_ID).append("executedAt", Instant.now()));
    }
}
```

- [ ] **Step 4: Extend the seed for fresh installations**

In `FieldDefinitionSeedMigration.java` den `cookingDuty`-Seed (Zeilen 116–125) ersetzen durch:

```java
        seedDef(defs, now, "cookingDuty",
                Map.of("de", "Kochdienst", "en", "Cooking Duty"),
                new Document("type", "object")
                        .append("properties", new Document()
                                .append("date", new Document("type", "string").append("format", "date"))
                                .append("groups", new Document("type", "array").append("items", new Document("type", "string")))
                                .append("description", new Document("type", "string"))
                                .append("foodProperties", new Document("type", "object"))
                                .append("reminderEnabled", new Document("type", "boolean"))
                                .append("reminderDaysBefore", new Document("type", "integer")
                                        .append("minimum", 1)
                                        .append("maximum", 14)))
                        .append("required", List.of("date", "groups")),
                false, null);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=CookingDutyReminderSchemaMigrationTest`
Expected: PASS (3 Tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/migration/CookingDutyReminderSchemaMigration.java backend/src/main/java/at/kigruapp/migration/FieldDefinitionSeedMigration.java backend/src/test/java/at/kigruapp/migration/CookingDutyReminderSchemaMigrationTest.java
git commit -m "feat(be): Kochdienst-Schema um Erinnerungsfelder erweitert"
```

---

### Task 4: Erinnerungsfelder im CookingDutyDTO ausliefern

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/dto/CookingDutyDTO.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/CookingDutyResource.java` (in `list`, beim Befüllen des DTO)
- Test: `backend/src/test/java/at/kigruapp/resource/CookingDutyReminderFieldsTest.java`

**Interfaces:**
- Consumes: Schema aus Task 3.
- Produces: `CookingDutyDTO.reminderEnabled : boolean`, `CookingDutyDTO.reminderDaysBefore : Integer` (null, wenn nicht gesetzt).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/resource/CookingDutyReminderFieldsTest.java`:

```java
package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class CookingDutyReminderFieldsTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    FieldDefinition cookingDutyDef;

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    @BeforeEach
    void setup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());

        cookingDutyDef = new FieldDefinition();
        cookingDutyDef.fieldName = "cookingDuty";
        cookingDutyDef.createdAt = Instant.now();
        cookingDutyDef.persist();
    }

    private void persistDuty(Document value) {
        ObjectId instanceId = new ObjectId();
        fieldInstances().insertOne(new Document("_id", instanceId)
                .append("definitionId", cookingDutyDef.id)
                .append("value", value));

        Person person = new Person();
        person.familyId = new ObjectId();
        person.basicProperties = new ArrayList<>();
        person.schedules = new ArrayList<>();
        FieldRef ref = new FieldRef();
        ref.definitionId = cookingDutyDef.id;
        ref.fieldInstanceId = instanceId;
        person.schedules.add(ref);
        person.persist();
    }

    @Test
    void reminderFieldsAreReturned() {
        persistDuty(new Document("date", "2026-09-15")
                .append("groups", List.of("g1"))
                .append("reminderEnabled", true)
                .append("reminderDaysBefore", 5));

        given()
                .when().get("/api/v1/cooking-duties?month=2026-09")
                .then().statusCode(200)
                .body("[0].reminderEnabled", is(true))
                .body("[0].reminderDaysBefore", is(5));
    }

    @Test
    void dutyWithoutReminderReportsDisabled() {
        persistDuty(new Document("date", "2026-09-16").append("groups", List.of("g1")));

        given()
                .when().get("/api/v1/cooking-duties?month=2026-09")
                .then().statusCode(200)
                .body("[0].reminderEnabled", is(false))
                .body("[0].reminderDaysBefore", nullValue());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=CookingDutyReminderFieldsTest`
Expected: FAIL — die Felder fehlen in der Antwort.

- [ ] **Step 3: Extend the DTO**

In `CookingDutyDTO.java` unter `foodProperties` ergänzen:

```java
    public boolean reminderEnabled;
    public Integer reminderDaysBefore;
```

- [ ] **Step 4: Fill the fields in the resource**

In `CookingDutyResource.list`, direkt hinter `dto.foodProperties = foodProps;` einfügen:

```java
                Object reminderEnabledObj = valueDoc.get("reminderEnabled");
                dto.reminderEnabled = reminderEnabledObj instanceof Boolean b && b;
                Object daysObj = valueDoc.get("reminderDaysBefore");
                dto.reminderDaysBefore = daysObj instanceof Number n ? n.intValue() : null;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=CookingDutyReminderFieldsTest`
Expected: PASS (2 Tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/dto/CookingDutyDTO.java backend/src/main/java/at/kigruapp/resource/CookingDutyResource.java backend/src/test/java/at/kigruapp/resource/CookingDutyReminderFieldsTest.java
git commit -m "feat(be): Erinnerungsfelder im Kochdienst-DTO"
```

---

### Task 5: Duty-Namespace im MailTemplateRenderer

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/MailTemplateRenderer.java`
- Test: `backend/src/test/java/at/kigruapp/service/MailTemplateRendererTest.java` (Tests anhängen)

**Interfaces:**
- Consumes: nichts Neues.
- Produces: `String render(String bodyHtml, Map<String,String> personProperties, Map<String,String> dutyProperties)` — ersetzt zusätzlich `{{duty.<key>}}`. Die bestehende Signatur `render(String, Map)` bleibt unverändert und delegiert mit leerer Duty-Map.

- [ ] **Step 1: Write the failing test**

An `MailTemplateRendererTest` anhängen (Import `java.util.Map` ist dort vorhanden; falls nicht, ergänzen):

```java
    @Test
    void ersetztDutyTokens() {
        String result = renderer.render(
                "<p>Am {{duty.date}} kochst du fuer {{duty.groups}}.</p>",
                Map.of(),
                Map.of("date", "15.09.2026", "groups", "Rot, Blau"));

        assertEquals("<p>Am 15.09.2026 kochst du fuer Rot, Blau.</p>", result);
    }

    @Test
    void mischtPersonUndDutyTokens() {
        String result = renderer.render(
                "<p>Hallo {{person.firstName}}, dein Kochdienst ist am {{duty.date}}.</p>",
                Map.of("firstName", "Anna"),
                Map.of("date", "15.09.2026"));

        assertEquals("<p>Hallo Anna, dein Kochdienst ist am 15.09.2026.</p>", result);
    }

    @Test
    void unbekanntesDutyTokenWirdGeleert() {
        String result = renderer.render("<p>{{duty.unbekannt}}</p>", Map.of(), Map.of());

        assertEquals("<p></p>", result);
    }

    @Test
    void escaptDutyWerte() {
        String result = renderer.render("<p>{{duty.description}}</p>", Map.of(),
                Map.of("description", "<script>x</script>"));

        assertEquals("<p>&lt;script&gt;x&lt;/script&gt;</p>", result);
    }

    @Test
    void alteSignaturVerhaeltSichUnveraendert() {
        String result = renderer.render("<p>Hallo {{person.firstName}}</p>", Map.of("firstName", "Anna"));

        assertEquals("<p>Hallo Anna</p>", result);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=MailTemplateRendererTest`
Expected: Kompilierfehler — die dreiargumentige `render`-Methode existiert nicht.

- [ ] **Step 3: Extend the renderer**

`MailTemplateRenderer.java` so umbauen, dass beide Namespaces über eine gemeinsame Ersetzung laufen:

```java
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{(person|duty)\\.([a-zA-Z0-9_]+)}}");

    public String render(String bodyHtml, Map<String, String> properties) {
        return render(bodyHtml, properties, Map.of());
    }

    /**
     * Wie {@link #render(String, Map)}, löst zusätzlich {@code {{duty.<feld>}}}
     * aus der zweiten Map auf. Beide Namespaces teilen sich Escaping und die
     * Leerkommentar-Reparatur.
     */
    public String render(String bodyHtml, Map<String, String> personProperties,
                         Map<String, String> dutyProperties) {
        if (bodyHtml == null) {
            return null;
        }
        String normalized = EMPTY_COMMENT.matcher(bodyHtml).replaceAll("");
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String namespace = matcher.group(1);
            String fieldName = matcher.group(2);
            Map<String, String> source = "duty".equals(namespace) ? dutyProperties : personProperties;
            String value = source != null ? source.get(fieldName) : null;
            String replacement = value != null ? escapeHtml(value) : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=MailTemplateRendererTest`
Expected: PASS (alle bisherigen plus 5 neue Tests)

- [ ] **Step 5: Run the mail job tests to confirm nothing regressed**

Run: `.\mvnw.cmd test -Dtest=MailJobRunTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/MailTemplateRenderer.java backend/src/test/java/at/kigruapp/service/MailTemplateRendererTest.java
git commit -m "feat(be): duty-Platzhalter im Mail-Renderer"
```

---

### Task 6: Familien-Empfänger auflösen

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/service/RecipientResolverService.java` (neue öffentliche Methode neben `resolveAllParents`)
- Test: `backend/src/test/java/at/kigruapp/service/RecipientResolverServiceTest.java` (Tests anhängen)

**Interfaces:**
- Consumes: die vorhandenen privaten Helfer `isParent`, `hasNonBlankEmail`, `resolveEmail` sowie `PersonPropertyResolver`.
- Produces: `List<ResolvedRecipient> resolveFamilyRecipients(ObjectId familyId)` — alle Eltern der Familie mit nicht-leerer E-Mail-Adresse, dedupliziert, jeweils mit aufgelösten Person-Properties. Leere Liste bei `null`-familyId.

- [ ] **Step 1: Write the failing test**

An `RecipientResolverServiceTest` anhängen. Die Testklasse besitzt bereits Hilfsmethoden zum Anlegen von Personen; falls die Namen abweichen, orientiere dich an den vorhandenen Tests derselben Datei und lege Personen analog an.

```java
    @Test
    void resolveFamilyRecipients_liefertBeideElternDerFamilie() {
        ObjectId familyId = new ObjectId();
        persistParent(familyId, "Anna", "anna@example.org");
        persistParent(familyId, "Bernd", "bernd@example.org");
        persistParent(new ObjectId(), "Fremd", "fremd@example.org");

        List<RecipientResolverService.ResolvedRecipient> recipients =
                service.resolveFamilyRecipients(familyId);

        assertEquals(2, recipients.size());
        assertTrue(recipients.stream().anyMatch(r -> r.email().equals("anna@example.org")));
        assertTrue(recipients.stream().anyMatch(r -> r.email().equals("bernd@example.org")));
    }

    @Test
    void resolveFamilyRecipients_ueberspringtElternOhneEmail() {
        ObjectId familyId = new ObjectId();
        persistParent(familyId, "Anna", "anna@example.org");
        persistParent(familyId, "Ohne", "");

        List<RecipientResolverService.ResolvedRecipient> recipients =
                service.resolveFamilyRecipients(familyId);

        assertEquals(1, recipients.size());
        assertEquals("anna@example.org", recipients.get(0).email());
    }

    @Test
    void resolveFamilyRecipients_liefertPersonProperties() {
        ObjectId familyId = new ObjectId();
        persistParent(familyId, "Anna", "anna@example.org");

        List<RecipientResolverService.ResolvedRecipient> recipients =
                service.resolveFamilyRecipients(familyId);

        assertEquals("Anna", recipients.get(0).properties().get("firstName"));
    }

    @Test
    void resolveFamilyRecipients_leerBeiNull() {
        assertTrue(service.resolveFamilyRecipients(null).isEmpty());
    }
```

Existiert noch keine passende Hilfsmethode, ergänze sie in derselben Testklasse:

```java
    private void persistParent(ObjectId familyId, String firstName, String email) {
        Person person = new Person();
        person.familyId = familyId;
        person.basicProperties = new java.util.ArrayList<>();
        person.basicProperties.add(fieldRef(personTypeDef.id, persistFieldInstance(personTypeDef.id, "PARENT")));
        person.basicProperties.add(fieldRef(firstNameDef.id, persistFieldInstance(firstNameDef.id, firstName)));
        person.basicProperties.add(fieldRef(emailDef.id, persistFieldInstance(emailDef.id, email)));
        person.persist();
    }

    private FieldRef fieldRef(ObjectId definitionId, ObjectId instanceId) {
        FieldRef ref = new FieldRef();
        ref.definitionId = definitionId;
        ref.fieldInstanceId = instanceId;
        return ref;
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=RecipientResolverServiceTest`
Expected: Kompilierfehler — `resolveFamilyRecipients` existiert nicht.

- [ ] **Step 3: Implement the method**

In `RecipientResolverService`, direkt unter `resolveAllParents()` einfügen:

```java
    /**
     * Alle Eltern einer Familie mit hinterlegter E-Mail-Adresse, samt
     * aufgelösten Person-Properties. Für die Kochdienst-Erinnerung: erinnert
     * wird die ganze Familie, nicht nur die eingetragene Person.
     */
    public List<ResolvedRecipient> resolveFamilyRecipients(ObjectId familyId) {
        if (familyId == null) {
            return List.of();
        }
        Map<ObjectId, Person> deduped = new LinkedHashMap<>();
        for (Person candidate : Person.findByFamilyId(familyId)) {
            if (isParent(candidate) && hasNonBlankEmail(candidate)) {
                deduped.putIfAbsent(candidate.id, candidate);
            }
        }
        List<Person> parents = new ArrayList<>(deduped.values());
        Map<ObjectId, Map<String, String>> propertiesByPersonId = personPropertyResolver.resolve(parents);

        List<ResolvedRecipient> result = new ArrayList<>();
        for (Person parent : parents) {
            String email = resolveEmail(parent);
            if (email == null) continue;
            result.add(new ResolvedRecipient(email, propertiesByPersonId.getOrDefault(parent.id, Map.of())));
        }
        return result;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=RecipientResolverServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/service/RecipientResolverService.java backend/src/test/java/at/kigruapp/service/RecipientResolverServiceTest.java
git commit -m "feat(be): Familien-Empfaenger aufloesen"
```

---

### Task 7: Log-Entity der versendeten Erinnerungen

**Files:**
- Create: `backend/src/main/java/at/kigruapp/entity/CookingReminder.java`
- Create: `backend/src/main/java/at/kigruapp/entity/CookingReminderStatus.java`
- Create: `backend/src/main/java/at/kigruapp/migration/CookingReminderIndexMigration.java`
- Test: `backend/src/test/java/at/kigruapp/entity/CookingReminderTest.java`

**Interfaces:**
- Consumes: nichts.
- Produces:
  - `enum CookingReminderStatus { SENT, FAILED, NO_RECIPIENTS, ACCOUNT_UNAVAILABLE }`
  - `CookingReminder` mit `dutyId : ObjectId`, `dueDate : String`, `dutyDate : String`, `sentAt : Instant`, `status : CookingReminderStatus`, `recipientCount : int`, `error : String` und `static boolean existsFor(ObjectId dutyId, String dueDate)`.
  - Unique-Index `dutyId_1_dueDate_1` auf der Kollektion `cooking_reminders`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/entity/CookingReminderTest.java`:

```java
package at.kigruapp.entity;

import at.kigruapp.migration.CookingReminderIndexMigration;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CookingReminderTest {

    @Inject
    CookingReminderIndexMigration indexMigration;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void setup() {
        CookingReminder.deleteAll();
        indexMigration.run();
    }

    private CookingReminder reminder(ObjectId dutyId, String dueDate) {
        CookingReminder reminder = new CookingReminder();
        reminder.dutyId = dutyId;
        reminder.dueDate = dueDate;
        reminder.dutyDate = "2026-09-15";
        reminder.sentAt = Instant.now();
        reminder.status = CookingReminderStatus.SENT;
        reminder.recipientCount = 2;
        return reminder;
    }

    @Test
    void existsForFindsPersistedReminder() {
        ObjectId dutyId = new ObjectId();
        reminder(dutyId, "2026-09-12").persist();

        assertTrue(CookingReminder.existsFor(dutyId, "2026-09-12"));
        assertFalse(CookingReminder.existsFor(dutyId, "2026-09-13"));
        assertFalse(CookingReminder.existsFor(new ObjectId(), "2026-09-12"));
    }

    @Test
    void uniqueIndexRejectsDuplicateDutyAndDueDate() {
        ObjectId dutyId = new ObjectId();
        reminder(dutyId, "2026-09-12").persist();

        assertThrows(MongoWriteException.class, () -> reminder(dutyId, "2026-09-12").persist());
    }

    @Test
    void sameDutyWithDifferentDueDateIsAllowed() {
        ObjectId dutyId = new ObjectId();
        reminder(dutyId, "2026-09-12").persist();
        reminder(dutyId, "2026-09-14").persist();

        assertEquals(2, CookingReminder.count());
    }

    @Test
    void indexMigrationIsIdempotent() {
        indexMigration.run();
        indexMigration.run();

        long indexCount = 0;
        for (Document index : mongoClient.getDatabase(databaseName)
                .getCollection("cooking_reminders").listIndexes()) {
            if ("dutyId_1_dueDate_1".equals(index.getString("name"))) {
                indexCount++;
            }
        }
        assertEquals(1, indexCount);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=CookingReminderTest`
Expected: Kompilierfehler — `CookingReminder` existiert nicht.

- [ ] **Step 3: Write the status enum and entity**

Create `backend/src/main/java/at/kigruapp/entity/CookingReminderStatus.java`:

```java
package at.kigruapp.entity;

/** Ausgang eines Erinnerungs-Versands für einen einzelnen Kochdienst. */
public enum CookingReminderStatus {
    SENT,
    FAILED,
    NO_RECIPIENTS,
    ACCOUNT_UNAVAILABLE
}
```

Create `backend/src/main/java/at/kigruapp/entity/CookingReminder.java`:

```java
package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * Sende-Log der Kochdienst-Erinnerungen. Der Unique-Index auf
 * (dutyId, dueDate) macht den täglichen Lauf idempotent: derselbe Kochdienst
 * wird für denselben Fälligkeitstag nie zweimal erinnert, ein verschobener
 * Kochdienst bekommt über die neue dueDate wieder eine Erinnerung.
 */
@MongoEntity(collection = "cooking_reminders")
public class CookingReminder extends PanacheMongoEntity {

    /** Id der cookingDuty-FieldInstance. */
    public ObjectId dutyId;
    /** Tag des Versands, yyyy-MM-dd. */
    public String dueDate;
    /** Tag des Kochdienstes, yyyy-MM-dd. */
    public String dutyDate;
    public Instant sentAt;
    public CookingReminderStatus status;
    public int recipientCount;
    public String error;

    public static boolean existsFor(ObjectId dutyId, String dueDate) {
        return count("dutyId = ?1 and dueDate = ?2", dutyId, dueDate) > 0;
    }
}
```

- [ ] **Step 4: Write the index migration**

Create `backend/src/main/java/at/kigruapp/migration/CookingReminderIndexMigration.java`:

```java
package at.kigruapp.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.MongoClient;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Legt den Unique-Index (dutyId, dueDate) auf cooking_reminders an. Läuft bei
 * jedem Start; createIndex ist für einen bereits vorhandenen, identischen
 * Index ein No-Op.
 */
@ApplicationScoped
@Startup
public class CookingReminderIndexMigration {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@Observes StartupEvent ev) {
        run();
    }

    public void run() {
        mongoClient.getDatabase(databaseName)
                .getCollection("cooking_reminders")
                .createIndex(new Document("dutyId", 1).append("dueDate", 1),
                        new IndexOptions().unique(true));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=CookingReminderTest`
Expected: PASS (4 Tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/CookingReminder.java backend/src/main/java/at/kigruapp/entity/CookingReminderStatus.java backend/src/main/java/at/kigruapp/migration/CookingReminderIndexMigration.java backend/src/test/java/at/kigruapp/entity/CookingReminderTest.java
git commit -m "feat(be): Sende-Log fuer Kochdienst-Erinnerungen"
```

---

### Task 8: Täglicher Erinnerungslauf

**Files:**
- Create: `backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java`
- Test: `backend/src/test/java/at/kigruapp/scheduler/CookingReminderRunTest.java`

**Interfaces:**
- Consumes: `CookingReminderSettings` und `CookingReminderSettingsResource.isActive` (Task 1), `CookingReminder`/`CookingReminderStatus` (Task 7), `RecipientResolverService.resolveFamilyRecipients` (Task 6), `MailTemplateRenderer.render(String, Map, Map)` (Task 5), `MailService.sendHtml`.
- Produces: `void runFor(LocalDate today)` — der testbare Kern des Laufs. Task 9 ergänzt die Cron-Registrierung.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/scheduler/CookingReminderRunTest.java`:

```java
package at.kigruapp.scheduler;

import at.kigruapp.entity.*;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.mail.internet.MimeMessage;
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

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CookingReminderRunTest {

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
    MailTemplate template;
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
        CookingReminder.deleteAll();
        CookingReminderSettings.deleteAll();
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

        template = new MailTemplate();
        template.name = "Erinnerung";
        template.bodyHtml = "<p>Hallo {{person.firstName}}, am {{duty.date}} kochst du.</p>";
        template.createdAt = Instant.now();
        template.persist();

        CookingReminderSettings settings = new CookingReminderSettings();
        settings.senderAccountId = account.id.toHexString();
        settings.templateId = template.id.toHexString();
        settings.subject = "Dein Kochdienst";
        settings.sendTime = "07:00";
        settings.updatedAt = Instant.now();
        settings.persist();

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

    /** Hängt einen Kochdienst an die Person und liefert die Instanz-Id zurück. */
    private ObjectId persistDuty(Person person, String date, boolean reminderEnabled, Integer daysBefore) {
        Document value = new Document("date", date).append("groups", List.of("g1"))
                .append("description", "Suppe");
        if (reminderEnabled) {
            value.append("reminderEnabled", true).append("reminderDaysBefore", daysBefore);
        }
        ObjectId instanceId = persistFieldInstance(cookingDutyDef.id, value);
        person.schedules.add(ref(cookingDutyDef.id, instanceId));
        person.update();
        return instanceId;
    }

    @Test
    void sendetErinnerungAnBeideElternWennHeuteFaelligIst() throws Exception {
        Person anna = persistParent("Anna", "anna@example.org");
        persistParent("Bernd", "bernd@example.org");
        ObjectId dutyId = persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        assertTrue(greenMail.waitForIncomingEmail(5000, 2));
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(2, messages.length);
        String body = GreenMailUtil.getBody(messages[0]);
        assertTrue(body.contains("15.09.2026"), "Duty-Datum im Body erwartet, war: " + body);

        CookingReminder log = CookingReminder.find("dutyId", dutyId).firstResult();
        assertNotNull(log);
        assertEquals(CookingReminderStatus.SENT, log.status);
        assertEquals("2026-09-12", log.dueDate);
        assertEquals(2, log.recipientCount);
    }

    @Test
    void sendetNichtsWennHeuteNichtDerFaelligkeitstagIst() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 11));

        assertEquals(0, greenMail.getReceivedMessages().length);
        assertEquals(0, CookingReminder.count());
    }

    @Test
    void sendetNichtsOhneAktivierteErinnerung() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", false, null);

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        assertEquals(0, greenMail.getReceivedMessages().length);
        assertEquals(0, CookingReminder.count());
    }

    @Test
    void sendetNichtZweimalFuerDenselbenTag() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12));
        int afterFirstRun = greenMail.getReceivedMessages().length;

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        assertEquals(afterFirstRun, greenMail.getReceivedMessages().length);
        assertEquals(1, CookingReminder.count());
    }

    @Test
    void verschobenerKochdienstErinnertErneut() {
        Person anna = persistParent("Anna", "anna@example.org");
        ObjectId dutyId = persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        fieldInstances().updateOne(new Document("_id", dutyId),
                new Document("$set", new Document("value.date", "2026-09-22")));

        scheduler.runFor(LocalDate.of(2026, 9, 19));

        assertEquals(2, CookingReminder.count());
    }

    @Test
    void ohneErreichbaresKontoWirdNurGeloggt() {
        account.enabled = false;
        account.update();
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        assertEquals(0, greenMail.getReceivedMessages().length);
        CookingReminder log = CookingReminder.findAll().firstResult();
        assertNotNull(log);
        assertEquals(CookingReminderStatus.ACCOUNT_UNAVAILABLE, log.status);
    }

    @Test
    void familieOhneEmailWirdAlsNoRecipientsGeloggt() {
        Person ohne = persistParent("Ohne", "");
        persistDuty(ohne, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        assertEquals(0, greenMail.getReceivedMessages().length);
        CookingReminder log = CookingReminder.findAll().firstResult();
        assertNotNull(log);
        assertEquals(CookingReminderStatus.NO_RECIPIENTS, log.status);
    }

    @Test
    void ohneKonfigurationPassiertNichts() {
        CookingReminderSettings.deleteAll();
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        assertEquals(0, greenMail.getReceivedMessages().length);
        assertEquals(0, CookingReminder.count());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=CookingReminderRunTest`
Expected: Kompilierfehler — `CookingReminderScheduler` existiert nicht.

- [ ] **Step 3: Write the scheduler core**

Create `backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java`:

```java
package at.kigruapp.scheduler;

import at.kigruapp.entity.CookingReminder;
import at.kigruapp.entity.CookingReminderSettings;
import at.kigruapp.entity.CookingReminderStatus;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailTemplate;
import at.kigruapp.resource.CookingReminderSettingsResource;
import at.kigruapp.service.MailService;
import at.kigruapp.service.MailTemplateRenderer;
import at.kigruapp.service.RecipientResolverService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Täglicher Versand der Kochdienst-Erinnerungen. Der Lauf ist rein
 * datumsgesteuert: erinnert wird ein Kochdienst genau an dem Tag, an dem
 * {@code dutyDate − reminderDaysBefore} auf das Laufdatum fällt. Vergangene
 * Fälligkeiten werden bewusst nicht nachgeholt.
 */
@ApplicationScoped
public class CookingReminderScheduler {

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    RecipientResolverService recipientResolverService;

    @Inject
    MailTemplateRenderer renderer;

    @Inject
    MailService mailService;

    /** Ein fälliger Kochdienst samt der für die Mail nötigen Daten. */
    record DueDuty(ObjectId dutyId, ObjectId familyId, String dutyDate, String dueDate,
                   String description, int daysBefore, List<String> groupIds) {}

    public void runFor(LocalDate today) {
        CookingReminderSettings settings = CookingReminderSettings.findSingleton();
        if (settings == null || settings.senderAccountId == null || settings.templateId == null) {
            return;
        }

        List<DueDuty> due = findDueDuties(today);
        if (due.isEmpty()) {
            return;
        }

        if (!CookingReminderSettingsResource.isActive(settings)) {
            Log.warnf("Kochdienst-Erinnerung: Konto fehlt oder ist deaktiviert, %d faellige Erinnerung(en) entfallen", due.size());
            for (DueDuty duty : due) {
                writeLog(duty, CookingReminderStatus.ACCOUNT_UNAVAILABLE, 0, "Mailkonto fehlt oder ist deaktiviert");
            }
            return;
        }

        MailAccount account = CookingReminderSettingsResource.findAccount(settings.senderAccountId);
        MailTemplate template = CookingReminderSettingsResource.findTemplate(settings.templateId);

        for (DueDuty duty : due) {
            sendOne(duty, account, template, settings.subject);
        }
    }

    /**
     * Ein Fehler bei einem Kochdienst darf die übrigen nicht verhindern, daher
     * wird pro Kochdienst gefangen und geloggt.
     */
    private void sendOne(DueDuty duty, MailAccount account, MailTemplate template, String subject) {
        try {
            List<RecipientResolverService.ResolvedRecipient> recipients =
                    recipientResolverService.resolveFamilyRecipients(duty.familyId());
            if (recipients.isEmpty()) {
                writeLog(duty, CookingReminderStatus.NO_RECIPIENTS, 0, null);
                return;
            }

            Map<String, String> dutyProperties = buildDutyProperties(duty);
            int successCount = 0;
            String lastError = null;
            for (RecipientResolverService.ResolvedRecipient recipient : recipients) {
                try {
                    String html = renderer.render(template.bodyHtml, recipient.properties(), dutyProperties);
                    mailService.sendHtml(account, recipient.email(), subject, html);
                    successCount++;
                } catch (Exception e) {
                    lastError = e.getMessage();
                    Log.errorf(e, "Kochdienst-Erinnerung an %s fehlgeschlagen: %s", recipient.email(), e.getMessage());
                }
            }

            if (successCount == recipients.size()) {
                writeLog(duty, CookingReminderStatus.SENT, successCount, null);
            } else {
                writeLog(duty, CookingReminderStatus.FAILED, successCount,
                        (recipients.size() - successCount) + " von " + recipients.size()
                                + " fehlgeschlagen; letzter Fehler: " + lastError);
            }
        } catch (Exception e) {
            Log.errorf(e, "Kochdienst-Erinnerung fuer %s fehlgeschlagen: %s", duty.dutyId(), e.getMessage());
            writeLog(duty, CookingReminderStatus.FAILED, 0, e.getMessage());
        }
    }

    private Map<String, String> buildDutyProperties(DueDuty duty) {
        Map<String, String> properties = new HashMap<>();
        properties.put("date", LocalDate.parse(duty.dutyDate()).format(DISPLAY_DATE));
        properties.put("description", duty.description() == null ? "" : duty.description());
        properties.put("daysBefore", String.valueOf(duty.daysBefore()));
        properties.put("groups", resolveGroupLabels(duty.groupIds()));
        properties.put("personName", resolvePersonName(duty.dutyId()));
        return properties;
    }

    /**
     * Der Log-Eintrag ist zugleich die Idempotenz-Sperre. Verliert dieser
     * Insert gegen einen parallelen Lauf (Unique-Index), ist die Erinnerung
     * bereits verbucht und der Fehler wird verschluckt.
     */
    private void writeLog(DueDuty duty, CookingReminderStatus status, int recipientCount, String error) {
        CookingReminder reminder = new CookingReminder();
        reminder.dutyId = duty.dutyId();
        reminder.dueDate = duty.dueDate();
        reminder.dutyDate = duty.dutyDate();
        reminder.sentAt = Instant.now();
        reminder.status = status;
        reminder.recipientCount = recipientCount;
        reminder.error = error;
        try {
            reminder.persist();
        } catch (Exception e) {
            Log.warnf("Kochdienst-Erinnerung: Log-Eintrag fuer %s/%s bereits vorhanden", duty.dutyId(), duty.dueDate());
        }
    }

    /**
     * Sucht alle Kochdienste mit aktivierter Erinnerung, deren Fälligkeitstag
     * heute ist und für die noch kein Log-Eintrag existiert.
     */
    List<DueDuty> findDueDuties(LocalDate today) {
        FieldDefinition cookingDutyDef = FieldDefinition.find("fieldName", "cookingDuty").firstResult();
        if (cookingDutyDef == null) {
            return List.of();
        }
        String dueDate = today.toString();

        List<DueDuty> result = new ArrayList<>();
        MongoCollection<Document> instances = fieldInstances();
        for (Document doc : instances.find(Filters.and(
                Filters.eq("definitionId", cookingDutyDef.id),
                Filters.eq("value.reminderEnabled", true)))) {

            Object valueObj = doc.get("value");
            if (!(valueObj instanceof Document value)) continue;

            String dutyDate = value.getString("date");
            Object daysObj = value.get("reminderDaysBefore");
            if (dutyDate == null || !(daysObj instanceof Number days)) continue;

            LocalDate parsedDutyDate;
            try {
                parsedDutyDate = LocalDate.parse(dutyDate);
            } catch (DateTimeParseException e) {
                continue;
            }
            if (!parsedDutyDate.minusDays(days.intValue()).equals(today)) continue;

            ObjectId dutyId = doc.getObjectId("_id");
            if (CookingReminder.existsFor(dutyId, dueDate)) continue;

            ObjectId familyId = resolveFamilyId(dutyId);
            if (familyId == null) continue;

            List<String> groupIds = new ArrayList<>();
            Object groupsObj = value.get("groups");
            if (groupsObj instanceof List<?> list) {
                for (Object group : list) {
                    groupIds.add(group.toString());
                }
            }

            result.add(new DueDuty(dutyId, familyId, dutyDate, dueDate,
                    value.getString("description"), days.intValue(), groupIds));
        }
        return result;
    }

    /** Die Familie hängt am Person-Dokument, das den Kochdienst in schedules führt. */
    private ObjectId resolveFamilyId(ObjectId dutyInstanceId) {
        Document person = persons().find(Filters.eq("schedules.fieldInstanceId", dutyInstanceId)).first();
        return person == null ? null : person.getObjectId("familyId");
    }

    private String resolvePersonName(ObjectId dutyInstanceId) {
        Document person = persons().find(Filters.eq("schedules.fieldInstanceId", dutyInstanceId)).first();
        if (person == null) {
            return "";
        }
        String firstName = readBasicProperty(person, "firstName");
        String lastName = readBasicProperty(person, "lastName");
        return (firstName + " " + lastName).trim();
    }

    private String readBasicProperty(Document person, String fieldName) {
        FieldDefinition def = FieldDefinition.find("fieldName", fieldName).firstResult();
        if (def == null) {
            return "";
        }
        Object basicProperties = person.get("basicProperties");
        if (!(basicProperties instanceof List<?> refs)) {
            return "";
        }
        for (Object refObj : refs) {
            if (!(refObj instanceof Document ref)) continue;
            if (!def.id.equals(ref.getObjectId("definitionId"))) continue;
            Document instance = fieldInstances().find(Filters.eq("_id", ref.getObjectId("fieldInstanceId"))).first();
            if (instance != null && instance.get("value") != null) {
                return instance.get("value").toString();
            }
        }
        return "";
    }

    private String resolveGroupLabels(List<String> groupIds) {
        List<String> labels = new ArrayList<>();
        for (String groupId : groupIds) {
            if (!ObjectId.isValid(groupId)) continue;
            Document instance = fieldInstances().find(Filters.eq("_id", new ObjectId(groupId))).first();
            if (instance == null) continue;
            Object value = instance.get("value");
            if (value instanceof Document valueDoc) {
                Object label = valueDoc.get("label");
                if (label instanceof Document labelDoc && labelDoc.getString("de") != null) {
                    labels.add(labelDoc.getString("de"));
                    continue;
                }
            }
            if (value != null) {
                labels.add(value.toString());
            }
        }
        return String.join(", ", labels);
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private MongoCollection<Document> persons() {
        return mongoClient.getDatabase(databaseName).getCollection("persons");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=CookingReminderRunTest`
Expected: PASS (8 Tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java backend/src/test/java/at/kigruapp/scheduler/CookingReminderRunTest.java
git commit -m "feat(be): taeglicher Erinnerungslauf fuer Kochdienste"
```

---

### Task 9: Cron-Registrierung, Start-Rearm und Neuplanung beim Speichern

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java` (Registrierung ergänzen)
- Create: `backend/src/main/java/at/kigruapp/scheduler/CookingReminderStartupRearmer.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/CookingReminderSettingsResource.java` (Neuplanung nach `PUT`)
- Test: `backend/src/test/java/at/kigruapp/scheduler/CookingReminderSchedulerTest.java`

**Interfaces:**
- Consumes: `CookingReminderScheduler.runFor` (Task 8), `io.quarkus.scheduler.Scheduler`.
- Produces:
  - `CookingReminderScheduler.JOB_ID : String` (`"cooking-reminder-daily"`)
  - `void reschedule()` — liest die Einstellungen und registriert den Cron neu; ist keine Zeit gesetzt, gilt `07:00`.
  - `static String toCron(String sendTime)` — `"18:30"` wird zu `"0 30 18 * * ?"`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/at/kigruapp/scheduler/CookingReminderSchedulerTest.java`:

```java
package at.kigruapp.scheduler;

import at.kigruapp.entity.CookingReminderSettings;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class CookingReminderSchedulerTest {

    @Inject
    CookingReminderScheduler reminderScheduler;

    @Inject
    Scheduler scheduler;

    @BeforeEach
    void setup() {
        CookingReminderSettings.deleteAll();
        if (scheduler.getScheduledJob(CookingReminderScheduler.JOB_ID) != null) {
            scheduler.unscheduleJob(CookingReminderScheduler.JOB_ID);
        }
    }

    @Test
    void toCronBildetUhrzeitAb() {
        assertEquals("0 30 18 * * ?", CookingReminderScheduler.toCron("18:30"));
        assertEquals("0 0 7 * * ?", CookingReminderScheduler.toCron("07:00"));
        assertEquals("0 0 7 * * ?", CookingReminderScheduler.toCron(null));
        assertEquals("0 0 7 * * ?", CookingReminderScheduler.toCron("kaputt"));
    }

    @Test
    void rescheduleRegistriertJob() {
        CookingReminderSettings settings = new CookingReminderSettings();
        settings.sendTime = "18:30";
        settings.updatedAt = Instant.now();
        settings.persist();

        reminderScheduler.reschedule();

        assertNotNull(scheduler.getScheduledJob(CookingReminderScheduler.JOB_ID));
    }

    @Test
    void rescheduleIstIdempotent() {
        CookingReminderSettings settings = new CookingReminderSettings();
        settings.sendTime = "07:00";
        settings.updatedAt = Instant.now();
        settings.persist();

        reminderScheduler.reschedule();
        reminderScheduler.reschedule();

        assertNotNull(scheduler.getScheduledJob(CookingReminderScheduler.JOB_ID));
    }

    @Test
    void rescheduleOhneEinstellungenNutztStandardzeit() {
        reminderScheduler.reschedule();

        assertNotNull(scheduler.getScheduledJob(CookingReminderScheduler.JOB_ID));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=CookingReminderSchedulerTest`
Expected: Kompilierfehler — `JOB_ID`, `toCron` und `reschedule` existieren nicht.

- [ ] **Step 3: Add registration to the scheduler**

In `CookingReminderScheduler` ergänzen (Imports `io.quarkus.scheduler.Scheduler`, `java.time.ZoneId`, `java.time.LocalTime`, `java.time.format.DateTimeParseException` sind teils schon vorhanden):

```java
    public static final String JOB_ID = "cooking-reminder-daily";

    private static final String TIMEZONE = "Europe/Vienna";

    @Inject
    Scheduler scheduler;

    /** "18:30" -> "0 30 18 * * ?". Unlesbare oder fehlende Zeiten fallen auf 07:00 zurück. */
    public static String toCron(String sendTime) {
        LocalTime time = LocalTime.of(7, 0);
        if (sendTime != null) {
            try {
                time = LocalTime.parse(sendTime);
            } catch (DateTimeParseException ignored) {
                // Fallback bleibt 07:00
            }
        }
        return "0 " + time.getMinute() + " " + time.getHour() + " * * ?";
    }

    /** Registriert den täglichen Lauf neu. Idempotent — hebt eine bestehende Registrierung vorher auf. */
    public void reschedule() {
        CookingReminderSettings settings = CookingReminderSettings.findSingleton();
        String cron = toCron(settings == null ? null : settings.sendTime);
        if (scheduler.getScheduledJob(JOB_ID) != null) {
            scheduler.unscheduleJob(JOB_ID);
        }
        scheduler.newJob(JOB_ID)
                .setCron(cron)
                .setTimeZone(TIMEZONE)
                .setTask(ctx -> runFor(LocalDate.now(ZoneId.of(TIMEZONE))))
                .schedule();
        Log.infof("Kochdienst-Erinnerung: taeglicher Lauf registriert (%s, %s)", cron, TIMEZONE);
    }
```

- [ ] **Step 4: Write the startup rearmer**

Create `backend/src/main/java/at/kigruapp/scheduler/CookingReminderStartupRearmer.java`:

```java
package at.kigruapp.scheduler;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Registriert den täglichen Erinnerungslauf bei jedem Start neu —
 * programmatische Schedules überleben einen Neustart nicht. Analog
 * {@link MailJobStartupRearmer}.
 */
@ApplicationScoped
public class CookingReminderStartupRearmer {

    @Inject
    CookingReminderScheduler cookingReminderScheduler;

    void onStart(@Observes StartupEvent ev) {
        cookingReminderScheduler.reschedule();
    }
}
```

- [ ] **Step 5: Reschedule after saving the settings**

In `CookingReminderSettingsResource` ergänzen:

```java
    @jakarta.inject.Inject
    at.kigruapp.scheduler.CookingReminderScheduler cookingReminderScheduler;
```

und in `save(...)` direkt vor `return toDto(settings);`:

```java
        cookingReminderScheduler.reschedule();
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `.\mvnw.cmd test -Dtest=CookingReminderSchedulerTest`
Expected: PASS (4 Tests)

Run: `.\mvnw.cmd test -Dtest=CookingReminderSettingsResourceTest`
Expected: PASS (weiterhin 7 Tests)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/scheduler/CookingReminderScheduler.java backend/src/main/java/at/kigruapp/scheduler/CookingReminderStartupRearmer.java backend/src/main/java/at/kigruapp/resource/CookingReminderSettingsResource.java backend/src/test/java/at/kigruapp/scheduler/CookingReminderSchedulerTest.java
git commit -m "feat(be): Cron-Registrierung fuer Kochdienst-Erinnerungen"
```

---

### Task 10: Frontend-Model und -Service

**Files:**
- Create: `frontend/src/app/shared/models/cooking-reminder-settings.model.ts`
- Create: `frontend/src/app/shared/services/cooking-reminder-settings.service.ts`
- Test: `frontend/src/app/shared/services/cooking-reminder-settings.service.spec.ts`
- Modify: `frontend/src/app/shared/models/organisation.model.ts` (Interface `CookingDutyDTO` um die Erinnerungsfelder erweitern)

**Interfaces:**
- Consumes: Endpunkt aus Task 1, DTO-Felder aus Task 4, bestehender `ApiService`.
- Produces:
  - `interface CookingReminderSettings { senderAccountId: string | null; templateId: string | null; subject: string | null; sendTime: string; active: boolean; }`
  - `CookingReminderSettingsService.get(): Observable<CookingReminderSettings>` und `save(settings): Observable<CookingReminderSettings>`
  - `CookingDutyDTO.reminderEnabled: boolean`, `CookingDutyDTO.reminderDaysBefore: number | null`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/shared/services/cooking-reminder-settings.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { CookingReminderSettingsService } from './cooking-reminder-settings.service';
import { ApiService } from '../../core/services/api.service';

describe('CookingReminderSettingsService', () => {
  let service: CookingReminderSettingsService;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj('ApiService', ['get', 'put']);
    TestBed.configureTestingModule({
      providers: [CookingReminderSettingsService, { provide: ApiService, useValue: api }],
    });
    service = TestBed.inject(CookingReminderSettingsService);
  });

  it('liest die Einstellungen von /cooking-reminder-settings', () => {
    api.get.and.returnValue(of({ senderAccountId: null, templateId: null, subject: null, sendTime: '07:00', active: false }));

    service.get().subscribe();

    expect(api.get).toHaveBeenCalledWith('/cooking-reminder-settings');
  });

  it('speichert die Einstellungen per PUT', () => {
    const settings = { senderAccountId: 'a1', templateId: 't1', subject: 'Betreff', sendTime: '18:30', active: true };
    api.put.and.returnValue(of(settings));

    service.save(settings).subscribe();

    expect(api.put).toHaveBeenCalledWith('/cooking-reminder-settings', settings);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/cooking-reminder-settings.service.spec.ts` (aus `frontend/`)
Expected: FAIL — Modul `./cooking-reminder-settings.service` nicht gefunden.

- [ ] **Step 3: Write model and service**

Create `frontend/src/app/shared/models/cooking-reminder-settings.model.ts`:

```typescript
export interface CookingReminderSettings {
  senderAccountId: string | null;
  templateId: string | null;
  subject: string | null;
  /** Versandzeit im Format HH:mm, Zeitzone Europe/Vienna. */
  sendTime: string;
  /** Vom Backend abgeleitet: Konto und Vorlage gesetzt und nutzbar. */
  active: boolean;
}
```

Create `frontend/src/app/shared/services/cooking-reminder-settings.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { CookingReminderSettings } from '../models/cooking-reminder-settings.model';

@Injectable({ providedIn: 'root' })
export class CookingReminderSettingsService {
  constructor(private api: ApiService) {}

  get(): Observable<CookingReminderSettings> {
    return this.api.get<CookingReminderSettings>('/cooking-reminder-settings');
  }

  save(settings: CookingReminderSettings): Observable<CookingReminderSettings> {
    return this.api.put<CookingReminderSettings>('/cooking-reminder-settings', settings);
  }
}
```

- [ ] **Step 4: Extend CookingDutyDTO**

In `frontend/src/app/shared/models/organisation.model.ts` im Interface `CookingDutyDTO` ergänzen:

```typescript
  reminderEnabled: boolean;
  reminderDaysBefore: number | null;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/cooking-reminder-settings.service.spec.ts`
Expected: PASS (2 Tests)

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/models/cooking-reminder-settings.model.ts frontend/src/app/shared/services/cooking-reminder-settings.service.ts frontend/src/app/shared/services/cooking-reminder-settings.service.spec.ts frontend/src/app/shared/models/organisation.model.ts
git commit -m "feat(fe): Service fuer Erinnerungs-Einstellungen"
```

---

### Task 11: Admin-Sektion in der Kochdienst-Maske

**Files:**
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.html` (Tab „Dienst-Einstellungen", unter der Tabelle der Essen-Eigenschaften)
- Test: `frontend/src/app/settings/organisation/cooking-reminder-settings.spec.ts`

**Interfaces:**
- Consumes: `CookingReminderSettingsService` (Task 10), bestehende `MailAccountService` und `MailTemplateService`, bestehender `NotificationService`.
- Produces: in `OrganisationComponent` die Felder `mailAccounts`, `mailTemplates`, `reminderSettingsActive` und `reminderForm` (`FormGroup` mit `senderAccountId`, `templateId`, `subject`, `sendTime`) sowie die Methode `saveReminderSettings()`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/settings/organisation/cooking-reminder-settings.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { OrganisationComponent } from './organisation.component';
import { CookingReminderSettingsService } from '../../shared/services/cooking-reminder-settings.service';
import { MailAccountService } from '../../shared/services/mail-account.service';
import { MailTemplateService } from '../../shared/services/mail-template.service';

describe('OrganisationComponent — Erinnerungs-Einstellungen', () => {
  let fixture: ComponentFixture<OrganisationComponent>;
  let component: OrganisationComponent;
  let reminderService: jasmine.SpyObj<CookingReminderSettingsService>;

  beforeEach(async () => {
    reminderService = jasmine.createSpyObj('CookingReminderSettingsService', ['get', 'save']);
    reminderService.get.and.returnValue(of({
      senderAccountId: 'a1', templateId: 't1', subject: 'Betreff', sendTime: '18:30', active: true,
    }));
    reminderService.save.and.returnValue(of({
      senderAccountId: 'a1', templateId: 't1', subject: 'Betreff', sendTime: '18:30', active: true,
    }));

    const mailAccountService = jasmine.createSpyObj('MailAccountService', ['list']);
    mailAccountService.list.and.returnValue(of([{ id: 'a1', name: 'Kiga', enabled: true }]));

    const mailTemplateService = jasmine.createSpyObj('MailTemplateService', ['list']);
    mailTemplateService.list.and.returnValue(of([{ id: 't1', name: 'Erinnerung' }]));

    await TestBed.configureTestingModule({
      imports: [OrganisationComponent, NoopAnimationsModule],
      providers: [
        { provide: CookingReminderSettingsService, useValue: reminderService },
        { provide: MailAccountService, useValue: mailAccountService },
        { provide: MailTemplateService, useValue: mailTemplateService },
      ],
    })
      .compileComponents();

    fixture = TestBed.createComponent(OrganisationComponent);
    component = fixture.componentInstance;
  });

  it('laedt die Einstellungen in das Formular', () => {
    component.loadReminderSettings();

    expect(component.reminderForm.value.senderAccountId).toBe('a1');
    expect(component.reminderForm.value.sendTime).toBe('18:30');
    expect(component.reminderSettingsActive).toBeTrue();
  });

  it('laedt Mailkonten und Vorlagen fuer die Auswahl', () => {
    component.loadReminderSettings();

    expect(component.mailAccounts.length).toBe(1);
    expect(component.mailTemplates.length).toBe(1);
  });

  it('speichert die Einstellungen', () => {
    component.loadReminderSettings();
    component.reminderForm.patchValue({ subject: 'Neu' });

    component.saveReminderSettings();

    expect(reminderService.save).toHaveBeenCalled();
    const payload = reminderService.save.calls.mostRecent().args[0];
    expect(payload.subject).toBe('Neu');
  });
});
```

Falls `OrganisationComponent` in `ngOnInit` weitere Dienste anspricht, die im Test nicht gestellt sind: Der Test ruft bewusst nur `loadReminderSettings()` statt `fixture.detectChanges()`, damit `ngOnInit` nicht anläuft. Bleibt die Erzeugung der Komponente dennoch an einem fehlenden Dienst hängen, ergänze für diesen ebenfalls ein `jasmine.createSpyObj` mit `of([])`-Rückgaben.

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/cooking-reminder-settings.spec.ts`
Expected: FAIL — `loadReminderSettings` existiert nicht.

- [ ] **Step 3: Extend the component**

In `organisation.component.ts` die Importe ergänzen:

```typescript
import { CookingReminderSettingsService } from '../../shared/services/cooking-reminder-settings.service';
import { MailAccountService } from '../../shared/services/mail-account.service';
import { MailTemplateService } from '../../shared/services/mail-template.service';
import { MailAccount } from '../../shared/models/mail-account.model';
import { MailTemplate } from '../../shared/models/mail-template.model';
```

In der Klasse, bei den übrigen Feldern des Dienst-Einstellungen-Tabs:

```typescript
  // Kochdienst-Erinnerungen
  mailAccounts: MailAccount[] = [];
  mailTemplates: MailTemplate[] = [];
  reminderSettingsActive = false;
  reminderForm = new FormGroup({
    senderAccountId: new FormControl<string | null>(null),
    templateId: new FormControl<string | null>(null),
    subject: new FormControl<string>(''),
    sendTime: new FormControl<string>('07:00', Validators.required),
  });
```

Die drei Dienste in den Konstruktor aufnehmen:

```typescript
    private cookingReminderSettingsService: CookingReminderSettingsService,
    private mailAccountService: MailAccountService,
    private mailTemplateService: MailTemplateService,
```

Und die Methoden ergänzen:

```typescript
  loadReminderSettings(): void {
    this.mailAccountService.list().subscribe((accounts) => {
      this.mailAccounts = accounts.filter((a) => a.enabled);
    });
    this.mailTemplateService.list().subscribe((templates) => {
      this.mailTemplates = templates;
    });
    this.cookingReminderSettingsService.get().subscribe((settings) => {
      this.reminderSettingsActive = settings.active;
      this.reminderForm.patchValue({
        senderAccountId: settings.senderAccountId,
        templateId: settings.templateId,
        subject: settings.subject ?? '',
        sendTime: settings.sendTime,
      });
    });
  }

  saveReminderSettings(): void {
    const value = this.reminderForm.value;
    this.cookingReminderSettingsService.save({
      senderAccountId: value.senderAccountId ?? null,
      templateId: value.templateId ?? null,
      subject: value.subject ?? null,
      sendTime: value.sendTime ?? '07:00',
      active: false,
    }).subscribe({
      next: (saved) => {
        this.reminderSettingsActive = saved.active;
        this.notificationService.success('Erinnerungs-Einstellungen gespeichert');
      },
      error: (err) => {
        this.notificationService.error(err?.error?.message ?? 'Speichern fehlgeschlagen');
      },
    });
  }
```

Ist der `NotificationService` in dieser Komponente noch nicht eingebunden, importiere ihn aus `../../shared/services/notification.service` und nimm ihn als `private notificationService: NotificationService` in den Konstruktor auf. Weichen die Methodennamen ab, verwende die dort tatsächlich vorhandenen Erfolgs- und Fehlermethoden.

In `ngOnInit` den Aufruf `this.loadReminderSettings();` ergänzen.

- [ ] **Step 4: Extend the template**

In `organisation.component.html` im Tab *Dienst-Einstellungen*, nach der Tabelle der Essen-Eigenschaften (nach dem schließenden `</table>`) einfügen:

```html
        <h3>Kochdienst — Erinnerungen</h3>

        @if (!reminderSettingsActive) {
          <p class="hint">Erinnerungen sind deaktiviert. Waehle ein Mailkonto und eine Vorlage, damit Eltern beim Anlegen eines Kochdienstes eine Erinnerung aktivieren koennen.</p>
        }

        <form [formGroup]="reminderForm" (ngSubmit)="saveReminderSettings()" class="add-form">
          <div class="row">
            <mat-form-field appearance="outline">
              <mat-label>Mailkonto</mat-label>
              <mat-select formControlName="senderAccountId">
                <mat-option [value]="null">— keine —</mat-option>
                @for (acc of mailAccounts; track acc.id) {
                  <mat-option [value]="acc.id">{{ acc.name }}</mat-option>
                }
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Mail-Vorlage</mat-label>
              <mat-select formControlName="templateId">
                <mat-option [value]="null">— keine —</mat-option>
                @for (tpl of mailTemplates; track tpl.id) {
                  <mat-option [value]="tpl.id">{{ tpl.name }}</mat-option>
                }
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Betreff</mat-label>
              <input matInput formControlName="subject">
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Versandzeit</mat-label>
              <input matInput type="time" formControlName="sendTime">
            </mat-form-field>

            <button mat-raised-button color="primary" type="submit" [disabled]="!reminderForm.valid">
              Speichern
            </button>
          </div>
        </form>

        <p class="hint">
          Verfuegbare Platzhalter in der Vorlage:
          <code>&#123;&#123;duty.date&#125;&#125;</code>,
          <code>&#123;&#123;duty.groups&#125;&#125;</code>,
          <code>&#123;&#123;duty.description&#125;&#125;</code>,
          <code>&#123;&#123;duty.daysBefore&#125;&#125;</code>,
          <code>&#123;&#123;duty.personName&#125;&#125;</code>,
          <code>&#123;&#123;person.firstName&#125;&#125;</code>,
          <code>&#123;&#123;person.lastName&#125;&#125;</code>
        </p>
```

- [ ] **Step 5: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/cooking-reminder-settings.spec.ts`
Expected: PASS (3 Tests)

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/settings/organisation/organisation.component.ts frontend/src/app/settings/organisation/organisation.component.html frontend/src/app/settings/organisation/cooking-reminder-settings.spec.ts
git commit -m "feat(fe): Admin-Sektion fuer Kochdienst-Erinnerungen"
```

---

### Task 12: Erinnerung im Kochdienst-Dialog

Diese Task korrigiert nebenbei einen bestehenden Fehler: `cookingDutyDefId` in `cooking.component.ts` wird nie befüllt, `create` und `update` senden deshalb eine leere `definitionId`. Ohne diese Korrektur lässt sich das Feature nicht von Hand abnehmen.

**Files:**
- Modify: `frontend/src/app/cooking/cooking-duty-dialog.component.ts`
- Modify: `frontend/src/app/cooking/cooking-duty-dialog.component.html`
- Modify: `frontend/src/app/cooking/cooking-duty-dialog.component.scss`
- Modify: `frontend/src/app/cooking/cooking.component.ts`
- Test: `frontend/src/app/cooking/cooking-duty-dialog.component.spec.ts`

**Interfaces:**
- Consumes: `CookingReminderSettingsService` (Task 10), `FieldDefinitionService` (bestehend, für die `cookingDuty`-Definition).
- Produces:
  - `CookingDutyDialogData.reminderAvailable : boolean`
  - `CookingDutyDialogResult.reminderEnabled : boolean`, `CookingDutyDialogResult.reminderDaysBefore : number | null`
  - `CookingDutyDialogComponent.reminderDate : string | null` (Klartextdatum) und `reminderInPast : boolean`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/cooking/cooking-duty-dialog.component.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNativeDateAdapter } from '@angular/material/core';
import {
  CookingDutyDialogComponent,
  CookingDutyDialogData,
} from './cooking-duty-dialog.component';

describe('CookingDutyDialogComponent — Erinnerung', () => {
  let fixture: ComponentFixture<CookingDutyDialogComponent>;
  let component: CookingDutyDialogComponent;

  const baseData: CookingDutyDialogData = {
    groups: [],
    foodProperties: [],
    familyParents: [],
    currentUserId: 'p1',
    canEdit: true,
    reminderAvailable: true,
  };

  async function createComponent(data: CookingDutyDialogData): Promise<void> {
    await TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [CookingDutyDialogComponent, NoopAnimationsModule],
      providers: [
        provideNativeDateAdapter(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: jasmine.createSpyObj('MatDialogRef', ['close']) },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CookingDutyDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('zeigt die Checkbox nur bei aktiver Erinnerungsfunktion', async () => {
    await createComponent({ ...baseData, reminderAvailable: false });

    expect(fixture.nativeElement.querySelector('[data-testid="reminder-toggle"]')).toBeNull();

    await createComponent(baseData);

    expect(fixture.nativeElement.querySelector('[data-testid="reminder-toggle"]')).not.toBeNull();
  });

  it('blendet das Tage-Feld erst nach dem Anhaken ein, vorbelegt mit 3', async () => {
    await createComponent(baseData);

    expect(fixture.nativeElement.querySelector('[data-testid="reminder-days"]')).toBeNull();

    component.form.patchValue({ reminderEnabled: true });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="reminder-days"]')).not.toBeNull();
    expect(component.form.value.reminderDaysBefore).toBe(3);
  });

  it('erzwingt die Grenzen 1 und 14', async () => {
    await createComponent(baseData);
    component.form.patchValue({ reminderEnabled: true });

    component.form.patchValue({ reminderDaysBefore: 0 });
    expect(component.form.get('reminderDaysBefore')!.valid).toBeFalse();

    component.form.patchValue({ reminderDaysBefore: 15 });
    expect(component.form.get('reminderDaysBefore')!.valid).toBeFalse();

    component.form.patchValue({ reminderDaysBefore: 14 });
    expect(component.form.get('reminderDaysBefore')!.valid).toBeTrue();
  });

  it('berechnet das Erinnerungsdatum aus Dienstdatum und Vorlaufzeit', async () => {
    await createComponent(baseData);
    const inTwentyDays = new Date();
    inTwentyDays.setDate(inTwentyDays.getDate() + 20);

    component.form.patchValue({ date: inTwentyDays, reminderEnabled: true, reminderDaysBefore: 5 });

    const expected = new Date(inTwentyDays);
    expected.setDate(expected.getDate() - 5);
    expect(component.reminderDate).toContain(String(expected.getFullYear()));
    expect(component.reminderInPast).toBeFalse();
  });

  it('warnt, wenn das Erinnerungsdatum in der Vergangenheit liegt', async () => {
    await createComponent(baseData);
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);

    component.form.patchValue({ date: tomorrow, reminderEnabled: true, reminderDaysBefore: 5 });
    fixture.detectChanges();

    expect(component.reminderInPast).toBeTrue();
    expect(fixture.nativeElement.querySelector('[data-testid="reminder-warning"]')).not.toBeNull();
  });

  it('uebernimmt gespeicherte Werte beim Bearbeiten', async () => {
    await createComponent({
      ...baseData,
      existingDuty: {
        id: 'd1', personId: 'p1', familyId: 'f1', personName: 'Anna',
        date: '2026-09-15', groups: [], description: '', foodProperties: {},
        reminderEnabled: true, reminderDaysBefore: 7,
      },
    });

    expect(component.form.value.reminderEnabled).toBeTrue();
    expect(component.form.value.reminderDaysBefore).toBe(7);
  });

  it('liefert die Erinnerungswerte im Ergebnis', async () => {
    await createComponent(baseData);
    const inTenDays = new Date();
    inTenDays.setDate(inTenDays.getDate() + 10);
    component.form.patchValue({ date: inTenDays, person: 'p1', reminderEnabled: true, reminderDaysBefore: 4 });

    component.save();

    const dialogRef = TestBed.inject(MatDialogRef) as unknown as jasmine.SpyObj<MatDialogRef<CookingDutyDialogComponent>>;
    expect(dialogRef.close).toHaveBeenCalled();
    const result = dialogRef.close.calls.mostRecent().args[0];
    expect(result.reminderEnabled).toBeTrue();
    expect(result.reminderDaysBefore).toBe(4);
  });
});
```

Der Test lässt `hasSelectedGroups()` außer Acht, weil `groups` leer ist; ist `save()` dadurch blockiert, ergänze im Test eine Gruppe in `baseData.groups` und hake sie über `component.form.patchValue({ ['group_' + id]: true })` an.

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/cooking-duty-dialog.component.spec.ts`
Expected: FAIL — `reminderAvailable` ist kein bekanntes Feld der Dialogdaten.

- [ ] **Step 3: Extend the dialog component**

In `cooking-duty-dialog.component.ts`:

Interface `CookingDutyDialogData` um `reminderAvailable: boolean;` erweitern, `CookingDutyDialogResult` um

```typescript
  reminderEnabled: boolean;
  reminderDaysBefore: number | null;
```

In der Klasse ergänzen:

```typescript
  /** Klartextdatum der Erinnerung, null solange Datum oder Vorlaufzeit fehlen. */
  reminderDate: string | null = null;
  reminderInPast = false;
```

In `ngOnInit` das Formular um die beiden Controls erweitern und die Berechnung anstoßen:

```typescript
    this.form.addControl('reminderEnabled', new FormControl(duty?.reminderEnabled ?? false));
    this.form.addControl('reminderDaysBefore', new FormControl(
      duty?.reminderDaysBefore ?? 3,
      [Validators.required, Validators.min(1), Validators.max(14)],
    ));

    this.updateReminderPreview();
    this.form.valueChanges.subscribe(() => this.updateReminderPreview());
```

`this.updateReminderPreview()` und das Abonnement müssen vor dem abschließenden `if (!this.canEdit) { this.form.disable(); }` stehen.

Und die Berechnung als Methode:

```typescript
  /**
   * Erinnerungstag = Dienstdatum minus Vorlaufzeit. Liegt er vor heute, wird
   * nichts versendet — der Dialog weist darauf hin, blockiert das Speichern
   * aber nicht.
   */
  private updateReminderPreview(): void {
    const date: Date | null = this.form.get('date')?.value ?? null;
    const days: number | null = this.form.get('reminderDaysBefore')?.value ?? null;
    const enabled: boolean = this.form.get('reminderEnabled')?.value ?? false;

    if (!enabled || !date || !days) {
      this.reminderDate = null;
      this.reminderInPast = false;
      return;
    }

    const reminder = new Date(date);
    reminder.setDate(reminder.getDate() - days);
    reminder.setHours(0, 0, 0, 0);

    const today = new Date();
    today.setHours(0, 0, 0, 0);

    this.reminderInPast = reminder.getTime() < today.getTime();
    this.reminderDate = reminder.toLocaleDateString('de-AT', {
      weekday: 'short', day: '2-digit', month: '2-digit', year: 'numeric',
    });
  }
```

In `save()` das Ergebnis erweitern — vor dem `dialogRef.close`:

```typescript
    const reminderEnabled: boolean = this.form.get('reminderEnabled')?.value ?? false;
    const reminderDaysBefore: number | null = reminderEnabled
      ? (this.form.get('reminderDaysBefore')?.value ?? null)
      : null;
```

und im geschlossenen Objekt ergänzen:

```typescript
      reminderEnabled,
      reminderDaysBefore,
```

- [ ] **Step 4: Extend the dialog template**

In `cooking-duty-dialog.component.html`, nach dem Block „Essen ist" und vor `</form>` einfügen:

```html
    @if (data.reminderAvailable) {
      <div class="reminder-section">
        <mat-checkbox formControlName="reminderEnabled" data-testid="reminder-toggle">
          Erinnerung aktivieren
        </mat-checkbox>

        @if (form.get('reminderEnabled')?.value) {
          <div class="reminder-details">
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Tage vorher</mat-label>
              <input matInput type="number" min="1" max="14"
                     formControlName="reminderDaysBefore" data-testid="reminder-days">
            </mat-form-field>

            @if (reminderInPast) {
              <p class="reminder-warning" data-testid="reminder-warning">
                Erinnerung liegt in der Vergangenheit — es wird keine Mail versendet.
              </p>
            } @else if (reminderDate) {
              <p class="reminder-hint">Erinnerung am {{ reminderDate }}</p>
            }
          </div>
        }
      </div>
    }
```

- [ ] **Step 5: Add the styles**

An `cooking-duty-dialog.component.scss` anhängen:

```scss
.reminder-section {
  margin-top: 16px;
}

.reminder-details {
  margin-top: 8px;
  padding-left: 8px;
  border-left: 2px solid rgba(0, 0, 0, 0.12);
}

.reminder-hint {
  margin: 0;
  color: rgba(0, 0, 0, 0.6);
  font-size: 0.875rem;
}

.reminder-warning {
  margin: 0;
  color: #c62828;
  font-size: 0.875rem;
}
```

- [ ] **Step 6: Wire it up in cooking.component.ts**

Importe ergänzen:

```typescript
import { CookingReminderSettingsService } from '../shared/services/cooking-reminder-settings.service';
import { FieldDefinitionService } from '../settings/custom-fields/services/field-definition.service';
```

Feld ergänzen und die Dienste in den Konstruktor aufnehmen:

```typescript
  reminderAvailable = false;
```

```typescript
    private cookingReminderSettingsService: CookingReminderSettingsService,
    private fieldDefinitionService: FieldDefinitionService,
```

In `loadOrganisationData()` ergänzen — der zweite Block behebt zugleich die nie befüllte `cookingDutyDefId`:

```typescript
    this.cookingReminderSettingsService.get().subscribe((settings) => {
      this.reminderAvailable = settings.active;
    });

    this.fieldDefinitionService.list().subscribe((defs) => {
      this.cookingDutyDefId = defs.find((d) => d.fieldName === 'cookingDuty')?.id ?? '';
    });
```

Weicht der Methodenname des `FieldDefinitionService` ab, verwende die dort vorhandene Listen-Methode.

In `openDialog(...)` das Datenobjekt um `reminderAvailable: this.reminderAvailable,` erweitern.

In `createCookingDuty` und `updateCookingDuty` jeweils das `value`-Objekt erweitern:

```typescript
      reminderEnabled: result.reminderEnabled,
      ...(result.reminderEnabled ? { reminderDaysBefore: result.reminderDaysBefore } : {}),
```

- [ ] **Step 7: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/cooking-duty-dialog.component.spec.ts`
Expected: PASS (7 Tests)

- [ ] **Step 8: Run the full frontend suite**

Run: `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS bis auf den einen bereits vor dieser Arbeit fehlschlagenden Test.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/app/cooking/cooking-duty-dialog.component.ts frontend/src/app/cooking/cooking-duty-dialog.component.html frontend/src/app/cooking/cooking-duty-dialog.component.scss frontend/src/app/cooking/cooking.component.ts frontend/src/app/cooking/cooking-duty-dialog.component.spec.ts
git commit -m "feat(fe): Erinnerung im Kochdienst-Dialog"
```

---

### Task 13: Gesamtlauf und Abschluss

**Files:**
- keine Änderungen, sofern die Läufe sauber sind

- [ ] **Step 1: Run the full backend suite**

Run: `.\mvnw.cmd test` (aus `backend/`)
Expected: Nur die 13 bereits auf `main` fehlschlagenden Tests schlagen fehl. Jeder zusätzliche Fehlschlag gehört zu dieser Arbeit und muss behoben werden.

- [ ] **Step 2: Run the frontend production build**

Run: `npm run build` (aus `frontend/`)
Expected: erfolgreicher Build ohne Fehler.

- [ ] **Step 3: Report**

Fasse zusammen: welche Tests laufen, welche vorbestehenden Fehlschläge unverändert bleiben, und dass der manuelle Abnahmetest aussteht (Admin konfiguriert Mailkonto und Vorlage, Elternteil legt Kochdienst mit Erinnerung an, Versandzeit kurz in die Zukunft stellen und den Lauf beobachten).
