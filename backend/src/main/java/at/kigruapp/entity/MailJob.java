package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@MongoEntity(collection = "mail_jobs")
public class MailJob extends PanacheMongoEntity {
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
    public Instant createdAt;
    public Instant updatedAt;
}
