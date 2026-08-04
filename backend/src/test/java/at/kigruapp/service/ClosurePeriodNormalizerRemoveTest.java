package at.kigruapp.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;

import static at.kigruapp.service.ClosurePeriodNormalizer.DateSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClosurePeriodNormalizerRemoveTest {

    private static final Predicate<LocalDate> WEEKDAYS = ClosurePeriodNormalizer::isWeekday;

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static DateSpan span(String from, String to) {
        return new DateSpan(d(from), d(to));
    }

    @Test
    void removingFromTheMiddleSplitsTheSpan() {
        // Mo 07. bis Fr 11., Mi 09. wird herausgenommen.
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-11")),
            List.of(d("2026-09-09")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-08"),
                             span("2026-09-10", "2026-09-11")), result);
    }

    @Test
    void removingAtTheStartMovesFrom() {
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-11")),
            List.of(d("2026-09-07"), d("2026-09-08")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-09", "2026-09-11")), result);
    }

    @Test
    void removingAtTheEndMovesTo() {
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-11")),
            List.of(d("2026-09-10"), d("2026-09-11")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-09")), result);
    }

    @Test
    void removingEverythingDropsTheSpan() {
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-08")),
            List.of(d("2026-09-07"), d("2026-09-08")),
            WEEKDAYS);

        assertTrue(result.isEmpty());
    }

    @Test
    void weekendOnlyRemnantIsDropped() {
        // Mo 07. bis Mo 14.; alle Werktage der ersten Woche und Mo 14. gehen weg.
        // Uebrig blieben nur Sa 12. und So 13. — kein sinnvoller Zeitraum.
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-14")),
            List.of(d("2026-09-07"), d("2026-09-08"), d("2026-09-09"),
                    d("2026-09-10"), d("2026-09-11"), d("2026-09-14")),
            WEEKDAYS);

        assertTrue(result.isEmpty());
    }

    @Test
    void spanEndsAreTrimmedToSelectableDays() {
        // Mo 07. bis Mo 14.; nur Mo 14. geht weg. Der Rest darf nicht auf So 13. enden.
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-14")),
            List.of(d("2026-09-14")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-11")), result);
    }

    @Test
    void removingUnrelatedDaysChangesNothing() {
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-11")),
            List.of(d("2026-11-02")),
            WEEKDAYS);

        assertEquals(List.of(span("2026-09-07", "2026-09-11")), result);
    }

    @Test
    void splitDoesNotRemergeAcrossTheRemovedDay() {
        // Gegenprobe zu Task 1: nach dem Entfernen darf nicht wieder verschmolzen werden.
        List<DateSpan> result = ClosurePeriodNormalizer.remove(
            List.of(span("2026-09-07", "2026-09-18")),
            List.of(d("2026-09-09")),
            WEEKDAYS);

        assertEquals(2, result.size());
    }
}
