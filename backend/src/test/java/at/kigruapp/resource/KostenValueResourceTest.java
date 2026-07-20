package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.Currency;
import at.kigruapp.entity.KostenDefinition;
import at.kigruapp.entity.KostenValue;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class KostenValueResourceTest {

    private static final String SEMESTER_ID = new ObjectId().toString();
    private static final String GROUP_ID = new ObjectId().toString();
    private static final String OTHER_GROUP_ID = new ObjectId().toString();

    @BeforeEach
    void cleanup() {
        KostenValue.deleteAll();
        KostenDefinition.deleteAll();
        Currency.deleteAll();
    }

    private String createCurrency() {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"code\": \"EUR\", \"symbol\": \"€\"}")
            .when().post("/api/v1/currencies")
            .then().statusCode(201)
            .extract().path("id");
    }

    private String createDefinition(String currencyId, String label) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"" + label + "\", \"currencyId\": \"" + currencyId + "\"}")
            .when().post("/api/v1/kosten-definitions")
            .then().statusCode(201)
            .extract().path("id");
    }

    @Test
    void listReturnsAllActiveDefinitionsWithNullAmountWhenNoneSet() {
        String currencyId = createCurrency();
        createDefinition(currencyId, "Elternbeitrag");

        given()
            .queryParam("semesterId", SEMESTER_ID)
            .queryParam("groupId", GROUP_ID)
            .when().get("/api/v1/kosten-values")
            .then()
            .statusCode(200)
            .body("$.size()", is(1))
            .body("[0].label", is("Elternbeitrag"))
            .body("[0].amount", nullValue());
    }

    @Test
    void upsertSetsAmountForSemesterAndGroup() {
        String currencyId = createCurrency();
        String defId = createDefinition(currencyId, "Elternbeitrag");

        given()
            .contentType(ContentType.JSON)
            .body("{\"semesterId\": \"" + SEMESTER_ID + "\", \"groupId\": \"" + GROUP_ID
                + "\", \"definitionId\": \"" + defId + "\", \"amount\": 340.00}")
            .when().put("/api/v1/kosten-values")
            .then()
            .statusCode(204);

        given()
            .queryParam("semesterId", SEMESTER_ID)
            .queryParam("groupId", GROUP_ID)
            .when().get("/api/v1/kosten-values")
            .then()
            .statusCode(200)
            .body("[0].amount", is(340.00f));

        given()
            .queryParam("semesterId", SEMESTER_ID)
            .queryParam("groupId", OTHER_GROUP_ID)
            .when().get("/api/v1/kosten-values")
            .then()
            .statusCode(200)
            .body("[0].amount", nullValue());
    }

    @Test
    void upsertOverwritesExistingAmount() {
        String currencyId = createCurrency();
        String defId = createDefinition(currencyId, "Elternbeitrag");
        String body = "{\"semesterId\": \"" + SEMESTER_ID + "\", \"groupId\": \"" + GROUP_ID
            + "\", \"definitionId\": \"" + defId + "\", \"amount\": %s}";

        given().contentType(ContentType.JSON).body(String.format(body, "340.00"))
            .when().put("/api/v1/kosten-values").then().statusCode(204);
        given().contentType(ContentType.JSON).body(String.format(body, "360.00"))
            .when().put("/api/v1/kosten-values").then().statusCode(204);

        given()
            .queryParam("semesterId", SEMESTER_ID)
            .queryParam("groupId", GROUP_ID)
            .when().get("/api/v1/kosten-values")
            .then()
            .statusCode(200)
            .body("$.size()", is(1))
            .body("[0].amount", is(360.00f));
    }

    @Test
    void upsertWithNullAmountDeletesExistingValue() {
        String currencyId = createCurrency();
        String defId = createDefinition(currencyId, "Elternbeitrag");

        given()
            .contentType(ContentType.JSON)
            .body("{\"semesterId\": \"" + SEMESTER_ID + "\", \"groupId\": \"" + GROUP_ID
                + "\", \"definitionId\": \"" + defId + "\", \"amount\": 340.00}")
            .when().put("/api/v1/kosten-values")
            .then().statusCode(204);

        given()
            .contentType(ContentType.JSON)
            .body("{\"semesterId\": \"" + SEMESTER_ID + "\", \"groupId\": \"" + GROUP_ID
                + "\", \"definitionId\": \"" + defId + "\", \"amount\": null}")
            .when().put("/api/v1/kosten-values")
            .then().statusCode(204);

        given()
            .queryParam("semesterId", SEMESTER_ID)
            .queryParam("groupId", GROUP_ID)
            .when().get("/api/v1/kosten-values")
            .then()
            .statusCode(200)
            .body("[0].amount", nullValue());
    }

    @Test
    void listOmitsInactiveDefinitions() {
        String currencyId = createCurrency();
        String defId = createDefinition(currencyId, "Elternbeitrag");

        given()
            .contentType(ContentType.JSON)
            .body("{\"active\": false}")
            .when().patch("/api/v1/kosten-definitions/" + defId + "/active")
            .then().statusCode(200);

        given()
            .queryParam("semesterId", SEMESTER_ID)
            .queryParam("groupId", GROUP_ID)
            .when().get("/api/v1/kosten-values")
            .then()
            .statusCode(200)
            .body("$.size()", is(0));
    }

    @Test
    void upsertRejectsInactiveDefinition() {
        String currencyId = createCurrency();
        String defId = createDefinition(currencyId, "Elternbeitrag");
        given()
            .contentType(ContentType.JSON)
            .body("{\"active\": false}")
            .when().patch("/api/v1/kosten-definitions/" + defId + "/active")
            .then().statusCode(200);

        given()
            .contentType(ContentType.JSON)
            .body("{\"semesterId\": \"" + SEMESTER_ID + "\", \"groupId\": \"" + GROUP_ID
                + "\", \"definitionId\": \"" + defId + "\", \"amount\": 340.00}")
            .when().put("/api/v1/kosten-values")
            .then()
            .statusCode(400);
    }

    @Test
    void listRequiresSemesterIdAndGroupId() {
        given()
            .queryParam("groupId", GROUP_ID)
            .when().get("/api/v1/kosten-values")
            .then().statusCode(400);

        given()
            .queryParam("semesterId", SEMESTER_ID)
            .when().get("/api/v1/kosten-values")
            .then().statusCode(400);
    }
}
