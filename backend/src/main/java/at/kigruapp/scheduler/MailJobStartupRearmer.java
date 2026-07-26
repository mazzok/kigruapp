package at.kigruapp.scheduler;

import at.kigruapp.entity.MailJob;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * On boot, re-arms every currently-active MailJob with the scheduler (R10).
 * Mirrors the existing @Startup migration-bean idiom, but runs on every
 * boot (not guarded by the migrations collection) since it must re-register
 * schedules after every restart, not just the first one.
 */
@ApplicationScoped
public class MailJobStartupRearmer {

    private static final Logger LOG = Logger.getLogger(MailJobStartupRearmer.class);

    @Inject
    MailJobScheduler mailJobScheduler;

    void onStart(@Observes StartupEvent ev) {
        rearmAll();
    }

    void rearmAll() {
        List<MailJob> activeJobs = MailJob.list("active", true);
        for (MailJob job : activeJobs) {
            mailJobScheduler.schedule(job);
        }
        LOG.infof("Re-armed %d active mail job(s) on startup", activeJobs.size());
    }
}
