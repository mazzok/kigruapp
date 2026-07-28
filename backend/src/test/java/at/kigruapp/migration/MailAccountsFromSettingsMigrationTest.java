package at.kigruapp.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class MailAccountsFromSettingsMigrationTest {

    private static final String MIGRATION_ID = "mail-accounts-from-settings-v1";

    @Inject
    MailAccountsFromSettingsMigration migration;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoDatabase db() {
        return mongoClient.getDatabase(databaseName);
    }

    @BeforeEach
    void reset() {
        db().getCollection("mail_accounts").deleteMany(new Document());
        db().getCollection("mail_settings").deleteMany(new Document());
        db().getCollection("migrations").deleteMany(new Document("_id", MIGRATION_ID));
    }

    @Test
    void importsSingletonAsAccountAndDropsSettings() {
        db().getCollection("mail_settings").insertOne(new Document()
                .append("host", "smtp.example.test").append("port", 587)
                .append("encryption", "STARTTLS").append("username", "user")
                .append("encryptedPassword", "enc").append("fromAddress", "kita@example.test")
                .append("fromName", "Kita").append("enabled", true));

        migration.run();

        Document account = db().getCollection("mail_accounts").find().first();
        assertEquals("kita@example.test", account.getString("name"));
        assertEquals("smtp.example.test", account.getString("host"));
        assertEquals("enc", account.getString("encryptedPassword"));
        assertEquals(0, db().getCollection("mail_settings").countDocuments());
    }

    @Test
    void isIdempotentAndSkipsWhenAccountsExist() {
        db().getCollection("mail_settings").insertOne(new Document()
                .append("host", "h").append("port", 25).append("encryption", "NONE")
                .append("fromAddress", "a@b.test").append("enabled", false));

        migration.run();
        migration.run(); // second run must not duplicate

        assertEquals(1, db().getCollection("mail_accounts").countDocuments());
    }

    @Test
    void noSettingsProducesNoAccount() {
        migration.run();
        assertNull(db().getCollection("mail_accounts").find().first());
    }
}
