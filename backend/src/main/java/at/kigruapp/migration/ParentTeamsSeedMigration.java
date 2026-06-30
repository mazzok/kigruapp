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

@ApplicationScoped
@Startup
public class ParentTeamsSeedMigration {

    private static final String MIGRATION_ID = "seed-parent-teams-v1";

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

        if (orgColl.find(new Document("tag", "parent-teams")).first() == null) {
            orgColl.insertOne(new Document()
                    .append("tag", "parent-teams")
                    .append("definitionIds", new ArrayList<>())
            );
        }

        migrations.insertOne(new Document("_id", MIGRATION_ID));
    }
}
