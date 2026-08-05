package at.kigruapp.scheduler;

import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailEncryption;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class CookingReminderSchedulerTest {

    @Inject
    CookingReminderScheduler reminderScheduler;

    @Inject
    Scheduler scheduler;

    @BeforeEach
    void setup() {
        MailJob.deleteAll();
        MailTemplate.deleteAll();
        MailAccount.deleteAll();
        for (MailJob job : MailJob.<MailJob>list("kind", MailJob.KIND_COOKING_REMINDER)) {
            String quartzId = CookingReminderScheduler.jobId(job.id);
            if (scheduler.getScheduledJob(quartzId) != null) {
                scheduler.unscheduleJob(quartzId);
            }
        }
    }

    private MailAccount persistAccount() {
        MailAccount account = new MailAccount();
        account.name = "Kiga";
        account.host = "localhost";
        account.port = 2525;
        account.encryption = MailEncryption.NONE;
        account.fromAddress = "kiga@example.org";
        account.fromName = "Kindergruppe";
        account.enabled = true;
        account.persist();
        return account;
    }

    private MailTemplate persistTemplate() {
        MailTemplate template = new MailTemplate();
        template.name = "Erinnerung";
        template.bodyHtml = "<p>Kochdienst</p>";
        template.kind = MailTemplate.KIND_COOKING_REMINDER;
        template.createdAt = Instant.now();
        template.persist();
        return template;
    }

    private MailJob persistCookingJob(MailAccount account, MailTemplate template, String sendTime, boolean active) {
        MailJob job = new MailJob();
        job.kind = MailJob.KIND_COOKING_REMINDER;
        job.name = "Kochdienst-Erinnerung";
        job.templateId = template.id;
        job.subject = "Dein Kochdienst";
        job.senderAccountId = account.id.toHexString();
        job.sendTime = sendTime;
        job.active = active;
        job.createdAt = Instant.now();
        job.updatedAt = job.createdAt;
        job.persist();
        return job;
    }

    @Test
    void toCronBildetUhrzeitAb() {
        assertEquals("0 30 18 * * ?", CookingReminderScheduler.toCron("18:30"));
        assertEquals("0 0 7 * * ?", CookingReminderScheduler.toCron("07:00"));
        assertEquals("0 0 7 * * ?", CookingReminderScheduler.toCron(null));
        assertEquals("0 0 7 * * ?", CookingReminderScheduler.toCron("kaputt"));
    }

    @Test
    void rescheduleRegistriertJob() {
        MailAccount account = persistAccount();
        MailTemplate template = persistTemplate();
        MailJob job = persistCookingJob(account, template, "18:30", true);

        reminderScheduler.reschedule();

        assertNotNull(scheduler.getScheduledJob(CookingReminderScheduler.jobId(job.id)));
    }

    @Test
    void rescheduleIstIdempotent() {
        MailAccount account = persistAccount();
        MailTemplate template = persistTemplate();
        MailJob job = persistCookingJob(account, template, "07:00", true);

        reminderScheduler.reschedule();
        reminderScheduler.reschedule();

        assertNotNull(scheduler.getScheduledJob(CookingReminderScheduler.jobId(job.id)));
    }

    @Test
    void rescheduleOhneAktiveJobsRegistriertNichts() {
        reminderScheduler.reschedule();

        assertEquals(0, MailJob.count("kind", MailJob.KIND_COOKING_REMINDER));
    }

    @Test
    void rescheduleUebergehtInaktiveJobs() {
        MailAccount account = persistAccount();
        MailTemplate template = persistTemplate();
        MailJob job = persistCookingJob(account, template, "07:00", false);

        reminderScheduler.reschedule();

        assertNull(scheduler.getScheduledJob(CookingReminderScheduler.jobId(job.id)));
    }
}
