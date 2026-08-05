package at.kigruapp.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Legt den Unique-Index (dutyId, dueDate, jobId) auf cooking_reminders an. Läuft bei
 * jedem Start; createIndex ist für einen bereits vorhandenen, identischen
 * Index ein No-Op.
 */
@ApplicationScoped
@Startup
public class CookingReminderIndexMigration {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@Observes StartupEvent ev) {
        run();
    }

    public void run() {
        MongoCollection<Document> collection = mongoClient.getDatabase(databaseName)
                .getCollection("cooking_reminders");
        try {
            collection.dropIndex("dutyId_1_dueDate_1");
        } catch (RuntimeException e) {
            // Index existiert nicht (Neuinstallation oder bereits migriert) — kein Fehlerfall.
        }
        collection.createIndex(new Document("dutyId", 1).append("dueDate", 1).append("jobId", 1),
                new IndexOptions().unique(true));
    }
}
