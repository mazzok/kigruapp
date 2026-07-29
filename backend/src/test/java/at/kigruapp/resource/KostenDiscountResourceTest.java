package at.kigruapp.resource;

import at.kigruapp.entity.KostenDiscount;
import at.kigruapp.entity.Semester;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class KostenDiscountResourceTest {

    @BeforeEach
    void cleanup() {
        KostenDiscount.deleteAll();
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
    void getReturnsDefaults() {
        String id = persistSemester();
        given().when().get("/api/v1/kosten-discount?semesterId=" + id)
            .then().statusCode(200)
            .body("applyToAll", is(false))
            .body("order", is("MOST_EXPENSIVE_FIRST"))
            .body("tiers.size()", is(0));
    }

    @Test
    void putThenGetRoundTrips() {
        String id = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"applyToAll\":true,\"order\":\"LEAST_EXPENSIVE_FIRST\",\"tiers\":[{\"fromChild\":2,\"percent\":50},{\"fromChild\":3,\"percent\":100}],\"eligibleDefinitionIds\":[\"507f1f77bcf86cd799439011\",\"507f1f77bcf86cd799439012\"]}")
            .when().put("/api/v1/kosten-discount?semesterId=" + id)
            .then().statusCode(200).body("applyToAll", is(true)).body("tiers.size()", is(2));
        given().when().get("/api/v1/kosten-discount?semesterId=" + id)
            .then().statusCode(200)
            .body("order", is("LEAST_EXPENSIVE_FIRST"))
            .body("tiers[0].percent", is(50))
            .body("tiers[1].fromChild", is(3))
            .body("eligibleDefinitionIds.size()", is(2));
    }

    @Test
    void putAcceptsZeroPercentTier() {
        String id = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"applyToAll\":false,\"order\":\"MOST_EXPENSIVE_FIRST\",\"tiers\":[{\"fromChild\":2,\"percent\":0}]}")
            .when().put("/api/v1/kosten-discount?semesterId=" + id)
            .then().statusCode(200).body("tiers[0].percent", is(0));
    }

    @Test
    void putRejectsNonAscendingTiers() {
        String id = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"applyToAll\":false,\"order\":\"MOST_EXPENSIVE_FIRST\",\"tiers\":[{\"fromChild\":3,\"percent\":10},{\"fromChild\":2,\"percent\":20}]}")
            .when().put("/api/v1/kosten-discount?semesterId=" + id).then().statusCode(400);
    }

    @Test
    void putRejectsFromChildBelowTwo() {
        String id = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"applyToAll\":false,\"order\":\"MOST_EXPENSIVE_FIRST\",\"tiers\":[{\"fromChild\":1,\"percent\":10}]}")
            .when().put("/api/v1/kosten-discount?semesterId=" + id).then().statusCode(400);
    }

    @Test
    void putRejectsPercentOutOfRange() {
        String id = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"applyToAll\":false,\"order\":\"MOST_EXPENSIVE_FIRST\",\"tiers\":[{\"fromChild\":2,\"percent\":150}]}")
            .when().put("/api/v1/kosten-discount?semesterId=" + id).then().statusCode(400);
    }

    @Test
    void putRejectsUnknownOrder() {
        String id = persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"applyToAll\":false,\"order\":\"RANDOM\",\"tiers\":[]}")
            .when().put("/api/v1/kosten-discount?semesterId=" + id).then().statusCode(400);
    }
}
