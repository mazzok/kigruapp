package at.kigruapp.resource;

import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.bson.types.ObjectId;

/**
 * Statusabfrage der Kochdienst-Erinnerungen. Fuer alle Angemeldeten lesbar
 * (siehe SecurityFilter), damit der Kochdienst-Dialog weiss, ob er die
 * Erinnerungs-Option anbieten darf. Konfiguriert wird ueber
 * /api/v1/cooking-reminder-jobs.
 */
@Path("/api/v1/cooking-reminder-settings")
@Produces(MediaType.APPLICATION_JSON)
public class CookingReminderSettingsResource {

    public record StatusDto(boolean active) {}

    @GET
    public StatusDto get() {
        for (MailJob job : MailJob.<MailJob>list("kind = ?1 and active = ?2", MailJob.KIND_COOKING_REMINDER, true)) {
            if (isSendable(job)) {
                return new StatusDto(true);
            }
        }
        return new StatusDto(false);
    }

    private boolean isSendable(MailJob job) {
        if (job.templateId == null || MailTemplate.findById(job.templateId) == null) {
            return false;
        }
        if (job.senderAccountId == null || !ObjectId.isValid(job.senderAccountId)) {
            return false;
        }
        MailAccount account = MailAccount.findById(new ObjectId(job.senderAccountId));
        return account != null && account.enabled;
    }
}
