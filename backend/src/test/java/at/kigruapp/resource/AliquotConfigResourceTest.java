package at.kigruapp.resource;

import at.kigruapp.entity.AliquotConfig;
import at.kigruapp.entity.Semester;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class AliquotConfigResourceTest {

    @BeforeEach
    void cleanup() {
        AliquotConfig.deleteAll();
        Semester.deleteAll();
    }

    private String persistSemester() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        s.createdAt = Instant.now();
        s.persist();
        return s.id.toHexString();
    }

    @Test
    void getDefaultsToNone() {
        String id = persistSemester();
        given().when().get("/api/v1/aliquot-config?semesterId=" + id)
            .then().statusCode(200).body("mode", is("NONE"));
    }

    @Test
    void putThenGetRoundTripsAndUpserts() {
        String id = persistSemester();
        given().contentType(ContentType.JSON).body("{\"mode\":\"PER_DAY\"}")
            .when().put("/api/v1/aliquot-config?semesterId=" + id)
            .then().statusCode(200).body("mode", is("PER_DAY"));
        given().contentType(ContentType.JSON).body("{\"mode\":\"WHOLE_MONTH\"}")
            .when().put("/api/v1/aliquot-config?semesterId=" + id).then().statusCode(200);
        given().when().get("/api/v1/aliquot-config?semesterId=" + id)
            .then().statusCode(200).body("mode", is("WHOLE_MONTH"));
    }

    @Test
    void putRejectsUnknownMode() {
        String id = persistSemester();
        given().contentType(ContentType.JSON).body("{\"mode\":\"DAILY\"}")
            .when().put("/api/v1/aliquot-config?semesterId=" + id).then().statusCode(400);
    }

    @Test
    void putRejectsMissingSemesterId() {
        given().contentType(ContentType.JSON).body("{\"mode\":\"NONE\"}")
            .when().put("/api/v1/aliquot-config").then().statusCode(400);
    }
}
