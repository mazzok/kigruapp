package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.time.Instant;

@MongoEntity(collection = "mail_templates")
public class MailTemplate extends PanacheMongoEntity {
    public static final String KIND_GENERAL = "GENERAL";
    public static final String KIND_COOKING = "COOKING";

    public String name;
    public String bodyHtml;
    /** GENERAL oder COOKING. Bestandsdaten ohne Feld gelten als GENERAL. */
    public String kind;
    public Instant createdAt;
    public Instant updatedAt;

    public boolean isCooking() {
        return KIND_COOKING.equals(kind);
    }

    /** Nie null — für Filter und Ausgabe. */
    public String effectiveKind() {
        return kind == null || kind.isBlank() ? KIND_GENERAL : kind;
    }
}
