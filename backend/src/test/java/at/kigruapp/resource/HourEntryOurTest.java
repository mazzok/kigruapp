package at.kigruapp.resource;

import at.kigruapp.entity.Family;
import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class HourEntryOurTest {

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

    private ObjectId persistFamily() {
        Family f = new Family();
        f.name = "Muster";
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

    private void persistConfig(String semesterId, int def) {
        RequiredHours c = new RequiredHours();
        c.semesterId = new ObjectId(semesterId);
        c.defaultMinutesPerMonth = def;
        c.persist();
    }

    @Test
    void ourReturnsFamilyScopedEntriesAndMonthlyRows() {
        ObjectId famId = persistFamily();
        Person me = persistPerson(famId);     // single non-admin person -> current user
        Person partner = persistPerson(famId);
        String semesterId = persistSemester();
        persistConfig(semesterId, 480);        // 1 Monatssatz-Basis (kein Kind platziert -> familyMonthly 0)

        persistEntry(me.id, semesterId, "2026-10-05", 120);
        persistEntry(partner.id, semesterId, "2026-11-03", 90);

        given().when().get("/api/v1/hour-entries/our?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("familyId", is(famId.toHexString()))
            .body("monthsInSemester", is(6))
            .body("months.size()", is(6))                       // alle Semester-Monate
            .body("months.find { it.month == '2026-10' }.istMinutes", is(120))
            .body("months.find { it.month == '2026-11' }.istMinutes", is(90))
            .body("months.find { it.month == '2026-09' }.istMinutes", is(0))
            .body("istMinutes", is(210))
            .body("entries.size()", is(2));                     // beide Elternteile sichtbar
    }

    @Test
    void ourExcludesOtherFamilies() {
        ObjectId myFam = persistFamily();
        Person me = persistPerson(myFam);
        String semesterId = persistSemester();
        persistConfig(semesterId, 480);

        ObjectId otherFam = persistFamily();
        ObjectId stranger = new ObjectId();
        persistEntry(stranger, semesterId, "2026-10-05", 999); // gehört nicht zur Familie
        // Achtung: stranger ist keine Person in myFam -> darf nicht auftauchen.
        persistEntry(me.id, semesterId, "2026-10-06", 60);

        given().when().get("/api/v1/hour-entries/our?semesterId=" + semesterId)
            .then().statusCode(200)
            .body("istMinutes", is(60))
            .body("entries.size()", is(1));
    }
}
