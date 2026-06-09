package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;
import java.util.List;

@MongoEntity(collection = "parents")
public class Parent extends PanacheMongoEntity {
    public ObjectId familyId;
    public String firstName;
    public String lastName;
    public String email;
    public String phone;
    public Address address;
    public String keycloakUserId;
    public List<String> permissions;

    public static java.util.List<Parent> findByFamilyId(ObjectId familyId) {
        return list("familyId", familyId);
    }
}
