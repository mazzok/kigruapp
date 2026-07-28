package at.kigruapp.service;

import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HoursBalanceServiceTest {

    private final HoursBalanceService service = new HoursBalanceService();

    private RequiredHours cfg(int def, RequiredHours.Tier... tiers) {
        RequiredHours c = new RequiredHours();
        c.defaultMinutesPerMonth = def;
        c.tiers = new java.util.ArrayList<>(List.of(tiers));
        return c;
    }

    private RequiredHours.Tier tier(int fromChild, int minutes) {
        RequiredHours.Tier t = new RequiredHours.Tier();
        t.fromChild = fromChild;
        t.minutesPerMonth = minutes;
        return t;
    }

    @Test
    void noTiers_multipliesDefaultByChildCount() {
        RequiredHours c = cfg(480); // 8h
        assertEquals(0, service.familyMonthlyMinutes(c, 0));
        assertEquals(480, service.familyMonthlyMinutes(c, 1));
        assertEquals(960, service.familyMonthlyMinutes(c, 2));
        assertEquals(1440, service.familyMonthlyMinutes(c, 3));
    }

    @Test
    void singleTierFromSecondChild() {
        RequiredHours c = cfg(480, tier(2, 360)); // default 8h, ab 2. Kind 6h
        assertEquals(480, service.familyMonthlyMinutes(c, 1));
        assertEquals(840, service.familyMonthlyMinutes(c, 2));   // 480 + 360
        assertEquals(1200, service.familyMonthlyMinutes(c, 3));  // 480 + 360 + 360
    }

    @Test
    void nestedTiers_examplesFromSpec() {
        RequiredHours c = cfg(480, tier(2, 360), tier(3, 0)); // 8h, ab 2. = 6h, ab 3. = 0h
        assertEquals(480, service.familyMonthlyMinutes(c, 1));
        assertEquals(840, service.familyMonthlyMinutes(c, 2));  // 480 + 360
        assertEquals(840, service.familyMonthlyMinutes(c, 3));  // 480 + 360 + 0
        assertEquals(840, service.familyMonthlyMinutes(c, 4));  // 480 + 360 + 0 + 0
    }

    @Test
    void tierFromFirstChildOverridesDefault() {
        RequiredHours c = cfg(480, tier(1, 120)); // pure function supports fromChild=1
        assertEquals(120, service.familyMonthlyMinutes(c, 1));
        assertEquals(240, service.familyMonthlyMinutes(c, 2));
    }

    @Test
    void nullConfigMeansZero() {
        assertEquals(0, service.familyMonthlyMinutes(null, 3));
    }

    @Test
    void unsortedTiersHandled() {
        RequiredHours c = cfg(480, tier(3, 0), tier(2, 360)); // deliberately out of order
        assertEquals(840, service.familyMonthlyMinutes(c, 3)); // 480 + 360 + 0
    }

    @Test
    void monthsInSemester_spanIncludingYearBoundary() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        assertEquals(6, service.monthsInSemester(s)); // Sep, Oct, Nov, Dec, Jan, Feb
    }

    @Test
    void monthsInSemester_sameMonth() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2026-09-30T00:00:00Z");
        assertEquals(1, service.monthsInSemester(s));
    }
}
