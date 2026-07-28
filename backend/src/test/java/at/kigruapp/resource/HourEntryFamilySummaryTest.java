package at.kigruapp.resource;

import at.kigruapp.entity.Family;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class HourEntryFamilySummaryTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        HourEntry.deleteAll();
        Person.deleteAll();
        Family.deleteAll();
        Semester.deleteAll();
        RequiredHours.deleteAll();
        FieldDefinition.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments").drop();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();
    }

    private String persistSemester() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z"); // 6 Monate
        s.createdAt = Instant.now();
        s.persist();
        return s.id.toHexString();
    }

    private void adminUser() {
        ObjectId adminInst = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
            .insertOne(new Document("_id", adminInst).append("value", "ADMIN"));
        Person admin = new Person();
        admin.roles = new ArrayList<>();
        admin.roles.add(new FieldRef(new ObjectId(), adminInst));
        admin.createdAt = Instant.now();
        admin.updatedAt = admin.createdAt;
        admin.persist();
    }

    private ObjectId persistFamily(String name) {
        Family f = new Family();
        f.name = name;
        f.createdAt = Instant.now();
        f.persist();
        return f.id;
    }

    private Person persistPerson(ObjectId familyId) {
        Person p = new Person();
        p.familyId = familyId;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p;
    }

    private void placeChild(ObjectId childPersonId, String semesterId) {
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments")
            .insertOne(new Document("_id", new ObjectId())
                .append("personId", childPersonId)
                .append("semesterId", new ObjectId(semesterId))
                .append("section", "group")
                .append("definitionId", new ObjectId())
                .append("fieldInstanceId", new ObjectId()));
    }

    private void persistConfig(String semesterId, int def, int tierFrom, int tierMin) {
        RequiredHours c = new RequiredHours();
        c.semesterId = new ObjectId(semesterId);
        c.defaultMinutesPerMonth = def;
        RequiredHours.Tier t = new RequiredHours.Tier();
        t.fromChild = tierFrom;
        t.minutesPerMonth = tierMin;
        c.tiers.add(t);
        c.persist();
    }

    private void persistEntry(ObjectId personId, String semesterId, String date, int minutes) {
        HourEntry e = new HourEntry();
        e.personId = personId;
        e.semesterId = new ObjectId(semesterId);
        e.roleLabel = "Kochen";
        e.date = date;
        e.minutes = minutes;
        e.comment = "";
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.persist();
    }

    @Test
    void familySummaryComputesSollAndIst() {
        adminUser();
        String semesterId = persistSemester();
        persistConfig(semesterId, 480, 2, 360); // default 8h, ab 2. Kind 6h

        ObjectId famId = persistFamily("Muster");
        Person parent = persistPerson(famId);
        Person child1 = persistPerson(famId);
        Person child2 = persistPerson(famId);
        placeChild(child1.id, semesterId);
        placeChild(child2.id, semesterId);

        persistEntry(parent.id, semesterId, "2026-10-01", 300); // Ist 300 min

        given().when().get("/api/v1/hour-entries/family-summary?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("find { it.familyId == '" + famId.toHexString() + "' }.childCount", is(2))
            .body("find { it.familyId == '" + famId.toHexString() + "' }.familyMonthlyMinutes", is(840)) // 480 + 360
            .body("find { it.familyId == '" + famId.toHexString() + "' }.monthsInSemester", is(6))
            .body("find { it.familyId == '" + famId.toHexString() + "' }.sollMinutes", is(5040)) // 840 * 6
            .body("find { it.familyId == '" + famId.toHexString() + "' }.istMinutes", is(300))
            .body("find { it.familyId == '" + famId.toHexString() + "' }.members.size()", is(1));
    }

    @Test
    void familyWithoutChildrenOrEntriesIsOmitted() {
        adminUser();
        String semesterId = persistSemester();
        persistConfig(semesterId, 480, 2, 360);
        ObjectId emptyFam = persistFamily("Leer");
        persistPerson(emptyFam); // parent only, no placement, no entries

        given().when().get("/api/v1/hour-entries/family-summary?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("find { it.familyId == '" + emptyFam.toHexString() + "' }", nullValue());
    }
}
