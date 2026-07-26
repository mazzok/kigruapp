package at.kigruapp.scheduler;

import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.RecipientMode;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class MailJobStartupRearmerTest {

    @Inject
    MailJobStartupRearmer rearmer;

    @Inject
    Scheduler scheduler;

    @BeforeEach
    void cleanup() {
        MailJob.deleteAll();
    }

    private MailJob persistJob(boolean active) {
        MailJob job = new MailJob();
        job.cron = "0 0 8 * * ?";
        job.recipientMode = RecipientMode.ALL_PARENTS;
        job.active = active;
        job.persist();
        return job;
    }

    @Test
    void rearmAllSchedulesOnlyActiveJobs() {
        MailJob activeJob = persistJob(true);
        MailJob inactiveJob = persistJob(false);

        rearmer.rearmAll();

        assertNotNull(scheduler.getScheduledJob(activeJob.id.toHexString()));
        assertNull(scheduler.getScheduledJob(inactiveJob.id.toHexString()));
    }
}
