package at.kigruapp.migration;

import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
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
import java.util.Date;

/**
 * Benennt bereits persistierte Dokumente mit kind="COOKING" auf
 * kind="COOKING_REMINDER" um (Umstellung auf zwei Kochdienst-Job-Arten).
 * Idempotent ueber die migrations-Collection, wie GenderEnumMigration.
 */
@ApplicationScoped
@Startup
public class CookingKindRenameMigration {

    private static final String MIGRATION_ID = "cooking-kind-rename-v1";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@Observes StartupEvent ev) {
        run();
    }

    public void run() {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> migrations = db.getCollection("migrations");
        if (migrations.find(new Document("_id", MIGRATION_ID)).first() != null) {
            return;
        }

        db.getCollection("mail_jobs").updateMany(
                new Document("kind", "COOKING"),
                new Document("$set", new Document("kind", MailJob.KIND_COOKING_REMINDER)));
        db.getCollection("mail_templates").updateMany(
                new Document("kind", "COOKING"),
                new Document("$set", new Document("kind", MailTemplate.KIND_COOKING_REMINDER)));

        migrations.insertOne(new Document("_id", MIGRATION_ID).append("executedAt", Date.from(Instant.now())));
    }
}
