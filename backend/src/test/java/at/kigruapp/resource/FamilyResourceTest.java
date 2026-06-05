package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.Family;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class FamilyResourceTest {

    @BeforeEach
    void cleanup() {
        Family.deleteAll();
    }

    @Test
    void listFamilies_empty() {
        given()
            .when().get("/api/v1/families")
            .then()
            .statusCode(200)
            .body("$.size()", is(0));
    }

    @Test
    void createAndGetFamily() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Mueller\"}")
            .when().post("/api/v1/families")
            .then()
            .statusCode(201)
            .body("name", is("Mueller"))
            .body("id", notNullValue())
            .extract().path("id");

        given()
            .when().get("/api/v1/families/" + id)
            .then()
            .statusCode(200)
            .body("name", is("Mueller"));
    }

    @Test
    void updateFamily() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Mueller\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Schmidt\"}")
            .when().put("/api/v1/families/" + id)
            .then()
            .statusCode(200)
            .body("name", is("Schmidt"));
    }

    @Test
    void deleteFamily() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Mueller\"}")
            .when().post("/api/v1/families")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .when().delete("/api/v1/families/" + id)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/families/" + id)
            .then().statusCode(404);
    }

    @Test
    void getFamily_notFound() {
        given()
            .when().get("/api/v1/families/000000000000000000000000")
            .then().statusCode(404);
    }
}
