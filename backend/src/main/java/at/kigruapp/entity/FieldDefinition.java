package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.util.List;
import java.util.Map;

@MongoEntity(collection = "field_definitions")
public class FieldDefinition extends PanacheMongoEntity {
    public EntityType entity;
    public String fieldName;
    public Map<String, String> label;  // { "de": "...", "en": "..." }
    public FieldType type;
    public List<String> options;       // for SELECT type
    public boolean required;

    public static List<FieldDefinition> findByEntity(EntityType entity) {
        return list("entity", entity);
    }
}
