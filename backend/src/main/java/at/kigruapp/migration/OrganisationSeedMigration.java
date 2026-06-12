package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Startup
public class OrganisationSeedMigration {

    private static final String MIGRATION_ID = "seed-organisation-v1";

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

        MongoCollection<Document> orgColl = db.getCollection("organisation");
        MongoCollection<Document> defs = db.getCollection("field_definitions");

        // Create groups document (initially empty)
        if (orgColl.find(new Document("tag", "groups")).first() == null) {
            orgColl.insertOne(new Document()
                    .append("tag", "groups")
                    .append("definitionIds", new ArrayList<>())
            );
        }

        // Create duty-settings document with cooking food-property references
        if (orgColl.find(new Document("tag", "duty-settings")).first() == null) {
            List<Object> foodDefIds = new ArrayList<>();
            for (Document def : defs.find(new Document("fieldName", "food-property"))) {
                foodDefIds.add(def.getObjectId("_id"));
            }

            Document dutySettings = new Document()
                    .append("tag", "duty-settings")
                    .append("entries", List.of(
                            new Document("name", "cooking").append("definitionIds", foodDefIds),
                            new Document("name", "cleaning").append("definitionIds", new ArrayList<>()),
                            new Document("name", "time-substitution").append("definitionIds", new ArrayList<>())
                    ));
            orgColl.insertOne(dutySettings);
        }

        migrations.insertOne(new Document("_id", MIGRATION_ID));
    }
}
