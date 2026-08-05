package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * Sende-Log der Kochdienst-Erinnerungen. Der Unique-Index auf
 * (dutyId, dueDate, jobId) macht den täglichen Lauf je Job idempotent:
 * derselbe Kochdienst wird für denselben Fälligkeitstag je Job nie zweimal erinnert,
 * und mehrere unabhängige Jobs können denselben Kochdienst am selben Tag erinnern.
 */
@MongoEntity(collection = "cooking_reminders")
public class CookingReminder extends PanacheMongoEntity {

    /** Id der cookingDuty-FieldInstance. */
    public ObjectId dutyId;
    /** Tag des Versands, yyyy-MM-dd. */
    public String dueDate;
    /** Tag des Kochdienstes, yyyy-MM-dd. */
    public String dutyDate;
    /** Id des Kochdienst-Jobs, der diese Erinnerung verschickt hat. */
    public ObjectId jobId;
    public Instant sentAt;
    public CookingReminderStatus status;
    public int recipientCount;
    public String error;

    public static boolean existsFor(ObjectId dutyId, String dueDate, ObjectId jobId) {
        return count("dutyId = ?1 and dueDate = ?2 and jobId = ?3", dutyId, dueDate, jobId) > 0;
    }
}
