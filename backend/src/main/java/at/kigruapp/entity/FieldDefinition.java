package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@MongoEntity(collection = "field_definitions")
public class FieldDefinition extends PanacheMongoEntity {
    public String fieldName;
    public Map<String, String> label;
    public String description;
    public Map<String, Object> jsonSchema;
    public boolean required;
    public String keycloakMapping;
    public Instant createdAt;
    public Instant outdatedAt;

    public static List<FieldDefinition> findActive() {
        return list("outdatedAt = null");
    }

    public static FieldDefinition findByKeycloakMapping(String mapping) {
        return find("keycloakMapping", mapping).firstResult();
    }
}
