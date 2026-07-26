package at.kigruapp.service;

import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RecipientMode;
import at.kigruapp.entity.SemesterAssignment;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Net-new batch recipient resolution (G-008) — resolves the live parent
 * recipient set for a MailJob's recipient selection (D5/R15). Does not reuse
 * PersonResource's private per-field N+1 accessors; follows the same
 * field-instance read pattern instead.
 */
@ApplicationScoped
public class RecipientResolverService {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    PersonPropertyResolver personPropertyResolver;

    public record ResolvedRecipient(String email, Map<String, String> properties) {}

    /**
     * Given a MailJob, produces the final (email, propertyMap) list by
     * dispatching to group or all-parents resolution and attaching property maps.
     */
    public List<ResolvedRecipient> resolve(MailJob job, ObjectId semesterId) {
        List<Person> parents = job.recipientMode == RecipientMode.GROUPS
                ? resolveGroupParents(job.recipientGroupDefinitionIds, semesterId)
                : resolveAllParents();

        Map<ObjectId, Map<String, String>> propertiesByPersonId = personPropertyResolver.resolve(parents);

        List<ResolvedRecipient> result = new ArrayList<>();
        for (Person parent : parents) {
            String email = resolveEmail(parent);
            if (email == null) continue;
            result.add(new ResolvedRecipient(email, propertiesByPersonId.getOrDefault(parent.id, Map.of())));
        }
        return result;
    }

    private MongoCollection<Document> semesterAssignments() {
        return mongoClient.getDatabase(databaseName).getCollection("semester_assignments");
    }

    /**
     * Resolves the deduped set of parent Persons who have a child assigned to
     * one of the given group definitionIds in the given semester.
     */
    public List<Person> resolveGroupParents(List<ObjectId> groupDefinitionIds, ObjectId semesterId) {
        if (groupDefinitionIds == null || groupDefinitionIds.isEmpty() || semesterId == null) {
            return List.of();
        }

        Set<ObjectId> childPersonIds = new HashSet<>();
        for (Document doc : semesterAssignments().find(Filters.and(
                Filters.eq("section", "group"),
                Filters.eq("semesterId", semesterId),
                Filters.in("definitionId", groupDefinitionIds)))) {
            SemesterAssignment sa = SemesterAssignment.fromDocument(doc);
            if (sa.personId != null) {
                childPersonIds.add(sa.personId);
            }
        }
        if (childPersonIds.isEmpty()) {
            return List.of();
        }

        Set<ObjectId> familyIds = new HashSet<>();
        for (ObjectId childId : childPersonIds) {
            Person child = Person.findById(childId);
            if (child != null && child.familyId != null) {
                familyIds.add(child.familyId);
            }
        }

        Map<ObjectId, Person> dedupedParents = new LinkedHashMap<>();
        for (ObjectId familyId : familyIds) {
            for (Person candidate : Person.findByFamilyId(familyId)) {
                if (isParent(candidate) && hasNonBlankEmail(candidate)) {
                    dedupedParents.put(candidate.id, candidate);
                }
            }
        }
        return new ArrayList<>(dedupedParents.values());
    }

    /** Resolves every parent Person with a non-blank email, regardless of group membership. */
    public List<Person> resolveAllParents() {
        List<Person> result = new ArrayList<>();
        for (Person person : Person.<Person>listAll()) {
            if (isParent(person) && hasNonBlankEmail(person)) {
                result.add(person);
            }
        }
        return result;
    }

    /** Mirrors PersonResource's private isChild check, for personType == PARENT. */
    boolean isParent(Person person) {
        if (person.basicProperties == null) return false;
        MongoCollection<Document> instColl = mongoClient.getDatabase(databaseName).getCollection("field_instances");
        for (FieldRef ref : person.basicProperties) {
            at.kigruapp.entity.FieldDefinition def = at.kigruapp.entity.FieldDefinition.findById(ref.definitionId);
            if (def != null && "personType".equals(def.fieldName)) {
                Document inst = instColl.find(Filters.eq("_id", ref.fieldInstanceId)).first();
                if (inst != null && "PARENT".equals(inst.get("value"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasNonBlankEmail(Person person) {
        return resolveEmail(person) != null;
    }

    String resolveEmail(Person person) {
        if (person.basicProperties == null) return null;
        MongoCollection<Document> instColl = mongoClient.getDatabase(databaseName).getCollection("field_instances");
        for (FieldRef ref : person.basicProperties) {
            at.kigruapp.entity.FieldDefinition def = at.kigruapp.entity.FieldDefinition.findById(ref.definitionId);
            if (def != null && "email".equals(def.fieldName)) {
                Document inst = instColl.find(Filters.eq("_id", ref.fieldInstanceId)).first();
                if (inst != null && inst.get("value") != null) {
                    String value = inst.get("value").toString();
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
        }
        return null;
    }
}
