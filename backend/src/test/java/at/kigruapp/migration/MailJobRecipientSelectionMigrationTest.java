package at.kigruapp.migration;

import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.RecipientKind;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MailJobRecipientSelectionMigrationTest {

    private static final String MIGRATION_ID = "mailjob-recipient-selections-v1";

    @Inject
    MailJobRecipientSelectionMigration migration;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoDatabase db() {
        return mongoClient.getDatabase(databaseName);
    }

    @BeforeEach
    void reset() {
        db().getCollection("mail_jobs").deleteMany(new Document());
        db().getCollection("migrations").deleteMany(new Document("_id", MIGRATION_ID));
    }

    private Document jobById(ObjectId id) {
        return db().getCollection("mail_jobs").find(new Document("_id", id)).first();
    }

    @Test
    void allParentsModeBecomesAllParentsFlag() {
        ObjectId id = new ObjectId();
        db().getCollection("mail_jobs").insertOne(new Document("_id", id)
                .append("name", "Alle").append("recipientMode", "ALL_PARENTS")
                .append("recipientGroupDefinitionIds", List.of()));

        migration.run();

        Document job = jobById(id);
        assertTrue(job.getBoolean("allParents"));
        assertEquals(List.of(), job.get("recipientSelections"));
        assertFalse(job.containsKey("recipientMode"));
        assertFalse(job.containsKey("recipientGroupDefinitionIds"));
    }

    @Test
    void groupsModeBecomesGroupSelections() {
        ObjectId id = new ObjectId();
        ObjectId g1 = new ObjectId();
        ObjectId g2 = new ObjectId();
        db().getCollection("mail_jobs").insertOne(new Document("_id", id)
                .append("name", "Gruppen").append("recipientMode", "GROUPS")
                .append("recipientGroupDefinitionIds", List.of(g1, g2)));

        migration.run();

        Document job = jobById(id);
        assertFalse(job.getBoolean("allParents"));
        List<Document> selections = job.getList("recipientSelections", Document.class);
        assertEquals(2, selections.size());
        assertEquals("GROUP", selections.get(0).getString("kind"));
        assertEquals(g1, selections.get(0).getObjectId("fieldInstanceId"));
        assertEquals(g2, selections.get(1).getObjectId("fieldInstanceId"));
        assertFalse(job.containsKey("recipientMode"));

        MailJob decoded = MailJob.findById(id);
        assertEquals(RecipientKind.GROUP, decoded.recipientSelections.get(0).kind);
        assertEquals(g1, decoded.recipientSelections.get(0).fieldInstanceId);
    }

    @Test
    void missingModeDefaultsToAllParents() {
        ObjectId id = new ObjectId();
        db().getCollection("mail_jobs").insertOne(new Document("_id", id).append("name", "Ohne Modus"));

        migration.run();

        assertTrue(jobById(id).getBoolean("allParents"));
    }

    @Test
    void secondRunIsANoop() {
        ObjectId id = new ObjectId();
        ObjectId g1 = new ObjectId();
        db().getCollection("mail_jobs").insertOne(new Document("_id", id)
                .append("name", "Gruppen").append("recipientMode", "GROUPS")
                .append("recipientGroupDefinitionIds", List.of(g1)));

        migration.run();
        // Simulate a job saved after the migration; a second run must not touch it.
        db().getCollection("mail_jobs").updateOne(new Document("_id", id),
                new Document("$set", new Document("allParents", true)));
        migration.run();

        Document job = jobById(id);
        assertTrue(job.getBoolean("allParents"));
        // Without the idempotency guard, recipientMode/recipientGroupDefinitionIds are
        // already gone (unset by the first run), so a guardless rerun would compute
        // allParents=true and blank recipientSelections to []. The correct idempotent
        // behavior is that the second run is a true no-op and the selection survives.
        List<Document> selections = job.getList("recipientSelections", Document.class);
        assertEquals(1, selections.size());
        assertEquals("GROUP", selections.get(0).getString("kind"));
        assertEquals(g1, selections.get(0).getObjectId("fieldInstanceId"));
    }
}
