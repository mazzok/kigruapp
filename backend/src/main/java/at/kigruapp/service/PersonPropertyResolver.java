package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Batch-resolves each person's scalar allowlisted properties in a bounded
 * number of queries (one for definitions, one for field instances) instead of
 * per-person/per-field N+1 lookups (G-008).
 *
 * The allowlist mirrors MailTemplateResource's placeholder-tile allowlist
 * (R3) — both express the same "scalar person property" contract.
 */
@ApplicationScoped
public class PersonPropertyResolver {

    private static final Set<String> SCALAR_PERSON_FIELD_ALLOWLIST = Set.of(
            "firstName", "lastName", "email", "phone", "dateOfBirth", "gender", "notes"
    );

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    public Map<ObjectId, Map<String, String>> resolve(List<Person> persons) {
        Map<ObjectId, String> allowedDefinitionIdToFieldName = FieldDefinition.findActive().stream()
                .filter(def -> SCALAR_PERSON_FIELD_ALLOWLIST.contains(def.fieldName))
                .collect(Collectors.toMap(def -> def.id, def -> def.fieldName));

        Map<ObjectId, Map<ObjectId, String>> perPersonInstanceToField = new HashMap<>();
        Set<ObjectId> allInstanceIds = new HashSet<>();
        for (Person person : persons) {
            Map<ObjectId, String> instanceToField = new HashMap<>();
            if (person.basicProperties != null) {
                for (FieldRef ref : person.basicProperties) {
                    String fieldName = allowedDefinitionIdToFieldName.get(ref.definitionId);
                    if (fieldName != null) {
                        instanceToField.put(ref.fieldInstanceId, fieldName);
                        allInstanceIds.add(ref.fieldInstanceId);
                    }
                }
            }
            perPersonInstanceToField.put(person.id, instanceToField);
        }

        Map<ObjectId, String> instanceIdToValue = new HashMap<>();
        if (!allInstanceIds.isEmpty()) {
            MongoCollection<Document> col = mongoClient.getDatabase(databaseName).getCollection("field_instances");
            for (Document doc : col.find(Filters.in("_id", allInstanceIds))) {
                Object value = doc.get("value");
                if (value != null) {
                    instanceIdToValue.put(doc.getObjectId("_id"), value.toString());
                }
            }
        }

        Map<ObjectId, Map<String, String>> result = new HashMap<>();
        for (Person person : persons) {
            Map<ObjectId, String> instanceToField = perPersonInstanceToField.getOrDefault(person.id, Map.of());
            Map<String, String> properties = new HashMap<>();
            for (Map.Entry<ObjectId, String> e : instanceToField.entrySet()) {
                String value = instanceIdToValue.get(e.getKey());
                if (value != null) {
                    properties.put(e.getValue(), value);
                }
            }
            result.put(person.id, properties);
        }
        return result;
    }

    /**
     * Wie {@link #resolve}, aber fuer benutzerdefinierte Felder: liefert je Person
     * eine Map "custom:<definitionIdHex>" -> Wert, beschraenkt auf die uebergebenen
     * Definitionen. Zwei Abfragen unabhaengig von der Personenzahl.
     */
    public Map<ObjectId, Map<String, String>> resolveCustom(List<Person> persons, Set<ObjectId> definitionIds) {
        Map<ObjectId, Map<String, String>> result = new HashMap<>();
        if (persons == null || persons.isEmpty() || definitionIds == null || definitionIds.isEmpty()) {
            return result;
        }

        Map<ObjectId, Map<ObjectId, String>> perPersonInstanceToKey = new HashMap<>();
        Set<ObjectId> allInstanceIds = new HashSet<>();
        for (Person person : persons) {
            Map<ObjectId, String> instanceToKey = new HashMap<>();
            if (person.customProperties != null) {
                for (FieldRef ref : person.customProperties) {
                    if (definitionIds.contains(ref.definitionId)) {
                        instanceToKey.put(ref.fieldInstanceId, "custom:" + ref.definitionId.toHexString());
                        allInstanceIds.add(ref.fieldInstanceId);
                    }
                }
            }
            perPersonInstanceToKey.put(person.id, instanceToKey);
        }

        Map<ObjectId, String> instanceIdToValue = new HashMap<>();
        if (!allInstanceIds.isEmpty()) {
            MongoCollection<Document> col = mongoClient.getDatabase(databaseName).getCollection("field_instances");
            for (Document doc : col.find(Filters.in("_id", allInstanceIds))) {
                Object value = doc.get("value");
                if (value != null) {
                    instanceIdToValue.put(doc.getObjectId("_id"), value.toString());
                }
            }
        }

        for (Person person : persons) {
            Map<String, String> properties = new HashMap<>();
            for (Map.Entry<ObjectId, String> e :
                    perPersonInstanceToKey.getOrDefault(person.id, Map.of()).entrySet()) {
                String value = instanceIdToValue.get(e.getKey());
                if (value != null) {
                    properties.put(e.getValue(), value);
                }
            }
            result.put(person.id, properties);
        }
        return result;
    }
}
