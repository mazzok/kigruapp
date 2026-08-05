package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

/**
 * One-time: erweitert das JSON-Schema der Definition {@code cookingDuty} um die
 * optionalen Felder {@code reminderEnabled} und {@code reminderDaysBefore}.
 * Ohne diesen Schritt weist der JsonSchemaValidatorService Kochdienste mit
 * Erinnerung ab. Idempotent über die migrations-Kollektion.
 */
@ApplicationScoped
@Startup
public class CookingDutyReminderSchemaMigration {

    private static final String MIGRATION_ID = "cookingduty-reminder-schema-v1";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@Observes StartupEvent ev) {
        run();
    }

    void run() {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> migrations = db.getCollection("migrations");
        if (migrations.find(new Document("_id", MIGRATION_ID)).first() != null) {
            return;
        }

        MongoCollection<Document> definitions = db.getCollection("field_definitions");
        definitions.updateOne(new Document("fieldName", "cookingDuty"),
                new Document("$set", new Document()
                        .append("jsonSchema.properties.reminderEnabled", new Document("type", "boolean"))
                        .append("jsonSchema.properties.reminderDaysBefore", new Document("type", "integer")
                                .append("minimum", 1)
                                .append("maximum", 14))));

        migrations.insertOne(new Document("_id", MIGRATION_ID).append("executedAt", Instant.now()));
    }
}
