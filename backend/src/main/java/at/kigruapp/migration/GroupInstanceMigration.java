package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Instant;
import java.util.Date;

@ApplicationScoped
@Startup
public class GroupInstanceMigration {

    private static final String MIGRATION_ID = "group-field-instances-v1";

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
        MongoCollection<Document> instances = db.getCollection("field_instances");
        Date now = Date.from(Instant.now());

        // Find all FieldDefinitions with fieldName "group"
        for (Document def : defs.find(new Document("fieldName", "group"))) {
            ObjectId defId = def.getObjectId("_id");
            // Check if a FieldInstance already exists for this definition
            if (instances.find(new Document("definitionId", defId)).first() == null) {
                instances.insertOne(new Document("_id", new ObjectId())
                        .append("definitionId", defId)
                        .append("value", true)
                        .append("createdAt", now)
                        .append("updatedAt", now));
            }
        }

        migrations.insertOne(new Document("_id", MIGRATION_ID).append("executedAt", now));
    }
}
