package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.arc.All;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the real CDI wiring: {@code MailTemplateRenderer}'s {@code @Inject @All
 * List<MailBlockRenderer>} field actually picks up {@link CookingDutyMailBlockRenderer}
 * as a managed bean. {@link MailTemplateRendererTest} deliberately stays CDI-free
 * (it constructs the renderer directly with hand-supplied fakes), so nothing else
 * exercises this seam. Because that field has a {@code = List.of()} initializer, a
 * broken CDI wiring would silently render every block marker as empty text instead
 * of failing loudly — this test would catch exactly that regression.
 */
@QuarkusTest
class MailTemplateRendererCdiTest {

    @Inject
    MailTemplateRenderer renderer;

    @Inject
    @All
    List<MailBlockRenderer> blockRenderers;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
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
        def.createdAt = Instant.now();
        def.persist();
        return def;
    }

    private ObjectId persistScalarInstance(ObjectId definitionId, String value) {
        ObjectId id = new ObjectId();
        fieldInstances().insertOne(new Document("_id", id).append("definitionId", definitionId).append("value", value));
        return id;
    }

    @Test
    void theBlockRendererListInjectedViaCdiIsNotEmptyAndContainsACookingDutySupportingRenderer() {
        assertFalse(blockRenderers.isEmpty(), "CDI should wire at least one MailBlockRenderer bean");
        assertTrue(blockRenderers.stream().anyMatch(r -> r.supports("cookingDuty")),
                "CDI-wired renderers should include one that supports 'cookingDuty'");
    }

    @Test
    void aCookingDutyBlockMarkerResolvesToNonEmptyRendererOutputThroughTheRealCdiWiredRenderer() throws Exception {
        FieldDefinition cookingDutyDef = persistDefinition("cookingDuty");
        FieldDefinition groupDef = persistDefinition("group");
        FieldDefinition firstNameDef = persistDefinition("firstName");
        FieldDefinition lastNameDef = persistDefinition("lastName");

        ObjectId groupInstanceId = new ObjectId();
        fieldInstances().insertOne(new Document("_id", groupInstanceId)
                .append("definitionId", groupDef.id)
                .append("value", new Document("label", "Rote Gruppe").append("color", "#f00")));

        String today = LocalDate.now(ZoneId.of("Europe/Vienna")).toString();
        ObjectId dutyInstanceId = new ObjectId();
        fieldInstances().insertOne(new Document("_id", dutyInstanceId)
                .append("definitionId", cookingDutyDef.id)
                .append("value", new Document("date", today)
                        .append("groups", List.of(groupInstanceId.toHexString()))
                        .append("description", "Suppe")));

        Person p = new Person();
        p.familyId = new ObjectId();
        p.basicProperties = List.of(
                new FieldRef(lastNameDef.id, persistScalarInstance(lastNameDef.id, "Muster")),
                new FieldRef(firstNameDef.id, persistScalarInstance(firstNameDef.id, "Anna")));
        p.schedules = List.of(new FieldRef(cookingDutyDef.id, dutyInstanceId));
        p.persist();

        ObjectNode config = mapper.createObjectNode()
                .put("type", "cookingDuty")
                .put("groupId", groupInstanceId.toHexString())
                .put("periodUnit", "week")
                .put("periodAmount", 2);
        String encodedConfig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(config));

        String result = renderer.render("<p>Vorher</p>{{block.cookingDuty:" + encodedConfig + "}}<p>Nachher</p>",
                Map.of());

        assertTrue(result.contains("<table"), "expected the cooking-duty renderer's table markup, got: " + result);
        assertTrue(result.contains("Muster Anna"));
        assertFalse(result.contains("{{block."), "marker should have been fully replaced");
    }
}
