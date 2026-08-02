package at.kigruapp.service;

import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RecipientKind;
import at.kigruapp.entity.RecipientSelection;
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
import java.util.EnumMap;
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
     * Given a MailJob, produces the final (email, propertyMap) list. Selections
     * are resolved per kind and unioned, deduplicated by person id.
     */
    public List<ResolvedRecipient> resolve(MailJob job, ObjectId semesterId) {
        List<Person> parents = job.allParents
                ? resolveAllParents()
                : resolveSelections(job.recipientSelections, semesterId);

        Map<ObjectId, Map<String, String>> propertiesByPersonId = personPropertyResolver.resolve(parents);

        List<ResolvedRecipient> result = new ArrayList<>();
        for (Person parent : parents) {
            String email = resolveEmail(parent);
            if (email == null) continue;
            result.add(new ResolvedRecipient(email, propertiesByPersonId.getOrDefault(parent.id, Map.of())));
        }
        return result;
    }

    /** Buckets the selections by kind, resolves each bucket and unions the result. */
    private List<Person> resolveSelections(List<RecipientSelection> selections, ObjectId semesterId) {
        if (selections == null || selections.isEmpty()) {
            return List.of();
        }
        Map<RecipientKind, List<ObjectId>> byKind = new EnumMap<>(RecipientKind.class);
        for (RecipientSelection sel : selections) {
            if (sel == null || sel.kind == null || sel.fieldInstanceId == null) continue;
            byKind.computeIfAbsent(sel.kind, k -> new ArrayList<>()).add(sel.fieldInstanceId);
        }

        Map<ObjectId, Person> union = new LinkedHashMap<>();
        addAll(union, resolveGroupParents(byKind.get(RecipientKind.GROUP), semesterId));
        addAll(union, resolveAssignedParents("team", byKind.get(RecipientKind.TEAM), semesterId));
        addAll(union, resolveAssignedParents("role", byKind.get(RecipientKind.ROLE), semesterId));
        return new ArrayList<>(union.values());
    }

    private void addAll(Map<ObjectId, Person> union, List<Person> people) {
        for (Person p : people) {
            union.putIfAbsent(p.id, p);
        }
    }

    /**
     * Resolves the parents directly assigned to any of the given field instances
     * in the given semester section ("team" or "role"). Unlike groups, these
     * assignments already point at parents, so no family detour is needed.
     * Instances that no longer exist simply match nothing.
     */
    public List<Person> resolveAssignedParents(String section, List<ObjectId> instanceIds, ObjectId semesterId) {
        if (instanceIds == null || instanceIds.isEmpty() || semesterId == null) {
            return List.of();
        }
        Map<ObjectId, Person> deduped = new LinkedHashMap<>();
        for (Document doc : semesterAssignments().find(Filters.and(
                Filters.eq("section", section),
                Filters.eq("semesterId", semesterId),
                Filters.in("fieldInstanceId", instanceIds)))) {
            SemesterAssignment sa = SemesterAssignment.fromDocument(doc);
            if (sa.personId == null || deduped.containsKey(sa.personId)) {
                continue;
            }
            Person person = Person.findById(sa.personId);
            if (person != null && isParent(person) && hasNonBlankEmail(person)) {
                deduped.put(person.id, person);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private MongoCollection<Document> semesterAssignments() {
        return mongoClient.getDatabase(databaseName).getCollection("semester_assignments");
    }

    /**
     * Resolves the deduped set of parent Persons who have a child assigned to
     * one of the given groups in the given semester. The ids identify individual
     * groups (field instances of the "group" template), matching how group
     * assignments are stored (a shared definitionId + a per-group fieldInstanceId).
     */
    public List<Person> resolveGroupParents(List<ObjectId> groupInstanceIds, ObjectId semesterId) {
        if (groupInstanceIds == null || groupInstanceIds.isEmpty() || semesterId == null) {
            return List.of();
        }

        Set<ObjectId> childPersonIds = new HashSet<>();
        for (Document doc : semesterAssignments().find(Filters.and(
                Filters.eq("section", "group"),
                Filters.eq("semesterId", semesterId),
                Filters.in("fieldInstanceId", groupInstanceIds)))) {
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

    /**
     * Alle Eltern einer Familie mit hinterlegter E-Mail-Adresse, samt
     * aufgelösten Person-Properties. Für die Kochdienst-Erinnerung: erinnert
     * wird die ganze Familie, nicht nur die eingetragene Person.
     */
    public List<ResolvedRecipient> resolveFamilyRecipients(ObjectId familyId) {
        if (familyId == null) {
            return List.of();
        }
        Map<ObjectId, Person> deduped = new LinkedHashMap<>();
        for (Person candidate : Person.findByFamilyId(familyId)) {
            if (isParent(candidate) && hasNonBlankEmail(candidate)) {
                deduped.putIfAbsent(candidate.id, candidate);
            }
        }
        List<Person> parents = new ArrayList<>(deduped.values());
        Map<ObjectId, Map<String, String>> propertiesByPersonId = personPropertyResolver.resolve(parents);

        List<ResolvedRecipient> result = new ArrayList<>();
        for (Person parent : parents) {
            String email = resolveEmail(parent);
            if (email == null) continue;
            result.add(new ResolvedRecipient(email, propertiesByPersonId.getOrDefault(parent.id, Map.of())));
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
