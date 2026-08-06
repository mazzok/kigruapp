package at.kigruapp.service;

import at.kigruapp.dto.CookingDutyDTO;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CookingDutyQueryServiceTest {

    @Inject
    CookingDutyQueryService queryService;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    FieldDefinition cookingDutyDef;
    FieldDefinition firstNameDef;
    FieldDefinition lastNameDef;

    @BeforeEach
    void setup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());

        cookingDutyDef = persistDefinition("cookingDuty");
        firstNameDef = persistDefinition("firstName");
        lastNameDef = persistDefinition("lastName");
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

    private ObjectId persistScalarInstance(ObjectId definitionId, String value) {
        ObjectId id = new ObjectId();
        fieldInstances().insertOne(new Document("_id", id).append("definitionId", definitionId).append("value", value));
        return id;
    }

    private ObjectId persistCookingDutyInstance(String date, List<String> groups, String description) {
        ObjectId id = new ObjectId();
        Document value = new Document("date", date).append("groups", groups).append("description", description);
        fieldInstances().insertOne(new Document("_id", id).append("definitionId", cookingDutyDef.id).append("value", value));
        return id;
    }

    private Person persistPersonWithDuty(String lastName, String firstName, ObjectId dutyInstanceId) {
        Person p = new Person();
        p.familyId = new ObjectId();
        p.basicProperties = List.of(
                new FieldRef(lastNameDef.id, persistScalarInstance(lastNameDef.id, lastName)),
                new FieldRef(firstNameDef.id, persistScalarInstance(firstNameDef.id, firstName)));
        p.schedules = List.of(new FieldRef(cookingDutyDef.id, dutyInstanceId));
        p.persist();
        return p;
    }

    @Test
    void returnsEntriesMatchingDateAndGroupFilter() {
        ObjectId duty = persistCookingDutyInstance("2026-09-10", List.of("g1"), "Suppe");
        persistPersonWithDuty("Muster", "Anna", duty);

        List<CookingDutyDTO> result = queryService.query(date -> date.compareTo("2026-09-01") >= 0 && date.compareTo("2026-09-30") < 0, Set.of("g1"));

        assertEquals(1, result.size());
        assertEquals("2026-09-10", result.get(0).date);
        assertEquals("Muster Anna", result.get(0).personName);
        assertEquals("Suppe", result.get(0).description);
        assertEquals(List.of("g1"), result.get(0).groups);
    }

    @Test
    void excludesEntriesOutsideTheDateFilter() {
        ObjectId duty = persistCookingDutyInstance("2026-10-01", List.of("g1"), "Suppe");
        persistPersonWithDuty("Muster", "Anna", duty);

        List<CookingDutyDTO> result = queryService.query(date -> date.compareTo("2026-09-01") >= 0 && date.compareTo("2026-09-30") < 0, Set.of("g1"));

        assertTrue(result.isEmpty());
    }

    @Test
    void excludesEntriesNotInTheGroupFilter() {
        ObjectId duty = persistCookingDutyInstance("2026-09-10", List.of("g2"), "Suppe");
        persistPersonWithDuty("Muster", "Anna", duty);

        List<CookingDutyDTO> result = queryService.query(date -> true, Set.of("g1"));

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyGroupFilterMeansAllGroups() {
        ObjectId duty = persistCookingDutyInstance("2026-09-10", List.of("g2"), "Suppe");
        persistPersonWithDuty("Muster", "Anna", duty);

        List<CookingDutyDTO> result = queryService.query(date -> true, Set.of());

        assertEquals(1, result.size());
    }

    @Test
    void resultsAreSortedByDate() {
        ObjectId later = persistCookingDutyInstance("2026-09-20", List.of("g1"), "Spät");
        persistPersonWithDuty("Muster", "Bea", later);
        ObjectId earlier = persistCookingDutyInstance("2026-09-05", List.of("g1"), "Früh");
        persistPersonWithDuty("Muster", "Anna", earlier);

        List<CookingDutyDTO> result = queryService.query(date -> true, Set.of("g1"));

        assertEquals(2, result.size());
        assertEquals("2026-09-05", result.get(0).date);
        assertEquals("2026-09-20", result.get(1).date);
    }
}
