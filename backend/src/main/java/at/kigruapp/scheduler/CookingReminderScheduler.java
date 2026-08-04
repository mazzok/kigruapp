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
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.ErrorCategory;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Täglicher Versand der Kochdienst-Erinnerungen. Der Lauf ist rein
 * datumsgesteuert: erinnert wird ein Kochdienst genau an dem Tag, an dem
 * {@code dutyDate − reminderDaysBefore} auf das Laufdatum fällt. Vergangene
 * Fälligkeiten werden bewusst nicht nachgeholt.
 */
@ApplicationScoped
public class CookingReminderScheduler {

    public static final String JOB_ID = "cooking-reminder-daily";

    private static final String TIMEZONE = "Europe/Vienna";

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Inject
    MongoClient mongoClient;

    @Inject
    Scheduler scheduler;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    RecipientResolverService recipientResolverService;

    @Inject
    MailTemplateRenderer renderer;

    @Inject
    MailService mailService;

    /** In-memory guard against overlapping runs (only one daily job, unlike MailJobScheduler). */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Test-only hook: marks a run as currently in progress, so the overlap
     * guard can be exercised without real concurrency. Package-private —
     * called via the CDI proxy so it correctly mutates the actual bean
     * instance's state (unlike direct field access on a proxy).
     */
    void markRunningForTest() {
        running.set(true);
    }

    /** Test-only hook: clears the guard set by {@link #markRunningForTest()}. */
    void clearRunningForTest() {
        running.set(false);
    }

    /** Ein fälliger Kochdienst samt der für die Mail nötigen Daten. */
    record DueDuty(ObjectId dutyId, ObjectId familyId, String dutyDate, String dueDate,
                   String description, int daysBefore, List<String> groupIds) {}

    /** "18:30" -> "0 30 18 * * ?". Unlesbare oder fehlende Zeiten fallen auf 07:00 zurück. */
    public static String toCron(String sendTime) {
        LocalTime time = LocalTime.of(7, 0);
        if (sendTime != null) {
            try {
                time = LocalTime.parse(sendTime);
            } catch (DateTimeParseException ignored) {
                // Fallback bleibt 07:00
            }
        }
        return "0 " + time.getMinute() + " " + time.getHour() + " * * ?";
    }

    /** Registriert den täglichen Lauf neu. Idempotent — hebt eine bestehende Registrierung vorher auf. */
    public void reschedule() {
        CookingReminderSettings settings = CookingReminderSettings.findSingleton();
        String cron = toCron(settings == null ? null : settings.sendTime);
        if (scheduler.getScheduledJob(JOB_ID) != null) {
            scheduler.unscheduleJob(JOB_ID);
        }
        scheduler.newJob(JOB_ID)
                .setCron(cron)
                .setTimeZone(TIMEZONE)
                .setTask(ctx -> runFor(LocalDate.now(ZoneId.of(TIMEZONE))))
                .schedule();
        Log.infof("Kochdienst-Erinnerung: taeglicher Lauf registriert (%s, %s)", cron, TIMEZONE);
    }

