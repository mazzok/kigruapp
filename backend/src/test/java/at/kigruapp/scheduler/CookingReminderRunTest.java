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

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CookingReminderRunTest {

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
    MailTemplate template;
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
        CookingReminder.deleteAll();
        CookingReminderSettings.deleteAll();
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

        template = new MailTemplate();
        template.name = "Erinnerung";
        template.bodyHtml = "<p>Hallo {{person.firstName}}, am {{duty.date}} kochst du.</p>";
        template.createdAt = Instant.now();
        template.persist();

        CookingReminderSettings settings = new CookingReminderSettings();
        settings.senderAccountId = account.id.toHexString();
        settings.templateId = template.id.toHexString();
        settings.subject = "Dein Kochdienst";
        settings.sendTime = "07:00";
        settings.updatedAt = Instant.now();
        settings.persist();

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

    /** Hängt einen Kochdienst an die Person und liefert die Instanz-Id zurück. */
    private ObjectId persistDuty(Person person, String date, boolean reminderEnabled, Integer daysBefore) {
        Document value = new Document("date", date).append("groups", List.of("g1"))
                .append("description", "Suppe");
        if (reminderEnabled) {
            value.append("reminderEnabled", true).append("reminderDaysBefore", daysBefore);
        }
        ObjectId instanceId = persistFieldInstance(cookingDutyDef.id, value);
        person.schedules.add(ref(cookingDutyDef.id, instanceId));
        person.update();
        return instanceId;
    }

    @Test
    void sendetErinnerungAnBeideElternWennHeuteFaelligIst() throws Exception {
        Person anna = persistParent("Anna", "anna@example.org");
        persistParent("Bernd", "bernd@example.org");
        ObjectId dutyId = persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        assertTrue(greenMail.waitForIncomingEmail(5000, 2));
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(2, messages.length);
        String body = GreenMailUtil.getBody(messages[0]);
        assertTrue(body.contains("15.09.2026"), "Duty-Datum im Body erwartet, war: " + body);

        CookingReminder log = CookingReminder.find("dutyId", dutyId).firstResult();
        assertNotNull(log);
        assertEquals(CookingReminderStatus.SENT, log.status);
        assertEquals("2026-09-12", log.dueDate);
        assertEquals(2, log.recipientCount);
    }

    @Test
    void sendetNichtsWennHeuteNichtDerFaelligkeitstagIst() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 11));

        assertEquals(0, greenMail.getReceivedMessages().length);
        assertEquals(0, CookingReminder.count());
    }

    @Test
    void sendetNichtsOhneAktivierteErinnerung() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", false, null);

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        assertEquals(0, greenMail.getReceivedMessages().length);
        assertEquals(0, CookingReminder.count());
    }

    @Test
    void sendetNichtZweimalFuerDenselbenTag() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12));
        int afterFirstRun = greenMail.getReceivedMessages().length;

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        assertEquals(afterFirstRun, greenMail.getReceivedMessages().length);
        assertEquals(1, CookingReminder.count());
    }

    @Test
    void verschobenerKochdienstErinnertErneut() {
        Person anna = persistParent("Anna", "anna@example.org");
        ObjectId dutyId = persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        fieldInstances().updateOne(new Document("_id", dutyId),
                new Document("$set", new Document("value.date", "2026-09-22")));

        scheduler.runFor(LocalDate.of(2026, 9, 19));

        assertEquals(2, CookingReminder.count());
    }

    @Test
    void ohneErreichbaresKontoWirdNurGeloggt() {
        account.enabled = false;
        account.update();
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        assertEquals(0, greenMail.getReceivedMessages().length);
        CookingReminder log = CookingReminder.findAll().firstResult();
        assertNotNull(log);
        assertEquals(CookingReminderStatus.ACCOUNT_UNAVAILABLE, log.status);
    }

    @Test
    void familieOhneEmailWirdAlsNoRecipientsGeloggt() {
        Person ohne = persistParent("Ohne", "");
        persistDuty(ohne, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        assertEquals(0, greenMail.getReceivedMessages().length);
        CookingReminder log = CookingReminder.findAll().firstResult();
        assertNotNull(log);
        assertEquals(CookingReminderStatus.NO_RECIPIENTS, log.status);
    }

    @Test
    void ohneKonfigurationPassiertNichts() {
        CookingReminderSettings.deleteAll();
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12));

        assertEquals(0, greenMail.getReceivedMessages().length);
        assertEquals(0, CookingReminder.count());
    }
}
