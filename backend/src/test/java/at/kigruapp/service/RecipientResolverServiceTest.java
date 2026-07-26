package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.RecipientMode;
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

    @BeforeEach
    void cleanup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        fieldInstances().deleteMany(new Document());
        semesterAssignments().deleteMany(new Document());

        groupDef = persistDefinition("group");
        personTypeDef = persistDefinition("personType");
        emailDef = persistDefinition("email");
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

    private void assignToGroup(ObjectId childId, ObjectId semesterId, ObjectId groupDefinitionId) {
        SemesterAssignment sa = new SemesterAssignment();
        sa.personId = childId;
        sa.semesterId = semesterId;
        sa.section = "group";
        sa.definitionId = groupDefinitionId;
        sa.fieldInstanceId = new ObjectId();
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

        assignToGroup(child1.id, semesterId, groupDef.id);
        assignToGroup(child2.id, semesterId, groupDef.id);
        assignToGroup(child3.id, semesterId, groupDef.id);

        List<Person> result = resolver.resolveGroupParents(List.of(groupDef.id), semesterId);

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
    void resolveDispatchesByRecipientModeAndAttachesProperties() {
        FieldDefinition firstNameDef = persistDefinition("firstName");
        ObjectId semesterId = new ObjectId();
        ObjectId familyId = new ObjectId();

        Person parent = persistPerson(familyId, "PARENT", "parent@example.test");
        ObjectId parentFirstNameInstance = persistFieldInstance(firstNameDef.id, "Anna");
        parent.basicProperties = new java.util.ArrayList<>(parent.basicProperties);
        parent.basicProperties.add(new FieldRef(firstNameDef.id, parentFirstNameInstance));
        parent.update();

        Person child = persistPerson(familyId, "CHILD", null);
        assignToGroup(child.id, semesterId, groupDef.id);

        MailJob groupsJob = new MailJob();
        groupsJob.recipientMode = RecipientMode.GROUPS;
        groupsJob.recipientGroupDefinitionIds = List.of(groupDef.id);

        List<RecipientResolverService.ResolvedRecipient> groupsResult = resolver.resolve(groupsJob, semesterId);
        assertEquals(1, groupsResult.size());
        assertEquals("parent@example.test", groupsResult.get(0).email());
        assertEquals("Anna", groupsResult.get(0).properties().get("firstName"));

        MailJob allParentsJob = new MailJob();
        allParentsJob.recipientMode = RecipientMode.ALL_PARENTS;

        List<RecipientResolverService.ResolvedRecipient> allResult = resolver.resolve(allParentsJob, semesterId);
        assertEquals(1, allResult.size());
        assertEquals("parent@example.test", allResult.get(0).email());
    }
}
