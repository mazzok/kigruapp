package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.time.Instant;

/**
 * Konfiguration der Kochdienst-Erinnerungen. Bewusst ein Singleton — es gibt
 * genau eine Einstellung, die erste Zeile der Collection ist maßgeblich.
 */
@MongoEntity(collection = "cooking_reminder_settings")
public class CookingReminderSettings extends PanacheMongoEntity {

    /** Hex-Id einer MailAccount. Null bedeutet: Erinnerungen sind abgeschaltet. */
    public String senderAccountId;
    /** Hex-Id einer MailTemplate. */
    public String templateId;
    public String subject;
    /** Versandzeit im Format HH:mm, Zeitzone Europe/Vienna. */
    public String sendTime;
    public Instant updatedAt;

    public static CookingReminderSettings findSingleton() {
        return findAll().firstResult();
    }
}
