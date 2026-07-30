package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RecipientKind;
import at.kigruapp.entity.RecipientSelection;
import at.kigruapp.entity.SemesterAssignment;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RecipientResolverServiceTest {

    @Inject
    RecipientResolverService resolver;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private FieldDefinition groupDef;
    private FieldDefinition personTypeDef;
    private FieldDefinition emailDef;
    private FieldDefinition teamDef;
    private FieldDefinition roleDef;

    @BeforeEach
    void cleanup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());
        semesterAssignments().deleteMany(new Document());

        groupDef = persistDefinition("group");
        personTypeDef = persistDefinition("personType");
        emailDef = persistDefinition("email");
        teamDef = persistDefinition("parent-team");
        roleDef = persistDefinition("board-role");
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private MongoCollection<Document> semesterAssignments() {
        return mongoClient.getDatabase(databaseName).getCollection("semester_assignments");
    }

    private FieldDefinition persistDefinition(String fieldName) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.createdAt = java.time.Instant.now();
        def.persist();
        return def;
    }

    private ObjectId persistFieldInstance(ObjectId definitionId, String value) {
        ObjectId id = new ObjectId();
        fieldInstances().insertOne(new Document("_id", id).append("definitionId", definitionId).append("value", value));
        return id;
    }

    private Person persistPerson(ObjectId familyId, String personType, String email) {
        Person p = new Person();
        p.familyId = familyId;
        java.util.ArrayList<FieldRef> basics = new java.util.ArrayList<>();
        basics.add(new FieldRef(personTypeDef.id, persistFieldInstance(personTypeDef.id, personType)));
        if (email != null) {
            basics.add(new FieldRef(emailDef.id, persistFieldInstance(emailDef.id, email)));
        }
        p.basicProperties = basics;
        p.persist();
        return p;
    }

    private void assignToGroup(ObjectId childId, ObjectId semesterId, ObjectId groupInstanceId) {
        SemesterAssignment sa = new SemesterAssignment();
        sa.personId = childId;
        sa.semesterId = semesterId;
        sa.section = "group";
        sa.definitionId = groupDef.id;
        sa.fieldInstanceId = groupInstanceId;
        semesterAssignments().insertOne(sa.toDocument());
    }

    private void assignToSection(ObjectId personId, ObjectId semesterId, String section,
                                 ObjectId definitionId, ObjectId instanceId) {
        SemesterAssignment sa = new SemesterAssignment();
        sa.personId = personId;
        sa.semesterId = semesterId;
        sa.section = section;
        sa.definitionId = definitionId;
        sa.fieldInstanceId = instanceId;
        semesterAssignments().insertOne(sa.toDocument());
    }

    @Test
    void resolveGroupParentsReturnsDedupedParentsWithEmail() {
        ObjectId semesterId = new ObjectId();
        ObjectId familyWithEmail = new ObjectId();
        ObjectId familyWithoutEmail = new ObjectId();
        ObjectId unrelatedFamily = new ObjectId();

        Person parentWithEmail = persistPerson(familyWithEmail, "PARENT", "parent1@example.test");
        Person parentWithoutEmail = persistPerson(familyWithoutEmail, "PARENT", null);
        persistPerson(unrelatedFamily, "PARENT", "unrelated@example.test");

        Person child1 = persistPerson(familyWithEmail, "CHILD", null);
        Person child2 = persistPerson(familyWithEmail, "CHILD", null); // second child, same family -> dedup
        Person child3 = persistPerson(familyWithoutEmail, "CHILD", null);

        ObjectId targetGroupInstanceId = new ObjectId();
        assignToGroup(child1.id, semesterId, targetGroupInstanceId);
        assignToGroup(child2.id, semesterId, targetGroupInstanceId);
        assignToGroup(child3.id, semesterId, targetGroupInstanceId);

        List<Person> result = resolver.resolveGroupParents(List.of(targetGroupInstanceId), semesterId);

        assertEquals(1, result.size(), "must be deduped and exclude the parent without email");
        assertEquals(parentWithEmail.id, result.get(0).id);
    }

    @Test
    void resolveAllParentsReturnsEveryParentWithEmail() {
        Person parentWithEmail = persistPerson(new ObjectId(), "PARENT", "parent@example.test");
        persistPerson(new ObjectId(), "PARENT", null);
        persistPerson(new ObjectId(), "CHILD", "child@example.test");

        List<Person> result = resolver.resolveAllParents();

        assertEquals(1, result.size());
        assertEquals(parentWithEmail.id, result.get(0).id);
    }

    @Test
    void resolveAssignedParentsReturnsParentsOfTeamWithEmail() {
        ObjectId semesterId = new ObjectId();
        ObjectId teamInstanceId = new ObjectId();

        Person parentWithEmail = persistPerson(new ObjectId(), "PARENT", "team@example.test");
        Person parentWithoutEmail = persistPerson(new ObjectId(), "PARENT", null);
        Person childInTeam = persistPerson(new ObjectId(), "CHILD", "child@example.test");
        Person unrelatedParent = persistPerson(new ObjectId(), "PARENT", "other@example.test");

        assignToSection(parentWithEmail.id, semesterId, "team", teamDef.id, teamInstanceId);
        assignToSection(parentWithoutEmail.id, semesterId, "team", teamDef.id, teamInstanceId);
        assignToSection(childInTeam.id, semesterId, "team", teamDef.id, teamInstanceId);
        assignToSection(unrelatedParent.id, semesterId, "team", teamDef.id, new ObjectId());

        List<Person> result = resolver.resolveAssignedParents("team", List.of(teamInstanceId), semesterId);

        assertEquals(1, result.size(), "only the parent with an email, not the child, not the other team");
        assertEquals(parentWithEmail.id, result.get(0).id);
    }

    @Test
    void resolveAssignedParentsIgnoresOtherSemesters() {
        ObjectId semesterId = new ObjectId();
        ObjectId otherSemesterId = new ObjectId();
        ObjectId roleInstanceId = new ObjectId();

        Person parent = persistPerson(new ObjectId(), "PARENT", "role@example.test");
        assignToSection(parent.id, otherSemesterId, "role", roleDef.id, roleInstanceId);

        List<Person> result = resolver.resolveAssignedParents("role", List.of(roleInstanceId), semesterId);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolveAssignedParentsReturnsEmptyForUnknownInstance() {
        assertTrue(resolver.resolveAssignedParents("team", List.of(new ObjectId()), new ObjectId()).isEmpty());
        assertTrue(resolver.resolveAssignedParents("team", List.of(), new ObjectId()).isEmpty());
    }

    @Test
    void resolveUnionsKindsAndDedupesByPerson() {
        FieldDefinition firstNameDef = persistDefinition("firstName");
        ObjectId semesterId = new ObjectId();
        ObjectId familyId = new ObjectId();
        ObjectId groupInstanceId = new ObjectId();
        ObjectId teamInstanceId = new ObjectId();
        ObjectId roleInstanceId = new ObjectId();

        // Parent A is reachable twice: via the group (through their child) and via the team.
        Person parentA = persistPerson(familyId, "PARENT", "a@example.test");
        parentA.basicProperties = new java.util.ArrayList<>(parentA.basicProperties);
        parentA.basicProperties.add(new FieldRef(firstNameDef.id, persistFieldInstance(firstNameDef.id, "Anna")));
        parentA.update();
        Person child = persistPerson(familyId, "CHILD", null);
        assignToGroup(child.id, semesterId, groupInstanceId);
        assignToSection(parentA.id, semesterId, "team", teamDef.id, teamInstanceId);

        // Parent B only via the role.
        Person parentB = persistPerson(new ObjectId(), "PARENT", "b@example.test");
        assignToSection(parentB.id, semesterId, "role", roleDef.id, roleInstanceId);

        MailJob job = new MailJob();
        job.allParents = false;
        job.recipientSelections = List.of(
                new RecipientSelection(RecipientKind.GROUP, groupInstanceId),
                new RecipientSelection(RecipientKind.TEAM, teamInstanceId),
                new RecipientSelection(RecipientKind.ROLE, roleInstanceId));

        List<RecipientResolverService.ResolvedRecipient> result = resolver.resolve(job, semesterId);

        assertEquals(2, result.size(), "parentA must appear exactly once despite two matching selections");
        List<String> emails = result.stream().map(RecipientResolverService.ResolvedRecipient::email).toList();
        assertTrue(emails.contains("a@example.test"));
        assertTrue(emails.contains("b@example.test"));
        RecipientResolverService.ResolvedRecipient a = result.stream()
                .filter(r -> r.email().equals("a@example.test")).findFirst().orElseThrow();
        assertEquals("Anna", a.properties().get("firstName"));
    }

    @Test
    void resolveWithAllParentsIgnoresSelections() {
        ObjectId semesterId = new ObjectId();
        Person parent = persistPerson(new ObjectId(), "PARENT", "all@example.test");
        assertNotNull(parent.id);

        MailJob job = new MailJob();
        job.allParents = true;
        job.recipientSelections = List.of(new RecipientSelection(RecipientKind.TEAM, new ObjectId()));

        List<RecipientResolverService.ResolvedRecipient> result = resolver.resolve(job, semesterId);

        assertEquals(1, result.size());
        assertEquals("all@example.test", result.get(0).email());
    }

    @Test
    void resolveWithoutAllParentsAndWithoutSelectionsReturnsNothing() {
        persistPerson(new ObjectId(), "PARENT", "nobody@example.test");

        MailJob job = new MailJob();
        job.allParents = false;
        job.recipientSelections = List.of();

        assertTrue(resolver.resolve(job, new ObjectId()).isEmpty());
    }
}
