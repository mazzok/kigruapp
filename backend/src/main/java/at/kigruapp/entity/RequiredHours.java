package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

@MongoEntity(collection = "requiredHours")
public class RequiredHours extends PanacheMongoEntity {

    public static final String MOST_EXPENSIVE_FIRST = "MOST_EXPENSIVE_FIRST";
    public static final String LEAST_EXPENSIVE_FIRST = "LEAST_EXPENSIVE_FIRST";

    public ObjectId semesterId;
    /** Satz je Kind und Monat; gilt, wenn allGroups true ist. */
    public int defaultMinutesPerMonth;
    /** true: ein Satz für alle Gruppen. false: Satz je Gruppe aus groupRates. */
    public boolean allGroups = true;
    /** Reihenfolge für den Geschwisterrabatt. */
    public String order = MOST_EXPENSIVE_FIRST;
    public List<GroupRate> groupRates = new ArrayList<>();
    /** Geschwisterrabatt in Prozent, gilt gruppenübergreifend. */
    public List<Tier> tiers = new ArrayList<>();

    public static class GroupRate {
        public ObjectId groupInstanceId;
        public int minutesPerMonth;
    }

    public static class Tier {
        public int fromChild;
        public int percent;
    }

    public static RequiredHours findBySemesterId(ObjectId semesterId) {
        return find("semesterId", semesterId).firstResult();
    }
}
