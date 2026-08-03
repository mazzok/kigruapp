package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Ein- und Austrittsdatum sind Eigenschaften der Gruppen-Zuweisung, nicht der
 * Person. Die gleichnamigen Personen-Definitionen werden stillgelegt statt
 * geloescht, damit vorhandene FieldRef-Verweise nicht ins Leere zeigen.
 */
@ApplicationScoped
@Startup
public class PersonEnrollmentFieldRetirementMigration {

    private static final String MIGRATION_ID = "retire-person-enrollment-fields-v1";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        MongoDatabase db = mongoClient.getDatabase(databaseName);

        MongoCollection<Document> migrations = db.getCollection("migrations");
        if (migrations.find(new Document("_id", MIGRATION_ID)).first() != null) {
            return;
        }

        Date now = Date.from(Instant.now());
        db.getCollection("field_definitions").updateMany(
                new Document("fieldName", new Document("$in", List.of("entryDate", "exitDate")))
                        .append("outdatedAt", null),
                new Document("$set", new Document("outdatedAt", now)));

        migrations.insertOne(new Document("_id", MIGRATION_ID).append("executedAt", now));
    }
}
