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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    @Inject
    FieldInstanceLabelResolver labelResolver;

    @Inject
    ParentDirectoryAttributeService attributeService;

    public ParentDirectoryDTO buildForFamily(ObjectId ownFamilyId) {
        ObjectId semesterId = personLookup.resolveNewestSemesterId();
        List<ParentDirectoryDTO.ColumnEntry> columns = attributeService.visibleCatalog().stream()
                .map(e -> new ParentDirectoryDTO.ColumnEntry(e.key(), e.label(), e.scope()))
                .toList();
        Set<String> visible = attributeService.visibleKeys();

        if (ownFamilyId == null || semesterId == null) {
            return new ParentDirectoryDTO(
                    semesterId != null ? semesterId.toHexString() : null, columns, List.of());
        }

        List<Person> ownFamilyMembers = Person.findByFamilyId(ownFamilyId);
        Set<ObjectId> ownChildIdSet = personLookup.filterByPersonType(ownFamilyMembers, "CHILD");
        List<ObjectId> ownChildIds = new ArrayList<>(ownChildIdSet);
        if (ownChildIds.isEmpty()) {
            return new ParentDirectoryDTO(semesterId.toHexString(), columns, List.of());
        }

        Set<ObjectId> ownGroupIds = new LinkedHashSet<>();
        for (Document doc : groupAssignments(semesterId, Filters.in("personId", ownChildIds))) {
            ObjectId instanceId = SemesterAssignment.fromDocument(doc).fieldInstanceId;
            if (instanceId != null) {
                ownGroupIds.add(instanceId);
            }
        }
        if (ownGroupIds.isEmpty()) {
            return new ParentDirectoryDTO(semesterId.toHexString(), columns, List.of());
        }

        // Alle Kinder dieser Gruppen, gruppiert nach Gruppe.
        record ChildDates(String entryDate, String exitDate) {}
        Map<String, ChildDates> datesByChildAndGroup = new LinkedHashMap<>();
        Map<ObjectId, List<ObjectId>> childIdsByGroup = new LinkedHashMap<>();
        Set<ObjectId> allChildIds = new LinkedHashSet<>();
        for (Document doc : groupAssignments(semesterId, Filters.in("fieldInstanceId", ownGroupIds))) {
            SemesterAssignment sa = SemesterAssignment.fromDocument(doc);
            if (sa.personId == null || sa.fieldInstanceId == null) continue;
            childIdsByGroup.computeIfAbsent(sa.fieldInstanceId, k -> new ArrayList<>()).add(sa.personId);
            allChildIds.add(sa.personId);
            datesByChildAndGroup.put(
                    sa.personId.toHexString() + "/" + sa.fieldInstanceId.toHexString(),
                    new ChildDates(doc.getString("entryDate"), doc.getString("exitDate")));
        }

        Map<ObjectId, Person> childrenById = new LinkedHashMap<>();
        for (Person child : Person.<Person>list("_id in ?1", new ArrayList<>(allChildIds))) {
            childrenById.put(child.id, child);
        }

        Set<ObjectId> familyIds = new LinkedHashSet<>();
        for (Person child : childrenById.values()) {
            if (child.familyId != null) {
                familyIds.add(child.familyId);
            }
        }

        List<Person> allFamilyMembers = familyIds.isEmpty()
                ? List.of()
                : Person.<Person>list("familyId in ?1", new ArrayList<>(familyIds));
        Map<ObjectId, List<Person>> membersByFamily = new LinkedHashMap<>();
        for (Person member : allFamilyMembers) {
            membersByFamily.computeIfAbsent(member.familyId, k -> new ArrayList<>()).add(member);
        }
        Set<ObjectId> parentIds = personLookup.filterByPersonType(allFamilyMembers, "PARENT");

        Map<ObjectId, List<Person>> parentsByFamily = new LinkedHashMap<>();
        List<Person> allParents = new ArrayList<>();
        for (ObjectId familyId : familyIds) {
            List<Person> parents = new ArrayList<>();
            for (Person candidate : membersByFamily.getOrDefault(familyId, List.of())) {
                if (parentIds.contains(candidate.id)) {
                    parents.add(candidate);
                }
            }
            parentsByFamily.put(familyId, parents);
            allParents.addAll(parents);
        }
        Map<ObjectId, Map<String, String>> parentProperties = personPropertyResolver.resolve(allParents);
        Map<ObjectId, Map<String, String>> childProperties =
                personPropertyResolver.resolve(new ArrayList<>(childrenById.values()));

        List<ObjectId> parentIdList = allParents.stream().map(p -> p.id).toList();
        Map<ObjectId, String> teamLabels = visible.contains(ParentDirectoryAttributeService.TEAM)
                ? resolveSectionLabels(semesterId, "team", parentIdList)
                : Map.of();
        Map<ObjectId, String> roleLabels = visible.contains(ParentDirectoryAttributeService.ROLE)
                ? resolveSectionLabels(semesterId, "role", parentIdList)
                : Map.of();

        Set<ObjectId> selectedCustomDefinitionIds = attributeService.customDefinitionIds().stream()
                .filter(id -> visible.contains("custom:" + id.toHexString()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<ObjectId, Map<String, String>> customProperties =
                personPropertyResolver.resolveCustom(allParents, selectedCustomDefinitionIds);

        Map<ObjectId, Family> familiesById = familyIds.isEmpty()
                ? Map.of()
                : Family.<Family>list("_id in ?1", new ArrayList<>(familyIds)).stream()
                        .collect(Collectors.toMap(f -> f.id, f -> f));

        Map<ObjectId, String> groupNames = labelResolver.resolveLabels(ownGroupIds);

        List<ParentDirectoryDTO.GroupEntry> groups = new ArrayList<>();
        for (Map.Entry<ObjectId, List<ObjectId>> entry : childIdsByGroup.entrySet()) {
            ObjectId groupInstanceId = entry.getKey();

            Map<ObjectId, List<ParentDirectoryDTO.ChildEntry>> childEntriesByFamily = new LinkedHashMap<>();
            boolean showEntry = visible.contains(ParentDirectoryAttributeService.CHILD_ENTRY_DATE);
            boolean showExit = visible.contains(ParentDirectoryAttributeService.CHILD_EXIT_DATE);
            for (ObjectId childId : entry.getValue()) {
                Person child = childrenById.get(childId);
                if (child == null || child.familyId == null) continue;
                String name = childProperties.getOrDefault(child.id, Map.of()).get("firstName");
                ChildDates dates = datesByChildAndGroup.get(
                        childId.toHexString() + "/" + groupInstanceId.toHexString());
                childEntriesByFamily.computeIfAbsent(child.familyId, k -> new ArrayList<>())
                        .add(new ParentDirectoryDTO.ChildEntry(
                                name,
                                showEntry && dates != null ? dates.entryDate() : null,
                                showExit && dates != null ? dates.exitDate() : null));
            }

            List<ParentDirectoryDTO.FamilyEntry> families = new ArrayList<>();
            for (Map.Entry<ObjectId, List<ParentDirectoryDTO.ChildEntry>> famEntry : childEntriesByFamily.entrySet()) {
                ObjectId familyId = famEntry.getKey();
                List<ParentDirectoryDTO.ChildEntry> childEntries = new ArrayList<>(famEntry.getValue());
                childEntries.sort(Comparator.comparing(ParentDirectoryDTO.ChildEntry::name,
                        Comparator.nullsFirst(Comparator.naturalOrder())));

                List<ParentDirectoryDTO.ParentEntry> parents = new ArrayList<>();
                for (Person parent : parentsByFamily.getOrDefault(familyId, List.of())) {
                    Map<String, String> props = parentProperties.getOrDefault(parent.id, Map.of());
                    Map<String, String> values = new LinkedHashMap<>();
                    putIfVisible(values, visible, ParentDirectoryAttributeService.FIRST_NAME, props.get("firstName"));
                    putIfVisible(values, visible, ParentDirectoryAttributeService.LAST_NAME, props.get("lastName"));
                    putIfVisible(values, visible, ParentDirectoryAttributeService.EMAIL, props.get("email"));
                    putIfVisible(values, visible, ParentDirectoryAttributeService.PHONE, props.get("phone"));
                    for (Map.Entry<String, String> custom :
                            customProperties.getOrDefault(parent.id, Map.of()).entrySet()) {
                        putIfVisible(values, visible, custom.getKey(), custom.getValue());
                    }
                    putIfVisible(values, visible, ParentDirectoryAttributeService.TEAM, teamLabels.get(parent.id));
                    putIfVisible(values, visible, ParentDirectoryAttributeService.ROLE, roleLabels.get(parent.id));
                    parents.add(new ParentDirectoryDTO.ParentEntry(values));
                }

                families.add(new ParentDirectoryDTO.FamilyEntry(
                        familyId.toHexString(),
                        familyId.equals(ownFamilyId),
                        childEntries,
                        parents,
                        visible.contains(ParentDirectoryAttributeService.ADDRESS)
                                ? formatAddress(familiesById.get(familyId))
                                : null));
            }

            // Eigene Familie zuerst, danach nach dem ersten Kindernamen (fehlende Namen zuletzt).
            families.sort(Comparator
                    .comparing(ParentDirectoryDTO.FamilyEntry::isOwnFamily).reversed()
                    .thenComparing(
                            (ParentDirectoryDTO.FamilyEntry f) ->
                                    f.children().isEmpty() ? null : f.children().get(0).name(),
                            Comparator.nullsLast(Comparator.naturalOrder())));

            groups.add(new ParentDirectoryDTO.GroupEntry(
                    groupInstanceId.toHexString(), groupNames.get(groupInstanceId), families));
        }

        groups.sort(Comparator.comparing(g -> g.groupName() != null ? g.groupName() : ""));
        return new ParentDirectoryDTO(semesterId.toHexString(), columns, groups);
    }

    private void putIfVisible(Map<String, String> target, Set<String> visible, String key, String value) {
        if (value != null && visible.contains(key)) {
            target.put(key, value);
        }
    }

    /**
     * Team- bzw. Rollenzuweisungen der Eltern im laufenden Semester, als
     * Anzeigetext je Person. Mehrfachzuweisungen werden verbunden; die
     * Reihenfolge folgt der Reihenfolge in semester_assignments.
     */
    private Map<ObjectId, String> resolveSectionLabels(
            ObjectId semesterId, String section, Collection<ObjectId> personIds) {
        Map<ObjectId, List<ObjectId>> instancesByPerson = new LinkedHashMap<>();
        if (personIds.isEmpty()) return Map.of();

        MongoCollection<Document> collection = mongoClient.getDatabase(databaseName)
                .getCollection("semester_assignments");
        Set<ObjectId> allInstanceIds = new LinkedHashSet<>();
        for (Document doc : collection.find(Filters.and(
                Filters.eq("section", section),
                Filters.eq("semesterId", semesterId),
                Filters.in("personId", personIds)))) {
            SemesterAssignment sa = SemesterAssignment.fromDocument(doc);
            if (sa.personId == null || sa.fieldInstanceId == null) continue;
            instancesByPerson.computeIfAbsent(sa.personId, k -> new ArrayList<>()).add(sa.fieldInstanceId);
            allInstanceIds.add(sa.fieldInstanceId);
        }
        if (allInstanceIds.isEmpty()) return Map.of();

        Map<ObjectId, String> labels = labelResolver.resolveLabels(allInstanceIds);
        Map<ObjectId, String> result = new LinkedHashMap<>();
        for (Map.Entry<ObjectId, List<ObjectId>> entry : instancesByPerson.entrySet()) {
            String joined = entry.getValue().stream()
                    .map(labels::get)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.joining(", "));
            if (!joined.isEmpty()) {
                result.put(entry.getKey(), joined);
            }
        }
        return result;
    }

    private Iterable<Document> groupAssignments(ObjectId semesterId, org.bson.conversions.Bson extraFilter) {
        MongoCollection<Document> collection = mongoClient.getDatabase(databaseName)
                .getCollection("semester_assignments");
        return collection.find(Filters.and(
                Filters.eq("section", "group"),
                Filters.eq("semesterId", semesterId),
                extraFilter));
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
