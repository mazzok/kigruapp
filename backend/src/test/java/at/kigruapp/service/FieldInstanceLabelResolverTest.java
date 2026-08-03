package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class FieldInstanceLabelResolverTest {

    @Inject
    FieldInstanceLabelResolver resolver;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    ObjectId definitionId;

    @BeforeEach
    void setUp() {
        FieldDefinition.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();

        FieldDefinition def = new FieldDefinition();
        def.fieldName = "group";
        def.label = Map.of("de", "Gruppen");
        def.createdAt = Instant.now();
        def.persist();
        definitionId = def.id;
    }

    private ObjectId persistInstance(Object value) {
        ObjectId id = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", id)
                        .append("definitionId", definitionId)
                        .append("value", value));
        return id;
    }

    @Test
    void objectValueUsesItsLabel() {
        ObjectId id = persistInstance(new Document("label", "Kaefergruppe").append("color", "#f00"));

        assertEquals("Kaefergruppe", resolver.resolveLabels(List.of(id)).get(id));
    }

    @Test
    void stringValueIsUsedDirectly() {
        ObjectId id = persistInstance("Bienengruppe");

        assertEquals("Bienengruppe", resolver.resolveLabels(List.of(id)).get(id));
    }

    @Test
    void valueWithoutLabelFallsBackToDefinitionLabel() {
        ObjectId id = persistInstance(true);

        assertEquals("Gruppen", resolver.resolveLabels(List.of(id)).get(id));
    }

    @Test
    void missingInstanceIsAbsentFromResult() {
        assertTrue(resolver.resolveLabels(List.of(new ObjectId())).isEmpty());
    }

    @Test
    void blankLabelAndBlankFieldNameYieldNull() {
        FieldDefinition nameless = new FieldDefinition();
        nameless.fieldName = "   ";
        nameless.createdAt = Instant.now();
        nameless.persist();

        ObjectId id = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", id)
                        .append("definitionId", nameless.id)
                        .append("value", new Document("color", "#f00")));

        assertNull(resolver.resolveLabels(List.of(id)).get(id));
    }
}
