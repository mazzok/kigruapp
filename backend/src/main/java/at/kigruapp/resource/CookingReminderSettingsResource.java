package at.kigruapp.resource;

import at.kigruapp.entity.CookingReminderSettings;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailTemplate;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Einstellungen der Kochdienst-Erinnerungen. GET ist für alle Angemeldeten
 * freigeschaltet (siehe SecurityFilter), damit der Kochdienst-Dialog weiß, ob
 * die Funktion aktiv ist. PUT bleibt durch das Default-Deny admin-only.
 */
@Path("/api/v1/cooking-reminder-settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CookingReminderSettingsResource {

    @jakarta.inject.Inject
    at.kigruapp.scheduler.CookingReminderScheduler cookingReminderScheduler;

    static final String DEFAULT_SEND_TIME = "07:00";

    private static final Pattern SEND_TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    public record SettingsDto(String senderAccountId, String templateId, String subject,
                              String sendTime, boolean active) {}

    /**
     * Aktiv ist die Funktion genau dann, wenn Konto und Vorlage gesetzt sind,
     * beide existieren und das Konto freigeschaltet ist.
     */
    public static boolean isActive(CookingReminderSettings settings) {
        if (settings == null || settings.senderAccountId == null || settings.templateId == null) {
            return false;
        }
        MailAccount account = findAccount(settings.senderAccountId);
        if (account == null || !account.enabled) {
            return false;
        }
        return findTemplate(settings.templateId) != null;
    }

    /** Öffentlich, weil der Scheduler (anderes Package) beide Referenzen auflöst. */
    public static MailAccount findAccount(String hexId) {
        if (hexId == null || !ObjectId.isValid(hexId)) {
            return null;
        }
        return MailAccount.findById(new ObjectId(hexId));
    }

    public static MailTemplate findTemplate(String hexId) {
        if (hexId == null || !ObjectId.isValid(hexId)) {
            return null;
        }
        return MailTemplate.findById(new ObjectId(hexId));
    }

    @GET
    public SettingsDto get() {
        CookingReminderSettings settings = CookingReminderSettings.findSingleton();
        if (settings == null) {
            return new SettingsDto(null, null, null, DEFAULT_SEND_TIME, false);
        }
        return toDto(settings);
    }

    @PUT
    public SettingsDto save(SettingsDto dto) {
        String sendTime = dto == null || dto.sendTime() == null ? DEFAULT_SEND_TIME : dto.sendTime().trim();
        if (!SEND_TIME_PATTERN.matcher(sendTime).matches()) {
            throw new BadRequestException("sendTime muss im Format HH:mm vorliegen");
        }

        String accountId = blankToNull(dto == null ? null : dto.senderAccountId());
        String templateId = blankToNull(dto == null ? null : dto.templateId());
        String subject = dto == null ? null : dto.subject();

        if (accountId != null) {
            if (findAccount(accountId) == null) {
                throw new BadRequestException("Mailkonto existiert nicht");
            }
            if (templateId == null || findTemplate(templateId) == null) {
                throw new BadRequestException("Zu einem Mailkonto muss eine gültige Vorlage gewählt werden");
            }
            if (subject == null || subject.isBlank()) {
                throw new BadRequestException("Betreff darf nicht leer sein");
            }
        } else {
            templateId = null;
            subject = null;
        }

        CookingReminderSettings settings = CookingReminderSettings.findSingleton();
        if (settings == null) {
            settings = new CookingReminderSettings();
        }
        settings.senderAccountId = accountId;
        settings.templateId = templateId;
        settings.subject = subject;
        settings.sendTime = sendTime;
        settings.updatedAt = Instant.now();
        settings.persistOrUpdate();

        cookingReminderScheduler.reschedule();

        return toDto(settings);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private SettingsDto toDto(CookingReminderSettings settings) {
        return new SettingsDto(settings.senderAccountId, settings.templateId, settings.subject,
                settings.sendTime == null ? DEFAULT_SEND_TIME : settings.sendTime, isActive(settings));
    }
}
