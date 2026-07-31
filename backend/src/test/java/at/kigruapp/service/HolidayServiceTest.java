package at.kigruapp.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class HolidayServiceTest {

    @Inject
    HolidayService holidayService;

    @Test
    void findsAustrianNationalHoliday() {
        List<HolidayService.HolidayDto> holidays =
            holidayService.between(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

        assertTrue(holidays.stream().anyMatch(h -> h.date().equals(LocalDate.parse("2026-10-26"))),
            "Nationalfeiertag am 26.10. erwartet, erhalten: " + holidays);
    }

    @Test
    void resultIsLimitedToTheRequestedWindow() {
        List<HolidayService.HolidayDto> holidays =
            holidayService.between(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

        assertTrue(holidays.stream().allMatch(h ->
                !h.date().isBefore(LocalDate.parse("2026-10-01"))
                    && !h.date().isAfter(LocalDate.parse("2026-10-31"))),
            "Alle Feiertage muessen im Fenster liegen, erhalten: " + holidays);
    }

    @Test
    void holidaysCarryAName() {
        List<HolidayService.HolidayDto> holidays =
            holidayService.between(LocalDate.parse("2026-12-24"), LocalDate.parse("2026-12-26"));

        assertFalse(holidays.isEmpty());
        assertTrue(holidays.stream().allMatch(h -> h.name() != null && !h.name().isBlank()));
    }

    @Test
    void resultIsSortedByDate() {
        List<HolidayService.HolidayDto> holidays =
            holidayService.between(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));

        for (int i = 1; i < holidays.size(); i++) {
            assertFalse(holidays.get(i).date().isBefore(holidays.get(i - 1).date()),
                "Liste muss nach Datum sortiert sein");
        }
    }

    @Test
    void datesBetweenReturnsPlainDates() {
        Set<LocalDate> dates =
            holidayService.datesBetween(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

        assertTrue(dates.contains(LocalDate.parse("2026-10-26")));
    }

    @Test
    void emptyWindowYieldsNothing() {
        List<HolidayService.HolidayDto> holidays =
            holidayService.between(LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-11"));

        assertTrue(holidays.isEmpty(), "In dieser Woche liegt kein Feiertag, erhalten: " + holidays);
    }
}
