package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CookingDutyMailBlockRendererTest {

    @Inject
    CookingDutyMailBlockRenderer renderer;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private final ObjectMapper mapper = new ObjectMapper();

    FieldDefinition cookingDutyDef;
    FieldDefinition groupDef;
    FieldDefinition firstNameDef;
    FieldDefinition lastNameDef;
    ObjectId groupInstanceId;

    @BeforeEach
    void setup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());

        cookingDutyDef = persistDefinition("cookingDuty");
        firstNameDef = persistDefinition("firstName");
        lastNameDef = persistDefinition("lastName");
        groupDef = persistDefinition("group");

        groupInstanceId = new ObjectId();
        fieldInstances().insertOne(new Document("_id", groupInstanceId)
                .append("definitionId", groupDef.id)
                .append("value", new Document("label", "Rote Gruppe").append("color", "#f00")));
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

    private ObjectId persistCookingDutyInstance(String date, String description) {
        ObjectId id = new ObjectId();
        Document value = new Document("date", date).append("groups", List.of(groupInstanceId.toHexString())).append("description", description);
        fieldInstances().insertOne(new Document("_id", id).append("definitionId", cookingDutyDef.id).append("value", value));
        return id;
    }

    private void persistPersonWithDuty(String lastName, String firstName, ObjectId dutyInstanceId) {
        Person p = new Person();
        p.familyId = new ObjectId();
        p.basicProperties = List.of(
                new FieldRef(lastNameDef.id, persistScalarInstance(lastNameDef.id, lastName)),
                new FieldRef(firstNameDef.id, persistScalarInstance(firstNameDef.id, firstName)));
        p.schedules = List.of(new FieldRef(cookingDutyDef.id, dutyInstanceId));
        p.persist();
    }

    private JsonNode config(String groupId, String periodUnit, int periodAmount) {
        return mapper.createObjectNode()
                .put("type", "cookingDuty")
                .put("groupId", groupId)
                .put("periodUnit", periodUnit)
                .put("periodAmount", periodAmount);
    }

    @Test
    void supportsOnlyCookingDuty() {
        assertTrue(renderer.supports("cookingDuty"));
        assertFalse(renderer.supports("somethingElse"));
    }

    @Test
    void rendersATableRowPerEntryWithinTheRelativePeriod() {
        String today = LocalDate.now(ZoneId.of("Europe/Vienna")).toString();
        persistPersonWithDuty("Muster", "Anna", persistCookingDutyInstance(today, "Suppe"));

        String html = renderer.render(config(groupInstanceId.toHexString(), "week", 2));

        assertTrue(html.contains("<table"));
        assertTrue(html.contains("Muster Anna"));
        assertTrue(html.contains("Suppe"));
        assertTrue(html.contains(today));
    }

    @Test
    void rendersAHintWhenNoEntriesFallInThePeriod() {
        String farFuture = LocalDate.now(ZoneId.of("Europe/Vienna")).plusYears(5).toString();
        persistPersonWithDuty("Muster", "Anna", persistCookingDutyInstance(farFuture, "Suppe"));

        String html = renderer.render(config(groupInstanceId.toHexString(), "week", 2));

        assertFalse(html.contains("<table"));
        assertTrue(html.contains("Keine Kochdienst-Einträge im gewählten Zeitraum."));
    }

    @Test
    void rendersAHintWhenTheGroupNoLongerExists() {
        String html = renderer.render(config(new ObjectId().toHexString(), "week", 2));

        assertFalse(html.contains("<table"));
        assertTrue(html.contains("Gruppe nicht mehr vorhanden."));
    }

    @Test
    void rendersAHintWhenTheGroupIdIsMalformed() {
        String html = renderer.render(config("not-an-object-id", "week", 2));

        assertTrue(html.contains("Gruppe nicht mehr vorhanden."));
    }
}
