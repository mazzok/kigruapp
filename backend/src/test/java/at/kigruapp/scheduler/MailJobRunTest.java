package at.kigruapp.scheduler;

import at.kigruapp.entity.*;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.mail.internet.MimeMessage;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MailJobRunTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Inject
    MailJobScheduler mailJobScheduler;

    @Inject
    io.quarkus.scheduler.Scheduler scheduler;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    FieldDefinition groupDef;
    FieldDefinition personTypeDef;
    FieldDefinition emailDef;
    FieldDefinition firstNameDef;
    ObjectId semesterId;

    @BeforeEach
    void setup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        MailJob.deleteAll();
        MailTemplate.deleteAll();
        MailSettings.deleteAll();
        Semester.deleteAll();
        fieldInstances().deleteMany(new Document());
        semesterAssignments().deleteMany(new Document());

        MailSettings s = new MailSettings();
        s.host = "localhost";
        s.port = ServerSetupTest.SMTP.getPort();
        s.encryption = MailEncryption.NONE;
        s.fromAddress = "kita@example.test";
        s.fromName = "Kita";
        s.enabled = true;
        s.persistSingleton();

        Semester semester = new Semester();
        semester.createdAt = java.time.Instant.now();
        semester.persist();
        semesterId = semester.id;

        groupDef = persistDefinition("group");
        personTypeDef = persistDefinition("personType");
        emailDef = persistDefinition("email");
        firstNameDef = persistDefinition("firstName");
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

    private void assignToGroup(ObjectId childId, ObjectId groupDefinitionId) {
        SemesterAssignment sa = new SemesterAssignment();
        sa.personId = childId;
        sa.semesterId = semesterId;
        sa.section = "group";
        sa.definitionId = groupDefinitionId;
        sa.fieldInstanceId = new ObjectId();
        semesterAssignments().insertOne(sa.toDocument());
    }

    private MailTemplate persistTemplate() {
        MailTemplate t = new MailTemplate();
        t.name = "Willkommen";
        t.bodyHtml = "<p>Hallo {{person.firstName}}</p>";
        t.persist();
        return t;
    }

    @Test
    void runJobSendsToAllRecipientsAndRecordsSuccess() {
        MailTemplate template = persistTemplate();

        ObjectId familyId = new ObjectId();
        Person parent = new Person();
        parent.familyId = familyId;
        parent.basicProperties = List.of(
                new FieldRef(personTypeDef.id, persistFieldInstance(personTypeDef.id, "PARENT")),
                new FieldRef(emailDef.id, persistFieldInstance(emailDef.id, "parent@example.test")),
                new FieldRef(firstNameDef.id, persistFieldInstance(firstNameDef.id, "Anna"))
        );
        parent.persist();

        Person child = new Person();
        child.familyId = familyId;
        child.basicProperties = List.of(new FieldRef(personTypeDef.id, persistFieldInstance(personTypeDef.id, "CHILD")));
        child.persist();
        assignToGroup(child.id, groupDef.id);

        MailJob job = new MailJob();
        job.templateId = template.id;
        job.subject = "Willkommen";
        job.recipientMode = RecipientMode.GROUPS;
        job.recipientGroupDefinitionIds = List.of(groupDef.id);
        job.persist();

        mailJobScheduler.runJob(job, template);

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);
        assertTrue(GreenMailUtil.getBody(received[0]).contains("Hallo Anna"));

        assertEquals("SUCCESS", job.lastRunStatus);
        assertNotNull(job.lastRunAt);
    }

    @Test
    void runJobSkipsWhenAlreadyRunning() {
        MailTemplate template = persistTemplate();
        MailJob job = new MailJob();
        job.templateId = template.id;
        job.subject = "Willkommen";
        job.recipientMode = RecipientMode.ALL_PARENTS;
        job.persist();

        mailJobScheduler.markRunningForTest(job.id);

        mailJobScheduler.runJob(job, template);

        assertEquals("SKIPPED_OVERLAP", job.lastRunStatus);
        assertEquals(0, greenMail.getReceivedMessages().length, "no send should be attempted");
    }

    @Test
    void runJobRecordsNoRecipientsWhenResolutionIsEmpty() {
        MailTemplate template = persistTemplate();
        MailJob job = new MailJob();
        job.templateId = template.id;
        job.subject = "Willkommen";
        job.recipientMode = RecipientMode.GROUPS;
        job.recipientGroupDefinitionIds = List.of(groupDef.id); // no children assigned to this group
        job.persist();

        mailJobScheduler.runJob(job, template);

        assertEquals("NO_RECIPIENTS", job.lastRunStatus);
        assertEquals(0, greenMail.getReceivedMessages().length);
    }

    @Test
    void fireAutoDeactivatesJobWithMissingTemplate() {
        MailJob job = new MailJob();
        job.templateId = new ObjectId(); // no such template
        job.subject = "Willkommen";
        job.cron = "0 0 8 * * ?";
        job.recipientMode = RecipientMode.ALL_PARENTS;
        job.active = true;
        job.persist();
        mailJobScheduler.schedule(job);

        mailJobScheduler.fire(job.id);

        MailJob reloaded = MailJob.findById(job.id);
        assertFalse(reloaded.active);
        assertEquals("FAILED", reloaded.lastRunStatus);
        assertNull(scheduler.getScheduledJob(job.id.toHexString()));
    }

    @Test
    void runJobRecordsPartialWhenOneRecipientFails() {
        MailTemplate template = persistTemplate();

        Person goodParent = new Person();
        goodParent.familyId = new ObjectId();
        goodParent.basicProperties = List.of(
                new FieldRef(personTypeDef.id, persistFieldInstance(personTypeDef.id, "PARENT")),
                new FieldRef(emailDef.id, persistFieldInstance(emailDef.id, "good@example.test"))
        );
        goodParent.persist();

        Person badParent = new Person();
        badParent.familyId = new ObjectId();
        badParent.basicProperties = List.of(
                new FieldRef(personTypeDef.id, persistFieldInstance(personTypeDef.id, "PARENT")),
                new FieldRef(emailDef.id, persistFieldInstance(emailDef.id, "invalid@@example.test"))
        );
        badParent.persist();

        MailJob job = new MailJob();
        job.templateId = template.id;
        job.subject = "Willkommen";
        job.recipientMode = RecipientMode.ALL_PARENTS;
        job.persist();

        mailJobScheduler.runJob(job, template);

        assertEquals("PARTIAL", job.lastRunStatus);
        assertNotNull(job.lastRunError);
        assertEquals(1, greenMail.getReceivedMessages().length, "the valid recipient must still receive mail");
    }
}
