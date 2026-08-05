package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CookingDutyReminderSchemaMigrationTest {

    private static final String MIGRATION_ID = "cookingduty-reminder-schema-v1";

    @Inject
    CookingDutyReminderSchemaMigration migration;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoCollection<Document> definitions() {
        return mongoClient.getDatabase(databaseName).getCollection("field_definitions");
    }

    private MongoCollection<Document> migrations() {
        return mongoClient.getDatabase(databaseName).getCollection("migrations");
    }

    @BeforeEach
    void setup() {
        definitions().deleteMany(new Document("fieldName", "cookingDuty"));
        migrations().deleteMany(new Document("_id", MIGRATION_ID));

        definitions().insertOne(new Document("fieldName", "cookingDuty")
                .append("jsonSchema", new Document("type", "object")
                        .append("properties", new Document()
                                .append("date", new Document("type", "string").append("format", "date"))
                                .append("groups", new Document("type", "array")))
                        .append("required", List.of("date", "groups"))));
    }

    @Test
    void addsReminderPropertiesToExistingDefinition() {
        migration.run();

        Document def = definitions().find(new Document("fieldName", "cookingDuty")).first();
        assertNotNull(def);
        Document properties = def.get("jsonSchema", Document.class).get("properties", Document.class);

        assertEquals("boolean", properties.get("reminderEnabled", Document.class).getString("type"));
        Document days = properties.get("reminderDaysBefore", Document.class);
        assertEquals("integer", days.getString("type"));
        assertEquals(1, days.getInteger("minimum"));
        assertEquals(14, days.getInteger("maximum"));
    }

    @Test
    void keepsExistingPropertiesAndRequiredList() {
        migration.run();

        Document def = definitions().find(new Document("fieldName", "cookingDuty")).first();
        Document schema = def.get("jsonSchema", Document.class);
        assertNotNull(schema.get("properties", Document.class).get("date"));
        assertEquals(List.of("date", "groups"), schema.get("required"));
    }

    @Test
    void isIdempotentViaMigrationsCollection() {
        migration.run();
        migration.run();

        assertEquals(1, migrations().countDocuments(new Document("_id", MIGRATION_ID)));
        assertTrue(definitions().countDocuments(new Document("fieldName", "cookingDuty")) == 1);
    }
}
