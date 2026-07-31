package at.kigruapp.service;

import at.kigruapp.entity.ClosurePeriod;
import at.kigruapp.entity.FieldDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import org.bson.Document;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Einzige Quelle fuer die Frage "hat der Kindergarten an diesem Tag geschlossen?".
 *
 * <p>Feiertage zaehlen wie erfasste Schliesszeitraeume, obwohl sie nicht
 * persistiert sind — sonst waere ein Kochdienst am 25. Dezember erlaubt, ein
 * identischer Dienst am selbst eingetragenen Schliesstag daneben aber nicht.
 */
@ApplicationScoped
public class ClosureGuard {

    /** Feldname der Kochdienst-Definition in {@code field_definitions}. */
    private static final String COOKING_DUTY = "cookingDuty";

    @Inject
    HolidayService holidayService;

    public boolean isClosed(LocalDate day) {
        if (day == null) {
            return false;
        }
        if (holidayService.datesBetween(day, day).contains(day)) {
            return true;
        }
        return !ClosurePeriod.findOverlapping(day, day).isEmpty();
    }

    /**
     * Lehnt Kochdienste an geschlossenen Tagen ab. Andere Feldtypen passieren
     * unveraendert; der generische Endpoint bleibt dadurch generisch.
     */
    public void rejectIfClosed(FieldDefinition definition, Object value) {
        if (definition == null || !COOKING_DUTY.equals(definition.fieldName)) {
            return;
        }
        LocalDate date = extractDate(value);
        if (date != null && isClosed(date)) {
            throw new ClientErrorException(
                "Am " + date + " hat der Kindergarten geschlossen. Es kann kein Kochdienst eingetragen werden.",
                Response.Status.CONFLICT);
        }
    }

    /** Der Wert kommt je nach Aufrufweg als BSON-Document oder als Map an. */
    private LocalDate extractDate(Object value) {
        Object raw = null;
        if (value instanceof Document document) {
            raw = document.get("date");
        } else if (value instanceof Map<?, ?> map) {
            raw = map.get("date");
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
