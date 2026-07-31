package at.kigruapp.service;

import de.focus_shift.jollyday.core.Holiday;
import de.focus_shift.jollyday.core.HolidayManager;
import de.focus_shift.jollyday.core.ManagerParameters;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gesetzliche Feiertage fuer den Standort, an dem die App betrieben wird.
 *
 * <p>Die Region wird einmalig beim Deploy gesetzt. Ueber Intl oder die
 * Server-Locale sind Feiertage nicht ermittelbar; es gibt dafuer weder eine
 * Browser-API noch einen Standard.
 */
@ApplicationScoped
public class HolidayService {

    private static final Logger LOG = Logger.getLogger(HolidayService.class);

    public record HolidayDto(LocalDate date, String name) {}

    // Optional statt String: SmallRye wertet einen leeren Property-Wert als null und
    // laesst die Anwendung sonst beim Start scheitern. Leer heisst hier "nicht gesetzt".
    @ConfigProperty(name = "kigruapp.holidays.country")
    Optional<String> country;

    @ConfigProperty(name = "kigruapp.holidays.subdivision")
    Optional<String> subdivision;

    public List<HolidayDto> between(LocalDate from, LocalDate to) {
        String countryCode = country.map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        if (countryCode == null || from == null || to == null || to.isBefore(from)) {
            return List.of();
        }
        String subdivisionCode = subdivision.map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        try {
            HolidayManager manager =
                HolidayManager.getInstance(ManagerParameters.create(countryCode.toLowerCase()));

            Set<Holiday> holidays = subdivisionCode == null
                ? manager.getHolidays(from, to)
                : manager.getHolidays(from, to, subdivisionCode.toLowerCase());

            return holidays.stream()
                .filter(h -> !h.getDate().isBefore(from) && !h.getDate().isAfter(to))
                .map(h -> new HolidayDto(h.getDate(), h.getDescription()))
                .sorted(Comparator.comparing(HolidayDto::date))
                .collect(Collectors.toList());
        } catch (RuntimeException e) {
            // Unbekanntes Land oder fehlende Regionsdaten duerfen die App nicht lahmlegen.
            LOG.warnf(e, "Feiertage fuer '%s'/'%s' konnten nicht ermittelt werden", country, subdivision);
            return List.of();
        }
    }

    public Set<LocalDate> datesBetween(LocalDate from, LocalDate to) {
        return between(from, to).stream()
            .map(HolidayDto::date)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
