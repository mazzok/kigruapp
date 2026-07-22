package at.kigruapp.resource;

import at.kigruapp.entity.MailEncryption;
import at.kigruapp.entity.MailSettings;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class MailSettingsResourceTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        MailSettings.deleteAll();
    }

    private String storedEncryptedPassword() {
        Document doc = mongoClient.getDatabase(databaseName)
                .getCollection("mail_settings")
                .find()
                .first();
        return doc == null ? null : doc.getString("encryptedPassword");
    }

    private String validPayload(String password, Boolean clearPassword) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"host\":\"smtp.example.test\",");
        b.append("\"port\":587,");
        b.append("\"encryption\":\"STARTTLS\",");
        b.append("\"username\":\"mailer\",");
        b.append("\"fromAddress\":\"kita@example.test\",");
        b.append("\"fromName\":\"Kita\",");
        b.append("\"enabled\":true");
        if (password != null) {
            b.append(",\"password\":\"").append(password).append("\"");
        }
        if (clearPassword != null) {
            b.append(",\"clearPassword\":").append(clearPassword);
        }
        b.append("}");
        return b.toString();
    }

    private MailSettings persistWithPassword(String encryptedPassword) {
        MailSettings s = new MailSettings();
        s.host = "smtp.example.test";
        s.port = 587;
        s.encryption = MailEncryption.STARTTLS;
        s.username = "mailer";
        s.encryptedPassword = encryptedPassword;
        s.fromAddress = "kita@example.test";
        s.fromName = "Kita";
        s.enabled = true;
        s.persistSingleton();
        return s;
    }

    @Test
    void getReturnsMaskedSettings() {
        // Default case: no document yet
        given()
                .when().get("/api/v1/mail-settings")
                .then().statusCode(200)
                .body("enabled", is(false))
                .body("passwordSet", is(false));

        // With a stored (already-encrypted) password
        persistWithPassword("ENCRYPTED-BLOB");

        given()
                .when().get("/api/v1/mail-settings")
                .then().statusCode(200)
                .body("host", is("smtp.example.test"))
                .body("passwordSet", is(true))
                .body("password", nullValue())
                .body("encryptedPassword", nullValue());
    }

    @Test
    void putEncryptsPassword() {
        String plaintext = "sup3r-s3cret";

        given()
                .contentType(ContentType.JSON)
                .body(validPayload(plaintext, null))
                .when().put("/api/v1/mail-settings")
                .then().statusCode(200)
                .body("passwordSet", is(true))
                .body("password", nullValue());

        given()
                .when().get("/api/v1/mail-settings")
                .then().statusCode(200)
                .body("passwordSet", is(true));

        String stored = storedEncryptedPassword();
        assertNotNull(stored, "password must be persisted");
        assertNotEquals(plaintext, stored, "stored password must be encrypted, not plaintext");
    }

    @Test
    void putKeepsAndClearsPassword() {
        // establish a stored password
        given().contentType(ContentType.JSON).body(validPayload("initial-pw", null))
                .when().put("/api/v1/mail-settings")
                .then().statusCode(200).body("passwordSet", is(true));

        // (a) PUT without password → keep
        given().contentType(ContentType.JSON).body(validPayload(null, null))
                .when().put("/api/v1/mail-settings")
                .then().statusCode(200).body("passwordSet", is(true));

        // (b) PUT with clearPassword=true → removed
        given().contentType(ContentType.JSON).body(validPayload(null, true))
                .when().put("/api/v1/mail-settings")
                .then().statusCode(200).body("passwordSet", is(false));
    }

    private void expectBadRequest(String json) {
        given().contentType(ContentType.JSON).body(json)
                .when().put("/api/v1/mail-settings")
                .then().statusCode(400);
    }

    @Test
    void putRejectsInvalidInput() {
        // empty host
        expectBadRequest("{\"host\":\"\",\"port\":587,\"encryption\":\"NONE\","
                + "\"fromAddress\":\"a@b.test\",\"enabled\":true}");
        // port out of range
        expectBadRequest("{\"host\":\"smtp.x\",\"port\":70000,\"encryption\":\"NONE\","
                + "\"fromAddress\":\"a@b.test\",\"enabled\":true}");
        // invalid from address
        expectBadRequest("{\"host\":\"smtp.x\",\"port\":587,\"encryption\":\"NONE\","
                + "\"fromAddress\":\"nope\",\"enabled\":true}");
        // unknown encryption enum
        expectBadRequest("{\"host\":\"smtp.x\",\"port\":587,\"encryption\":\"BOGUS\","
                + "\"fromAddress\":\"a@b.test\",\"enabled\":true}");
    }
}
