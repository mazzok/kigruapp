package at.kigruapp.resource;

import at.kigruapp.entity.Semester;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.inject.Inject;
import com.mongodb.client.MongoClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class PersonResourceTest {

    @Inject
    com.mongodb.client.MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

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

    @Test
    public void testTeamAndRoleAssignmentIsolatedPerSemester() {
        String familyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Testfamilie-Team\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        String personId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"basicProperties\": []}")
            .when().post("/api/v1/persons")
            .then().statusCode(201)
            .extract().path("id");

        String teamDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"parent-team\", \"label\": {\"de\": \"Teams\"}, \"jsonSchema\": {\"type\": \"object\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String teamInstId = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + teamDefId + "\", \"value\": {\"label\": \"Team A\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String roleDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"parent-role\", \"label\": {\"de\": \"Rollen\"}, \"jsonSchema\": {\"type\": \"object\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String roleInstId = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + roleDefId + "\", \"value\": {\"label\": \"Leader\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String semester1Id = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2020-09-01T00:00:00Z\", \"end\": \"2021-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        String semester2Id = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2021-09-01T00:00:00Z\", \"end\": \"2022-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + teamDefId + "\", \"fieldInstanceId\": \"" + teamInstId + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/assigned-duty?semesterId=" + semester1Id)
            .then().statusCode(204);

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + roleDefId + "\", \"fieldInstanceId\": \"" + roleInstId + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/assigned-role?semesterId=" + semester1Id)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/persons/" + personId + "/full?semesterId=" + semester1Id)
            .then()
            .statusCode(200)
            .body("assignedDuty.size()", is(1))
            .body("assignedRole.size()", is(1));

        given()
            .when().get("/api/v1/persons/" + personId + "/full?semesterId=" + semester2Id)
            .then()
            .statusCode(200)
            .body("assignedDuty.size()", is(0));
    }

    @Test
    public void testDeletePersonRemovesSemesterAssignments() {
        String familyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Testfamilie-Delete\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        String personId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"basicProperties\": []}")
            .when().post("/api/v1/persons")
            .then().statusCode(201)
            .extract().path("id");

        String teamDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"parent-team\", \"label\": {\"de\": \"Teams\"}, \"jsonSchema\": {\"type\": \"object\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String teamInstId = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + teamDefId + "\", \"value\": {\"label\": \"Team B\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String semesterId = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2019-09-01T00:00:00Z\", \"end\": \"2020-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + teamDefId + "\", \"fieldInstanceId\": \"" + teamInstId + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/assigned-duty?semesterId=" + semesterId)
            .then().statusCode(204);

        given()
            .when().delete("/api/v1/persons/" + personId)
            .then().statusCode(204);

        long remaining = mongoClient.getDatabase(databaseName)
            .getCollection("semester_assignments")
            .countDocuments(new org.bson.Document("personId", new org.bson.types.ObjectId(personId)));
        org.junit.jupiter.api.Assertions.assertEquals(0, remaining);
    }
}
