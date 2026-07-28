package at.kigruapp.resource;

import at.kigruapp.entity.HourEntry;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.bson.Document;
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

    /** Legt eine Rollen-Zuweisung (section="role") inkl. field_instance mit Label an. */
    private String assignRole(ObjectId personId, String semesterId, String label) {
        ObjectId defId = new ObjectId();
        ObjectId instId = new ObjectId();
        fieldInstancesForTest().insertOne(new Document("_id", instId)
                .append("definitionId", defId)
                .append("value", new Document("label", label)));
        semesterAssignmentsForTest().insertOne(new Document("_id", new ObjectId())
                .append("personId", personId)
                .append("semesterId", new ObjectId(semesterId))
                .append("section", "role")
                .append("definitionId", defId)
                .append("fieldInstanceId", instId));
        return instId.toHexString();
    }

    private com.mongodb.client.MongoCollection<Document> semesterAssignmentsForTest() {
        return mongoClient.getDatabase(databaseName).getCollection("semester_assignments");
    }

    private com.mongodb.client.MongoCollection<Document> fieldInstancesForTest() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    @Test
    void roleOptionsReturnsAssignedRolesPlusKochen() {
        Person me = persistCurrentPerson();
        String semesterId = persistSemester();
        assignRole(me.id, semesterId, "Gartenteam");

        given()
            .when().get("/api/v1/hour-entries/role-options")
            .then().statusCode(200)
            .body("label", hasItem("Gartenteam"))
            .body("label", hasItem("Kochen"));
    }

    private HourEntry persistEntry(ObjectId personId, String semesterId, String date, int minutes) {
        HourEntry e = new HourEntry();
        e.personId = personId;
        e.semesterId = new ObjectId(semesterId);
        e.roleFieldInstanceId = null;
        e.roleLabel = "Kochen";
        e.date = date;
        e.minutes = minutes;
        e.comment = "";
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.persist();
        return e;
    }

    @Test
    void ownerCanUpdateOwnEntry() {
        Person me = persistCurrentPerson();
        String semesterId = persistSemester();
        HourEntry e = persistEntry(me.id, semesterId, "2026-10-05", 60);

        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-06\",\"minutes\":120,\"comment\":\"korrigiert\"}")
            .when().put("/api/v1/hour-entries/" + e.id)
            .then().statusCode(200)
            .body("minutes", is(120))
            .body("date", is("2026-10-06"))
            .body("comment", is("korrigiert"));
    }

    @Test
    void ownerCanDeleteOwnEntry() {
        Person me = persistCurrentPerson();
        String semesterId = persistSemester();
        HourEntry e = persistEntry(me.id, semesterId, "2026-10-05", 60);

        given().when().delete("/api/v1/hour-entries/" + e.id).then().statusCode(204);
        given().when().get("/api/v1/hour-entries/me").then().statusCode(200).body("size()", is(0));
    }

    @Test
    void nonOwnerNonAdminCannotUpdateForeignEntry() {
        persistCurrentPerson();              // aktuelle (nicht-Admin) Person
        String semesterId = persistSemester();
        HourEntry foreign = persistEntry(new ObjectId(), semesterId, "2026-10-05", 60); // anderer personId

        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-06\",\"minutes\":120,\"comment\":\"x\"}")
            .when().put("/api/v1/hour-entries/" + foreign.id)
            .then().statusCode(403);
    }

    @Test
    void nonOwnerNonAdminCannotDeleteForeignEntry() {
        persistCurrentPerson();
        String semesterId = persistSemester();
        HourEntry foreign = persistEntry(new ObjectId(), semesterId, "2026-10-05", 60);

        given().when().delete("/api/v1/hour-entries/" + foreign.id).then().statusCode(403);
    }

    @Test
    void adminCanUpdateForeignEntry() {
        // Admin-Person: roles verweist auf field_instance mit value "ADMIN".
        ObjectId adminInst = new ObjectId();
        fieldInstancesForTest().insertOne(new Document("_id", adminInst).append("value", "ADMIN"));
        Person admin = new Person();
        admin.roles = new java.util.ArrayList<>();
        admin.roles.add(new at.kigruapp.entity.FieldRef(new ObjectId(), adminInst));
        admin.createdAt = Instant.now();
        admin.updatedAt = admin.createdAt;
        admin.persist(); // einzige Person mit ADMIN-Rolle -> current user = admin

        String semesterId = persistSemester();
        ObjectId ownerId = new ObjectId();
        HourEntry foreign = persistEntry(ownerId, semesterId, "2026-10-05", 60);

        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":null,\"date\":\"2026-10-07\",\"minutes\":45,\"comment\":\"admin-fix\"}")
            .when().put("/api/v1/hour-entries/" + foreign.id)
            .then().statusCode(200)
            .body("minutes", is(45))
            .body("comment", is("admin-fix"))
            .body("personId", is(ownerId.toHexString())); // personId bleibt beim Eigentümer
    }

    @Test
    void roleChangedResolvesAgainstOwnerContextNotCurrentUser() {
        // Aktueller User = Admin (Nicht-Eigentümer), ohne die Rolle "Gartenteam".
        ObjectId adminInst = new ObjectId();
        fieldInstancesForTest().insertOne(new Document("_id", adminInst).append("value", "ADMIN"));
        Person admin = new Person();
        admin.roles = new java.util.ArrayList<>();
        admin.roles.add(new at.kigruapp.entity.FieldRef(new ObjectId(), adminInst));
        admin.createdAt = Instant.now();
        admin.updatedAt = admin.createdAt;
        admin.persist(); // einzige Person mit ADMIN-Rolle -> current user = admin

        Person owner = persistCurrentPerson(); // fremder Eigentümer (andere personId)
        String semesterId = persistSemester();
        String instId = assignRole(owner.id, semesterId, "Gartenteam"); // Rolle NUR dem Owner zugewiesen
        HourEntry foreign = persistEntry(owner.id, semesterId, "2026-10-05", 60); // Kochen

        // Admin ändert die Rolle auf eine Rolle, die nur dem OWNER zugewiesen ist.
        given().contentType(ContentType.JSON)
            .body("{\"roleFieldInstanceId\":\"" + instId + "\",\"date\":\"2026-10-08\",\"minutes\":30,\"comment\":\"rolle geaendert\"}")
            .when().put("/api/v1/hour-entries/" + foreign.id)
            .then().statusCode(200)
            .body("roleFieldInstanceId", is(instId))
            .body("roleLabel", is("Gartenteam")); // gelingt nur, wenn resolveRole(entry.personId, ...) genutzt wird
    }
}
