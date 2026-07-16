package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@MongoEntity(collection = "persons")
public class Person extends PanacheMongoEntity {
    public ObjectId familyId;
    public String keycloakUserId;
    public List<FieldRef> basicProperties = new ArrayList<>();
    public List<FieldRef> roles = new ArrayList<>();
    public List<FieldRef> schedules = new ArrayList<>();
    public List<FieldRef> duties = new ArrayList<>();
    public List<FieldRef> finance = new ArrayList<>();
    public List<FieldRef> customProperties = new ArrayList<>();
    public Instant createdAt;
    public Instant updatedAt;

    public static List<Person> findByFamilyId(ObjectId familyId) {
        return list("familyId", familyId);
    }
}
