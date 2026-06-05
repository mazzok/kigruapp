package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.Family;
import at.kigruapp.entity.Parent;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class ParentResourceTest {

    @BeforeEach
    void cleanup() {
        Parent.deleteAll();
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
    void createAndGetParent() {
        String familyId = createFamily("Mueller");

        String parentId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Hans\", \"lastName\": \"Mueller\", \"email\": \"hans@example.com\", \"phone\": \"+43 664 1234567\", \"address\": {\"street\": \"Hauptstr. 1\", \"zip\": \"1010\", \"city\": \"Wien\"}}")
            .when().post("/api/v1/parents")
            .then()
            .statusCode(201)
            .body("firstName", is("Hans"))
            .body("email", is("hans@example.com"))
            .body("address.city", is("Wien"))
            .extract().path("id");

        given()
            .when().get("/api/v1/parents/" + parentId)
            .then()
            .statusCode(200)
            .body("firstName", is("Hans"));
    }

    @Test
    void listParentsByFamily() {
        String familyId = createFamily("Mueller");

        given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Hans\", \"lastName\": \"Mueller\"}")
            .when().post("/api/v1/parents").then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Maria\", \"lastName\": \"Mueller\"}")
            .when().post("/api/v1/parents").then().statusCode(201);

        given()
            .when().get("/api/v1/families/" + familyId + "/parents")
            .then()
            .statusCode(200)
            .body("$.size()", is(2));
    }

    @Test
    void updateParent() {
        String familyId = createFamily("Mueller");

        String parentId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Hans\", \"lastName\": \"Mueller\"}")
            .when().post("/api/v1/parents")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("{\"firstName\": \"Johann\", \"lastName\": \"Mueller\", \"email\": \"johann@example.com\"}")
            .when().put("/api/v1/parents/" + parentId)
            .then()
            .statusCode(200)
            .body("firstName", is("Johann"))
            .body("email", is("johann@example.com"));
    }

    @Test
    void deleteParent() {
        String familyId = createFamily("Mueller");

        String parentId = given()
            .contentType(ContentType.JSON)
            .body("{\"familyId\": \"" + familyId + "\", \"firstName\": \"Hans\", \"lastName\": \"Mueller\"}")
            .when().post("/api/v1/parents")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .when().delete("/api/v1/parents/" + parentId)
            .then().statusCode(204);

        given()
            .when().get("/api/v1/parents/" + parentId)
            .then().statusCode(404);
    }
}
