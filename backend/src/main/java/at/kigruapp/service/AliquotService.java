package at.kigruapp.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

@ApplicationScoped
public class AliquotService {

    /** Presence weight in [0,1] for a child in the given month, per the aliquot mode. */
    public BigDecimal monthFraction(AliquotMode mode, String entryDate, String exitDate, int year, int month) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());

        LocalDate entry = parse(entryDate);
        LocalDate exit = parse(exitDate);

        LocalDate effStart = (entry != null && entry.isAfter(monthStart)) ? entry : monthStart;
        LocalDate effEnd = (exit != null && exit.isBefore(monthEnd)) ? exit : monthEnd;

        if (effStart.isAfter(effEnd)) {
            return BigDecimal.ZERO; // not present at all this month
        }
        if (mode == AliquotMode.PER_DAY) {
            long presentDays = ChronoUnit.DAYS.between(effStart, effEnd) + 1;
            long daysInMonth = monthEnd.getDayOfMonth();
            return BigDecimal.valueOf(presentDays)
                    .divide(BigDecimal.valueOf(daysInMonth), 6, RoundingMode.HALF_UP);
        }
        return BigDecimal.ONE; // NONE / WHOLE_MONTH: present any day -> full month
    }

    private LocalDate parse(String date) {
        if (date == null || date.isBlank() || date.length() < 10) {
            return null;
        }
        return LocalDate.parse(date.substring(0, 10));
    }
}
