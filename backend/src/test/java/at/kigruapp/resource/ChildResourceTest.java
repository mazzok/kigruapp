package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.Child;
import at.kigruapp.entity.Family;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class ChildResourceTest {

    @BeforeEach
    void cleanup() {
        Child.deleteAll();
        Family.deleteAll();
    }

    private String createFamily(String name) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"" + name + "\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");
    }

    @Test
    void createAndGetChild() {
        String familyId = createFamily("Mueller");

        String childId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Anna\", \"lastName\": \"Mueller\", \"dateOfBirth\": \"2020-03-15\", \"gender\": \"female\"}")
            .when().post("/api/v1/children")
            .then()
            .statusCode(201)
            .body("firstName", is("Anna"))
            .body("lastName", is("Mueller"))
            .body("gender", is("female"))
            .extract().path("id");

        given()
            .when().get("/api/v1/children/" + childId)
            .then()
            .statusCode(200)
            .body("firstName", is("Anna"));
    }

    @Test
    void listChildrenByFamily() {
        String familyId = createFamily("Mueller");

        given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Anna\", \"lastName\": \"Mueller\", \"dateOfBirth\": \"2020-03-15\", \"gender\": \"female\"}")
            .when().post("/api/v1/children")
            .then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Max\", \"lastName\": \"Mueller\", \"dateOfBirth\": \"2022-07-01\", \"gender\": \"male\"}")
            .when().post("/api/v1/children")
            .then().statusCode(201);

        given()
            .when().get("/api/v1/families/" + familyId + "/children")
            .then()
            .statusCode(200)
            .body("$.size()", is(2));
    }

    @Test
    void updateChild() {
        String familyId = createFamily("Mueller");

        String childId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Anna\", \"lastName\": \"Mueller\", \"dateOfBirth\": \"2020-03-15\", \"gender\": \"female\"}")
            .when().post("/api/v1/children")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"firstName\": \"Marie\", \"lastName\": \"Mueller\", \"dateOfBirth\": \"2020-03-15\", \"gender\": \"female\"}")
            .when().put("/api/v1/children/" + childId)
            .then()
            .statusCode(200)
            .body("firstName", is("Marie"));
    }

    @Test
    void deleteChild() {
        String familyId = createFamily("Mueller");

        String childId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Anna\", \"lastName\": \"Mueller\", \"dateOfBirth\": \"2020-03-15\", \"gender\": \"female\"}")
            .when().post("/api/v1/children")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .when().delete("/api/v1/children/" + childId)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/children/" + childId)
            .then().statusCode(404);
    }
}
