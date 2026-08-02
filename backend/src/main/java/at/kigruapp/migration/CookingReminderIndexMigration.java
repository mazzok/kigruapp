package at.kigruapp.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.MongoClient;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Legt den Unique-Index (dutyId, dueDate) auf cooking_reminders an. Läuft bei
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
        mongoClient.getDatabase(databaseName)
                .getCollection("cooking_reminders")
                .createIndex(new Document("dutyId", 1).append("dueDate", 1),
                        new IndexOptions().unique(true));
    }
}
