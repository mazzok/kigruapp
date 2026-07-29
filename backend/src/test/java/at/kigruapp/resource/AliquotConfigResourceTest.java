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
    void getDefaultsToNoneNone() {
        String id = persistSemester();
        given().when().get("/api/v1/aliquot-config?semesterId=" + id)
            .then().statusCode(200)
            .body("stundenMode", is("NONE"))
            .body("kostenMode", is("NONE"));
    }

    @Test
    void putThenGetRoundTripsBothModes() {
        String id = persistSemester();
        given().contentType(ContentType.JSON).body("{\"stundenMode\":\"PER_DAY\",\"kostenMode\":\"WHOLE_MONTH\"}")
            .when().put("/api/v1/aliquot-config?semesterId=" + id)
            .then().statusCode(200).body("stundenMode", is("PER_DAY")).body("kostenMode", is("WHOLE_MONTH"));
        given().when().get("/api/v1/aliquot-config?semesterId=" + id)
            .then().statusCode(200).body("stundenMode", is("PER_DAY")).body("kostenMode", is("WHOLE_MONTH"));
    }

    @Test
    void putRejectsUnknownMode() {
        String id = persistSemester();
        given().contentType(ContentType.JSON).body("{\"stundenMode\":\"DAILY\",\"kostenMode\":\"NONE\"}")
            .when().put("/api/v1/aliquot-config?semesterId=" + id).then().statusCode(400);
    }

    @Test
    void putRejectsMissingModeField() {
        String id = persistSemester();
        given().contentType(io.restassured.http.ContentType.JSON).body("{\"kostenMode\":\"NONE\"}")
            .when().put("/api/v1/aliquot-config?semesterId=" + id).then().statusCode(400);
    }

    @Test
    void putRejectsMissingSemesterId() {
        given().contentType(ContentType.JSON).body("{\"stundenMode\":\"NONE\",\"kostenMode\":\"NONE\"}")
            .when().put("/api/v1/aliquot-config").then().statusCode(400);
    }
}
