package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.ParentDirectorySettings;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ParentDirectoryAttributeServiceTest {

    @Inject
    ParentDirectoryAttributeService service;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void setUp() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        ParentDirectorySettings.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();
    }

    private FieldDefinition persistDefinition(String fieldName, String labelDe) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        if (labelDe != null) def.label = Map.of("de", labelDe);
        def.createdAt = Instant.now();
        def.persist();
        return def;
    }

    private void persistPersonWithCustomField(ObjectId definitionId) {
        ObjectId instanceId = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", instanceId)
                        .append("definitionId", definitionId)
                        .append("value", "ja"));
        Person p = new Person();
        p.customProperties.add(new FieldRef(definitionId, instanceId));
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
    }

    @Test
    void catalogContainsCoreAttributesInFixedOrder() {
        List<String> keys = service.catalog().stream().map(ParentDirectoryAttributeService.CatalogEntry::key).toList();

        assertEquals(List.of("childName", "childEntryDate", "childExitDate",
                "firstName", "lastName", "email", "phone", "team", "role", "address"), keys);
    }

    @Test
    void catalogContainsCustomFieldsUsedByPersons() {
        FieldDefinition allergies = persistDefinition("allergies", "Allergien");
        persistPersonWithCustomField(allergies.id);

        var entry = service.catalog().stream()
                .filter(e -> e.key().equals("custom:" + allergies.id.toHexString()))
                .findFirst().orElseThrow();

        assertEquals("Allergien", entry.label());
        assertEquals("PARENT", entry.scope());
    }

    @Test
    void catalogIgnoresDefinitionsNoPersonUses() {
        persistDefinition("group", "Gruppen");

        assertTrue(service.catalog().stream().noneMatch(e -> e.key().startsWith("custom:")));
    }

    @Test
    void defaultsApplyWhenNothingWasSaved() {
        assertEquals(Set.of("childName", "firstName", "lastName", "email", "phone", "address"),
                service.visibleKeys());
    }

    @Test
    void savedSelectionIsReturnedAndChildNameIsForced() {
        service.save(List.of("firstName", "team"));

        assertEquals(Set.of("childName", "firstName", "team"), service.visibleKeys());
    }

    @Test
    void savingTwiceReplacesTheSelectionInsteadOfAppending() {
        service.save(List.of("firstName", "team"));
        service.save(List.of("email"));

        assertEquals(Set.of("childName", "email"), service.visibleKeys());
        assertEquals(1, ParentDirectorySettings.count());
    }

    @Test
    void unknownKeyIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save(List.of("firstName", "salary")));

        assertTrue(ex.getMessage().contains("salary"));
    }

    @Test
    void visibleCatalogKeepsCatalogOrderAndDropsUnselected() {
        service.save(List.of("email", "childName", "firstName"));

        assertEquals(List.of("childName", "firstName", "email"),
                service.visibleCatalog().stream()
                        .map(ParentDirectoryAttributeService.CatalogEntry::key).toList());
        assertFalse(service.visibleKeys().contains("address"));
    }
}
