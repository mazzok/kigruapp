package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Gemeinsame Personen- und Semester-Auflösung für Resources und Services.
 * Die Logik lag zuvor privat in PersonResource; sie wird hier geteilt, damit
 * es nur eine Definition von "Kind" bzw. "Elternteil" gibt.
 */
@ApplicationScoped
public class PersonLookupService {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    public boolean isChild(Person person) {
        return hasPersonType(person, "CHILD");
    }

    public boolean isParent(Person person) {
        return hasPersonType(person, "PARENT");
    }

    /** Die id des zuletzt angelegten Semesters, oder null wenn keines existiert. */
    public ObjectId resolveNewestSemesterId() {
        List<Semester> latest = Semester.listAll(Sort.descending("createdAt"));
        return latest.isEmpty() ? null : latest.get(0).id;
    }

    private boolean hasPersonType(Person person, String expected) {
        if (person == null || person.basicProperties == null) return false;
        MongoCollection<Document> instances = mongoClient.getDatabase(databaseName)
                .getCollection("field_instances");
        for (FieldRef ref : person.basicProperties) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def == null || !"personType".equals(def.fieldName)) continue;
            Document instance = instances.find(new Document("_id", ref.fieldInstanceId)).first();
            if (instance != null && expected.equals(instance.get("value"))) {
                return true;
            }
        }
        return false;
    }
}
