package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.Semester;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class SemesterResourceTest {

    @BeforeEach
    void cleanup() {
        Semester.deleteAll();
    }

    @Test
    void createAndListSemesters() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2024-09-01T00:00:00Z\", \"end\": \"2025-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("start", is("2024-09-01T00:00:00Z"))
            .body("end", is("2025-08-31T00:00:00Z"));

        given()
            .when().get("/api/v1/semesters")
            .then()
            .statusCode(200)
            .body("$.size()", is(1));
    }

    @Test
    void listIsSortedByCreatedAtDescending() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2023-09-01T00:00:00Z\", \"end\": \"2024-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2024-09-01T00:00:00Z\", \"end\": \"2025-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201);

        given()
            .when().get("/api/v1/semesters")
            .then()
            .statusCode(200)
            .body("$.size()", is(2))
            .body("[0].start", is("2024-09-01T00:00:00Z"));
    }

    @Test
    void rejectsStartAfterEnd() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2025-08-31T00:00:00Z\", \"end\": \"2024-09-01T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then()
            .statusCode(400);
    }

    @Test
    void rejectsOverlappingSemester() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2024-09-01T00:00:00Z\", \"end\": \"2025-08-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("{\"start\": \"2025-01-01T00:00:00Z\", \"end\": \"2025-12-31T00:00:00Z\"}")
            .when().post("/api/v1/semesters")
            .then()
            .statusCode(400);
    }
}
