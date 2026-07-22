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
    public void testChildEntryExitDatesDefaultNull() {
        String familyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Testfamilie-Dates\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        String personId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"basicProperties\": []}")
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
            .body("{\"definitionId\": \"" + groupDefId + "\", \"value\": {\"label\": \"Baeren-Dates\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String semesterId = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2024-09-01T00:00:00Z\", \"end\": \"2025-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"fieldInstanceId\": \"" + groupInstId + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/group?semesterId=" + semesterId)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/persons/children?semesterId=" + semesterId)
            .then()
            .statusCode(200)
            .body("find { it.id == '" + personId + "' }.entryDate", nullValue())
            .body("find { it.id == '" + personId + "' }.exitDate", nullValue());
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

    @Test
    public void testSetEnrollmentDatesRequiresGroupAssignment() {
        String familyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Testfamilie-NoGroup\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        String personId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"basicProperties\": []}")
            .when().post("/api/v1/persons")
            .then().statusCode(201)
            .extract().path("id");

        String semesterId = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2024-09-01T00:00:00Z\", \"end\": \"2025-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"entryDate\": \"2024-09-01\", \"exitDate\": null}")
            .when().patch("/api/v1/persons/" + personId + "/enrollment-dates?semesterId=" + semesterId)
            .then()
            .statusCode(400);
    }

    @Test
    public void testSetEnrollmentDatesValidatesOrderAndDependency() {
        String familyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Testfamilie-Order\"}")
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
            .body("{\"definitionId\": \"" + groupDefId + "\", \"value\": {\"label\": \"Baeren-Order\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String semesterId = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2024-09-01T00:00:00Z\", \"end\": \"2025-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"fieldInstanceId\": \"" + groupInstId + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/group?semesterId=" + semesterId)
            .then().statusCode(204);

        // exitDate without entryDate -> 400
        given()
            .contentType(ContentType.JSON)
            .body("{\"entryDate\": null, \"exitDate\": \"2025-01-01\"}")
            .when().patch("/api/v1/persons/" + personId + "/enrollment-dates?semesterId=" + semesterId)
            .then().statusCode(400);

        // exitDate before entryDate -> 400
        given()
            .contentType(ContentType.JSON)
            .body("{\"entryDate\": \"2025-01-01\", \"exitDate\": \"2024-12-01\"}")
            .when().patch("/api/v1/persons/" + personId + "/enrollment-dates?semesterId=" + semesterId)
            .then().statusCode(400);

        // valid pair -> 204, then reflected in children list
        given()
            .contentType(ContentType.JSON)
            .body("{\"entryDate\": \"2024-09-15\", \"exitDate\": \"2025-06-30\"}")
            .when().patch("/api/v1/persons/" + personId + "/enrollment-dates?semesterId=" + semesterId)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/persons/children?semesterId=" + semesterId)
            .then()
            .statusCode(200)
            .body("find { it.id == '" + personId + "' }.entryDate", is("2024-09-15"))
            .body("find { it.id == '" + personId + "' }.exitDate", is("2025-06-30"));
    }

    @Test
    public void testChangingGroupResetsEnrollmentDates() {
        String familyId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Testfamilie-Reset\"}")
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

        String groupInstId1 = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"value\": {\"label\": \"Baeren-Reset\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String groupInstId2 = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"value\": {\"label\": \"Fuechse-Reset\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String semesterId = given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2024-09-01T00:00:00Z\", \"end\": \"2025-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"fieldInstanceId\": \"" + groupInstId1 + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/group?semesterId=" + semesterId)
            .then().statusCode(204);

        given()
            .contentType(ContentType.JSON)
            .body("{\"entryDate\": \"2024-09-15\", \"exitDate\": null}")
            .when().patch("/api/v1/persons/" + personId + "/enrollment-dates?semesterId=" + semesterId)
            .then().statusCode(204);

        // switch to a different group
        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + groupDefId + "\", \"fieldInstanceId\": \"" + groupInstId2 + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/group?semesterId=" + semesterId)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/persons/children?semesterId=" + semesterId)
            .then()
            .statusCode(200)
            .body("find { it.id == '" + personId + "' }.groupInstanceId", is(groupInstId2))
            .body("find { it.id == '" + personId + "' }.entryDate", nullValue());
    }

    // --- Board (Vorstand) role endpoints ---

    private record BoardFixture(String teamDefId, String teamInstId, String roleDefId, String roleInstId) {}

    private String createFamily(String name) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"" + name + "\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");
    }

    private String createParent(String familyId) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"basicProperties\": []}")
            .when().post("/api/v1/persons")
            .then().statusCode(201)
            .extract().path("id");
    }

    private String createSemester(String start, String end) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"" + start + "\", \"end\": \"" + end + "\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201)
            .extract().path("id");
    }

    /**
     * Creates the board-team singleton (registered under the {@code board} org tag) and one
     * board role (registered under {@code board-roles}), mirroring the Definition-tab setup.
     */
    private BoardFixture setupBoard() {
        String teamDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"board\", \"label\": {\"de\": \"Vorstand\"}, \"jsonSchema\": {\"type\": \"object\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String teamInstId = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + teamDefId + "\", \"value\": {\"label\": \"Vorstand\", \"color\": \"#4285f4\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String boardOrgId = given()
            .when().get("/api/v1/organisation/board")
            .then().statusCode(200)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionIds\": [\"" + teamDefId + "\"], \"entries\": []}")
            .when().put("/api/v1/organisation/id/" + boardOrgId)
            .then().statusCode(200);

        String roleDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"board-role\", \"label\": {\"de\": \"Vorstandsrolle\"}, \"jsonSchema\": {\"type\": \"object\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String roleInstId = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + roleDefId + "\", \"value\": {\"label\": \"Obmann\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        String rolesOrgId = given()
            .when().get("/api/v1/organisation/board-roles")
            .then().statusCode(200)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionIds\": [\"" + roleDefId + "\"], \"entries\": []}")
            .when().put("/api/v1/organisation/id/" + rolesOrgId)
            .then().statusCode(200);

        return new BoardFixture(teamDefId, teamInstId, roleDefId, roleInstId);
    }

    @Test
    public void testBoardRoleAssignmentAddsBoardTeam() {
        BoardFixture board = setupBoard();
        String familyId = createFamily("Testfamilie-Board-Add");
        String personId = createParent(familyId);
        String semesterId = createSemester("2023-09-01T00:00:00Z", "2024-08-31T00:00:00Z");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + board.roleDefId() + "\", \"fieldInstanceId\": \"" + board.roleInstId() + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/board-role?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("assignedRole.find { it.id == '" + board.roleInstId() + "' }", notNullValue())
            .body("assignedDuty.find { it.id == '" + board.teamInstId() + "' }", notNullValue());

        given()
            .when().get("/api/v1/persons/" + personId + "/full?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("assignedDuty.find { it.id == '" + board.teamInstId() + "' }", notNullValue())
            .body("assignedRole.find { it.id == '" + board.roleInstId() + "' }", notNullValue());
    }

    @Test
    public void testUnassignLastBoardRoleRemovesBoardTeam() {
        BoardFixture board = setupBoard();
        String familyId = createFamily("Testfamilie-Board-Remove");
        String personId = createParent(familyId);
        String semesterId = createSemester("2022-09-01T00:00:00Z", "2023-08-31T00:00:00Z");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + board.roleDefId() + "\", \"fieldInstanceId\": \"" + board.roleInstId() + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/board-role?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("assignedDuty.find { it.id == '" + board.teamInstId() + "' }", notNullValue());

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + board.roleDefId() + "\", \"fieldInstanceId\": \"" + board.roleInstId() + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/board-role?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("assignedRole.size()", is(0))
            .body("assignedDuty.size()", is(0));
    }

    @Test
    public void testParentTeamRoleDoesNotCreateBoardTeam() {
        BoardFixture board = setupBoard();
        String familyId = createFamily("Testfamilie-Board-ParentRole");
        String personId = createParent(familyId);
        String semesterId = createSemester("2021-09-01T00:00:00Z", "2022-08-31T00:00:00Z");

        String parentRoleDefId = given()
            .contentType(ContentType.JSON)
            .body("{\"fieldName\": \"parent-role\", \"label\": {\"de\": \"Rollen\"}, \"jsonSchema\": {\"type\": \"object\"}, \"required\": false}")
            .when().post("/api/v1/field-definitions")
            .then().statusCode(201)
            .extract().path("id");

        String parentRoleInstId = given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + parentRoleDefId + "\", \"value\": {\"label\": \"Leader\"}}")
            .when().post("/api/v1/field-instances")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + parentRoleDefId + "\", \"fieldInstanceId\": \"" + parentRoleInstId + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/assigned-role?semesterId=" + semesterId)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/persons/" + personId + "/full?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("assignedRole.size()", is(1))
            .body("assignedDuty.find { it.id == '" + board.teamInstId() + "' }", nullValue());
    }

    @Test
    public void testDeleteBoardRoleCascadesAndRemovesOrphanedBoardTeam() {
        BoardFixture board = setupBoard();
        String familyId = createFamily("Testfamilie-Board-Delete");
        String personId = createParent(familyId);
        String semesterId = createSemester("2020-09-01T00:00:00Z", "2021-08-31T00:00:00Z");

        // Assign the single board role → board team present
        given()
            .contentType(ContentType.JSON)
            .body("{\"definitionId\": \"" + board.roleDefId() + "\", \"fieldInstanceId\": \"" + board.roleInstId() + "\"}")
            .when().patch("/api/v1/persons/" + personId + "/board-role?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("assignedDuty.find { it.id == '" + board.teamInstId() + "' }", notNullValue());

        // Delete the board-role instance entirely
        given()
            .when().delete("/api/v1/persons/board-role/" + board.roleInstId())
            .then().statusCode(204);

        // The person's board-team row for the semester is gone (last board role removed)
        given()
            .when().get("/api/v1/persons/" + personId + "/full?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("assignedRole.size()", is(0))
            .body("assignedDuty.size()", is(0));

        // The board-role field-instance no longer exists
        given()
            .when().get("/api/v1/field-instances/" + board.roleInstId())
            .then().statusCode(404);
    }
}
