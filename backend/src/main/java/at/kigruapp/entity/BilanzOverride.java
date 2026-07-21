package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.math.BigDecimal;

@MongoEntity(collection = "bilanz_overrides")
public class BilanzOverride extends PanacheMongoEntity {
    public ObjectId personId;
    public int year;
    public int month;
    public ObjectId definitionId;
    public BigDecimal amount;

    public static BilanzOverride findByKeys(ObjectId personId, int year, int month, ObjectId definitionId) {
        return find("{'personId': ?1, 'year': ?2, 'month': ?3, 'definitionId': ?4}",
                personId, year, month, definitionId).firstResult();
    }
}
