package at.kigruapp.scheduler;

import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import at.kigruapp.entity.Semester;
import at.kigruapp.service.MailTemplateRenderer;
import at.kigruapp.service.RecipientResolverService;
import at.kigruapp.service.MailService;
import io.quarkus.logging.Log;
import io.quarkus.panache.common.Sort;
import io.quarkus.scheduler.Scheduler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns registration/unregistration of MailJob cron schedules with the Quarkus
 * programmatic Scheduler (D4), fixed to the Europe/Vienna timezone (G-006).
 */
@ApplicationScoped
public class MailJobScheduler {

    private static final String TIMEZONE = "Europe/Vienna";

    @Inject
    Scheduler scheduler;

    @Inject
    RecipientResolverService recipientResolverService;

    @Inject
    MailTemplateRenderer renderer;

    @Inject
    MailService mailService;

    /** In-memory guard against overlapping runs of the same job (G-004, single instance). */
    private final Set<ObjectId> runningJobIds = ConcurrentHashMap.newKeySet();

    /**
     * Test-only hook: marks a job id as currently running, so the overlap
     * guard can be exercised without real concurrency. Package-private —
     * called via the CDI proxy so it correctly mutates the actual bean
     * instance's state (unlike direct field access on a proxy).
     */
    void markRunningForTest(ObjectId jobId) {
        runningJobIds.add(jobId);
    }

    /** Registers (or re-registers) a job's cron schedule. Idempotent — unschedules any existing registration first. */
    public void schedule(MailJob job) {
        if (job.isCooking()) {
            // Kochdienst-Jobs haben keinen Cron; sie laufen im CookingReminderScheduler.
            return;
        }
        String jobId = job.id.toHexString();
        if (scheduler.getScheduledJob(jobId) != null) {
            scheduler.unscheduleJob(jobId);
        }
        scheduler.newJob(jobId)
                .setCron(job.cron)
                .setTimeZone(TIMEZONE)
                .setTask(ctx -> fire(job.id))
                .schedule();
    }

    public void unschedule(ObjectId jobId) {
        scheduler.unscheduleJob(jobId.toHexString());
    }

    /** Invoked by the scheduler on each fire. */
    void fire(ObjectId jobId) {
        MailJob job = MailJob.findById(jobId);
        if (job == null) {
            return;
        }
        Log.infof("MailJob '%s' (%s) fired", job.name, jobId.toHexString());
        MailTemplate template = MailTemplate.findById(job.templateId);
        if (template == null) {
            Log.warnf("MailJob '%s' (%s) deactivated: template %s missing", job.name, jobId.toHexString(), job.templateId);
            job.lastRunAt = Instant.now();
            job.lastRunStatus = "FAILED";
            job.lastRunError = "template missing";
            job.active = false;
            job.update();
            unschedule(job.id);
            return;
        }
        runJob(job, template);
    }

    /**
     * Per-fire orchestration happy path: resolve recipients, render, send to
     * each, record a SUCCESS outcome.
     */
    void runJob(MailJob job, MailTemplate template) {
        if (!runningJobIds.add(job.id)) {
            job.lastRunAt = Instant.now();
            job.lastRunStatus = "SKIPPED_OVERLAP";
            job.lastRunError = null;
            job.update();
            return;
        }
        try {
            MailAccount account = null;
            try {
                account = MailAccount.findById(new ObjectId(job.senderAccountId));
            } catch (IllegalArgumentException ignored) {
                // malformed id -> treated as missing below
            }
            if (account == null || !account.enabled) {
                Log.warnf("MailJob '%s' (%s): sender account missing or disabled", job.name, job.id.toHexString());
                job.lastRunAt = Instant.now();
                job.lastRunStatus = "FAILED";
                job.lastRunError = "sender account missing or disabled";
                job.update();
                return;
            }

            ObjectId semesterId = resolveCurrentSemesterId();
            List<RecipientResolverService.ResolvedRecipient> recipients =
                    recipientResolverService.resolve(job, semesterId);

            if (recipients.isEmpty()) {
                Log.warnf("MailJob '%s' (%s): no recipients resolved, nothing sent", job.name, job.id.toHexString());
                job.lastRunAt = Instant.now();
                job.lastRunStatus = "NO_RECIPIENTS";
                job.lastRunError = null;
                job.update();
                return;
            }

            Log.infof("MailJob '%s' (%s): sending to %d recipient(s)", job.name, job.id.toHexString(), recipients.size());
            int successCount = 0;
            int failureCount = 0;
            String lastError = null;
            for (RecipientResolverService.ResolvedRecipient recipient : recipients) {
                try {
                    String renderedHtml = renderer.render(template.bodyHtml, recipient.properties());
                    mailService.sendHtml(account, recipient.email(), job.subject, renderedHtml);
                    successCount++;
                    Log.infof("MailJob '%s': sent to %s", job.name, recipient.email());
                } catch (Exception e) {
                    failureCount++;
                    lastError = e.getMessage();
                    Log.errorf(e, "MailJob '%s': send to %s failed: %s", job.name, recipient.email(), e.getMessage());
                }
            }

            job.lastRunAt = Instant.now();
            if (failureCount == 0) {
                job.lastRunStatus = "SUCCESS";
                job.lastRunError = null;
            } else if (successCount > 0) {
                job.lastRunStatus = "PARTIAL";
                job.lastRunError = failureCount + " of " + recipients.size() + " failed; last error: " + lastError;
            } else {
                job.lastRunStatus = "FAILED";
                job.lastRunError = failureCount + " of " + recipients.size() + " failed; last error: " + lastError;
            }
            Log.infof("MailJob '%s' (%s) finished: status=%s, sent=%d, failed=%d",
                    job.name, job.id.toHexString(), job.lastRunStatus, successCount, failureCount);
            job.update();
        } finally {
            runningJobIds.remove(job.id);
        }
    }

    private ObjectId resolveCurrentSemesterId() {
        List<Semester> latest = Semester.listAll(Sort.descending("createdAt"));
        return latest.isEmpty() ? null : latest.get(0).id;
    }
}
