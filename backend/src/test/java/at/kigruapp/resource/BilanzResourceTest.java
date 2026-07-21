package at.kigruapp.resource;

import at.kigruapp.entity.BilanzOverride;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class BilanzResourceTest {

    @BeforeEach
    void cleanup() {
        BilanzOverride.deleteAll();
    }

    @Test
    void putOverrideCreatesRow() {
        String personId = new ObjectId().toString();
        String definitionId = new ObjectId().toString();

        given()
            .contentType(ContentType.JSON)
            .body("{\"personId\":\"" + personId + "\",\"year\":2026,\"month\":3,"
                + "\"definitionId\":\"" + definitionId + "\",\"amount\":123.45}")
            .when().put("/api/v1/bilanzen/overrides")
            .then().statusCode(204);

        BilanzOverride o = BilanzOverride.findByKeys(
            new ObjectId(personId), 2026, 3, new ObjectId(definitionId));
        assertNotNull(o);
        assertEquals(0, new BigDecimal("123.45").compareTo(o.amount));
    }

    @Test
    void putOverrideOverwritesExistingRow() {
        String personId = new ObjectId().toString();
        String definitionId = new ObjectId().toString();
        String body = "{\"personId\":\"" + personId + "\",\"year\":2026,\"month\":3,"
            + "\"definitionId\":\"" + definitionId + "\",\"amount\":%s}";

        given().contentType(ContentType.JSON).body(String.format(body, "100.00"))
            .when().put("/api/v1/bilanzen/overrides").then().statusCode(204);
        given().contentType(ContentType.JSON).body(String.format(body, "250.00"))
            .when().put("/api/v1/bilanzen/overrides").then().statusCode(204);

        assertEquals(1, BilanzOverride.count());
        BilanzOverride o = BilanzOverride.findByKeys(
            new ObjectId(personId), 2026, 3, new ObjectId(definitionId));
        assertEquals(0, new BigDecimal("250.00").compareTo(o.amount));
    }

    @Test
    void putOverrideRejectsInvalidMonth() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"personId\":\"" + new ObjectId() + "\",\"year\":2026,\"month\":13,"
                + "\"definitionId\":\"" + new ObjectId() + "\",\"amount\":1}")
            .when().put("/api/v1/bilanzen/overrides")
            .then().statusCode(400);
    }
}
