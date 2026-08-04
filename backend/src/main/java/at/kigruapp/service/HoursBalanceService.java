package at.kigruapp.service;

import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class HoursBalanceService {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    AliquotService aliquotService;

    public static class ChildPlacement {
        public String childId;
        public String entryDate;
        public String exitDate;
        public ObjectId groupInstanceId;
    }

    /** Anteil eines Kindes an einem Monat: Minuten samt Anwesenheits- und Rabattanteil. */
    public record ChildMonthShare(String childId, int minutes, int fractionPercent, int discountPercent) {}

    /** Distinct children (persons in the family) with a group placement in the semester. */
    public int countPlacedChildren(ObjectId familyId, ObjectId semesterId) {
        List<at.kigruapp.entity.Person> familyPersons =
                at.kigruapp.entity.Person.findByFamilyId(familyId);
        if (familyPersons.isEmpty()) {
            return 0;
        }
        Set<ObjectId> personIds = new HashSet<>();
        for (at.kigruapp.entity.Person p : familyPersons) {
            personIds.add(p.id);
        }
        Document filter = new Document("semesterId", semesterId)
                .append("section", "group")
                .append("personId", new Document("$in", new java.util.ArrayList<>(personIds)));
        Set<ObjectId> placed = new HashSet<>();
        for (Document d : mongoClient.getDatabase(databaseName)
                .getCollection("semester_assignments").find(filter)) {
            placed.add(d.getObjectId("personId"));
        }
        return placed.size();
    }

    /** Satz je Monat für die Gruppe des Kindes; 0, wenn für die Gruppe nichts hinterlegt ist. */
    public int baseRate(RequiredHours cfg, ObjectId groupInstanceId) {
        if (cfg == null) {
            return 0;
        }
        if (cfg.allGroups) {
            return cfg.defaultMinutesPerMonth;
        }
        if (groupInstanceId == null || cfg.groupRates == null) {
            return 0;
        }
        int best = 0;
        for (RequiredHours.GroupRate rate : cfg.groupRates) {
            if (groupInstanceId.equals(rate.groupInstanceId)) {
                best = Math.max(best, rate.minutesPerMonth);
            }
        }
        return best;
    }

    /** Rabatt der höchsten passenden Staffel für einen 1-basierten Rang; 0 wenn keine passt. */
    public int discountPercentForRank(RequiredHours cfg, int rank) {
        if (cfg == null || cfg.tiers == null) {
            return 0;
        }
        int bestFrom = 0;
        int percent = 0;
        for (RequiredHours.Tier t : cfg.tiers) {
            if (t.fromChild <= rank && t.fromChild >= bestFrom) {
                bestFrom = t.fromChild;
                percent = t.percent;
            }
        }
        return percent;
    }

    /** Distinct calendar months touched by [start, end] inclusive. */
    public int monthsInSemester(Semester semester) {
        if (semester == null || semester.start == null || semester.end == null) {
            return 0;
        }
        YearMonth start = YearMonth.from(semester.start.atZone(ZoneOffset.UTC));
        YearMonth end = YearMonth.from(semester.end.atZone(ZoneOffset.UTC));
        if (end.isBefore(start)) {
            return 0;
        }
        int months = 0;
        YearMonth cur = start;
        while (!cur.isAfter(end)) {
            months++;
            cur = cur.plusMonths(1);
        }
        return months;
    }

    /** Aufteilung je Monat ("YYYY-MM") auf die in diesem Monat anwesenden Kinder. */
    public java.util.Map<String, List<ChildMonthShare>> familySharesByMonth(
            RequiredHours cfg, AliquotMode mode, Semester semester, List<ChildPlacement> placements) {
        java.util.Map<String, List<ChildMonthShare>> out = new java.util.LinkedHashMap<>();
        if (semester == null || semester.start == null || semester.end == null) {
            return out;
        }
        YearMonth cur = YearMonth.from(semester.start.atZone(ZoneOffset.UTC));
        YearMonth last = YearMonth.from(semester.end.atZone(ZoneOffset.UTC));
        while (!cur.isAfter(last)) {
            String key = String.format("%04d-%02d", cur.getYear(), cur.getMonthValue());
            out.put(key, monthShares(cfg, mode, cur, placements));
            cur = cur.plusMonths(1);
        }
        return out;
    }

    public java.util.Map<String, Integer> familySollByMonth(RequiredHours cfg, AliquotMode mode,
            Semester semester, List<ChildPlacement> placements) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        familySharesByMonth(cfg, mode, semester, placements).forEach((month, shares) ->
                out.put(month, shares.stream().mapToInt(ChildMonthShare::minutes).sum()));
        return out;
    }

    public int familySollMinutes(RequiredHours cfg, AliquotMode mode, Semester semester,
                                 List<ChildPlacement> placements) {
        return familySollByMonth(cfg, mode, semester, placements).values().stream()
                .mapToInt(Integer::intValue).sum();
    }

    /**
     * Monatswert eines Monats, in dem alle Kinder voll anwesend sind — die Zahl, die als
     * "x h/Monat" angezeigt wird. 0, wenn es keinen solchen Monat gibt.
     */
    public int fullMonthMinutes(RequiredHours cfg, AliquotMode mode, Semester semester,
                                List<ChildPlacement> placements) {
        if (placements == null || placements.isEmpty()) {
            return 0;
        }
        for (List<ChildMonthShare> shares :
                familySharesByMonth(cfg, mode, semester, placements).values()) {
            if (shares.size() != placements.size()) {
                continue;
            }
            boolean allFull = shares.stream().allMatch(s -> s.fractionPercent() == 100);
            if (allFull) {
                return shares.stream().mapToInt(ChildMonthShare::minutes).sum();
            }
        }
        return 0;
    }

    /** Anteile eines einzelnen Monats: Basissatz × Anwesenheit, Rang nach cfg.order, dann Rabatt. */
    private List<ChildMonthShare> monthShares(RequiredHours cfg, AliquotMode mode, YearMonth ym,
                                              List<ChildPlacement> placements) {
        record Candidate(String childId, BigDecimal base, int fractionPercent) {}
        List<Candidate> present = new ArrayList<>();
        for (ChildPlacement p : placements) {
            BigDecimal fraction = aliquotService.monthFraction(
                    mode, p.entryDate, p.exitDate, ym.getYear(), ym.getMonthValue());
            if (fraction.signum() <= 0) {
                continue;
            }
            BigDecimal base = BigDecimal.valueOf(baseRate(cfg, p.groupInstanceId)).multiply(fraction);
            int fractionPercent = fraction.multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP).intValue();
            present.add(new Candidate(p.childId, base, fractionPercent));
        }

        boolean leastFirst = RequiredHours.LEAST_EXPENSIVE_FIRST.equals(cfg == null ? null : cfg.order);
        Comparator<Candidate> byBase = Comparator.comparing(Candidate::base);
        present.sort((leastFirst ? byBase : byBase.reversed())
                .thenComparing(Candidate::childId));

        List<ChildMonthShare> shares = new ArrayList<>();
        for (int i = 0; i < present.size(); i++) {
            Candidate c = present.get(i);
            int discount = discountPercentForRank(cfg, i + 1);
            int minutes = c.base().multiply(BigDecimal.valueOf(100 - discount))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).intValue();
            shares.add(new ChildMonthShare(c.childId(), minutes, c.fractionPercent(), discount));
        }
        return shares;
    }
}
