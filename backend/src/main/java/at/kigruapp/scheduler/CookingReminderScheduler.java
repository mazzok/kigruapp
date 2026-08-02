package at.kigruapp.scheduler;

import at.kigruapp.entity.CookingReminder;
import at.kigruapp.entity.CookingReminderSettings;
import at.kigruapp.entity.CookingReminderStatus;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailTemplate;
import at.kigruapp.resource.CookingReminderSettingsResource;
import at.kigruapp.service.MailService;
import at.kigruapp.service.MailTemplateRenderer;
import at.kigruapp.service.RecipientResolverService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Täglicher Versand der Kochdienst-Erinnerungen. Der Lauf ist rein
 * datumsgesteuert: erinnert wird ein Kochdienst genau an dem Tag, an dem
 * {@code dutyDate − reminderDaysBefore} auf das Laufdatum fällt. Vergangene
 * Fälligkeiten werden bewusst nicht nachgeholt.
 */
@ApplicationScoped
public class CookingReminderScheduler {

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    RecipientResolverService recipientResolverService;

    @Inject
    MailTemplateRenderer renderer;

    @Inject
    MailService mailService;

    /** Ein fälliger Kochdienst samt der für die Mail nötigen Daten. */
    record DueDuty(ObjectId dutyId, ObjectId familyId, String dutyDate, String dueDate,
                   String description, int daysBefore, List<String> groupIds) {}

    public void runFor(LocalDate today) {
        CookingReminderSettings settings = CookingReminderSettings.findSingleton();
        if (settings == null || settings.senderAccountId == null || settings.templateId == null) {
            return;
        }

        List<DueDuty> due = findDueDuties(today);
        if (due.isEmpty()) {
            return;
        }

        if (!CookingReminderSettingsResource.isActive(settings)) {
            Log.warnf("Kochdienst-Erinnerung: Konto fehlt oder ist deaktiviert, %d faellige Erinnerung(en) entfallen", due.size());
            for (DueDuty duty : due) {
                writeLog(duty, CookingReminderStatus.ACCOUNT_UNAVAILABLE, 0, "Mailkonto fehlt oder ist deaktiviert");
            }
            return;
        }

        MailAccount account = CookingReminderSettingsResource.findAccount(settings.senderAccountId);
        MailTemplate template = CookingReminderSettingsResource.findTemplate(settings.templateId);

        for (DueDuty duty : due) {
            sendOne(duty, account, template, settings.subject);
        }
    }

    /**
     * Ein Fehler bei einem Kochdienst darf die übrigen nicht verhindern, daher
     * wird pro Kochdienst gefangen und geloggt.
     */
    private void sendOne(DueDuty duty, MailAccount account, MailTemplate template, String subject) {
        try {
            List<RecipientResolverService.ResolvedRecipient> recipients =
                    recipientResolverService.resolveFamilyRecipients(duty.familyId());
            if (recipients.isEmpty()) {
                writeLog(duty, CookingReminderStatus.NO_RECIPIENTS, 0, null);
                return;
            }

            Map<String, String> dutyProperties = buildDutyProperties(duty);
            int successCount = 0;
            String lastError = null;
            for (RecipientResolverService.ResolvedRecipient recipient : recipients) {
                try {
                    String html = renderer.render(template.bodyHtml, recipient.properties(), dutyProperties);
                    mailService.sendHtml(account, recipient.email(), subject, html);
                    successCount++;
                } catch (Exception e) {
                    lastError = e.getMessage();
                    Log.errorf(e, "Kochdienst-Erinnerung an %s fehlgeschlagen: %s", recipient.email(), e.getMessage());
                }
            }

            if (successCount == recipients.size()) {
                writeLog(duty, CookingReminderStatus.SENT, successCount, null);
            } else {
                writeLog(duty, CookingReminderStatus.FAILED, successCount,
                        (recipients.size() - successCount) + " von " + recipients.size()
                                + " fehlgeschlagen; letzter Fehler: " + lastError);
            }
        } catch (Exception e) {
            Log.errorf(e, "Kochdienst-Erinnerung fuer %s fehlgeschlagen: %s", duty.dutyId(), e.getMessage());
            writeLog(duty, CookingReminderStatus.FAILED, 0, e.getMessage());
        }
    }

    private Map<String, String> buildDutyProperties(DueDuty duty) {
        Map<String, String> properties = new HashMap<>();
        properties.put("date", LocalDate.parse(duty.dutyDate()).format(DISPLAY_DATE));
        properties.put("description", duty.description() == null ? "" : duty.description());
        properties.put("daysBefore", String.valueOf(duty.daysBefore()));
        properties.put("groups", resolveGroupLabels(duty.groupIds()));
        properties.put("personName", resolvePersonName(duty.dutyId()));
        return properties;
    }

