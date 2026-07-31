package at.kigruapp.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Split- und Merge-Regeln fuer Schliesszeitraeume einer einzelnen Definition.
 *
 * <p>Bewusst ohne Datenbankzugriff, damit die gesamte Regellogik per Unit-Test
 * abgedeckt werden kann. Der {@code selectable}-Predicate entscheidet, ob ein Tag
 * ein Betriebstag ist; damit deckt dieselbe Logik Wochenenden und Feiertage ab.
 */
public final class ClosurePeriodNormalizer {

    private ClosurePeriodNormalizer() {
    }

    public record DateSpan(LocalDate from, LocalDate to) {
    }

    public static boolean isWeekday(LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    public static List<DateSpan> assign(List<DateSpan> existing,
                                        Collection<LocalDate> days,
                                        Predicate<LocalDate> selectable) {
        List<DateSpan> all = new ArrayList<>(existing);
        all.addAll(toSpans(days));
        return coalesce(all, selectable);
    }

    /**
     * Nimmt Tage aus bestehenden Zeitraeumen heraus. Es wird bewusst nicht neu
     * verschmolzen: Entfernen kann nur trennen, nie verbinden.
     */
    public static List<DateSpan> remove(List<DateSpan> existing,
                                        Collection<LocalDate> days,
                                        Predicate<LocalDate> selectable) {
        Set<LocalDate> cut = new HashSet<>(days);
        List<DateSpan> out = new ArrayList<>();

        for (DateSpan span : existing) {
            LocalDate start = null;
            for (LocalDate day = span.from(); !day.isAfter(span.to()); day = day.plusDays(1)) {
                if (cut.contains(day)) {
                    if (start != null) {
                        addTrimmed(out, start, day.minusDays(1), selectable);
                        start = null;
                    }
                } else if (start == null) {
                    start = day;
                }
            }
            if (start != null) {
                addTrimmed(out, start, span.to(), selectable);
            }
        }

        out.sort(Comparator.comparing(DateSpan::from));
        return out;
    }

    /**
     * Beschneidet einen Rest auf Betriebstage und verwirft ihn, wenn danach nichts
     * uebrig bleibt. Haelt die Invariante, dass ein Zeitraum immer auf einem
     * auswaehlbaren Tag beginnt und endet.
     */
    private static void addTrimmed(List<DateSpan> out, LocalDate from, LocalDate to,
                                   Predicate<LocalDate> selectable) {
        LocalDate first = from;
        LocalDate last = to;
        while (!first.isAfter(last) && !selectable.test(first)) {
            first = first.plusDays(1);
        }
        while (!last.isBefore(first) && !selectable.test(last)) {
            last = last.minusDays(1);
        }
        if (!first.isAfter(last)) {
            out.add(new DateSpan(first, last));
        }
    }

    /** Verdichtet eine Tagesmenge zu zusammenhaengenden Spans. */
    private static List<DateSpan> toSpans(Collection<LocalDate> days) {
        List<LocalDate> sorted = days.stream().distinct().sorted().toList();
        List<DateSpan> spans = new ArrayList<>();
        for (LocalDate day : sorted) {
            if (!spans.isEmpty()) {
                DateSpan last = spans.get(spans.size() - 1);
                if (last.to().plusDays(1).equals(day)) {
                    spans.set(spans.size() - 1, new DateSpan(last.from(), day));
                    continue;
                }
            }
            spans.add(new DateSpan(day, day));
        }
        return spans;
    }

    private static List<DateSpan> coalesce(List<DateSpan> spans, Predicate<LocalDate> selectable) {
        List<DateSpan> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparing(DateSpan::from).thenComparing(DateSpan::to));

        List<DateSpan> out = new ArrayList<>();
        for (DateSpan span : sorted) {
            if (out.isEmpty()) {
                out.add(span);
                continue;
            }
            DateSpan last = out.get(out.size() - 1);
            if (bridgeable(last, span, selectable)) {
                LocalDate end = last.to().isAfter(span.to()) ? last.to() : span.to();
                out.set(out.size() - 1, new DateSpan(last.from(), end));
            } else {
                out.add(span);
            }
        }
        return out;
    }

    /**
     * Zwei Spans gelten als verbindbar, wenn sie ueberlappen, direkt aneinander
     * grenzen, oder die Luecke dazwischen ausschliesslich aus Nicht-Betriebstagen
     * besteht. Ohne die letzte Regel wuerden zusammenhaengende Ferien in
     * Wochenpakete zerfallen.
     */
    private static boolean bridgeable(DateSpan before, DateSpan after, Predicate<LocalDate> selectable) {
        if (!after.from().isAfter(before.to())) {
            return true;
        }
        for (LocalDate day = before.to().plusDays(1); day.isBefore(after.from()); day = day.plusDays(1)) {
            if (selectable.test(day)) {
                return false;
            }
        }
        return true;
    }
}
