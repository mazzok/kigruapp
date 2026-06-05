package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.FieldDefinition;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class FieldDefinitionResourceTest {

    @BeforeEach
    void cleanup() {
        FieldDefinition.deleteAll();
    }

    @Test
    void createAndListFieldDefinitions() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"entity\": \"CHILD\", \"fieldName\": \"svnr\", \"label\": {\"de\": \"Sozialversicherungsnummer\", \"en\": \"Social security number\"}, \"type\": \"TEXT\", \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then()
            .statusCode(201)
            .body("fieldName", is("svnr"))
            .body("entity", is("CHILD"))
            .body("label.de", is("Sozialversicherungsnummer"));

        given()
            .when().get("/api/v1/field-definitions")
            .then()
            .statusCode(200)
            .body("$.size()", is(1));
    }

    @Test
    void createSelectFieldWithOptions() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"entity\": \"CHILD\", \"fieldName\": \"kaliumjodid\", \"label\": {\"de\": \"Kaliumjodid-Tabletten\", \"en\": \"Potassium iodide tablets\"}, \"type\": \"SELECT\", \"options\": [\"Ja\", \"Nein\"], \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then()
            .statusCode(201)
            .body("type", is("SELECT"))
            .body("options.size()", is(2));
    }

    @Test
    void updateFieldDefinition() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("{\"entity\": \"CHILD\", \"fieldName\": \"svnr\", \"label\": {\"de\": \"SVNr\", \"en\": \"SSN\"}, \"type\": \"TEXT\", \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"entity\": \"CHILD\", \"fieldName\": \"svnr\", \"label\": {\"de\": \"Sozialversicherungsnummer\", \"en\": \"Social security number\"}, \"type\": \"TEXT\", \"required\": true}")
            .when().put("/api/v1/field-definitions/" + id)
            .then()
            .statusCode(200)
            .body("label.de", is("Sozialversicherungsnummer"))
            .body("required", is(true));
    }

    @Test
    void deleteFieldDefinition() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("{\"entity\": \"CHILD\", \"fieldName\": \"svnr\", \"label\": {\"de\": \"SVNr\", \"en\": \"SSN\"}, \"type\": \"TEXT\", \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .when().delete("/api/v1/field-definitions/" + id)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/field-definitions")
            .then().body("$.size()", is(0));
    }
}
