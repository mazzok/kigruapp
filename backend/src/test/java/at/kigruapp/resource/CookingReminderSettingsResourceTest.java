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
