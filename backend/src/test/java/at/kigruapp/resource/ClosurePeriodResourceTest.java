package at.kigruapp.resource;

import at.kigruapp.entity.ClosureDefinition;
import at.kigruapp.entity.ClosurePeriod;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class ClosurePeriodResourceTest {

    private String ferienId;
    private String fortbildungId;

    @BeforeEach
    void setup() {
        ClosureDefinition.deleteAll();
        ClosurePeriod.deleteAll();
        ferienId = createDefinition("Ferien", "#d94f4f");
        fortbildungId = createDefinition("Fortbildung", "#e0a020");
    }

    private String createDefinition(String label, String color) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"" + label + "\", \"color\": \"" + color + "\"}")
            .when().post("/api/v1/closure-definitions")
            .then().statusCode(201)
            .extract().path("id");
    }

    private io.restassured.response.Response apply(String definitionId, String mode, String... days) {
        String list = String.join("\", \"", days);
        return given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + definitionId + "\", \"mode\": \"" + mode
                + "\", \"days\": [\"" + list + "\"]}")
            .when().post("/api/v1/closure-periods/apply");
    }

    @Test
    void assignCreatesOneSpan() {
        apply(ferienId, "assign", "2026-09-07", "2026-09-08", "2026-09-09")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].from", is("2026-09-07"))
            .body("[0].to", is("2026-09-09"))
            .body("[0].definitionId", is(ferienId));
    }

    @Test
    void assignMergesAcrossTheWeekend() {
        apply(ferienId, "assign", "2026-09-10", "2026-09-11").then().statusCode(200);

        apply(ferienId, "assign", "2026-09-14", "2026-09-15")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].from", is("2026-09-10"))
            .body("[0].to", is("2026-09-15"));
    }

    @Test
    void removeSplitsTheSpan() {
        apply(ferienId, "assign", "2026-09-07", "2026-09-08", "2026-09-09",
                                  "2026-09-10", "2026-09-11").then().statusCode(200);

        apply(ferienId, "remove", "2026-09-09")
            .then().statusCode(200)
            .body("$.size()", is(2))
            .body("[0].to", is("2026-09-08"))
            .body("[1].from", is("2026-09-10"));
    }

    @Test
    void applyIsRepeatableWithoutCreatingDuplicates() {
        apply(ferienId, "assign", "2026-09-07", "2026-09-08").then().statusCode(200);
        apply(ferienId, "assign", "2026-09-07", "2026-09-08")
            .then().statusCode(200)
            .body("$.size()", is(1));
    }

    @Test
    void differentDefinitionsCoexistOnTheSameDay() {
        apply(ferienId, "assign", "2026-09-07").then().statusCode(200);
        apply(fortbildungId, "assign", "2026-09-07").then().statusCode(200);

        given()
            .when().get("/api/v1/closure-periods?from=2026-09-01&to=2026-09-30")
            .then().statusCode(200)
            .body("$.size()", is(2));
    }

    @Test
    void applyOnlyTouchesItsOwnDefinition() {
        apply(ferienId, "assign", "2026-09-07").then().statusCode(200);
        apply(fortbildungId, "assign", "2026-09-08")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].definitionId", is(fortbildungId));

        given()
            .when().get("/api/v1/closure-periods?from=2026-09-01&to=2026-09-30")
            .then().statusCode(200)
            .body("$.size()", is(2));
    }

    @Test
    void listOnlyReturnsOverlappingPeriods() {
        apply(ferienId, "assign", "2026-09-07", "2026-09-08").then().statusCode(200);

        given()
            .when().get("/api/v1/closure-periods?from=2026-11-01&to=2026-11-30")
            .then().statusCode(200)
            .body("$.size()", is(0));

        given()
            .when().get("/api/v1/closure-periods?from=2026-09-08&to=2026-09-30")
            .then().statusCode(200)
            .body("$.size()", is(1));
    }

    @Test
    void weekendDaysAreRejected() {
        // 2026-09-12 ist ein Samstag.
        apply(ferienId, "assign", "2026-09-12").then().statusCode(400);
    }

    @Test
    void holidaysAreRejected() {
        // 2026-10-26 ist der oesterreichische Nationalfeiertag.
        apply(ferienId, "assign", "2026-10-26").then().statusCode(400);
    }

    @Test
    void emptyDayListIsRejected() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + ferienId + "\", \"mode\": \"assign\", \"days\": []}")
            .when().post("/api/v1/closure-periods/apply")
            .then().statusCode(400);
    }

    @Test
    void unknownModeIsRejected() {
        apply(ferienId, "toggle", "2026-09-07").then().statusCode(400);
    }

    @Test
    void unknownDefinitionYields404() {
        apply("64b7f1c2a1b2c3d4e5f60718", "assign", "2026-09-07").then().statusCode(404);
    }

    @Test
    void assigningToADeactivatedDefinitionIsRejected() {
        given().when().delete("/api/v1/closure-definitions/" + ferienId).then().statusCode(204);
        apply(ferienId, "assign", "2026-09-07").then().statusCode(409);
    }

    @Test
    void removingFromADeactivatedDefinitionIsAllowed() {
        apply(ferienId, "assign", "2026-09-07", "2026-09-08").then().statusCode(200);
        given().when().delete("/api/v1/closure-definitions/" + ferienId).then().statusCode(204);

        apply(ferienId, "remove", "2026-09-08")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].to", is("2026-09-07"));
    }
}
