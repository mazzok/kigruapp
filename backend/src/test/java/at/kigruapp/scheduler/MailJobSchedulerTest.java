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
}
