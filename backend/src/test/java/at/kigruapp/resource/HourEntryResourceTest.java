package at.kigruapp.resource;

import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class HourEntryResourceTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        HourEntry.deleteAll();
        Person.deleteAll();
        Semester.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments").drop();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();
    }

    /** Genau eine (nicht-Admin) Person anlegen -> wird im Dev-Mode zur "aktuellen" Person. */
    private Person persistCurrentPerson() {
        Person p = new Person();
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p;
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
    void createStoresEntryForCurrentPersonAndActiveSemester() {
        persistCurrentPerson();
        String semesterId = persistSemester();

        given()
            .contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-05\",\"minutes\":90,\"comment\":\"Suppe gekocht\"}")
            .when().post("/api/v1/hour-entries")
            .then().statusCode(201)
            .body("roleLabel", is("Kochen"))
            .body("roleFieldInstanceId", nullValue())
            .body("date", is("2026-10-05"))
            .body("minutes", is(90))
            .body("semesterId", is(semesterId));
    }

    @Test
    void listMeReturnsOwnEntriesNewestFirst() {
        persistCurrentPerson();
        persistSemester();

        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-01\",\"minutes\":60,\"comment\":\"\"}")
            .when().post("/api/v1/hour-entries").then().statusCode(201);
        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-09\",\"minutes\":30,\"comment\":\"\"}")
            .when().post("/api/v1/hour-entries").then().statusCode(201);

        given()
            .when().get("/api/v1/hour-entries/me")
            .then().statusCode(200)
            .body("size()", is(2))
            .body("[0].date", is("2026-10-09"))
            .body("[1].date", is("2026-10-01"));
    }

    @Test
    void createRejectsZeroMinutes() {
        persistCurrentPerson();
        persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-05\",\"minutes\":0,\"comment\":\"\"}")
            .when().post("/api/v1/hour-entries")
            .then().statusCode(400);
    }

    @Test
    void createRejectsMalformedDate() {
        persistCurrentPerson();
        persistSemester();
        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"05.10.2026\",\"minutes\":30,\"comment\":\"\"}")
            .when().post("/api/v1/hour-entries")
            .then().statusCode(400);
    }
}
