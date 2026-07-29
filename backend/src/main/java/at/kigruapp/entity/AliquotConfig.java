package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

@MongoEntity(collection = "aliquot_configs")
public class AliquotConfig extends PanacheMongoEntity {
    public ObjectId semesterId;
    public String stundenMode = "NONE";
    public String kostenMode = "NONE";

    public static AliquotConfig findBySemesterId(ObjectId semesterId) {
        return find("semesterId", semesterId).firstResult();
    }
}
