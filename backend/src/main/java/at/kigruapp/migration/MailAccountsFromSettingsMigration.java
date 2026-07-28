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

import java.time.Instant;
import java.util.Date;

/**
 * One-time: import the legacy {@code mail_settings} singleton as an ordinary
 * {@code mail_accounts} document, then drop {@code mail_settings}. Idempotent.
 */
@ApplicationScoped
@Startup
public class MailAccountsFromSettingsMigration {

    private static final String MIGRATION_ID = "mail-accounts-from-settings-v1";

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

        MongoCollection<Document> accounts = db.getCollection("mail_accounts");
        MongoCollection<Document> settings = db.getCollection("mail_settings");

        Document s = settings.find().first();
        if (s != null && accounts.countDocuments() == 0) {
            String from = s.getString("fromAddress");
            String name = (from != null && !from.isBlank()) ? from : "Standard";
            accounts.insertOne(new Document("_id", new ObjectId())
                    .append("name", name)
                    .append("host", s.getString("host"))
                    .append("port", s.get("port"))
                    .append("encryption", s.get("encryption"))
                    .append("username", s.getString("username"))
                    .append("encryptedPassword", s.getString("encryptedPassword"))
                    .append("fromAddress", from)
                    .append("fromName", s.getString("fromName"))
                    .append("enabled", s.get("enabled")));
        }

        settings.drop();
        migrations.insertOne(new Document("_id", MIGRATION_ID).append("executedAt", Date.from(Instant.now())));
    }
}
