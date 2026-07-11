package at.kigruapp.resource;

import at.kigruapp.entity.Semester;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class PersonResourceTest {

    @BeforeEach
    void cleanupSemesters() {
        Semester.deleteAll();
    }

    @Test
    public void testListPersons() {
        given()
            .when().get("/api/v1/persons")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON);
    }

    @Test
    public void testGetNonExistentPerson() {
        given()
            .when().get("/api/v1/persons/000000000000000000000000")
            .then()
            .statusCode(404);
    }

    @Test
    public void testFieldDefinitionsList() {
        given()
            .when().get("/api/v1/field-definitions")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("size()", greaterThan(0));
    }

    @Test
    public void testFieldDefinitionsActiveFilter() {
        given()
            .when().get("/api/v1/field-definitions?active=true")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON);
    }

    @Test
    public void testPatchAssignedDutyNotFound() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"000000000000000000000000\", \"fieldInstanceId\": \"000000000000000000000000\"}")
            .when().patch("/api/v1/persons/000000000000000000000000/assigned-duty")
            .then()
            .statusCode(404);
    }

    @Test
    public void testGroupAssignmentIsolatedPerSemester() {
        String familyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Testfamilie-Gruppe\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        String personTypeDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"personType\", \"label\": {\"de\": \"Typ\"}, \"jsonSchema\": {\"type\": \"string\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String personId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"basicProperties\": [{\"definitionId\": \"" + personTypeDefId + "\", \"value\": \"CHILD\"}]}")
            .when().post("/api/v1/persons")
            .then().statusCode(201)
            .extract().path("id");

        String groupDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"group\", \"label\": {\"de\": \"Gruppen\"}, \"jsonSchema\": {\"type\": \"object\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String groupInstId = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"value\": {\"label\": \"Baeren\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String semester1Id = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2024-09-01T00:00:00Z\", \"end\": \"2025-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        String semester2Id = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2025-09-01T00:00:00Z\", \"end\": \"2026-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"fieldInstanceId\": \"" + groupInstId + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/group?semesterId=" + semester1Id)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/persons/children?semesterId=" + semester1Id)
            .then()
            .statusCode(200)
            .body("find { it.id == '" + personId + "' }.groupInstanceId", is(groupInstId));

        given()
            .when().get("/api/v1/persons/children?semesterId=" + semester2Id)
            .then()
            .statusCode(200)
            .body("find { it.id == '" + personId + "' }.groupInstanceId", nullValue());
    }
}
