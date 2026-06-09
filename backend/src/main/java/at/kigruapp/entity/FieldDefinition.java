package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@MongoEntity(collection = "field_definitions")
public class FieldDefinition extends PanacheMongoEntity {
    public EntityType entity;
    public String fieldName;
    public Map<String, String> label;
    public String description;
    public Map<String, Object> jsonSchema;
    public boolean required;
    public Instant createdAt;
    public Instant outdatedAt;

    public static List<FieldDefinition> findByEntity(EntityType entity) {
        return list("entity", entity);
    }

    public static List<FieldDefinition> findActiveByEntity(EntityType entity) {
        return list("entity = ?1 and outdatedAt = null", entity);
    }
}
