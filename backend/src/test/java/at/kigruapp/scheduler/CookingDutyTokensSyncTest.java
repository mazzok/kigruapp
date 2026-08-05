package at.kigruapp.scheduler;

import at.kigruapp.service.CookingDutyTokens;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Haelt die angebotenen Chips und die tatsaechlich befuellten Properties
 * zusammen: ein neues duty-Property ohne Chip (oder umgekehrt) faellt hier auf.
 */
@QuarkusTest
class CookingDutyTokensSyncTest {

    @Inject
    CookingReminderScheduler scheduler;

    @Test
    void everyDutyPropertyHasAToken() {
        CookingReminderScheduler.DueDuty duty = new CookingReminderScheduler.DueDuty(
                new ObjectId(), new ObjectId(), "2026-08-10", "2026-08-08", "Suppe", 2, List.of());

        Map<String, String> properties = scheduler.buildDutyProperties(duty);

        assertEquals(new HashSet<>(CookingDutyTokens.fieldNames()), properties.keySet());
    }
}
