package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.util.List;

@MongoEntity(collection = "kosten_definitions")
public class KostenDefinition extends PanacheMongoEntity {
    public String label;
    public ObjectId currencyId;
    public boolean active;

    public static List<KostenDefinition> findActive() {
        return list("{'active': true}");
    }
}
