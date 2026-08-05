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
    MailJob job;
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

        template = new MailTemplate();
        template.name = "Erinnerung";
        template.bodyHtml = "<p>Hallo {{person.firstName}}, am {{duty.date}} kochst du.</p>";
        template.kind = MailTemplate.KIND_COOKING_REMINDER;
        template.createdAt = Instant.now();
        template.persist();

        job = new MailJob();
        job.kind = MailJob.KIND_COOKING_REMINDER;
        job.name = "Kochdienst-Erinnerung";
        job.templateId = template.id;
        job.subject = "Dein Kochdienst";
        job.senderAccountId = account.id.toHexString();
        job.sendTime = "07:00";
        job.active = true;
        job.createdAt = Instant.now();
        job.updatedAt = job.createdAt;
        job.persist();

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

    /** HÃ¤ngt einen Kochdienst an die Person und liefert die Instanz-Id zurÃ¼ck. */
    private ObjectId persistDuty(Person person, String date, boolean reminderEnabled, Integer daysBefore) {
        return persistDuty(person, date, reminderEnabled, daysBefore, List.of("g1"));
    }

    private ObjectId persistDuty(Person person, String date, boolean reminderEnabled, Integer daysBefore,
                                  List<String> groups) {
        Document value = new Document("date", date).append("groups", groups)
                .append("description", "Suppe");
        if (reminderEnabled) {
            value.append("reminderEnabled", true).append("reminderDaysBefore", daysBefore);
        }
        ObjectId instanceId = persistFieldInstance(cookingDutyDef.id, value);
        person.schedules.add(ref(cookingDutyDef.id, instanceId));
        person.update();
        return instanceId;
    }

    /** Persistiert eine Gruppen-FieldDefinition mit deutschem Label, wie sie /organisation/groups liefert. */
    private FieldDefinition persistGroup(String labelDe) {
        FieldDefinition def = new FieldDefinition();
        def.fieldName = "group";
        def.label = new java.util.HashMap<>();
        def.label.put("de", labelDe);
        def.createdAt = Instant.now();
        def.persist();
        return def;
    }

    @Test
    void sendetErinnerungAnBeideElternWennHeuteFaelligIst() throws Exception {
        Person anna = persistParent("Anna", "anna@example.org");
        persistParent("Bernd", "bernd@example.org");
        ObjectId dutyId = persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12), job);

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

        scheduler.runFor(LocalDate.of(2026, 9, 11), job);

        assertEquals(0, greenMail.getReceivedMessages().length);
        assertEquals(0, CookingReminder.count());
    }

    @Test
    void sendetNichtsOhneAktivierteErinnerung() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", false, null);

        scheduler.runFor(LocalDate.of(2026, 9, 12), job);

        assertEquals(0, greenMail.getReceivedMessages().length);
        assertEquals(0, CookingReminder.count());
    }

    @Test
    void sendetNichtZweimalFuerDenselbenTag() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12), job);
        int afterFirstRun = greenMail.getReceivedMessages().length;

        scheduler.runFor(LocalDate.of(2026, 9, 12), job);

        assertEquals(afterFirstRun, greenMail.getReceivedMessages().length);
        assertEquals(1, CookingReminder.count());
    }

    @Test
    void verschobenerKochdienstErinnertErneut() {
        Person anna = persistParent("Anna", "anna@example.org");
        ObjectId dutyId = persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12), job);

        fieldInstances().updateOne(new Document("_id", dutyId),
                new Document("$set", new Document("value.date", "2026-09-22")));

        scheduler.runFor(LocalDate.of(2026, 9, 19), job);

        assertEquals(2, CookingReminder.count());
    }

    @Test
    void ohneErreichbaresKontoWirdNurGeloggt() {
        account.enabled = false;
        account.update();
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12), job);

        assertEquals(0, greenMail.getReceivedMessages().length);
        CookingReminder log = CookingReminder.findAll().firstResult();
        assertNotNull(log);
        assertEquals(CookingReminderStatus.ACCOUNT_UNAVAILABLE, log.status);
    }

    @Test
    void familieOhneEmailWirdAlsNoRecipientsGeloggt() {
        Person ohne = persistParent("Ohne", "");
        persistDuty(ohne, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12), job);

        assertEquals(0, greenMail.getReceivedMessages().length);
        CookingReminder log = CookingReminder.findAll().firstResult();
        assertNotNull(log);
        assertEquals(CookingReminderStatus.NO_RECIPIENTS, log.status);
    }

    /**
     * Echte Nebenlaeufigkeit laesst sich in diesem Test nicht sauber
     * provozieren; stattdessen wird der Guard direkt geprueft, indem der
     * Running-Zustand ueber den Testhook gesetzt wird, bevor runFor
     * aufgerufen wird. Ein zweiter, "ueberlappender" Lauf darf dann nichts
     * versenden und nichts loggen.
     */
    @Test
    void ueberlappenderLaufWirdUebersprungen() {
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.markRunningForTest(job.id);
        try {
            scheduler.runFor(LocalDate.of(2026, 9, 12), job);

            assertEquals(0, greenMail.getReceivedMessages().length);
            assertEquals(0, CookingReminder.count());
        } finally {
            scheduler.clearRunningForTest(job.id);
        }
    }

    @Test
    void ohneVorlagePassiertNichts() {
        job.templateId = null;
        job.update();
        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12), job);

        assertEquals(0, greenMail.getReceivedMessages().length);
        CookingReminder log = CookingReminder.findAll().firstResult();
        assertNotNull(log);
        assertEquals(CookingReminderStatus.ACCOUNT_UNAVAILABLE, log.status);
    }

    /**
     * value.groups eines Kochdienstes speichert Ids von FieldDefinitions (so wie
     * sie GET /api/v1/organisation/groups liefert), nicht Ids von field_instances.
     * Die AuflÃ¶sung muss also Ã¼ber FieldDefinition laufen.
     */
    @Test
    void loestGruppenLabelsUeberFieldDefinitionAuf() throws Exception {
        FieldDefinition groupA = persistGroup("Adlergruppe");
        FieldDefinition groupB = persistGroup("Fuchsgruppe");

        MailTemplate groupsTemplate = new MailTemplate();
        groupsTemplate.name = "Erinnerung mit Gruppen";
        groupsTemplate.bodyHtml = "<p>Gruppen: {{duty.groups}}</p>";
        groupsTemplate.kind = MailTemplate.KIND_COOKING_REMINDER;
        groupsTemplate.createdAt = Instant.now();
        groupsTemplate.persist();

        job.templateId = groupsTemplate.id;
        job.update();

        Person anna = persistParent("Anna", "anna@example.org");
        persistDuty(anna, "2026-09-15", true, 3,
                List.of(groupA.id.toHexString(), groupB.id.toHexString()));

        scheduler.runFor(LocalDate.of(2026, 9, 12), job);

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        String body = GreenMailUtil.getBody(greenMail.getReceivedMessages()[0]);
        assertTrue(body.contains("Adlergruppe, Fuchsgruppe"),
                "Beide Gruppenlabels im Body erwartet, war: " + body);
    }

    /**
     * Regression: ein defekter Log-Insert (echter Schreibfehler, nicht der
     * Duplicate-Key-Fall) darf nur den betroffenen Kochdienst treffen und
     * nicht die runFor-Schleife verlassen. Ein echter Mongo-Schreibfehler
     * lÃ¤sst sich im Test nicht sinnvoll provozieren (der Testcontainer lÃ¤uft
     * durchgehend); stattdessen wird hier der bereits vorhandene
     * Teilausfall-Pfad Ã¼ber einen fehlschlagenden Mailversand geprÃ¼ft: bei
     * zwei fÃ¤lligen Kochdiensten scheitert der Versand fÃ¼r den einen
     * (syntaktisch ungÃ¼ltige EmpfÃ¤ngeradresse), der zweite wird trotzdem
     * zugestellt und beide werden korrekt geloggt.
     */
    @Test
    void fehlgeschlagenerVersandBeiEinemKochdienstVerhindertNichtDenAnderen() throws Exception {
        familyId = new ObjectId();
        Person anna = persistParent("Anna", "anna@example.org");
        ObjectId dutyIdOk = persistDuty(anna, "2026-09-15", true, 3);

        familyId = new ObjectId();
        Person peter = persistParent("Peter", "invalid address@example.org");
        ObjectId dutyIdFailing = persistDuty(peter, "2026-09-15", true, 3);

        scheduler.runFor(LocalDate.of(2026, 9, 12), job);

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        assertEquals(1, greenMail.getReceivedMessages().length);

        CookingReminder logOk = CookingReminder.find("dutyId", dutyIdOk).firstResult();
        assertNotNull(logOk);
        assertEquals(CookingReminderStatus.SENT, logOk.status);

        CookingReminder logFailing = CookingReminder.find("dutyId", dutyIdFailing).firstResult();
        assertNotNull(logFailing);
        assertEquals(CookingReminderStatus.FAILED, logFailing.status);
    }
}

