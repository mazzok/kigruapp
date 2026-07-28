package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.MailEncryption;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailSettings;
import at.kigruapp.entity.RecipientMode;
import com.mongodb.client.MongoClient;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class MailJobResourceTest {

    @Inject
    Scheduler scheduler;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    /** Inserts a group field instance and returns its id (a selectable group). */
    private ObjectId persistGroupInstance(ObjectId groupDefinitionId) {
        ObjectId id = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", id)
                        .append("definitionId", groupDefinitionId)
                        .append("value", new Document("label", "Bären")));
        return id;
    }

    @BeforeEach
    void cleanup() {
        MailJob.deleteAll();
        MailSettings.deleteAll();
        FieldDefinition.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").deleteMany(new Document());
        MailSettings s = new MailSettings();
        s.host = "smtp.example.test";
        s.port = 587;
        s.encryption = MailEncryption.STARTTLS;
        s.fromAddress = "kita@example.test";
        s.fromName = "Kita";
        s.enabled = true;
        s.persistSingleton();
    }

    private String validPayload(ObjectId templateId) {
        return "{\"name\":\"Willkommen-Job\",\"templateId\":\"" + templateId
                + "\",\"subject\":\"Willkommen\",\"cron\":\"0 0 8 * * ?\",\"recipientMode\":\"ALL_PARENTS\","
                + "\"senderAccountId\":\"" + MailSettings.SINGLETON_ID.toHexString() + "\"}";
    }

    private MailJob persistJob(String name) {
        MailJob job = new MailJob();
        job.name = name;
        job.templateId = new ObjectId();
        job.subject = "Subject";
        job.cron = "0 0 8 * * ?";
        job.recipientMode = RecipientMode.ALL_PARENTS;
        job.senderAccountId = MailSettings.SINGLETON_ID.toHexString();
        job.createdAt = java.time.Instant.now();
        job.updatedAt = job.createdAt;
        job.persist();
        return job;
    }

    @Test
    void createAndListJobs() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload(new ObjectId()))
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(201)
                .body("name", is("Willkommen-Job"))
                .body("active", is(false));

        given()
                .when().get("/api/v1/mail-jobs")
                .then().statusCode(200)
                .body("name", hasItem("Willkommen-Job"));
    }

    @Test
    void getById404WhenMissing() {
        given()
                .when().get("/api/v1/mail-jobs/000000000000000000000000")
                .then().statusCode(404);
    }

    @Test
    void updateChangesFields() {
        MailJob job = persistJob("Old");

        given()
                .contentType(ContentType.JSON)
                .body(validPayload(job.templateId).replace("Willkommen-Job", "New"))
                .when().put("/api/v1/mail-jobs/" + job.id)
                .then().statusCode(200)
                .body("name", is("New"));
    }

    @Test
    void deleteRemovesJob() {
        MailJob job = persistJob("ToDelete");

        given()
                .when().delete("/api/v1/mail-jobs/" + job.id)
                .then().statusCode(204);

        given()
                .when().get("/api/v1/mail-jobs/" + job.id)
                .then().statusCode(404);
    }

    @Test
    void rejectsInvalidCronOnCreate() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload(new ObjectId()).replace("0 0 8 * * ?", "not-a-cron"))
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(400);
    }

    @Test
    void validationErrorBodyCarriesMessageForClientFeedback() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload(new ObjectId()).replace("0 0 8 * * ?", "not-a-cron"))
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(400)
                .body("message", org.hamcrest.Matchers.containsString("invalid cron expression"));
    }

    @Test
    void rejectsInvalidCronOnUpdate() {
        MailJob job = persistJob("Inactive");

        given()
                .contentType(ContentType.JSON)
                .body(validPayload(job.templateId).replace("0 0 8 * * ?", "not-a-cron"))
                .when().put("/api/v1/mail-jobs/" + job.id)
                .then().statusCode(400);
    }

    @Test
    void acceptsValidCron() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload(new ObjectId()))
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(201);
    }

    @Test
    void rejectsUnknownSenderAccountId() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload(new ObjectId()).replace(MailSettings.SINGLETON_ID.toHexString(), "000000000000000000000099"))
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(400);
    }

    @Test
    void acceptsValidSenderAccountId() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload(new ObjectId()))
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(201);
    }

    @Test
    void rejectsUnknownOrOutdatedGroupDefinitionId() {
        String payload = validPayload(new ObjectId())
                .replace("\"recipientMode\":\"ALL_PARENTS\"",
                        "\"recipientMode\":\"GROUPS\",\"recipientGroupDefinitionIds\":[\"000000000000000000000099\"]");

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(400);
    }

    @Test
    void acceptsValidGroupDefinitionIds() {
        FieldDefinition group = new FieldDefinition();
        group.fieldName = "group";
        group.createdAt = java.time.Instant.now();
        group.persist();
        ObjectId groupInstanceId = persistGroupInstance(group.id);

        String payload = validPayload(new ObjectId())
                .replace("\"recipientMode\":\"ALL_PARENTS\"",
                        "\"recipientMode\":\"GROUPS\",\"recipientGroupDefinitionIds\":[\"" + groupInstanceId + "\"]");

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(201);
    }

    @Test
    void activateSchedulesAndSetsActive() {
        MailJob job = persistJob("ToActivate");

        given()
                .when().post("/api/v1/mail-jobs/" + job.id + "/activate")
                .then().statusCode(200)
                .body("active", is(true));

        assertNotNull(scheduler.getScheduledJob(job.id.toHexString()));
    }

    @Test
    void deactivateUnschedulesAndClearsActive() {
        MailJob job = persistJob("ToDeactivate");
        given().when().post("/api/v1/mail-jobs/" + job.id + "/activate").then().statusCode(200);

        given()
                .when().post("/api/v1/mail-jobs/" + job.id + "/deactivate")
                .then().statusCode(200)
                .body("active", is(false));

        assertNull(scheduler.getScheduledJob(job.id.toHexString()));
    }

    @Test
    void updatingCronOnActiveJobReArms() {
        MailJob job = persistJob("ActiveJob");
        given().when().post("/api/v1/mail-jobs/" + job.id + "/activate").then().statusCode(200);
        java.time.Instant firstNextFire = scheduler.getScheduledJob(job.id.toHexString()).getNextFireTime();

        given()
                .contentType(ContentType.JSON)
                .body(validPayload(job.templateId).replace("0 0 8 * * ?", "0 0 9 * * ?"))
                .when().put("/api/v1/mail-jobs/" + job.id)
                .then().statusCode(200);

        java.time.Instant secondNextFire = scheduler.getScheduledJob(job.id.toHexString()).getNextFireTime();
        assertNotNull(secondNextFire);
        org.junit.jupiter.api.Assertions.assertNotEquals(firstNextFire, secondNextFire);
    }

    @Test
    void deletingActiveJobUnschedulesIt() {
        MailJob job = persistJob("ToDeleteActive");
        given().when().post("/api/v1/mail-jobs/" + job.id + "/activate").then().statusCode(200);

        given().when().delete("/api/v1/mail-jobs/" + job.id).then().statusCode(204);

        assertNull(scheduler.getScheduledJob(job.id.toHexString()));
    }

    @Test
    void createRejectsBlankName() {
        given()
                .contentType(ContentType.JSON)
                .body(validPayload(new ObjectId()).replace("\"Willkommen-Job\"", "\"\""))
                .when().post("/api/v1/mail-jobs")
                .then().statusCode(400);
    }
}
