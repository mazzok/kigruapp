package at.kigruapp.service;

import at.kigruapp.dto.ParentDirectoryDTO;
import at.kigruapp.entity.Family;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.ParentDirectorySettings;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ParentDirectoryServiceTest {

    @Inject
    ParentDirectoryService service;

    @Inject
    ParentDirectoryAttributeService attributeService;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    ObjectId personTypeDefId;
    ObjectId firstNameDefId;
    ObjectId lastNameDefId;
    ObjectId emailDefId;
    ObjectId phoneDefId;
    ObjectId groupDefId;
    ObjectId semesterId;

    @BeforeEach
    void setUp() {
        Person.deleteAll();
        Family.deleteAll();
        Semester.deleteAll();
        FieldDefinition.deleteAll();
        ParentDirectorySettings.deleteAll();
        mongoClient.getDatabase(databaseName).getCollection("field_instances").drop();
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments").drop();

        personTypeDefId = persistDefinition("personType");
        firstNameDefId = persistDefinition("firstName");
        lastNameDefId = persistDefinition("lastName");
        emailDefId = persistDefinition("email");
        phoneDefId = persistDefinition("phone");
        groupDefId = persistDefinition("group");

        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        s.createdAt = Instant.parse("2026-08-01T00:00:00Z");
        s.persist();
        semesterId = s.id;
    }

    private ObjectId persistDefinition(String fieldName) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.createdAt = Instant.now();
        def.persist();
        return def.id;
    }

    private ObjectId persistInstance(ObjectId definitionId, String value) {
        ObjectId id = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", id)
                        .append("definitionId", definitionId)
                        .append("value", value));
        return id;
    }

    private ObjectId persistFamily(String name, String street, String zip, String city) {
        Family f = new Family();
        f.name = name;
        if (street != null) {
            f.address = Map.of("street", street, "zip", zip, "city", city);
        }
        f.createdAt = Instant.now();
        f.persist();
        return f.id;
    }

    /** props: firstName, lastName, email, phone — null-Werte werden ausgelassen. */
    private Person persistPerson(ObjectId familyId, String personType,
                                 String firstName, String lastName, String email, String phone) {
        Person p = new Person();
        p.familyId = familyId;
        p.basicProperties.add(new FieldRef(personTypeDefId, persistInstance(personTypeDefId, personType)));
        if (firstName != null) p.basicProperties.add(new FieldRef(firstNameDefId, persistInstance(firstNameDefId, firstName)));
        if (lastName != null) p.basicProperties.add(new FieldRef(lastNameDefId, persistInstance(lastNameDefId, lastName)));
        if (email != null) p.basicProperties.add(new FieldRef(emailDefId, persistInstance(emailDefId, email)));
        if (phone != null) p.basicProperties.add(new FieldRef(phoneDefId, persistInstance(phoneDefId, phone)));
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
        return p;
    }

    private ObjectId persistGroup(String name) {
        return persistInstance(groupDefId, name);
    }

    private void assign(ObjectId childId, ObjectId groupInstanceId, ObjectId inSemester) {
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments")
                .insertOne(new Document("_id", new ObjectId())
                        .append("personId", childId)
                        .append("semesterId", inSemester)
                        .append("section", "group")
                        .append("definitionId", groupDefId)
                        .append("fieldInstanceId", groupInstanceId));
    }

    @Test
    void ownGroupContainsOtherFamiliesButNotForeignGroups() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);
        persistPerson(ownFamily, "PARENT", "Anna", "Muster", "anna@x.at", "0660 111");

        ObjectId otherFamily = persistFamily("Sommer", "Gasse 7", "1020", "Wien");
        Person otherChild = persistPerson(otherFamily, "CHILD", "Tim", "Sommer", null, null);
        persistPerson(otherFamily, "PARENT", "Clara", "Sommer", "clara@y.at", "0664 333");

        ObjectId strangerFamily = persistFamily("Fremd", "Weg 3", "1030", "Wien");
        Person strangerChild = persistPerson(strangerFamily, "CHILD", "Max", "Fremd", null, null);
        persistPerson(strangerFamily, "PARENT", "Doris", "Fremd", "doris@z.at", null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        ObjectId biene = persistGroup("Bienengruppe");
        assign(ownChild.id, kaefer, semesterId);
        assign(otherChild.id, kaefer, semesterId);
        assign(strangerChild.id, biene, semesterId);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals(semesterId.toHexString(), result.semesterId());
        assertEquals(1, result.groups().size());
        ParentDirectoryDTO.GroupEntry group = result.groups().get(0);
        assertEquals("Kaefergruppe", group.groupName());
        assertEquals(2, group.families().size());

        ParentDirectoryDTO.FamilyEntry own = group.families().get(0);
        assertTrue(own.isOwnFamily());
        assertEquals(List.of("Lena"), childNames(own));
        assertEquals("Hauptstrasse 1, 1010 Wien", own.address());
        assertEquals("anna@x.at", own.parents().get(0).values().get("email"));

        ParentDirectoryDTO.FamilyEntry other = group.families().get(1);
        assertFalse(other.isOwnFamily());
        assertEquals(List.of("Tim"), childNames(other));
        assertEquals("Clara", other.parents().get(0).values().get("firstName"));
    }

    private List<String> childNames(ParentDirectoryDTO.FamilyEntry family) {
        return family.children().stream().map(ParentDirectoryDTO.ChildEntry::name).toList();
    }

    @Test
    void familyWithTwoChildrenInSameGroupAppearsOnce() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId otherFamily = persistFamily("Sommer", "Gasse 7", "1020", "Wien");
        Person twinA = persistPerson(otherFamily, "CHILD", "Tim", "Sommer", null, null);
        Person twinB = persistPerson(otherFamily, "CHILD", "Nina", "Sommer", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);
        assign(twinA.id, kaefer, semesterId);
        assign(twinB.id, kaefer, semesterId);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        List<ParentDirectoryDTO.FamilyEntry> families = result.groups().get(0).families();
        assertEquals(2, families.size());
        assertEquals(List.of("Nina", "Tim"), childNames(families.get(1)));
    }

    @Test
    void onlyChildrenOfTheSameGroupAreListedForAFamily() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId otherFamily = persistFamily("Sommer", "Gasse 7", "1020", "Wien");
        Person inGroup = persistPerson(otherFamily, "CHILD", "Tim", "Sommer", null, null);
        Person elsewhere = persistPerson(otherFamily, "CHILD", "Nina", "Sommer", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        ObjectId biene = persistGroup("Bienengruppe");
        assign(ownChild.id, kaefer, semesterId);
        assign(inGroup.id, kaefer, semesterId);
        assign(elsewhere.id, biene, semesterId);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals(List.of("Tim"), childNames(result.groups().get(0).families().get(1)));
    }

    @Test
    void childWithoutFirstNameYieldsNullInChildrenListWithoutThrowing() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId otherFamily = persistFamily("Sommer", "Gasse 7", "1020", "Wien");
        Person namedChild = persistPerson(otherFamily, "CHILD", "Tim", "Sommer", null, null);
        Person unnamedChild = persistPerson(otherFamily, "CHILD", null, "Sommer", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);
        assign(namedChild.id, kaefer, semesterId);
        assign(unnamedChild.id, kaefer, semesterId);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        List<String> children = childNames(result.groups().get(0).families().get(1));
        assertEquals(2, children.size());
        assertTrue(children.contains(null));
        assertTrue(children.contains("Tim"));
    }

    @Test
    void parentWithoutEmailIsListedWithNullEmail() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId otherFamily = persistFamily("Sommer", null, null, null);
        Person otherChild = persistPerson(otherFamily, "CHILD", "Tim", "Sommer", null, null);
        persistPerson(otherFamily, "PARENT", "Clara", "Sommer", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);
        assign(otherChild.id, kaefer, semesterId);

        ParentDirectoryDTO.FamilyEntry other = service.buildForFamily(ownFamily).groups().get(0).families().get(1);
        assertEquals(1, other.parents().size());
        assertNull(other.parents().get(0).values().get("email"));
        assertNull(other.parents().get(0).values().get("phone"));
        assertNull(other.address());
    }

    @Test
    void assignmentsOfOtherSemestersAreIgnored() {
        Semester older = new Semester();
        older.start = Instant.parse("2025-09-01T00:00:00Z");
        older.end = Instant.parse("2026-02-28T00:00:00Z");
        older.createdAt = Instant.parse("2025-08-01T00:00:00Z");
        older.persist();

        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId otherFamily = persistFamily("Sommer", "Gasse 7", "1020", "Wien");
        Person otherChild = persistPerson(otherFamily, "CHILD", "Tim", "Sommer", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);
        assign(otherChild.id, kaefer, older.id);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals(1, result.groups().get(0).families().size());
        assertTrue(result.groups().get(0).families().get(0).isOwnFamily());
    }

    @Test
    void childWithoutGroupYieldsEmptyGroupList() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals(semesterId.toHexString(), result.semesterId());
        assertTrue(result.groups().isEmpty());
    }

    @Test
    void withoutSemesterResultIsEmpty() {
        Semester.deleteAll();
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertNull(result.semesterId());
        assertTrue(result.groups().isEmpty());
    }

    @Test
    void groupWithDeletedFieldInstanceRendersWithNullNameWithoutThrowing() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);

        // Die field_instance der Gruppe wird geloescht, die Zuweisung bleibt bestehen.
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .deleteOne(new Document("_id", kaefer));

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals(1, result.groups().size());
        assertNull(result.groups().get(0).groupName());
        assertEquals(1, result.groups().get(0).families().size());
    }

    @Test
    void groupNameComesFromValueLabelWhenValueIsAnObject() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId groupInstance = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", groupInstance)
                        .append("definitionId", groupDefId)
                        .append("value", new Document("label", "Kaefergruppe").append("color", "#f00")));
        assign(ownChild.id, groupInstance, semesterId);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals("Kaefergruppe", result.groups().get(0).groupName());
    }

    @Test
    void groupNameFallsBackToDefinitionLabelWhenValueCarriesNoLabel() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        FieldDefinition def = new FieldDefinition();
        def.fieldName = "group";
        def.label = Map.of("de", "Baerengruppe");
        def.createdAt = Instant.now();
        def.persist();

        ObjectId groupInstance = new ObjectId();
        mongoClient.getDatabase(databaseName).getCollection("field_instances")
                .insertOne(new Document("_id", groupInstance)
                        .append("definitionId", def.id)
                        .append("value", true));
        assign(ownChild.id, groupInstance, semesterId);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals("Baerengruppe", result.groups().get(0).groupName());
    }

    @Test
    void groupsAreSortedByName() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person childA = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);
        Person childB = persistPerson(ownFamily, "CHILD", "Paul", "Muster", null, null);

        ObjectId zebra = persistGroup("Zebragruppe");
        ObjectId ameise = persistGroup("Ameisengruppe");
        assign(childA.id, zebra, semesterId);
        assign(childB.id, ameise, semesterId);

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals(List.of("Ameisengruppe", "Zebragruppe"),
                result.groups().stream().map(ParentDirectoryDTO.GroupEntry::groupName).toList());
    }

    @Test
    void onlyVisibleAttributesAreDelivered() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);
        persistPerson(ownFamily, "PARENT", "Anna", "Muster", "anna@x.at", "0660 111");

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);

        attributeService.save(List.of("firstName"));

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        Map<String, String> values = result.groups().get(0).families().get(0).parents().get(0).values();
        assertEquals(Map.of("firstName", "Anna"), values);
        assertNull(result.groups().get(0).families().get(0).address());
        assertEquals(List.of("childName", "firstName"),
                result.columns().stream().map(ParentDirectoryDTO.ColumnEntry::key).toList());
    }

    @Test
    void addressIsDeliveredOnlyWhenSelected() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);

        attributeService.save(List.of("address"));

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals("Hauptstrasse 1, 1010 Wien", result.groups().get(0).families().get(0).address());
    }

    @Test
    void customFieldOfParentIsDeliveredWhenSelected() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);
        Person parent = persistPerson(ownFamily, "PARENT", "Anna", "Muster", null, null);

        FieldDefinition allergies = new FieldDefinition();
        allergies.fieldName = "allergies";
        allergies.label = Map.of("de", "Allergien");
        allergies.createdAt = Instant.now();
        allergies.persist();

        parent.customProperties.add(new FieldRef(allergies.id, persistInstance(allergies.id, "Nuesse")));
        parent.update();

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);

        String key = "custom:" + allergies.id.toHexString();
        attributeService.save(List.of(key));

        ParentDirectoryDTO result = service.buildForFamily(ownFamily);

        assertEquals("Nuesse",
                result.groups().get(0).families().get(0).parents().get(0).values().get(key));
    }

    @Test
    void childDatesComeFromTheGroupAssignment() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments")
                .insertOne(new Document("_id", new ObjectId())
                        .append("personId", ownChild.id)
                        .append("semesterId", semesterId)
                        .append("section", "group")
                        .append("definitionId", groupDefId)
                        .append("fieldInstanceId", kaefer)
                        .append("entryDate", "2026-09-01")
                        .append("exitDate", "2027-01-31"));

        attributeService.save(List.of("childEntryDate", "childExitDate"));

        ParentDirectoryDTO.ChildEntry child =
                service.buildForFamily(ownFamily).groups().get(0).families().get(0).children().get(0);

        assertEquals("2026-09-01", child.entryDate());
        assertEquals("2027-01-31", child.exitDate());
    }

    @Test
    void childDatesAreOmittedWhenNotSelected() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments")
                .insertOne(new Document("_id", new ObjectId())
                        .append("personId", ownChild.id)
                        .append("semesterId", semesterId)
                        .append("section", "group")
                        .append("definitionId", groupDefId)
                        .append("fieldInstanceId", kaefer)
                        .append("entryDate", "2026-09-01")
                        .append("exitDate", "2027-01-31"));

        attributeService.save(List.of("firstName"));

        ParentDirectoryDTO.ChildEntry child =
                service.buildForFamily(ownFamily).groups().get(0).families().get(0).children().get(0);

        assertNull(child.entryDate());
        assertNull(child.exitDate());
    }

    private void assignSection(ObjectId personId, String section, ObjectId definitionId, ObjectId instanceId) {
        mongoClient.getDatabase(databaseName).getCollection("semester_assignments")
                .insertOne(new Document("_id", new ObjectId())
                        .append("personId", personId)
                        .append("semesterId", semesterId)
                        .append("section", section)
                        .append("definitionId", definitionId)
                        .append("fieldInstanceId", instanceId));
    }

    @Test
    void teamAndRoleComeFromTheCurrentSemester() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);
        Person parent = persistPerson(ownFamily, "PARENT", "Anna", "Muster", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);

        ObjectId teamDefId = persistDefinition("parent-team");
        ObjectId roleDefId = persistDefinition("parent-team-role");
        ObjectId gartenTeam = persistInstance(teamDefId, "Gartenteam");
        ObjectId kassier = persistInstance(roleDefId, "Kassier");

        assignSection(parent.id, "team", teamDefId, gartenTeam);
        assignSection(parent.id, "role", roleDefId, kassier);

        attributeService.save(List.of("team", "role"));

        Map<String, String> values = service.buildForFamily(ownFamily)
                .groups().get(0).families().get(0).parents().get(0).values();

        assertEquals("Gartenteam", values.get("team"));
        assertEquals("Kassier", values.get("role"));
    }

    @Test
    void severalTeamsAreJoinedIntoOneValue() {
        ObjectId ownFamily = persistFamily("Muster", "Hauptstrasse 1", "1010", "Wien");
        Person ownChild = persistPerson(ownFamily, "CHILD", "Lena", "Muster", null, null);
        Person parent = persistPerson(ownFamily, "PARENT", "Anna", "Muster", null, null);

        ObjectId kaefer = persistGroup("Kaefergruppe");
        assign(ownChild.id, kaefer, semesterId);

        ObjectId teamDefId = persistDefinition("parent-team");
        assignSection(parent.id, "team", teamDefId, persistInstance(teamDefId, "Gartenteam"));
        assignSection(parent.id, "team", teamDefId, persistInstance(teamDefId, "Festteam"));

        attributeService.save(List.of("team"));

        String team = service.buildForFamily(ownFamily)
                .groups().get(0).families().get(0).parents().get(0).values().get("team");

        assertEquals("Gartenteam, Festteam", team);
    }
}
