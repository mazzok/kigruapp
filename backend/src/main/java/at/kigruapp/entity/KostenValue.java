package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.math.BigDecimal;

@MongoEntity(collection = "kosten_values")
public class KostenValue extends PanacheMongoEntity {
    public ObjectId semesterId;
    public ObjectId groupId;
    public ObjectId definitionId;
    public BigDecimal amount;

    public static KostenValue findByKeys(ObjectId semesterId, ObjectId groupId, ObjectId definitionId) {
        return find("{'semesterId': ?1, 'groupId': ?2, 'definitionId': ?3}", semesterId, groupId, definitionId)
                .firstResult();
    }
}
