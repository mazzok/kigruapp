package at.kigruapp.migration;

import at.kigruapp.entity.CookingReminderSettings;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * Ueberfuehrt die alte Singleton-Konfiguration in einen Kochdienst-Job.
 * Das Singleton bleibt als Datensatz liegen, damit ein Rueckbau moeglich ist;
 * gelesen wird es nach dieser Migration nicht mehr.
 */
@ApplicationScoped
@Startup
public class CookingReminderSettingsToJobMigration {

    void onStart(@Observes StartupEvent ev) {
        run();
    }

    public void run() {
        if (MailJob.count("kind", MailJob.KIND_COOKING) > 0) {
            return;
        }
        CookingReminderSettings settings = CookingReminderSettings.findSingleton();
        if (settings == null || settings.senderAccountId == null || settings.templateId == null) {
            return;
        }
        MailTemplate source = ObjectId.isValid(settings.templateId)
                ? MailTemplate.findById(new ObjectId(settings.templateId))
                : null;
        if (source == null) {
            return;
        }

        MailTemplate target = usedByGeneralJob(source) ? copyOf(source) : adopt(source);

        MailJob job = new MailJob();
        job.kind = MailJob.KIND_COOKING;
        job.name = "Kochdienst-Erinnerung";
        job.templateId = target.id;
        job.subject = settings.subject;
        job.senderAccountId = settings.senderAccountId;
        job.sendTime = settings.sendTime == null ? "07:00" : settings.sendTime;
        job.active = isSendable(settings);
        job.createdAt = Instant.now();
        job.updatedAt = job.createdAt;
        job.persist();
        Log.infof("Kochdienst-Erinnerung: Einstellungen in Job %s ueberfuehrt", job.id.toHexString());
    }

    private boolean usedByGeneralJob(MailTemplate template) {
        for (MailJob job : MailJob.<MailJob>list("templateId", template.id)) {
            if (!job.isCooking()) {
                return true;
            }
        }
        return false;
    }

    private MailTemplate adopt(MailTemplate template) {
        template.kind = MailTemplate.KIND_COOKING;
        template.updatedAt = Instant.now();
        template.update();
        return template;
    }

    private MailTemplate copyOf(MailTemplate source) {
        MailTemplate copy = new MailTemplate();
        copy.name = source.name + " (Kochdienst)";
        copy.bodyHtml = source.bodyHtml;
        copy.kind = MailTemplate.KIND_COOKING;
        copy.createdAt = Instant.now();
        copy.updatedAt = copy.createdAt;
        copy.persist();
        return copy;
    }

    /** Aktiv nur, wenn das Konto existiert und freigeschaltet ist — sonst haette der Job nie gesendet. */
    private boolean isSendable(CookingReminderSettings settings) {
        if (!ObjectId.isValid(settings.senderAccountId)) {
            return false;
        }
        MailAccount account = MailAccount.findById(new ObjectId(settings.senderAccountId));
        return account != null && account.enabled;
    }
}
