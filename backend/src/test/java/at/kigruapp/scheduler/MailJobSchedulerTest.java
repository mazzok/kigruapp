package at.kigruapp.scheduler;

import at.kigruapp.entity.MailJob;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MailJobSchedulerTest {

    @Inject
    MailJobScheduler mailJobScheduler;

    @Inject
    Scheduler scheduler;

    private MailJob newJob(String cron) {
        MailJob job = new MailJob();
        job.id = new org.bson.types.ObjectId();
        job.cron = cron;
        return job;
    }

    @Test
    void scheduleRegistersJobWithScheduler() {
        MailJob job = newJob("0 0 8 * * ?");

        mailJobScheduler.schedule(job);

        assertNotNull(scheduler.getScheduledJob(job.id.toHexString()));
    }

    @Test
    void unscheduleRemovesJob() {
        MailJob job = newJob("0 0 8 * * ?");
        mailJobScheduler.schedule(job);

        mailJobScheduler.unschedule(job.id);

        assertNull(scheduler.getScheduledJob(job.id.toHexString()));
    }

    @Test
    void scheduleTwiceIsIdempotent() {
        MailJob job = newJob("0 0 8 * * ?");

        mailJobScheduler.schedule(job);
        mailJobScheduler.schedule(job);

        long matching = scheduler.getScheduledJobs().stream()
                .filter(t -> t.getId().equals(job.id.toHexString()))
                .count();
        assertEquals(1, matching);
    }

    /**
     * COOKING jobs never set cron (only sendTime), so scheduling one via the
     * GENERAL job path would NPE on the null cron. This reproduces the
     * startup-crash scenario that MailJobStartupRearmer would otherwise hit
     * for every active COOKING job.
     */
    @Test
    void scheduleSkipsCookingJobsInsteadOfCrashingOnNullCron() {
        MailJob job = new MailJob();
        job.id = new org.bson.types.ObjectId();
        job.kind = MailJob.KIND_COOKING_REMINDER;
        job.cron = null;

        assertDoesNotThrow(() -> mailJobScheduler.schedule(job));
        assertNull(scheduler.getScheduledJob(job.id.toHexString()));
    }

    /**
     * Only COOKING_REMINDER jobs are driven by CookingReminderScheduler and
     * therefore skipped here. COOKING_OVERVIEW jobs have a real cron and must
     * schedule exactly like GENERAL jobs.
     */
    @Test
    void scheduleRegistersCookingOverviewJobsNormally() {
        MailJob job = newJob("0 0 8 * * ?");
        job.kind = MailJob.KIND_COOKING_OVERVIEW;

        mailJobScheduler.schedule(job);

        assertNotNull(scheduler.getScheduledJob(job.id.toHexString()));
    }
}
