package at.kigruapp.scheduler;

import at.kigruapp.entity.*;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zwei aktive Kochdienst-Jobs muessen fuer denselben faelligen Kochdienst
 * beide senden und beide protokollieren.
 */
@QuarkusTest
class CookingReminderMultiJobRunTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Inject
    CookingReminderScheduler scheduler;

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    FieldDefinition cookingDutyDef;
    FieldDefinition personTypeDef;
    FieldDefinition emailDef;
    FieldDefinition firstNameDef;
    MailAccount account;
    ObjectId familyId;

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    @BeforeEach
    void setup() {
        Person.deleteAll();
        FieldDefinition.deleteAll();
        MailAccount.deleteAll();
        MailTemplate.deleteAll();
        MailJob.deleteAll();
        CookingReminder.deleteAll();
        fieldInstances().deleteMany(new Document());

        cookingDutyDef = persistDefinition("cookingDuty");
        personTypeDef = persistDefinition("personType");
        emailDef = persistDefinition("email");
        firstNameDef = persistDefinition("firstName");

        account = new MailAccount();
        account.name = "Kiga";
        account.host = "localhost";
        account.port = greenMail.getSmtp().getPort();
        account.encryption = MailEncryption.NONE;
        account.fromAddress = "kiga@example.org";
        account.fromName = "Kindergruppe";
        account.enabled = true;
        account.persist();

        familyId = new ObjectId();
    }

    private FieldDefinition persistDefinition(String fieldName) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = fieldName;
        def.createdAt = Instant.now();
        def.persist();
        return def;
    }

    private ObjectId persistFieldInstance(ObjectId definitionId, Object value) {
        ObjectId id = new ObjectId();
        fieldInstances().insertOne(new Document("_id", id)
                .append("definitionId", definitionId).append("value", value));
        return id;
    }

    private FieldRef ref(ObjectId definitionId, ObjectId instanceId) {
        FieldRef fieldRef = new FieldRef();
        fieldRef.definitionId = definitionId;
        fieldRef.fieldInstanceId = instanceId;
        return fieldRef;
    }

    private Person persistParent(String firstName, String email) {
        Person person = new Person();
        person.familyId = familyId;
        person.basicProperties = new ArrayList<>();
        person.basicProperties.add(ref(personTypeDef.id, persistFieldInstance(personTypeDef.id, "PARENT")));
        person.basicProperties.add(ref(firstNameDef.id, persistFieldInstance(firstNameDef.id, firstName)));
        person.basicProperties.add(ref(emailDef.id, persistFieldInstance(emailDef.id, email)));
        person.schedules = new ArrayList<>();
        person.persist();
        return person;
    }

    private void persistDuty(Person person, String date, int daysBefore) {
        Document value = new Document("date", date).append("groups", List.of("g1"))
                .append("description", "Suppe")
                .append("reminderEnabled", true).append("reminderDaysBefore", daysBefore);
        ObjectId instanceId = persistFieldInstance(cookingDutyDef.id, value);
        person.schedules.add(ref(cookingDutyDef.id, instanceId));
        person.update();
    }

    private MailJob persistCookingJob(String name, String bodyHtml, boolean active) {
        MailTemplate template = new MailTemplate();
        template.name = name;
        template.bodyHtml = bodyHtml;
        template.kind = MailTemplate.KIND_COOKING_REMINDER;
        template.createdAt = Instant.now();
        template.updatedAt = template.createdAt;
        template.persist();

        MailJob job = new MailJob();
        job.kind = MailJob.KIND_COOKING_REMINDER;
        job.name = name;
        job.templateId = template.id;
        job.subject = "Dein Kochdienst — " + name;
        job.senderAccountId = account.id.toHexString();
        job.sendTime = "07:00";
        job.active = active;
        job.createdAt = Instant.now();
        job.updatedAt = job.createdAt;
        job.persist();
        return job;
    }

    @Test
    void everyActiveJobSendsItsOwnReminder() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", 3);
        LocalDate today = LocalDate.of(2026, 9, 12);

        MailJob first = persistCookingJob("Kurz", "<p>Kurz {{duty.date}}</p>", true);
        MailJob second = persistCookingJob("Lang", "<p>Lang {{duty.date}}</p>", true);
        MailJob inactive = persistCookingJob("Aus", "<p>Aus</p>", false);

        scheduler.runFor(today, first);
        scheduler.runFor(today, second);

        assertTrue(greenMail.waitForIncomingEmail(5000, 2));
        assertEquals(2, greenMail.getReceivedMessages().length);
        assertEquals(2, CookingReminder.count());
        assertEquals(1, CookingReminder.count("jobId", first.id));
        assertEquals(1, CookingReminder.count("jobId", second.id));
        assertEquals(0, CookingReminder.count("jobId", inactive.id));
    }

    @Test
    void aJobDoesNotSendTwiceForTheSameDay() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", 3);
        LocalDate today = LocalDate.of(2026, 9, 12);
        MailJob job = persistCookingJob("Kurz", "<p>Kurz</p>", true);

        scheduler.runFor(today, job);
        scheduler.runFor(today, job);

        assertEquals(1, CookingReminder.count());
    }
}
