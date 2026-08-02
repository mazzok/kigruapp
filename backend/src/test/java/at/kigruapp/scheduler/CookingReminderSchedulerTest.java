package at.kigruapp.scheduler;

import at.kigruapp.entity.CookingReminderSettings;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class CookingReminderSchedulerTest {

    @Inject
    CookingReminderScheduler reminderScheduler;

    @Inject
    Scheduler scheduler;

    @BeforeEach
    void setup() {
        CookingReminderSettings.deleteAll();
        if (scheduler.getScheduledJob(CookingReminderScheduler.JOB_ID) != null) {
            scheduler.unscheduleJob(CookingReminderScheduler.JOB_ID);
        }
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
        CookingReminderSettings settings = new CookingReminderSettings();
        settings.sendTime = "18:30";
        settings.updatedAt = Instant.now();
        settings.persist();

        reminderScheduler.reschedule();

        assertNotNull(scheduler.getScheduledJob(CookingReminderScheduler.JOB_ID));
    }

    @Test
    void rescheduleIstIdempotent() {
        CookingReminderSettings settings = new CookingReminderSettings();
        settings.sendTime = "07:00";
        settings.updatedAt = Instant.now();
        settings.persist();

        reminderScheduler.reschedule();
        reminderScheduler.reschedule();

        assertNotNull(scheduler.getScheduledJob(CookingReminderScheduler.JOB_ID));
    }

    @Test
    void rescheduleOhneEinstellungenNutztStandardzeit() {
        reminderScheduler.reschedule();

        assertNotNull(scheduler.getScheduledJob(CookingReminderScheduler.JOB_ID));
    }
}
