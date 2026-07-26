package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.time.Instant;

@MongoEntity(collection = "mail_templates")
public class MailTemplate extends PanacheMongoEntity {
    public String name;
    public String bodyHtml;
    public Instant createdAt;
    public Instant updatedAt;
}
