package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.time.Instant;
import java.util.Map;

@MongoEntity(collection = "families")
public class Family extends PanacheMongoEntity {
    public String name;
    public Instant createdAt;
    public Map<String, Object> customFields;
}
