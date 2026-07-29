package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

@MongoEntity(collection = "kosten_discounts")
public class KostenDiscount extends PanacheMongoEntity {
    public ObjectId semesterId;
    public boolean applyToAll;
    public String order = "MOST_EXPENSIVE_FIRST";
    public List<Tier> tiers = new ArrayList<>();

    public static class Tier {
        public int fromChild;
        public int percent;
    }

    public static KostenDiscount findBySemesterId(ObjectId semesterId) {
        return find("semesterId", semesterId).firstResult();
    }
}
