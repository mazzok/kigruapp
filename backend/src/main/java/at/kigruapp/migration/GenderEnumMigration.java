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

@ApplicationScoped
@Startup
public class GenderEnumMigration {

    private static final String MIGRATION_ID = "gender-enum-bub-maedchen-v1";

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

        MongoCollection<Document> defs = db.getCollection("field_definitions");

        defs.updateOne(
                new Document("fieldName", "gender"),
                new Document("$set", new Document("jsonSchema",
                        new Document("type", "string").append("enum", List.of("Bub", "Mädchen"))))
        );

        Date now = Date.from(Instant.now());
        migrations.insertOne(new Document("_id", MIGRATION_ID).append("executedAt", now));
    }
}
