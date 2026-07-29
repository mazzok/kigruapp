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
    }

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

    /** Minutes/month owed for the n-th child (1-based). Highest matching tier wins, else default. */
    public int rateForChild(RequiredHours cfg, int childOrdinal) {
        if (cfg == null) {
            return 0;
        }
        int rate = cfg.defaultMinutesPerMonth;
        if (cfg.tiers != null) {
            int bestFrom = 0;
            for (RequiredHours.Tier t : cfg.tiers) {
                if (t.fromChild <= childOrdinal && t.fromChild >= bestFrom) {
                    bestFrom = t.fromChild;
                    rate = t.minutesPerMonth;
                }
            }
        }
        return rate;
    }

    /** Σ rateForChild(1..childCount) — the family's per-month Soll. */
    public int familyMonthlyMinutes(RequiredHours cfg, int childCount) {
        int total = 0;
        for (int n = 1; n <= childCount; n++) {
            total += rateForChild(cfg, n);
        }
        return total;
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

    /** Family Soll (minutes) over the semester, applying the aliquot mode. Pure given placements. */
    public int familySollMinutes(RequiredHours cfg, AliquotMode mode, Semester semester,
                                 List<ChildPlacement> placements) {
        return familySollByMonth(cfg, mode, semester, placements).values().stream()
                .mapToInt(Integer::intValue).sum();
    }

    /** Per-month Soll ("YYYY-MM" -> minutes) over the semester under the aliquot mode.
     *  NONE => each month = familyMonthlyMinutes(cfg, placements.size()). Sum == familySollMinutes. */
    public java.util.Map<String, Integer> familySollByMonth(RequiredHours cfg, AliquotMode mode,
            Semester semester, List<ChildPlacement> placements) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (semester == null || semester.start == null || semester.end == null) {
            return out;
        }
        YearMonth cur = YearMonth.from(semester.start.atZone(ZoneOffset.UTC));
        YearMonth last = YearMonth.from(semester.end.atZone(ZoneOffset.UTC));
        while (!cur.isAfter(last)) {
            String key = String.format("%04d-%02d", cur.getYear(), cur.getMonthValue());
            out.put(key, monthSoll(cfg, mode, cur, placements));
            cur = cur.plusMonths(1);
        }
        return out;
    }

    /** Soll (minutes) for a single month under the aliquot mode. NONE => flat family monthly rate. */
    private int monthSoll(RequiredHours cfg, AliquotMode mode, YearMonth ym,
                          List<ChildPlacement> placements) {
        if (mode == AliquotMode.NONE) {
            return familyMonthlyMinutes(cfg, placements.size());
        }
        List<BigDecimal> presentFractions = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (ChildPlacement p : placements) {
            BigDecimal f = aliquotService.monthFraction(
                    mode, p.entryDate, p.exitDate, ym.getYear(), ym.getMonthValue());
            if (f.signum() > 0) {
                presentFractions.add(f);
                ids.add(p.childId);
            }
        }
        // ordinal by fraction desc, tie-break childId
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < presentFractions.size(); i++) idx.add(i);
        final List<BigDecimal> fr = presentFractions;
        final List<String> idList = ids;
        idx.sort(Comparator.<Integer, BigDecimal>comparing(fr::get).reversed()
                .thenComparing(idList::get));
        int total = 0;
        for (int ordinalPos = 0; ordinalPos < idx.size(); ordinalPos++) {
            int childIdx = idx.get(ordinalPos);
            int rate = rateForChild(cfg, ordinalPos + 1);
            total += BigDecimal.valueOf(rate).multiply(fr.get(childIdx))
                    .setScale(0, RoundingMode.HALF_UP).intValue();
        }
        return total;
    }
}
