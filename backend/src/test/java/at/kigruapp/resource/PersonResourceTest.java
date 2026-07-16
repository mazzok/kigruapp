package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class PersonResourceTest {

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
}
