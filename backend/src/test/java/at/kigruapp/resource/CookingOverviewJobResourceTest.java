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
                + "\",\"subject\":\"Kochdienste diese Woche\",\"cron\":\"0 0 7 ? * MON\",\"allParents\":true,"
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
                .body("cron", is("0 0 7 ? * MON"))
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
                        + "\",\"subject\":\"Neuer Betreff\",\"cron\":\"0 0 8 ? * MON\",\"allParents\":true,"
                        + "\"recipientSelections\":[],\"active\":false"
                        + ",\"templateName\":\"Neue Vorlage\",\"templateBodyHtml\":\"<p>Hallo</p>\"}")
                .when().put("/api/v1/cooking-overview-jobs/" + id)
                .then().statusCode(200)
                .body("name", is("Neu"))
                .body("cron", is("0 0 8 ? * MON"))
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
