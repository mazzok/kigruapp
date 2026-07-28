package at.kigruapp.service;

import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class HoursBalanceService {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

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
}
