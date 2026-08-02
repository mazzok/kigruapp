package at.kigruapp.service;

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

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PersonLookupServiceTest {

    @Inject
    PersonLookupService lookup;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    ObjectId personTypeDefId;

    @BeforeEach
    void cleanup() {
        Person.deleteAll();
        Semester.deleteAll();
        FieldDefinition.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();

        FieldDefinition def = new FieldDefinition();
        def.fieldName = "personType";
        def.createdAt = Instant.now();
        def.persist();
        personTypeDefId = def.id;
    }

    private Person persistPerson(String personType) {
        ObjectId instanceId = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", instanceId)
                        .append("definitionId", personTypeDefId)
                        .append("value", personType));
        Person p = new Person();
        p.familyId = new ObjectId();
        p.basicProperties.add(new FieldRef(personTypeDefId, instanceId));
        p.createdAt = Instant.now();
        p.persist();
        return p;
    }

    @Test
    void isChildRecognisesChildAndRejectsParent() {
        assertTrue(lookup.isChild(persistPerson("CHILD")));
        assertFalse(lookup.isChild(persistPerson("PARENT")));
    }

    @Test
    void isParentRecognisesParentAndRejectsChild() {
        assertTrue(lookup.isParent(persistPerson("PARENT")));
        assertFalse(lookup.isParent(persistPerson("CHILD")));
    }

    @Test
    void personWithoutPropertiesIsNeitherChildNorParent() {
        Person p = new Person();
        p.createdAt = Instant.now();
        p.persist();
        assertFalse(lookup.isChild(p));
        assertFalse(lookup.isParent(p));
    }

    @Test
    void resolveNewestSemesterIdReturnsNullWithoutSemester() {
        assertNull(lookup.resolveNewestSemesterId());
    }

    @Test
    void resolveNewestSemesterIdReturnsMostRecentlyCreated() {
        Semester older = new Semester();
        older.start = Instant.parse("2025-09-01T00:00:00Z");
        older.end = Instant.parse("2026-02-28T00:00:00Z");
        older.createdAt = Instant.parse("2025-08-01T00:00:00Z");
        older.persist();

        Semester newer = new Semester();
        newer.start = Instant.parse("2026-09-01T00:00:00Z");
        newer.end = Instant.parse("2027-02-28T00:00:00Z");
        newer.createdAt = Instant.parse("2026-08-01T00:00:00Z");
        newer.persist();

        assertEquals(newer.id, lookup.resolveNewestSemesterId());
    }
}
