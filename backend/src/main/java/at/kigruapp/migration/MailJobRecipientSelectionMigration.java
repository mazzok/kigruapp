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
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time: replace the mutually exclusive {@code recipientMode} /
 * {@code recipientGroupDefinitionIds} pair with {@code allParents} plus the
 * combinable {@code recipientSelections} list. Idempotent via the migrations
 * collection.
 */
@ApplicationScoped
@Startup
public class MailJobRecipientSelectionMigration {

    private static final String MIGRATION_ID = "mailjob-recipient-selections-v1";

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

        MongoCollection<Document> jobs = db.getCollection("mail_jobs");
        for (Document job : jobs.find()) {
            String mode = job.getString("recipientMode");
            boolean allParents = !"GROUPS".equals(mode);

            List<Document> selections = new ArrayList<>();
            if (!allParents) {
                for (ObjectId instanceId : job.getList("recipientGroupDefinitionIds", ObjectId.class, List.of())) {
                    selections.add(new Document("kind", "GROUP").append("fieldInstanceId", instanceId));
                }
            }

            jobs.updateOne(new Document("_id", job.getObjectId("_id")), new Document()
                    .append("$set", new Document("allParents", allParents)
                            .append("recipientSelections", selections))
                    .append("$unset", new Document("recipientMode", "")
                            .append("recipientGroupDefinitionIds", "")));
        }

        migrations.insertOne(new Document("_id", MIGRATION_ID));
    }
}
