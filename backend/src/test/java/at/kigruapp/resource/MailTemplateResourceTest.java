package at.kigruapp.resource;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.RecipientMode;
import at.kigruapp.entity.MailTemplate;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class MailTemplateResourceTest {

    @BeforeEach
    void cleanup() {
        MailTemplate.deleteAll();
        FieldDefinition.deleteAll();
        MailJob.deleteAll();
    }

    private FieldDefinition persistDefinition(String fieldName, String labelDe) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.label = Map.of("de", labelDe, "en", labelDe);
        def.createdAt = java.time.Instant.now();
        def.persist();
        return def;
    }

    private MailTemplate persistTemplate(String name, String bodyHtml) {
        MailTemplate t = new MailTemplate();
        t.name = name;
        t.bodyHtml = bodyHtml;
        t.createdAt = java.time.Instant.now();
        t.updatedAt = t.createdAt;
        t.persist();
        return t;
    }

    @Test
    void createAndListTemplates() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"Willkommen\",\"bodyHtml\":\"<p>Hallo {{person.firstName}}</p>\"}")
                .when().post("/api/v1/mail-templates")
                .then().statusCode(201)
                .body("name", is("Willkommen"));

        given()
                .when().get("/api/v1/mail-templates")
                .then().statusCode(200)
                .body("name", hasItem("Willkommen"));
    }

    @Test
    void getById404WhenMissing() {
        given()
                .when().get("/api/v1/mail-templates/000000000000000000000000")
                .then().statusCode(404);
    }

    @Test
    void updateChangesFields() {
        MailTemplate t = persistTemplate("Old", "<p>old</p>");

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"New\",\"bodyHtml\":\"<p>new</p>\"}")
                .when().put("/api/v1/mail-templates/" + t.id)
                .then().statusCode(200)
                .body("name", is("New"))
                .body("bodyHtml", is("<p>new</p>"));
    }

    @Test
    void deleteRemovesTemplate() {
        MailTemplate t = persistTemplate("ToDelete", "<p>x</p>");

        given()
                .when().delete("/api/v1/mail-templates/" + t.id)
                .then().statusCode(204);

        given()
                .when().get("/api/v1/mail-templates/" + t.id)
                .then().statusCode(404);
    }

    @Test
    void sanitizesScriptTagsButKeepsInlineStyles() {
        String bodyJson = "{\"name\":\"Sanitized\",\"bodyHtml\":\"<script>alert(1)</script><span style=\\\"color:red\\\">x</span>\"}";

        String returnedBody = given()
                .contentType(ContentType.JSON)
                .body(bodyJson)
                .when().post("/api/v1/mail-templates")
                .then().statusCode(201)
                .extract().path("bodyHtml");

        org.junit.jupiter.api.Assertions.assertFalse(returnedBody.contains("<script"));
        org.junit.jupiter.api.Assertions.assertTrue(returnedBody.contains("style="));
    }

    @Test
    void deleteRejectedWhenReferencedByJob() {
        MailTemplate t = persistTemplate("Referenced", "<p>x</p>");
        MailJob job = new MailJob();
        job.name = "Job";
        job.templateId = t.id;
        job.subject = "Subject";
        job.cron = "0 0 8 * * ?";
        job.recipientMode = RecipientMode.ALL_PARENTS;
        job.createdAt = java.time.Instant.now();
        job.updatedAt = job.createdAt;
        job.persist();

        given()
                .when().delete("/api/v1/mail-templates/" + t.id)
                .then().statusCode(409);
    }

    @Test
    void placeholdersReturnsOnlyAllowlistedScalarFields() {
        persistDefinition("firstName", "Vorname");
        persistDefinition("group", "Gruppe");
        persistDefinition("address", "Adresse");
        persistDefinition("personType", "Personentyp");

        given()
                .when().get("/api/v1/mail-templates/placeholders")
                .then().statusCode(200)
                .body("fieldName", hasItem("firstName"))
                .body("fieldName", not(hasItem("group")))
                .body("fieldName", not(hasItem("address")))
                .body("fieldName", not(hasItem("personType")))
                .body("token", everyItem(org.hamcrest.Matchers.startsWith("{{person.")));
    }

    @Test
    void createRejectsBlankName() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"\",\"bodyHtml\":\"<p>x</p>\"}")
                .when().post("/api/v1/mail-templates")
                .then().statusCode(400);
    }
}
