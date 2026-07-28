package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

@MongoEntity(collection = "requiredHours")
public class RequiredHours extends PanacheMongoEntity {
    public ObjectId semesterId;
    public int defaultMinutesPerMonth;
    public List<Tier> tiers = new ArrayList<>();

    public static class Tier {
        public int fromChild;
        public int minutesPerMonth;
    }

    public static RequiredHours findBySemesterId(ObjectId semesterId) {
        return find("semesterId", semesterId).firstResult();
    }
}
