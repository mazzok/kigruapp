package at.kigruapp.resource;

import at.kigruapp.entity.ClosureDefinition;
import at.kigruapp.entity.ClosurePeriod;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class ClosureDefinitionResourceTest {

    @BeforeEach
    void cleanup() {
        ClosureDefinition.deleteAll();
        ClosurePeriod.deleteAll();
    }

    private String createDefinition(String label, String color) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"" + label + "\", \"color\": \"" + color + "\"}")
            .when().post("/api/v1/closure-definitions")
            .then().statusCode(201)
            .extract().path("id");
    }

    private void linkPeriod(String definitionId) {
        ClosurePeriod period = new ClosurePeriod();
        period.from = LocalDate.parse("2026-09-07");
        period.to = LocalDate.parse("2026-09-11");
        period.definitionId = new ObjectId(definitionId);
        period.persist();
    }

    @Test
    void createAndList() {
        createDefinition("Ferien", "#d94f4f");

        given()
            .when().get("/api/v1/closure-definitions")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].label", is("Ferien"))
            .body("[0].color", is("#d94f4f"))
            .body("[0].active", is(true));
    }

    @Test
    void rejectsMissingLabel() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"\", \"color\": \"#d94f4f\"}")
            .when().post("/api/v1/closure-definitions")
            .then().statusCode(400);
    }

    @Test
    void rejectsMissingColor() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Ferien\", \"color\": \"\"}")
            .when().post("/api/v1/closure-definitions")
            .then().statusCode(400);
    }

    @Test
    void updateIsAllowedWhileNoPeriodsAreLinked() {
        String id = createDefinition("Ferien", "#d94f4f");

        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Ferien neu\", \"color\": \"#4f86d9\"}")
            .when().put("/api/v1/closure-definitions/" + id)
            .then().statusCode(200)
            .body("label", is("Ferien neu"))
            .body("color", is("#4f86d9"));
    }

    @Test
    void updateIsRejectedOncePeriodsAreLinked() {
        String id = createDefinition("Ferien", "#d94f4f");
        linkPeriod(id);

        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Ferien neu\", \"color\": \"#4f86d9\"}")
            .when().put("/api/v1/closure-definitions/" + id)
            .then().statusCode(409);
    }

    @Test
    void reviseCreatesCopyAndDeactivatesOriginal() {
        String id = createDefinition("Ferien", "#d94f4f");
        linkPeriod(id);

        String copyId = given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Ferien neu\", \"color\": \"#4f86d9\"}")
            .when().post("/api/v1/closure-definitions/" + id + "/revise")
            .then().statusCode(201)
            .body("label", is("Ferien neu"))
            .body("color", is("#4f86d9"))
            .body("active", is(true))
            .extract().path("id");

        // Die aktive Liste enthaelt nur noch die Kopie.
        given()
            .when().get("/api/v1/closure-definitions")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].id", is(copyId));

        // Das Original bleibt unveraendert erhalten, nur deaktiviert.
        given()
            .when().get("/api/v1/closure-definitions?includeInactive=true")
            .then().statusCode(200)
            .body("$.size()", is(2))
            .body("find { it.id == '" + id + "' }.label", is("Ferien"))
            .body("find { it.id == '" + id + "' }.color", is("#d94f4f"))
            .body("find { it.id == '" + id + "' }.active", is(false));
    }

    @Test
    void deleteOnlyDeactivates() {
        String id = createDefinition("Ferien", "#d94f4f");

        given()
            .when().delete("/api/v1/closure-definitions/" + id)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/closure-definitions")
            .then().statusCode(200)
            .body("$.size()", is(0));

        given()
            .when().get("/api/v1/closure-definitions?includeInactive=true")
            .then().statusCode(200)
            .body("$.size()", is(1))
            .body("[0].active", is(false));
    }

    @Test
    void reactivateViaUpdate() {
        String id = createDefinition("Ferien", "#d94f4f");
        given().when().delete("/api/v1/closure-definitions/" + id).then().statusCode(204);

        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Ferien\", \"color\": \"#d94f4f\", \"active\": true}")
            .when().put("/api/v1/closure-definitions/" + id)
            .then().statusCode(200)
            .body("active", is(true));
    }

    @Test
    void linkedDefinitionCanStillBeReactivated() {
        // Nur das active-Flag kippt, Label und Farbe bleiben — das darf kein 409 geben,
        // sonst waere eine verknuepfte Definition dauerhaft deaktiviert.
        String id = createDefinition("Ferien", "#d94f4f");
        linkPeriod(id);
        given().when().delete("/api/v1/closure-definitions/" + id).then().statusCode(204);

        given()
            .contentType(ContentType.JSON)
            .body("{\"label\": \"Ferien\", \"color\": \"#d94f4f\", \"active\": true}")
            .when().put("/api/v1/closure-definitions/" + id)
            .then().statusCode(200)
            .body("active", is(true));
    }

    @Test
    void newestDefinitionComesFirst() {
        createDefinition("Ferien", "#d94f4f");
        createDefinition("Fortbildung", "#e0a020");

        given()
            .when().get("/api/v1/closure-definitions")
            .then().statusCode(200)
            .body("[0].label", is("Fortbildung"));
    }
}
