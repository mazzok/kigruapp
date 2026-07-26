package at.kigruapp.resource;

import at.kigruapp.entity.MailEncryption;
import at.kigruapp.entity.MailSettings;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class MailAccountResourceTest {

    @BeforeEach
    void cleanup() {
        MailSettings.deleteAll();
    }

    @Test
    void listReturnsSingletonAccountWhenConfigured() {
        MailSettings s = new MailSettings();
        s.host = "smtp.example.test";
        s.port = 587;
        s.encryption = MailEncryption.STARTTLS;
        s.fromAddress = "kita@example.test";
        s.fromName = "Kita";
        s.enabled = true;
        s.persistSingleton();

        given()
                .when().get("/api/v1/mail-accounts")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", is(MailSettings.SINGLETON_ID.toHexString()))
                .body("[0].fromAddress", is("kita@example.test"));
    }

    @Test
    void listReturnsEmptyWhenNoSettings() {
        given()
                .when().get("/api/v1/mail-accounts")
                .then().statusCode(200)
                .body("$", hasSize(0));
    }
}
