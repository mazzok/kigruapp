package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.Currency;
import at.kigruapp.entity.KostenDefinition;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class KostenDefinitionResourceTest {

    @BeforeEach
    void cleanup() {
        KostenDefinition.deleteAll();
        Currency.deleteAll();
    }

    private String createCurrency(String code, String symbol) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"code\": \"" + code + "\", \"symbol\": \"" + symbol + "\"}")
            .when().post("/api/v1/currencies")
            .then().statusCode(201)
            .extract().path("id");
    }

    @Test
    void createAndListDefinitions() {
        String currencyId = createCurrency("EUR", "€");

        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Elternbeitrag\", \"currencyId\": \"" + currencyId + "\"}")
            .when().post("/api/v1/kosten-definitions")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("label", is("Elternbeitrag"))
            .body("active", is(true))
            .body("currency.code", is("EUR"))
            .body("currency.symbol", is("€"));

        given()
            .when().get("/api/v1/kosten-definitions")
            .then()
            .statusCode(200)
            .body("$.size()", is(1))
            .body("[0].label", is("Elternbeitrag"));
    }

    @Test
    void rejectsMissingLabel() {
        String currencyId = createCurrency("EUR", "€");

        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"\", \"currencyId\": \"" + currencyId + "\"}")
            .when().post("/api/v1/kosten-definitions")
            .then()
            .statusCode(400);
    }

    @Test
    void rejectsUnknownCurrency() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Elternbeitrag\", \"currencyId\": \"000000000000000000000000\"}")
            .when().post("/api/v1/kosten-definitions")
            .then()
            .statusCode(400);
    }

    @Test
    void togglesActiveFlag() {
        String currencyId = createCurrency("EUR", "€");
        String defId = given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Elternbeitrag\", \"currencyId\": \"" + currencyId + "\"}")
            .when().post("/api/v1/kosten-definitions")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"active\": false}")
            .when().patch("/api/v1/kosten-definitions/" + defId + "/active")
            .then()
            .statusCode(200)
            .body("active", is(false));

        given()
            .when().get("/api/v1/kosten-definitions")
            .then()
            .statusCode(200)
            .body("[0].active", is(false));
    }

    @Test
    void activeToggleReturns404ForUnknownId() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"active\": false}")
            .when().patch("/api/v1/kosten-definitions/000000000000000000000000/active")
            .then()
            .statusCode(404);
    }
}
