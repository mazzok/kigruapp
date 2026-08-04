package at.kigruapp.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;

import static at.kigruapp.service.ClosurePeriodNormalizer.DateSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClosurePeriodNormalizerAssignTest {

    // Montag bis Freitag sind Betriebstage, Wochenenden nicht.
    private static final Predicate<LocalDate> WEEKDAYS = ClosurePeriodNormalizer::isWeekday;

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static DateSpan span(String from, String to) {
        return new DateSpan(d(from), d(to));
    }

    @Test
    void singleDayBecomesOneSpan() {
        // 2026-09-07 ist ein Montag.
        List<DateSpan> result =
            ClosurePeriodNormalizer.assign(List.of(), List.of(d("2026-09-07")), WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-07")), result);
    }

    @Test
    void consecutiveDaysBecomeOneSpan() {
        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(),
            List.of(d("2026-09-07"), d("2026-09-08"), d("2026-09-09")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-09")), result);
    }

    @Test
    void weekendGapIsBridged() {
        // Mo 07. bis Fr 11., dann Mo 14. bis Fr 18. — dazwischen nur Sa/So.
        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(span("2026-09-07", "2026-09-11")),
            List.of(d("2026-09-14"), d("2026-09-15"), d("2026-09-16"),
                    d("2026-09-17"), d("2026-09-18")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-18")), result);
    }

    @Test
    void workingDayGapIsNotBridged() {
        // Zwischen Di 08. und Do 10. liegt der Betriebstag Mi 09.
        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(span("2026-09-07", "2026-09-08")),
            List.of(d("2026-09-10")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-08"),
                             span("2026-09-10", "2026-09-10")), result);
    }

    @Test
    void filledGapMergesBothNeighbours() {
        // Lücke Mi 09. schließt zwei bestehende Zeiträume zusammen.
        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(span("2026-09-07", "2026-09-08"), span("2026-09-10", "2026-09-11")),
            List.of(d("2026-09-09")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-11")), result);
    }

    @Test
    void overlappingAssignmentIsAbsorbed() {
        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(span("2026-09-07", "2026-09-11")),
            List.of(d("2026-09-09"), d("2026-09-10")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-11")), result);
    }

    @Test
    void holidayGapIsBridgedViaPredicate() {
        // 2026-10-26 ist ein Montag und in Österreich Nationalfeiertag.
        Predicate<LocalDate> selectable =
            day -> ClosurePeriodNormalizer.isWeekday(day) && !day.equals(d("2026-10-26"));

        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(span("2026-10-23", "2026-10-23")),
            List.of(d("2026-10-27")),
            selectable);

        assertEquals(List.of(span("2026-10-23", "2026-10-27")), result);
    }

    @Test
    void resultIsSortedByFrom() {
        List<DateSpan> result = ClosurePeriodNormalizer.assign(
            List.of(span("2026-11-02", "2026-11-03")),
            List.of(d("2026-09-07")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-07"),
                             span("2026-11-02", "2026-11-03")), result);
    }
}
