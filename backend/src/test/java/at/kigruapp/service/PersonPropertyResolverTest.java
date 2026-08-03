package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PersonPropertyResolverTest {

    @Inject
    PersonPropertyResolver resolver;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private FieldDefinition persistDefinition(String fieldName) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.createdAt = java.time.Instant.now();
        def.persist();
        return def;
    }

    private ObjectId persistFieldInstance(ObjectId definitionId, String value) {
        ObjectId id = new ObjectId();
        fieldInstances().insertOne(new Document("_id", id).append("definitionId", definitionId).append("value", value));
        return id;
    }

    @Test
    void resolvesAllowlistedFieldsAndOmitsMissingOnes() {
        FieldDefinition firstName = persistDefinition("firstName");
        FieldDefinition email = persistDefinition("email");
        FieldDefinition address = persistDefinition("address");

        ObjectId p1FirstNameInstance = persistFieldInstance(firstName.id, "Peter");
        ObjectId p1EmailInstance = persistFieldInstance(email.id, "peter@example.test");
        ObjectId p1AddressInstance = persistFieldInstance(address.id, "Hauptstrasse 1");

        Person p1 = new Person();
        p1.basicProperties = List.of(
                new FieldRef(firstName.id, p1FirstNameInstance),
                new FieldRef(email.id, p1EmailInstance),
                new FieldRef(address.id, p1AddressInstance)
        );
        p1.persist();

        ObjectId p2FirstNameInstance = persistFieldInstance(firstName.id, "Anna");
        Person p2 = new Person();
        p2.basicProperties = List.of(new FieldRef(firstName.id, p2FirstNameInstance));
        p2.persist();

        Map<ObjectId, Map<String, String>> result = resolver.resolve(List.of(p1, p2));

        assertEquals("Peter", result.get(p1.id).get("firstName"));
        assertEquals("peter@example.test", result.get(p1.id).get("email"));
        assertFalse(result.get(p1.id).containsKey("address"), "compound/non-allowlisted fields must be ignored");

        assertEquals("Anna", result.get(p2.id).get("firstName"));
        assertFalse(result.get(p2.id).containsKey("email"), "missing property is simply absent, not null-valued");
    }

    @Test
    void enrollmentDatesAreNoLongerPersonProperties() {
        FieldDefinition entryDef = persistDefinition("entryDate");

        Person person = new Person();
        person.basicProperties.add(new FieldRef(entryDef.id, persistFieldInstance(entryDef.id, "2026-09-01")));
        person.createdAt = java.time.Instant.now();
        person.updatedAt = person.createdAt;
        person.persist();

        Map<String, String> props = resolver.resolve(List.of(person)).getOrDefault(person.id, Map.of());

        assertFalse(props.containsKey("entryDate"));
    }
}
