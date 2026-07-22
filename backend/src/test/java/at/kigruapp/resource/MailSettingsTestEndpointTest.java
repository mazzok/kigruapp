package at.kigruapp.resource;

import at.kigruapp.entity.MailEncryption;
import at.kigruapp.entity.MailSettings;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MailSettingsTestEndpointTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @BeforeEach
    void cleanup() {
        MailSettings.deleteAll();
    }

    private void persistEnabledNoneSettings() {
        MailSettings s = new MailSettings();
        s.host = "127.0.0.1";
        s.port = greenMail.getSmtp().getPort();
        s.encryption = MailEncryption.NONE;
        s.username = null;
        s.encryptedPassword = null;
        s.fromAddress = "kita@example.test";
        s.fromName = "Kita";
        s.enabled = true;
        s.persistSingleton();
    }

    @Test
    void testEndpointSendsMailAndReturnsOk() throws Exception {
        persistEnabledNoneSettings();

        given()
                .contentType(ContentType.JSON)
                .body("{\"recipient\":\"parent@example.test\"}")
                .when().post("/api/v1/mail-settings/test")
                .then().statusCode(200)
                .body("success", is(true))
                .body("category", is("OK"));

        assertTrue(greenMail.waitForIncomingEmail(5000, 1), "one mail must arrive");
    }

    @Test
    void testEndpointReturnsNormalizedFailureWhenDisabled() {
        // no settings persisted → feature disabled/missing
        given()
                .contentType(ContentType.JSON)
                .body("{\"recipient\":\"parent@example.test\"}")
                .when().post("/api/v1/mail-settings/test")
                .then().statusCode(200)
                .body("success", is(false))
                .body("category", is("CONFIG_MISSING"))
                // normalized message: no raw stacktrace / server text leaks through
                .body("message", not(containsString("Exception")));
    }
}
