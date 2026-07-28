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
        at.kigruapp.entity.MailJob.deleteAll();
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
}
