package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;
import java.time.LocalDate;
import java.util.Map;

@MongoEntity(collection = "children")
public class Child extends PanacheMongoEntity {
    public ObjectId familyId;
    public String firstName;
    public String lastName;
    public LocalDate dateOfBirth;
    public String gender;
    public LocalDate entryDate;
    public LocalDate exitDate;
    public String notes;
    public Map<String, Object> customFields;

    public static java.util.List<Child> findByFamilyId(ObjectId familyId) {
        return list("familyId", familyId);
    }
}
