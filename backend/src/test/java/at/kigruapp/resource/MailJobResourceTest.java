package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailEncryption;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.RecipientKind;
import at.kigruapp.entity.RecipientSelection;
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
import static org.hamcrest.Matchers.equalTo;
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

    private ObjectId persistDefinitionAndInstance(String fieldName, boolean outdated) {
        FieldDefinition def = new FieldDefinition();
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

    private String enabledAccountId;
    private ObjectId templateId;

    @BeforeEach
    void cleanup() {
        MailJob.deleteAll();
        FieldDefinition.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").deleteMany(new Document());

        MailAccount.deleteAll();
        MailAccount acc = new MailAccount();
        acc.name = "Haupt";
        acc.host = "smtp.example.test";
        acc.port = 587;
        acc.encryption = MailEncryption.STARTTLS;
        acc.fromAddress = "kita@example.test";
        acc.enabled = true;
        acc.persist();
        enabledAccountId = acc.id.toHexString();
        templateId = new ObjectId();
    }

    private String validPayload(ObjectId templateId) {
        return "{\"name\":\"Willkommen-Job\",\"templateId\":\"" + templateId
                + "\",\"subject\":\"Willkommen\",\"cron\":\"0 0 8 * * ?\",\"allParents\":true,"
                + "\"senderAccountId\":\"" + enabledAccountId + "\"}";
    }

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
                """.formatted(templateId.toHexString(), enabledAccountId, recipientJson);
    }

    private MailJob persistJob(String name) {
        MailJob job = new MailJob();
        job.name = name;
        job.templateId = new ObjectId();
        job.subject = "Subject";
        job.cron = "0 0 8 * * ?";
        job.allParents = true;
        job.senderAccountId = enabledAccountId;
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
        disabled.encryption = MailEncryption.STARTTLS;
        disabled.fromAddress = "aus@example.test";
        disabled.enabled = false;
        disabled.persist();

        String payload = validPayload(new ObjectId())
                .replace(enabledAccountId, disabled.id.toHexString());
        given().contentType(ContentType.JSON).body(payload)
                .when().post("/api/v1/mail-jobs").then().statusCode(400);
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
    void acceptsValidGroupDefinitionIds() {
        FieldDefinition group = new FieldDefinition();
        group.fieldName = "group";
        group.createdAt = java.time.Instant.now();
        group.persist();
        ObjectId groupInstanceId = persistGroupInstance(group.id);

        String payload = validPayload(new ObjectId())
                .replace("\"allParents\":true",
                        "\"allParents\":false,\"recipientSelections\":[{\"kind\":\"GROUP\",\"fieldInstanceId\":\"" + groupInstanceId + "\"}]");

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
}
