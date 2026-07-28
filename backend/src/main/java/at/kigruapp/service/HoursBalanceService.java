package at.kigruapp.service;

import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.ZoneOffset;
import java.time.YearMonth;

@ApplicationScoped
public class HoursBalanceService {

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