    public void runFor(LocalDate today) {
        if (!running.compareAndSet(false, true)) {
            Log.warnf("Kochdienst-Erinnerung: Lauf fuer %s uebersprungen, vorheriger Lauf noch aktiv", today);
            return;
        }
        try {
            CookingReminderSettings settings = CookingReminderSettings.findSingleton();
            if (settings == null || settings.senderAccountId == null || settings.templateId == null) {
                return;
            }

            List<DueDuty> due = findDueDuties(today);
            if (due.isEmpty()) {
                return;
            }

            if (!CookingReminderSettingsResource.isActive(settings)) {
                String reason = inactiveReason(settings);
                Log.warnf("Kochdienst-Erinnerung: %s, %d faellige Erinnerung(en) entfallen", reason, due.size());
                for (DueDuty duty : due) {
                    writeLogSafely(duty, CookingReminderStatus.ACCOUNT_UNAVAILABLE, 0, reason);
                }
                return;
            }

            MailAccount account = CookingReminderSettingsResource.findAccount(settings.senderAccountId);
            MailTemplate template = CookingReminderSettingsResource.findTemplate(settings.templateId);

            for (DueDuty duty : due) {
                sendOne(duty, account, template, settings.subject);
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * isActive prüft Konto und Vorlage gemeinsam; hier wird unterschieden,
     * welche der beiden Ursachen tatsächlich zutrifft, damit der Log-Eintrag
     * nicht pauschal auf das Konto zeigt, wenn in Wahrheit die Vorlage fehlt.
     */
    private String inactiveReason(CookingReminderSettings settings) {
        MailAccount account = CookingReminderSettingsResource.findAccount(settings.senderAccountId);
        if (account == null || !account.enabled) {
            return "Mailkonto fehlt oder ist deaktiviert";
        }
        return "Mailvorlage fehlt";
    }

    /**
     * Ein Fehler bei einem Kochdienst darf die übrigen nicht verhindern, daher
     * wird pro Kochdienst gefangen und geloggt. Das gilt auch für den
     * Log-Insert selbst — {@link #writeLogSafely} stellt sicher, dass ein
     * defekter Insert (z. B. Mongo nicht erreichbar) nur diesen einen
     * Kochdienst betrifft und nicht die runFor-Schleife verlässt.
     */
    private void sendOne(DueDuty duty, MailAccount account, MailTemplate template, String subject) {
        try {
            List<RecipientResolverService.ResolvedRecipient> recipients =
                    recipientResolverService.resolveFamilyRecipients(duty.familyId());
            if (recipients.isEmpty()) {
                writeLogSafely(duty, CookingReminderStatus.NO_RECIPIENTS, 0, null);
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
                    Log.errorf(e, "Kochdienst-Erinnerung fuer %s an einen Empfaenger fehlgeschlagen: %s", duty.dutyId(), e.getMessage());
                }
            }

            if (successCount == recipients.size()) {
                writeLogSafely(duty, CookingReminderStatus.SENT, successCount, null);
            } else {
                writeLogSafely(duty, CookingReminderStatus.FAILED, successCount,
                        (recipients.size() - successCount) + " von " + recipients.size()
                                + " fehlgeschlagen; letzter Fehler: " + lastError);
            }
        } catch (Exception e) {
            Log.errorf(e, "Kochdienst-Erinnerung fuer %s fehlgeschlagen: %s", duty.dutyId(), e.getMessage());
            writeLogSafely(duty, CookingReminderStatus.FAILED, 0, e.getMessage());
        }
    }

    Map<String, String> buildDutyProperties(DueDuty duty) {
        Map<String, String> properties = new HashMap<>();
        properties.put("date", LocalDate.parse(duty.dutyDate()).format(DISPLAY_DATE));
        properties.put("description", duty.description() == null ? "" : duty.description());
        properties.put("daysBefore", String.valueOf(duty.daysBefore()));
        properties.put("groups", resolveGroupLabels(duty.groupIds()));
        properties.put("personName", resolvePersonName(duty.dutyId()));
        return properties;
    }

    /**
     * Wrapper um {@link #writeLog}, der jede Exception — auch den bewussten
     * Rethrow bei einem echten Schreibfehler — an dieser Stelle abfängt.
     * Ohne diese Absicherung würde ein defekter Insert für einen einzigen
     * Kochdienst die komplette runFor-Schleife verlassen und die übrigen
     * fälligen Kochdienste unbearbeitet lassen.
     */
    private void writeLogSafely(DueDuty duty, CookingReminderStatus status, int recipientCount, String error) {
        try {
            writeLog(duty, status, recipientCount, error);
        } catch (Exception e) {
            Log.errorf(e, "Kochdienst-Erinnerung: Log-Eintrag fuer %s/%s konnte nicht geschrieben werden, Kochdienst wird uebersprungen",
                    duty.dutyId(), duty.dueDate());
        }
    }

    /**
     * Der Log-Eintrag ist zugleich die Idempotenz-Sperre. Verliert dieser
     * Insert gegen einen parallelen Lauf (Unique-Index), ist die Erinnerung
     * bereits verbucht und der Fehler wird verschluckt. Jeder andere
     * Schreibfehler (Mongo weg, Validierung) muss dagegen sichtbar bleiben —
     * sonst gilt ein bereits versendeter Lauf am Folgetag wieder als fällig.
     * Nur über {@link #writeLogSafely} aufrufen, damit ein solcher Fehler
     * nicht die runFor-Schleife verlässt.
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
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                Log.warnf("Kochdienst-Erinnerung: Log-Eintrag fuer %s/%s bereits vorhanden", duty.dutyId(), duty.dueDate());
            } else {
                Log.errorf(e, "Kochdienst-Erinnerung: Log-Eintrag fuer %s/%s konnte nicht geschrieben werden", duty.dutyId(), duty.dueDate());
                throw e;
            }
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
            if (familyId == null) {
                Log.warnf("Kochdienst-Erinnerung: keine Familie fuer faelligen Kochdienst %s (Datum %s) gefunden, wird uebersprungen",
                        dutyId, dutyDate);
                continue;
            }

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

    /**
     * value.groups eines Kochdienstes enthält Ids von FieldDefinitions — die
     * Kochdienst-Ansicht lädt die Gruppen über GET /api/v1/organisation/groups,
     * das die FieldDefinitions liefert, deren Ids das Frontend hier speichert.
     * Nicht auffindbare Ids werden übersprungen.
     */
    private String resolveGroupLabels(List<String> groupIds) {
        List<String> labels = new ArrayList<>();
        for (String groupId : groupIds) {
            if (!ObjectId.isValid(groupId)) continue;
            FieldDefinition def = FieldDefinition.findById(new ObjectId(groupId));
            if (def == null || def.label == null) continue;
            String label = def.label.get("de");
            if (label != null) {
                labels.add(label);
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