    /**
     * Der Log-Eintrag ist zugleich die Idempotenz-Sperre. Verliert dieser
     * Insert gegen einen parallelen Lauf (Unique-Index), ist die Erinnerung
     * bereits verbucht und der Fehler wird verschluckt.
     */
    private void writeLog(DueDuty duty, CookingReminderStatus status, int recipientCount, String error) {
        CookingReminder reminder = new CookingReminder();
        reminder.dutyId = duty.dutyId();
        reminder.dueDate = duty.dueDate();
        reminder.dutyDate = duty.dutyDate();
        reminder.sentAt = Instant.now();
        reminder.status = status;
        reminder.recipientCount = recipientCount;
        reminder.error = error;
        try {
            reminder.persist();
        } catch (Exception e) {
            Log.warnf("Kochdienst-Erinnerung: Log-Eintrag fuer %s/%s bereits vorhanden", duty.dutyId(), duty.dueDate());
        }
    }

    /**
     * Sucht alle Kochdienste mit aktivierter Erinnerung, deren Fälligkeitstag
     * heute ist und für die noch kein Log-Eintrag existiert.
     */
    List<DueDuty> findDueDuties(LocalDate today) {
        FieldDefinition cookingDutyDef = FieldDefinition.find("fieldName", "cookingDuty").firstResult();
        if (cookingDutyDef == null) {
            return List.of();
        }
        String dueDate = today.toString();

        List<DueDuty> result = new ArrayList<>();
        MongoCollection<Document> instances = fieldInstances();
        for (Document doc : instances.find(Filters.and(
                Filters.eq("definitionId", cookingDutyDef.id),
                Filters.eq("value.reminderEnabled", true)))) {

            Object valueObj = doc.get("value");
            if (!(valueObj instanceof Document value)) continue;

            String dutyDate = value.getString("date");
            Object daysObj = value.get("reminderDaysBefore");
            if (dutyDate == null || !(daysObj instanceof Number days)) continue;

            LocalDate parsedDutyDate;
            try {
                parsedDutyDate = LocalDate.parse(dutyDate);
            } catch (DateTimeParseException e) {
                continue;
            }
            if (!parsedDutyDate.minusDays(days.intValue()).equals(today)) continue;

            ObjectId dutyId = doc.getObjectId("_id");
            if (CookingReminder.existsFor(dutyId, dueDate)) continue;

            ObjectId familyId = resolveFamilyId(dutyId);
            if (familyId == null) continue;

            List<String> groupIds = new ArrayList<>();
            Object groupsObj = value.get("groups");
            if (groupsObj instanceof List<?> list) {
                for (Object group : list) {
                    groupIds.add(group.toString());
                }
            }

            result.add(new DueDuty(dutyId, familyId, dutyDate, dueDate,
                    value.getString("description"), days.intValue(), groupIds));
        }
        return result;
    }

    /** Die Familie hängt am Person-Dokument, das den Kochdienst in schedules führt. */
    private ObjectId resolveFamilyId(ObjectId dutyInstanceId) {
        Document person = persons().find(Filters.eq("schedules.fieldInstanceId", dutyInstanceId)).first();
        return person == null ? null : person.getObjectId("familyId");
    }

    private String resolvePersonName(ObjectId dutyInstanceId) {
        Document person = persons().find(Filters.eq("schedules.fieldInstanceId", dutyInstanceId)).first();
        if (person == null) {
            return "";
        }
        String firstName = readBasicProperty(person, "firstName");
        String lastName = readBasicProperty(person, "lastName");
        return (firstName + " " + lastName).trim();
    }

    private String readBasicProperty(Document person, String fieldName) {
        FieldDefinition def = FieldDefinition.find("fieldName", fieldName).firstResult();
        if (def == null) {
            return "";
        }
        Object basicProperties = person.get("basicProperties");
        if (!(basicProperties instanceof List<?> refs)) {
            return "";
        }
        for (Object refObj : refs) {
            if (!(refObj instanceof Document ref)) continue;
            if (!def.id.equals(ref.getObjectId("definitionId"))) continue;
            Document instance = fieldInstances().find(Filters.eq("_id", ref.getObjectId("fieldInstanceId"))).first();
            if (instance != null && instance.get("value") != null) {
                return instance.get("value").toString();
            }
        }
        return "";
    }

    private String resolveGroupLabels(List<String> groupIds) {
        List<String> labels = new ArrayList<>();
        for (String groupId : groupIds) {
            if (!ObjectId.isValid(groupId)) continue;
            Document instance = fieldInstances().find(Filters.eq("_id", new ObjectId(groupId))).first();
            if (instance == null) continue;
            Object value = instance.get("value");
            if (value instanceof Document valueDoc) {
                Object label = valueDoc.get("label");
                if (label instanceof Document labelDoc && labelDoc.getString("de") != null) {
                    labels.add(labelDoc.getString("de"));
                    continue;
                }
            }
            if (value != null) {
                labels.add(value.toString());
            }
        }
        return String.join(", ", labels);
    }

    private MongoCollection<Document> fieldInstances() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private MongoCollection<Document> persons() {
        return mongoClient.getDatabase(databaseName).getCollection("persons");
    }
}
