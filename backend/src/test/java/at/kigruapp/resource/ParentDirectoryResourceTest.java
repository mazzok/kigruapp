package at.kigruapp.resource;

import at.kigruapp.entity.Family;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
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
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ParentDirectoryResourceTest {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    ObjectId personTypeDefId;
    ObjectId firstNameDefId;
    ObjectId emailDefId;
    ObjectId groupDefId;
    ObjectId semesterId;

    @BeforeEach
    void setUp() {
        Person.deleteAll();
        Family.deleteAll();
        Semester.deleteAll();
        FieldDefinition.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments").drop();

        personTypeDefId = persistDefinition("personType");
        firstNameDefId = persistDefinition("firstName");
        emailDefId = persistDefinition("email");
        groupDefId = persistDefinition("group");

        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        s.createdAt = Instant.parse("2026-08-01T00:00:00Z");
        s.persist();
        semesterId = s.id;
    }

    private ObjectId persistDefinition(String fieldName) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.createdAt = Instant.now();
        def.persist();
        return def.id;
    }

    private ObjectId persistInstance(ObjectId definitionId, String value) {
        ObjectId id = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", id)
                        .append("definitionId", definitionId)
                        .append("value", value));
        return id;
    }

    private ObjectId persistFamily(String name) {
        Family f = new Family();
        f.name = name;
        f.address = Map.of("street", "Hauptstrasse 1", "zip", "1010", "city", "Wien");
        f.createdAt = Instant.now();
        f.persist();
        return f.id;
    }

    private Person persistPerson(ObjectId familyId, String personType, String firstName, String email) {
        Person p = new Person();
        p.familyId = familyId;
        p.basicProperties.add(new FieldRef(personTypeDefId, persistInstance(personTypeDefId, personType)));
        if (firstName != null) p.basicProperties.add(new FieldRef(firstNameDefId, persistInstance(firstNameDefId, firstName)));
        if (email != null) p.basicProperties.add(new FieldRef(emailDefId, persistInstance(emailDefId, email)));
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p;
    }

    private void assign(ObjectId childId, ObjectId groupInstanceId) {
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments")
                .insertOne(new Document("_id", new ObjectId())
                        .append("personId", childId)
                        .append("semesterId", semesterId)
                        .append("section", "group")
                        .append("definitionId", groupDefId)
                        .append("fieldInstanceId", groupInstanceId));
    }

    @Test
    void returnsOwnGroupWithOwnFamilyFirst() {
        ObjectId ownFamily = persistFamily("Muster");
        // Zuerst persistierte Person = aktueller Benutzer im Dev-Modus.
        persistPerson(ownFamily, "PARENT", "Anna", "anna@x.at");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", null);

        ObjectId otherFamily = persistFamily("Sommer");
        Person otherChild = persistPerson(otherFamily, "CHILD", "Tim", null);
        persistPerson(otherFamily, "PARENT", "Clara", "clara@y.at");

        ObjectId kaefer = persistInstance(groupDefId, "Kaefergruppe");
        assign(ownChild.id, kaefer);
        assign(otherChild.id, kaefer);

        given().when().get("/api/v1/parent-directory")
            .then().statusCode(200)
            .body("semesterId", is(semesterId.toHexString()))
            .body("groups.size()", is(1))
            .body("groups[0].groupName", is("Kaefergruppe"))
            .body("groups[0].families.size()", is(2))
            .body("groups[0].families[0].isOwnFamily", is(true))
            .body("groups[0].families[0].children", contains("Lena"))
            .body("groups[0].families[0].address", is("Hauptstrasse 1, 1010 Wien"))
            .body("groups[0].families[1].isOwnFamily", is(false))
            .body("groups[0].families[1].parents[0].firstName", is("Clara"))
            .body("groups[0].families[1].parents[0].email", is("clara@y.at"));
    }

    @Test
    void doesNotLeakPersonIds() {
        ObjectId ownFamily = persistFamily("Muster");
        persistPerson(ownFamily, "PARENT", "Anna", "anna@x.at");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", null);
        assign(ownChild.id, persistInstance(groupDefId, "Kaefergruppe"));

        String body = given().when().get("/api/v1/parent-directory")
            .then().statusCode(200)
            .extract().asString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains(ownChild.id.toHexString()),
                "Antwort darf keine Personen-IDs enthalten");
    }

    @Test
    void returnsEmptyGroupsWithoutAnyGroupAssignment() {
        ObjectId ownFamily = persistFamily("Muster");
        persistPerson(ownFamily, "PARENT", "Anna", "anna@x.at");
        persistPerson(ownFamily, "CHILD", "Lena", null);

        given().when().get("/api/v1/parent-directory")
            .then().statusCode(200)
            .body("groups.size()", is(0));
    }

    @Test
    void returnsEmptyResultWithoutSemester() {
        Semester.deleteAll();
        ObjectId ownFamily = persistFamily("Muster");
        persistPerson(ownFamily, "PARENT", "Anna", "anna@x.at");
        persistPerson(ownFamily, "CHILD", "Lena", null);

        given().when().get("/api/v1/parent-directory")
            .then().statusCode(200)
            .body("semesterId", nullValue())
            .body("groups.size()", is(0));
    }
}
