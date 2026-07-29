package at.kigruapp.service;

import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HoursBalanceServiceTest {

    private final HoursBalanceService service = new HoursBalanceService();

    // Plain unit test (not @QuarkusTest) — CDI injection does not run, so wire the
    // package-private field manually before any test that triggers monthFraction().
    @BeforeEach
    void wireAliquotService() {
        service.aliquotService = new AliquotService();
    }

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

    private final AliquotService aliquot = new AliquotService();

    private HoursBalanceService.ChildPlacement placement(String id, String entry, String exit) {
        HoursBalanceService.ChildPlacement p = new HoursBalanceService.ChildPlacement();
        p.childId = id;
        p.entryDate = entry;
        p.exitDate = exit;
        return p;
    }

    private Semester sepToFeb() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        return s; // 6 months
    }

    @Test
    void soll_noneMode_equalsLegacyFormula() {
        RequiredHours c = cfg(480, tier(2, 360)); // 8h, ab 2. Kind 6h
        Semester s = sepToFeb();
        // two children, windows irrelevant under NONE
        var placements = List.of(placement("a", "2026-11-01", null), placement("b", null, null));
        // familyMonthlyMinutes(c,2)=840, x6 = 5040
        assertEquals(5040, service.familySollMinutes(c, AliquotMode.NONE, s, placements));
    }

    @Test
    void soll_wholeMonth_dropsOutOfWindowMonths() {
        RequiredHours c = cfg(480); // 8h flat, no tiers
        Semester s = sepToFeb();
        // single child present only Dec..Feb (enters 2026-12-01) -> 3 months x 480 = 1440
        var placements = List.of(placement("a", "2026-12-01", null));
        assertEquals(1440, service.familySollMinutes(c, AliquotMode.WHOLE_MONTH, s, placements));
    }

    @Test
    void soll_perDay_proratesEntryMonth() {
        RequiredHours c = cfg(480); // 8h flat
        Semester s = sepToFeb();
        // enters Nov 16 -> Nov = 15/30*480 = 240; Dec,Jan,Feb full = 3*480=1440; total 1680
        var placements = List.of(placement("a", "2026-11-16", null));
        assertEquals(1680, service.familySollMinutes(c, AliquotMode.PER_DAY, s, placements));
    }

    @Test
    void sollByMonth_perDay_proratesEntryMonth_andSumMatchesTotal() {
        RequiredHours c = cfg(480); // 8h flat
        Semester s = sepToFeb();
        var placements = List.of(placement("a", "2026-11-16", null));
        var byMonth = service.familySollByMonth(c, AliquotMode.PER_DAY, s, placements);
        // pre-entry months 0, entry month prorated (15/30*480=240), later months full
        assertEquals(0, byMonth.get("2026-09"));
        assertEquals(0, byMonth.get("2026-10"));
        assertEquals(240, byMonth.get("2026-11"));
        assertEquals(480, byMonth.get("2026-12"));
        assertEquals(480, byMonth.get("2027-01"));
        assertEquals(480, byMonth.get("2027-02"));
        int sum = byMonth.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(1680, sum);
        assertEquals(service.familySollMinutes(c, AliquotMode.PER_DAY, s, placements), sum);
    }

    @Test
    void soll_perDay_twoConcurrentChildren_higherFractionGetsOrdinalOne() {
        RequiredHours c = cfg(480, tier(2, 360)); // rate(1)=480, rate(2)=360
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2026-09-30T00:00:00Z"); // single month, 30 days
        // child a present whole month (fraction 1.0); child b enters Sep 16 (fraction 15/30 = 0.5)
        var placements = List.of(
            placement("a", "2026-09-01", null),
            placement("b", "2026-09-16", null));
        // ordinal by fraction desc: a=ordinal1 -> 480*1.0=480 ; b=ordinal2 -> 360*0.5=180 ; total=660
        // (a reversed comparator would give 240+360=600, so 660 pins the direction)
        assertEquals(660, service.familySollMinutes(c, AliquotMode.PER_DAY, s, placements));
    }
}
