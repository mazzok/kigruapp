package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * Sende-Log der Kochdienst-Erinnerungen. Der Unique-Index auf
 * (dutyId, dueDate) macht den täglichen Lauf idempotent: derselbe Kochdienst
 * wird für denselben Fälligkeitstag nie zweimal erinnert, ein verschobener
 * Kochdienst bekommt über die neue dueDate wieder eine Erinnerung.
 */
@MongoEntity(collection = "cooking_reminders")
public class CookingReminder extends PanacheMongoEntity {

    /** Id der cookingDuty-FieldInstance. */
    public ObjectId dutyId;
    /** Tag des Versands, yyyy-MM-dd. */
    public String dueDate;
    /** Tag des Kochdienstes, yyyy-MM-dd. */
    public String dutyDate;
    public Instant sentAt;
    public CookingReminderStatus status;
    public int recipientCount;
    public String error;

    public static boolean existsFor(ObjectId dutyId, String dueDate) {
        return count("dutyId = ?1 and dueDate = ?2", dutyId, dueDate) > 0;
    }
}
