package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.List;

@MongoEntity(collection = "field_instances")
public class FieldInstance extends PanacheMongoEntity {
    public ObjectId definitionId;
    public EntityType entityType;
    public ObjectId entityId;
    public Object value;
    public Instant createdAt;
    public Instant updatedAt;

    public static List<FieldInstance> findByEntity(EntityType entityType, ObjectId entityId) {
        return list("entityType = ?1 and entityId = ?2", entityType, entityId);
    }

    public static List<FieldInstance> findByDefinitionId(ObjectId definitionId) {
        return list("definitionId", definitionId);
    }

    public static FieldInstance findByDefinitionAndEntity(ObjectId definitionId, ObjectId entityId) {
        return find("definitionId = ?1 and entityId = ?2", definitionId, entityId).firstResult();
    }
}
