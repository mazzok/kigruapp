package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;
import java.util.ArrayList;
import java.util.List;

@MongoEntity(collection = "organisation")
public class Organisation extends PanacheMongoEntity {
    public String tag;
    public List<ObjectId> definitionIds = new ArrayList<>();
    public List<DutyEntry> entries = new ArrayList<>();

    public static Organisation findByTag(String tag) {
        return find("tag", tag).firstResult();
    }
}
