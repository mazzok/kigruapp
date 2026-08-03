package at.kigruapp.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.kigruapp.entity.AliquotConfig;
import at.kigruapp.entity.KostenDiscount;
import at.kigruapp.entity.KostenValue;
import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;

import java.math.BigDecimal;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class SemesterResourceTest {

    @BeforeEach
    void cleanup() {
        Semester.deleteAll();
        RequiredHours.deleteAll();
        KostenDiscount.deleteAll();
        AliquotConfig.deleteAll();
        KostenValue.deleteAll();
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

    private ObjectId seedPrev() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        s.createdAt = Instant.parse("2026-08-01T00:00:00Z");
        s.persist();
        RequiredHours rh = new RequiredHours(); rh.semesterId = s.id; rh.defaultMinutesPerMonth = 480; rh.persist();
        KostenDiscount kd = new KostenDiscount(); kd.semesterId = s.id; kd.applyToAll = true;
        kd.order = "MOST_EXPENSIVE_FIRST"; kd.persist();
        AliquotConfig ac = new AliquotConfig(); ac.semesterId = s.id; ac.stundenMode = "PER_DAY"; ac.kostenMode = "WHOLE_MONTH"; ac.persist();
        KostenValue kv = new KostenValue(); kv.semesterId = s.id; kv.groupId = new ObjectId();
        kv.definitionId = new ObjectId(); kv.amount = new BigDecimal("123.45"); kv.persist();
        return s.id;
    }

    @Test
    void createCopiesPreviousSemesterConfig() {
        seedPrev();
        String newId = given().contentType(ContentType.JSON)
                .body("{\"start\":\"2027-09-01T00:00:00Z\",\"end\":\"2028-02-28T00:00:00Z\"}")
                .when().post("/api/v1/semesters").then().statusCode(201).extract().path("id");
        ObjectId nid = new ObjectId(newId);
        assertEquals(480, RequiredHours.findBySemesterId(nid).defaultMinutesPerMonth);
        assertTrue(KostenDiscount.findBySemesterId(nid).applyToAll);
        AliquotConfig ac = AliquotConfig.findBySemesterId(nid);
        assertEquals("PER_DAY", ac.stundenMode);
        assertEquals("WHOLE_MONTH", ac.kostenMode);
        assertEquals(1, KostenValue.find("semesterId", nid).count());
    }

    @Test
    void createWithoutPreviousDoesNotCopy() {
        String newId = given().contentType(ContentType.JSON)
                .body("{\"start\":\"2027-09-01T00:00:00Z\",\"end\":\"2028-02-28T00:00:00Z\"}")
                .when().post("/api/v1/semesters").then().statusCode(201).extract().path("id");
        assertNull(RequiredHours.findBySemesterId(new ObjectId(newId)));
    }

    private ObjectId seedSemester(String start, String end) {
        Semester s = new Semester();
        s.start = Instant.parse(start + "T00:00:00Z");
        s.end = Instant.parse(end + "T00:00:00Z");
        s.createdAt = Instant.now();
        s.persist();
        return s.id;
    }

    @Test
    void copiesGroupRatesAndOrderToNewSemester() {
        org.bson.types.ObjectId previous = seedSemester("2026-09-01", "2027-04-30");
        org.bson.types.ObjectId groupId = new org.bson.types.ObjectId();

        at.kigruapp.entity.RequiredHours cfg = new at.kigruapp.entity.RequiredHours();
        cfg.semesterId = previous;
        cfg.defaultMinutesPerMonth = 480;
        cfg.allGroups = false;
        cfg.order = at.kigruapp.entity.RequiredHours.LEAST_EXPENSIVE_FIRST;
        at.kigruapp.entity.RequiredHours.GroupRate rate = new at.kigruapp.entity.RequiredHours.GroupRate();
        rate.groupInstanceId = groupId;
        rate.minutesPerMonth = 300;
        cfg.groupRates = new java.util.ArrayList<>(java.util.List.of(rate));
        cfg.persist();

        String created = given().contentType("application/json")
                .body("{\"start\":\"2027-09-01T00:00:00Z\",\"end\":\"2028-04-30T00:00:00Z\"}")
                .when().post("/api/v1/semesters")
                .then().statusCode(201).extract().jsonPath().getString("id");

        at.kigruapp.entity.RequiredHours copied = at.kigruapp.entity.RequiredHours
                .findBySemesterId(new org.bson.types.ObjectId(created));
        org.junit.jupiter.api.Assertions.assertFalse(copied.allGroups);
        org.junit.jupiter.api.Assertions.assertEquals("LEAST_EXPENSIVE_FIRST", copied.order);
        org.junit.jupiter.api.Assertions.assertEquals(1, copied.groupRates.size());
        org.junit.jupiter.api.Assertions.assertEquals(300, copied.groupRates.get(0).minutesPerMonth);
        org.junit.jupiter.api.Assertions.assertEquals(groupId, copied.groupRates.get(0).groupInstanceId);
    }
}
