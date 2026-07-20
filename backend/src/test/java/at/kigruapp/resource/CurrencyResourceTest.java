package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.Currency;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class CurrencyResourceTest {

    @BeforeEach
    void cleanup() {
        Currency.deleteAll();
    }

    @Test
    void createAndListCurrencies() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"code\": \"EUR\", \"symbol\": \"€\"}")
            .when().post("/api/v1/currencies")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("code", is("EUR"))
            .body("symbol", is("€"));

        given()
            .when().get("/api/v1/currencies")
            .then()
            .statusCode(200)
            .body("$.size()", is(1))
            .body("[0].code", is("EUR"));
    }

    @Test
    void rejectsMissingCode() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"code\": \"\", \"symbol\": \"€\"}")
            .when().post("/api/v1/currencies")
            .then()
            .statusCode(400);
    }

    @Test
    void rejectsMissingSymbol() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"code\": \"EUR\", \"symbol\": \"\"}")
            .when().post("/api/v1/currencies")
            .then()
            .statusCode(400);
    }
}
