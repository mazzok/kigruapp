package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@MongoEntity(collection = "mail_jobs")
public class MailJob extends PanacheMongoEntity {
    public static final String KIND_GENERAL = "GENERAL";
    public static final String KIND_COOKING = "COOKING";

    public String name;
    public ObjectId templateId;
    public String subject;
    public String senderAccountId;
    public String cron;
    /** When true every parent is addressed and {@link #recipientSelections} is ignored. */
    public boolean allParents = false;
    public List<RecipientSelection> recipientSelections = new ArrayList<>();
    public boolean active;
    public Instant lastRunAt;
    public String lastRunStatus;
    public String lastRunError;
    /** GENERAL oder COOKING. Bestandsdaten ohne Feld gelten als GENERAL. */
    public String kind;
    /** Nur bei kind=COOKING gesetzt: Versandzeit HH:mm, Europe/Vienna. Ersetzt dort den Cron. */
    public String sendTime;
    public Instant createdAt;
    public Instant updatedAt;

    public boolean isCooking() {
        return KIND_COOKING.equals(kind);
    }

    public String effectiveKind() {
        return kind == null || kind.isBlank() ? KIND_GENERAL : kind;
    }
}
