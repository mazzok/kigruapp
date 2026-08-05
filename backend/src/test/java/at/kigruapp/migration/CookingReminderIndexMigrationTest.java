package at.kigruapp.migration;

import at.kigruapp.entity.CookingReminder;
import at.kigruapp.entity.CookingReminderStatus;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CookingReminderIndexMigrationTest {

    @Inject
    MongoClient mongoClient;

    @Inject
    CookingReminderIndexMigration migration;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void cleanup() {
        CookingReminder.deleteAll();
    }

    private List<String> indexNames() {
        List<String> names = new ArrayList<>();
        for (Document index : mongoClient.getDatabase(databaseName)
                .getCollection("cooking_reminders").listIndexes()) {
            names.add(index.getString("name"));
        }
        return names;
    }

    private void persistReminder(ObjectId dutyId, String dueDate, ObjectId jobId) {
        CookingReminder reminder = new CookingReminder();
        reminder.dutyId = dutyId;
        reminder.dueDate = dueDate;
        reminder.dutyDate = "2026-08-10";
        reminder.jobId = jobId;
        reminder.sentAt = Instant.now();
        reminder.status = CookingReminderStatus.SENT;
        reminder.recipientCount = 1;
        reminder.persist();
    }

    @Test
    void indexCoversJobId() {
        migration.run();

        assertTrue(indexNames().contains("dutyId_1_dueDate_1_jobId_1"));
        assertTrue(!indexNames().contains("dutyId_1_dueDate_1"));
    }

    @Test
    void twoJobsMayLogTheSameDutyOnTheSameDay() {
        migration.run();
        ObjectId dutyId = new ObjectId();

        persistReminder(dutyId, "2026-08-08", new ObjectId());
        persistReminder(dutyId, "2026-08-08", new ObjectId());

        assertTrue(CookingReminder.count() == 2);
    }
}
