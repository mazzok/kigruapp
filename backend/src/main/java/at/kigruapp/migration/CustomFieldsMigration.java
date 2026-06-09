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
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Startup
public class CustomFieldsMigration {

    private static final String MIGRATION_ID = "custom-fields-to-field-instances-v1";

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

        migrateFieldDefinitions(db);
        migrateEntityCustomFields(db, "parents", "PARENT");
        migrateEntityCustomFields(db, "children", "CHILD");
        migrateEntityCustomFields(db, "families", "FAMILY");

        createIndexes(db);

        migrations.insertOne(new Document("_id", MIGRATION_ID)
                .append("executedAt", Instant.now().toString()));
    }

    private void migrateFieldDefinitions(MongoDatabase db) {
        MongoCollection<Document> defs = db.getCollection("field_definitions");

        for (Document def : defs.find()) {
            if (def.containsKey("jsonSchema")) {
                continue;
            }
            String type = def.getString("type");
            if (type == null) {
                continue;
            }

            Document jsonSchema = switch (type) {
                case "TEXT" -> new Document("type", "string");
                case "DATE" -> new Document("type", "string").append("format", "date");
                case "BOOLEAN" -> new Document("type", "boolean");
                case "SELECT" -> {
                    Document schema = new Document("type", "string");
                    List<String> options = def.getList("options", String.class);
                    if (options != null && !options.isEmpty()) {
                        schema.append("enum", options);
                    }
                    yield schema;
                }
                default -> new Document("type", "string");
            };

            defs.updateOne(
                    new Document("_id", def.getObjectId("_id")),
                    new Document("$set", new Document()
                            .append("jsonSchema", jsonSchema)
                            .append("createdAt", Instant.now().toString())
                    ).append("$unset", new Document()
                            .append("type", "")
                            .append("options", "")
                    )
            );
        }
    }

    @SuppressWarnings("unchecked")
    private void migrateEntityCustomFields(MongoDatabase db, String collectionName, String entityType) {
        MongoCollection<Document> entities = db.getCollection(collectionName);
        MongoCollection<Document> instances = db.getCollection("field_instances");
        MongoCollection<Document> defs = db.getCollection("field_definitions");

        for (Document entity : entities.find(new Document("customFields", new Document("$exists", true)))) {
            Object customFieldsRaw = entity.get("customFields");
            if (!(customFieldsRaw instanceof Map)) {
                continue;
            }
            Map<String, Object> customFields = (Map<String, Object>) customFieldsRaw;
            ObjectId entityId = entity.getObjectId("_id");

            for (Map.Entry<String, Object> entry : customFields.entrySet()) {
                Document defDoc = defs.find(new Document()
                        .append("fieldName", entry.getKey())
                        .append("entity", entityType)
                ).first();

                if (defDoc == null) {
                    continue;
                }

                Document existing = instances.find(new Document()
                        .append("definitionId", defDoc.getObjectId("_id"))
                        .append("entityId", entityId)
                ).first();

                if (existing != null) {
                    continue;
                }

                String now = Instant.now().toString();
                instances.insertOne(new Document()
                        .append("definitionId", defDoc.getObjectId("_id"))
                        .append("entityType", entityType)
                        .append("entityId", entityId)
                        .append("value", entry.getValue())
                        .append("createdAt", now)
                        .append("updatedAt", now)
                );
            }

            entities.updateOne(
                    new Document("_id", entityId),
                    new Document("$unset", new Document("customFields", ""))
            );
        }
    }

    private void createIndexes(MongoDatabase db) {
        MongoCollection<Document> instances = db.getCollection("field_instances");
        instances.createIndex(new Document("entityType", 1).append("entityId", 1));
        instances.createIndex(new Document("definitionId", 1));
        instances.createIndex(new Document("definitionId", 1).append("entityId", 1));

        MongoCollection<Document> defs = db.getCollection("field_definitions");
        defs.createIndex(new Document("entity", 1).append("outdatedAt", 1));
    }
}
