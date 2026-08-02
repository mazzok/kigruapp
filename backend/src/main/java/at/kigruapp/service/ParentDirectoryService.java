package at.kigruapp.service;

import at.kigruapp.dto.ParentDirectoryDTO;
import at.kigruapp.entity.Family;
import at.kigruapp.entity.Person;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Baut das Eltern-Verzeichnis auf: ausgehend von den eigenen Kindern werden die
 * Gruppen des laufenden Semesters bestimmt und je Gruppe die dort vertretenen
 * Familien aufgelöst. Die Gruppenmenge stammt immer aus den eigenen Kindern —
 * es gibt keinen Parameter, über den fremde Gruppen angefragt werden könnten.
 */
@ApplicationScoped
public class ParentDirectoryService {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    PersonLookupService personLookup;

    @Inject
    PersonPropertyResolver personPropertyResolver;

    public ParentDirectoryDTO buildForFamily(ObjectId ownFamilyId) {
        ObjectId semesterId = personLookup.resolveNewestSemesterId();
        if (ownFamilyId == null || semesterId == null) {
            return new ParentDirectoryDTO(semesterId != null ? semesterId.toHexString() : null, List.of());
        }

        List<ObjectId> ownChildIds = new ArrayList<>();
        for (Person person : Person.findByFamilyId(ownFamilyId)) {
            if (personLookup.isChild(person)) {
                ownChildIds.add(person.id);
            }
        }
        if (ownChildIds.isEmpty()) {
            return new ParentDirectoryDTO(semesterId.toHexString(), List.of());
        }

        Set<ObjectId> ownGroupIds = new LinkedHashSet<>();
        for (Document doc : groupAssignments(semesterId, Filters.in("personId", ownChildIds))) {
            ObjectId instanceId = SemesterAssignment.fromDocument(doc).fieldInstanceId;
            if (instanceId != null) {
                ownGroupIds.add(instanceId);
            }
        }
        if (ownGroupIds.isEmpty()) {
            return new ParentDirectoryDTO(semesterId.toHexString(), List.of());
        }

        // Alle Kinder dieser Gruppen, gruppiert nach Gruppe.
        Map<ObjectId, List<ObjectId>> childIdsByGroup = new LinkedHashMap<>();
        Set<ObjectId> allChildIds = new LinkedHashSet<>();
        for (Document doc : groupAssignments(semesterId, Filters.in("fieldInstanceId", ownGroupIds))) {
            SemesterAssignment sa = SemesterAssignment.fromDocument(doc);
            if (sa.personId == null || sa.fieldInstanceId == null) continue;
            childIdsByGroup.computeIfAbsent(sa.fieldInstanceId, k -> new ArrayList<>()).add(sa.personId);
            allChildIds.add(sa.personId);
        }

        Map<ObjectId, Person> childrenById = new LinkedHashMap<>();
        for (ObjectId childId : allChildIds) {
            Person child = Person.findById(childId);
            if (child != null) {
                childrenById.put(childId, child);
            }
        }

        Set<ObjectId> familyIds = new LinkedHashSet<>();
        for (Person child : childrenById.values()) {
            if (child.familyId != null) {
                familyIds.add(child.familyId);
            }
        }

        Map<ObjectId, List<Person>> parentsByFamily = new LinkedHashMap<>();
        List<Person> allParents = new ArrayList<>();
        for (ObjectId familyId : familyIds) {
            List<Person> parents = new ArrayList<>();
            for (Person candidate : Person.findByFamilyId(familyId)) {
                if (personLookup.isParent(candidate)) {
                    parents.add(candidate);
                }
            }
            parentsByFamily.put(familyId, parents);
            allParents.addAll(parents);
        }
        Map<ObjectId, Map<String, String>> parentProperties = personPropertyResolver.resolve(allParents);
        Map<ObjectId, Map<String, String>> childProperties =
                personPropertyResolver.resolve(new ArrayList<>(childrenById.values()));

        List<ParentDirectoryDTO.GroupEntry> groups = new ArrayList<>();
        for (Map.Entry<ObjectId, List<ObjectId>> entry : childIdsByGroup.entrySet()) {
            ObjectId groupInstanceId = entry.getKey();

            Map<ObjectId, List<String>> childNamesByFamily = new LinkedHashMap<>();
            for (ObjectId childId : entry.getValue()) {
                Person child = childrenById.get(childId);
                if (child == null || child.familyId == null) continue;
                String name = childProperties.getOrDefault(child.id, Map.of()).get("firstName");
                childNamesByFamily.computeIfAbsent(child.familyId, k -> new ArrayList<>())
                        .add(name != null ? name : "");
            }

            List<ParentDirectoryDTO.FamilyEntry> families = new ArrayList<>();
            for (Map.Entry<ObjectId, List<String>> famEntry : childNamesByFamily.entrySet()) {
                ObjectId familyId = famEntry.getKey();
                List<String> childNames = new ArrayList<>(famEntry.getValue());
                childNames.sort(Comparator.naturalOrder());

                List<ParentDirectoryDTO.ParentEntry> parents = new ArrayList<>();
                for (Person parent : parentsByFamily.getOrDefault(familyId, List.of())) {
                    Map<String, String> props = parentProperties.getOrDefault(parent.id, Map.of());
                    parents.add(new ParentDirectoryDTO.ParentEntry(
                            props.get("firstName"), props.get("lastName"),
                            props.get("email"), props.get("phone")));
                }

                families.add(new ParentDirectoryDTO.FamilyEntry(
                        familyId.toHexString(),
                        familyId.equals(ownFamilyId),
                        childNames,
                        parents,
                        formatAddress(Family.<Family>findById(familyId))));
            }

            // Eigene Familie zuerst, danach nach dem ersten Kindernamen.
            families.sort(Comparator
                    .comparing(ParentDirectoryDTO.FamilyEntry::isOwnFamily).reversed()
                    .thenComparing(f -> f.children().isEmpty() ? "" : f.children().get(0)));

            groups.add(new ParentDirectoryDTO.GroupEntry(
                    groupInstanceId.toHexString(), resolveGroupName(groupInstanceId), families));
        }

        groups.sort(Comparator.comparing(g -> g.groupName() != null ? g.groupName() : ""));
        return new ParentDirectoryDTO(semesterId.toHexString(), groups);
    }

    private Iterable<Document> groupAssignments(ObjectId semesterId, org.bson.conversions.Bson extraFilter) {
        MongoCollection<Document> collection = mongoClient.getDatabase(databaseName)
                .getCollection("semester_assignments");
        return collection.find(Filters.and(
                Filters.eq("section", "group"),
                Filters.eq("semesterId", semesterId),
                extraFilter));
    }

    private String resolveGroupName(ObjectId groupInstanceId) {
        Document instance = mongoClient.getDatabase(databaseName)
                .getCollection("field_instances")
                .find(Filters.eq("_id", groupInstanceId))
                .first();
        Object value = instance != null ? instance.get("value") : null;
        return value != null ? value.toString() : null;
    }

    /** "Strasse, PLZ Ort" — fehlende Teile werden weggelassen, leeres Ergebnis wird null. */
    private String formatAddress(Family family) {
        if (family == null || family.address == null) return null;
        String street = trimToNull(family.address.get("street"));
        String zip = trimToNull(family.address.get("zip"));
        String city = trimToNull(family.address.get("city"));

        StringBuilder sb = new StringBuilder();
        if (street != null) sb.append(street);
        String place = zip != null && city != null ? zip + " " + city : (zip != null ? zip : city);
        if (place != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(place);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
