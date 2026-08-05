package at.kigruapp.migration;

import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CookingKindRenameMigrationTest {

    @Inject
    CookingKindRenameMigration migration;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        MailJob.deleteAll();
        MailTemplate.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("migrations")
                .deleteOne(new Document("_id", "cooking-kind-rename-v1"));
    }

    private MongoCollection<Document> mailJobs() {
        return mongoClient.getDatabase(databaseName).getCollection("mail_jobs");
    }

    private MongoCollection<Document> mailTemplates() {
        return mongoClient.getDatabase(databaseName).getCollection("mail_templates");
    }

    @Test
    void renamesPersistedCookingKindOnJobsAndTemplates() {
        mailJobs().insertOne(new Document("name", "Alt").append("kind", "COOKING"));
        mailTemplates().insertOne(new Document("name", "Alt").append("kind", "COOKING"));

        migration.run();

        assertEquals(0, mailJobs().countDocuments(new Document("kind", "COOKING")));
        assertEquals(1, mailJobs().countDocuments(new Document("kind", MailJob.KIND_COOKING_REMINDER)));
        assertEquals(0, mailTemplates().countDocuments(new Document("kind", "COOKING")));
        assertEquals(1, mailTemplates().countDocuments(new Document("kind", MailTemplate.KIND_COOKING_REMINDER)));
    }

    @Test
    void isIdempotent() {
        mailJobs().insertOne(new Document("name", "Alt").append("kind", "COOKING"));

        migration.run();
        migration.run();

        assertEquals(1, mailJobs().countDocuments(new Document("kind", MailJob.KIND_COOKING_REMINDER)));
    }

    @Test
    void leavesUnrelatedKindsUntouched() {
        mailJobs().insertOne(new Document("name", "General").append("kind", "GENERAL"));

        migration.run();

        assertEquals(1, mailJobs().countDocuments(new Document("kind", "GENERAL")));
    }
}
