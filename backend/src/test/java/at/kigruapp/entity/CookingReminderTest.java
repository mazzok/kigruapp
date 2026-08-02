package at.kigruapp.entity;

import at.kigruapp.migration.CookingReminderIndexMigration;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CookingReminderTest {

    @Inject
    CookingReminderIndexMigration indexMigration;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @BeforeEach
    void setup() {
        CookingReminder.deleteAll();
        indexMigration.run();
    }

    private CookingReminder reminder(ObjectId dutyId, String dueDate) {
        CookingReminder reminder = new CookingReminder();
        reminder.dutyId = dutyId;
        reminder.dueDate = dueDate;
        reminder.dutyDate = "2026-09-15";
        reminder.sentAt = Instant.now();
        reminder.status = CookingReminderStatus.SENT;
        reminder.recipientCount = 2;
        return reminder;
    }

    @Test
    void existsForFindsPersistedReminder() {
        ObjectId dutyId = new ObjectId();
        reminder(dutyId, "2026-09-12").persist();

        assertTrue(CookingReminder.existsFor(dutyId, "2026-09-12"));
        assertFalse(CookingReminder.existsFor(dutyId, "2026-09-13"));
        assertFalse(CookingReminder.existsFor(new ObjectId(), "2026-09-12"));
    }

    @Test
    void uniqueIndexRejectsDuplicateDutyAndDueDate() {
        ObjectId dutyId = new ObjectId();
        reminder(dutyId, "2026-09-12").persist();

        assertThrows(MongoWriteException.class, () -> reminder(dutyId, "2026-09-12").persist());
    }

    @Test
    void sameDutyWithDifferentDueDateIsAllowed() {
        ObjectId dutyId = new ObjectId();
        reminder(dutyId, "2026-09-12").persist();
        reminder(dutyId, "2026-09-14").persist();

        assertEquals(2, CookingReminder.count());
    }

    @Test
    void indexMigrationIsIdempotent() {
        indexMigration.run();
        indexMigration.run();

        long indexCount = 0;
        for (Document index : mongoClient.getDatabase(databaseName)
                .getCollection("cooking_reminders").listIndexes()) {
            if ("dutyId_1_dueDate_1".equals(index.getString("name"))) {
                indexCount++;
            }
        }
        assertEquals(1, indexCount);
    }
}
