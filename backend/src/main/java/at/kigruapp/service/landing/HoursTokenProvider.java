package at.kigruapp.service.landing;

import at.kigruapp.entity.Person;
import at.kigruapp.service.FamilyHoursTotalsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code {{stunden.*}}} — Soll, Ist und Bilanz der Familie im jüngsten Semester. */
@ApplicationScoped
public class HoursTokenProvider implements LandingTokenProvider {

    private static final String GELEISTET = "{{stunden.geleistet}}";
    private static final String SOLL = "{{stunden.soll}}";
    private static final String BILANZ = "{{stunden.bilanz}}";

    @Inject
    FamilyHoursTotalsService familyHoursTotalsService;

    @Override
    public List<LandingPlaceholder> placeholders() {
        return List.of(
                new LandingPlaceholder(GELEISTET, "Geleistete Stunden", "stunden"),
                new LandingPlaceholder(SOLL, "Soll-Stunden", "stunden"),
                new LandingPlaceholder(BILANZ, "Stunden-Bilanz", "stunden"));
    }

    @Override
    public Map<String, String> values(Person person) {
        Map<String, String> values = new LinkedHashMap<>();
        ObjectId semesterId = familyHoursTotalsService.latestSemesterId();
        if (semesterId == null) {
            values.put(GELEISTET, "");
            values.put(SOLL, "");
            values.put(BILANZ, "");
            return values;
        }
        FamilyHoursTotalsService.Totals totals = familyHoursTotalsService.totalsFor(person, semesterId);
        values.put(GELEISTET, formatHours(totals.istMinutes()));
        values.put(SOLL, formatHours(totals.sollMinutes()));
        values.put(BILANZ, formatHours(totals.istMinutes() - totals.sollMinutes()));
        return values;
    }

    /** Minuten als Stunden mit einer Nachkommastelle und deutschem Dezimalkomma. */
    private String formatHours(int minutes) {
        return String.format(Locale.GERMAN, "%.1f", minutes / 60.0);
    }
}
