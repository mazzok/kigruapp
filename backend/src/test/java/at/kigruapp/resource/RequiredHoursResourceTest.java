package at.kigruapp.resource;

import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import com.mongodb.client.MongoClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RequiredHoursResourceTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        RequiredHours.deleteAll();
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
    void getReturnsEmptyDtoWhenNoneSaved() {
        String semesterId = persistSemester();
        given().when().get("/api/v1/required-hours?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("defaultMinutesPerMonth", is(0))
            .body("tiers.size()", is(0));
    }

    @Test
    void putThenGetRoundTrips() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[{\"fromChild\":2,\"minutesPerMonth\":360},{\"fromChild\":3,\"minutesPerMonth\":0}]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("defaultMinutesPerMonth", is(480))
            .body("tiers.size()", is(2));

        given().when().get("/api/v1/required-hours?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("defaultMinutesPerMonth", is(480))
            .body("tiers[0].fromChild", is(2))
            .body("tiers[1].minutesPerMonth", is(0));
    }

    @Test
    void putUpsertsInsteadOfDuplicating() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(200);
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":600,\"tiers\":[]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(200);

        given().when().get("/api/v1/required-hours?semesterId=" + semesterId)
            .then().statusCode(200).body("defaultMinutesPerMonth", is(600));
    }

    @Test
    void putRejectsNonPositiveDefault() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":0,\"tiers\":[]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(400);
    }

    @Test
    void putRejectsNonAscendingTiers() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[{\"fromChild\":3,\"minutesPerMonth\":0},{\"fromChild\":2,\"minutesPerMonth\":360}]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(400);
    }

    @Test
    void putRejectsTierFromChildBelowTwo() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[{\"fromChild\":1,\"minutesPerMonth\":120}]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(400);
    }

    @Test
    void putRejectsNegativeTierMinutes() {
        String semesterId = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"defaultMinutesPerMonth\":480,\"tiers\":[{\"fromChild\":2,\"minutesPerMonth\":-1}]}")
            .when().put("/api/v1/required-hours?semesterId=" + semesterId).then().statusCode(400);
    }
}
