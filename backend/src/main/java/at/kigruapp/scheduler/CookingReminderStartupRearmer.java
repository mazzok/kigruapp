package at.kigruapp.scheduler;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Registriert den täglichen Erinnerungslauf bei jedem Start neu —
 * programmatische Schedules überleben einen Neustart nicht. Analog
 * {@link MailJobStartupRearmer}.
 */
@ApplicationScoped
public class CookingReminderStartupRearmer {

    @Inject
    CookingReminderScheduler cookingReminderScheduler;

    void onStart(@Observes StartupEvent ev) {
        cookingReminderScheduler.reschedule();
    }
}
