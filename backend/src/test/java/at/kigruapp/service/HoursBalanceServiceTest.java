package at.kigruapp.service;

import at.kigruapp.entity.RequiredHours;
import at.kigruapp.entity.Semester;
import org.bson.types.ObjectId;
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

    private RequiredHours.Tier tier(int fromChild, int percent) {
        RequiredHours.Tier t = new RequiredHours.Tier();
        t.fromChild = fromChild;
        t.percent = percent;
        return t;
    }

    private RequiredHours cfgAll(int def, RequiredHours.Tier... tiers) {
        RequiredHours c = new RequiredHours();
        c.defaultMinutesPerMonth = def;
        c.allGroups = true;
        c.order = RequiredHours.MOST_EXPENSIVE_FIRST;
        c.tiers = new java.util.ArrayList<>(List.of(tiers));
        return c;
    }

    private RequiredHours cfgPerGroup(String order, java.util.Map<ObjectId, Integer> rates,
                                      RequiredHours.Tier... tiers) {
        RequiredHours c = new RequiredHours();
        c.defaultMinutesPerMonth = 0;
        c.allGroups = false;
        c.order = order;
        c.groupRates = new java.util.ArrayList<>();
        rates.forEach((groupId, minutes) -> {
            RequiredHours.GroupRate r = new RequiredHours.GroupRate();
            r.groupInstanceId = groupId;
            r.minutesPerMonth = minutes;
            c.groupRates.add(r);
        });
        c.tiers = new java.util.ArrayList<>(List.of(tiers));
        return c;
    }

    private HoursBalanceService.ChildPlacement placement(String childId, ObjectId groupId,
                                                         String entryDate, String exitDate) {
        HoursBalanceService.ChildPlacement p = new HoursBalanceService.ChildPlacement();
        p.childId = childId;
        p.groupInstanceId = groupId;
        p.entryDate = entryDate;
        p.exitDate = exitDate;
        return p;
    }

    private Semester semester(String startIso, String endIso) {
        Semester s = new Semester();
        s.start = Instant.parse(startIso);
        s.end = Instant.parse(endIso);
        return s;
    }

    /** n Kinder, den ganzen Monat anwesend, alle in derselben (irrelevanten) Gruppe. */
    private List<HoursBalanceService.ChildPlacement> nChildren(int n) {
        List<HoursBalanceService.ChildPlacement> placements = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            placements.add(placement("child" + i, new ObjectId(), null, null));
        }
        return placements;
    }

    private int monthlyMinutesFor(RequiredHours c, int childCount) {
        Semester s = semester("2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z");
        return service.fullMonthMinutes(c, AliquotMode.NONE, s, nChildren(childCount));
    }

    @Test
    void noTiers_multipliesDefaultByChildCount() {
        RequiredHours c = cfgAll(480); // 8h
        assertEquals(0, monthlyMinutesFor(c, 0));
        assertEquals(480, monthlyMinutesFor(c, 1));
        assertEquals(960, monthlyMinutesFor(c, 2));
        assertEquals(1440, monthlyMinutesFor(c, 3));
    }

    @Test
    void singleTierFromSecondChild() {
        RequiredHours c = cfgAll(480, tier(2, 25)); // default 8h, ab 2. Kind 6h (25% Rabatt)
        assertEquals(480, monthlyMinutesFor(c, 1));
        assertEquals(840, monthlyMinutesFor(c, 2));   // 480 + 360
        assertEquals(1200, monthlyMinutesFor(c, 3));  // 480 + 360 + 360
    }

    @Test
    void nestedTiers_examplesFromSpec() {
        RequiredHours c = cfgAll(480, tier(2, 25), tier(3, 100)); // 8h, ab 2. = 6h (25%), ab 3. = 0h (100%)
        assertEquals(480, monthlyMinutesFor(c, 1));
        assertEquals(840, monthlyMinutesFor(c, 2));  // 480 + 360
        assertEquals(840, monthlyMinutesFor(c, 3));  // 480 + 360 + 0
        assertEquals(840, monthlyMinutesFor(c, 4));  // 480 + 360 + 0 + 0
    }

    @Test
    void tierFromFirstChildOverridesDefault() {
        RequiredHours c = cfgAll(480, tier(1, 75)); // fromChild=1 (75% Rabatt -> 120min)
        assertEquals(120, monthlyMinutesFor(c, 1));
        assertEquals(240, monthlyMinutesFor(c, 2));
    }

    @Test
    void nullConfigMeansZero() {
        assertEquals(0, monthlyMinutesFor(null, 3));
    }

    @Test
    void unsortedTiersHandled() {
        RequiredHours c = cfgAll(480, tier(3, 100), tier(2, 25)); // deliberately out of order
        assertEquals(840, monthlyMinutesFor(c, 3)); // 480 + 360 + 0
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

    private Semester sepToFeb() {
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2027-02-28T00:00:00Z");
        return s; // 6 months
    }

    @Test
    void soll_noneMode_respectsPresenceWindowButNotProration() {
        RequiredHours c = cfgAll(480, tier(2, 25)); // 8h, ab 2. Kind 6h (25% Rabatt)
        Semester s = sepToFeb();
        // NONE prorated nicht innerhalb eines Monats, aber a fehlt vor dem Eintritt ganz.
        var placements = List.of(placement("a", new ObjectId(), "2026-11-01", null),
                placement("b", new ObjectId(), null, null));
        // Sep, Okt: nur b da -> 480 je Monat. Nov..Feb: beide da -> 480 + 360 = 840 je Monat.
        // 2*480 + 4*840 = 960 + 3360 = 4320
        assertEquals(4320, service.familySollMinutes(c, AliquotMode.NONE, s, placements));
    }

    @Test
    void soll_wholeMonth_dropsOutOfWindowMonths() {
        RequiredHours c = cfgAll(480); // 8h flat, no tiers
        Semester s = sepToFeb();
        // single child present only Dec..Feb (enters 2026-12-01) -> 3 months x 480 = 1440
        var placements = List.of(placement("a", new ObjectId(), "2026-12-01", null));
        assertEquals(1440, service.familySollMinutes(c, AliquotMode.WHOLE_MONTH, s, placements));
    }

    @Test
    void soll_perDay_proratesEntryMonth() {
        RequiredHours c = cfgAll(480); // 8h flat
        Semester s = sepToFeb();
        // enters Nov 16 -> Nov = 15/30*480 = 240; Dec,Jan,Feb full = 3*480=1440; total 1680
        var placements = List.of(placement("a", new ObjectId(), "2026-11-16", null));
        assertEquals(1680, service.familySollMinutes(c, AliquotMode.PER_DAY, s, placements));
    }

    @Test
    void sollByMonth_perDay_proratesEntryMonth_andSumMatchesTotal() {
        RequiredHours c = cfgAll(480); // 8h flat
        Semester s = sepToFeb();
        var placements = List.of(placement("a", new ObjectId(), "2026-11-16", null));
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
        RequiredHours c = cfgAll(480, tier(2, 25)); // rate(1)=480, rate(2)=360
        Semester s = new Semester();
        s.start = Instant.parse("2026-09-01T00:00:00Z");
        s.end = Instant.parse("2026-09-30T00:00:00Z"); // single month, 30 days
        // child a present whole month (fraction 1.0); child b enters Sep 16 (fraction 15/30 = 0.5)
        var placements = List.of(
            placement("a", new ObjectId(), "2026-09-01", null),
            placement("b", new ObjectId(), "2026-09-16", null));
        // ordinal by fraction desc: a=ordinal1 -> 480*1.0=480 ; b=ordinal2 -> 360*0.5=180 ; total=660
        // (a reversed comparator would give 240+360=600, so 660 pins the direction)
        assertEquals(660, service.familySollMinutes(c, AliquotMode.PER_DAY, s, placements));
    }

    @Test
    void perGroupRates_mostExpensiveFirst() {
        ObjectId kaefer = new ObjectId();
        ObjectId baeren = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.MOST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480, baeren, 300), tier(2, 25));
        Semester s = semester("2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z");

        java.util.Map<String, Integer> byMonth = service.familySollByMonth(c, AliquotMode.NONE, s,
                List.of(placement("a", kaefer, null, null), placement("b", baeren, null, null)));

        // Käfer 480 voll (Rang 1), Bären 300 minus 25 % = 225 -> 705
        assertEquals(705, byMonth.get("2026-09"));
    }

    @Test
    void perGroupRates_leastExpensiveFirst() {
        ObjectId kaefer = new ObjectId();
        ObjectId baeren = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.LEAST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480, baeren, 300), tier(2, 25));
        Semester s = semester("2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z");

        java.util.Map<String, Integer> byMonth = service.familySollByMonth(c, AliquotMode.NONE, s,
                List.of(placement("a", kaefer, null, null), placement("b", baeren, null, null)));

        // Bären 300 voll (Rang 1), Käfer 480 minus 25 % = 360 -> 660
        assertEquals(660, byMonth.get("2026-09"));
    }

    @Test
    void childWithoutConfiguredGroupRateOwesNothing() {
        ObjectId kaefer = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.MOST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480));
        Semester s = semester("2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z");

        java.util.Map<String, Integer> byMonth = service.familySollByMonth(c, AliquotMode.NONE, s,
                List.of(placement("a", kaefer, null, null), placement("b", new ObjectId(), null, null)));

        assertEquals(480, byMonth.get("2026-09"));
    }

    @Test
    void allGroupsUsesDefaultRateForEveryGroup() {
        RequiredHours c = cfgAll(480, tier(2, 25));
        Semester s = semester("2026-09-01T00:00:00Z", "2026-09-30T00:00:00Z");

        java.util.Map<String, Integer> byMonth = service.familySollByMonth(c, AliquotMode.NONE, s,
                List.of(placement("a", new ObjectId(), null, null),
                        placement("b", new ObjectId(), null, null)));

        assertEquals(840, byMonth.get("2026-09"));   // 480 + 360
    }

    @Test
    void midSemesterEntry_changesRankAndFraction() {
        ObjectId kaefer = new ObjectId();
        ObjectId baeren = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.MOST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480, baeren, 300), tier(2, 25));
        Semester s = semester("2026-10-01T00:00:00Z", "2026-11-30T00:00:00Z");

        java.util.Map<String, List<HoursBalanceService.ChildMonthShare>> shares =
                service.familySharesByMonth(c, AliquotMode.PER_DAY, s,
                        List.of(placement("a", kaefer, null, null),
                                placement("b", baeren, "2026-11-16", null)));

        // Oktober: nur Kind a, voller Satz, kein Rabatt
        assertEquals(1, shares.get("2026-10").size());
        assertEquals(480, shares.get("2026-10").get(0).minutes());
        assertEquals(0, shares.get("2026-10").get(0).discountPercent());

        // November: b ab 16.11. -> 15 von 30 Tagen = 50 %, Basis 150, Rang 2 -> 25 % Rabatt = 113
        List<HoursBalanceService.ChildMonthShare> november = shares.get("2026-11");
        HoursBalanceService.ChildMonthShare b = november.stream()
                .filter(x -> x.childId().equals("b")).findFirst().orElseThrow();
        assertEquals(50, b.fractionPercent());
        assertEquals(25, b.discountPercent());
        assertEquals(113, b.minutes());
    }

    @Test
    void exitEndsObligationAfterLastMonth() {
        ObjectId kaefer = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.MOST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480));
        Semester s = semester("2026-09-01T00:00:00Z", "2026-11-30T00:00:00Z");

        java.util.Map<String, Integer> byMonth = service.familySollByMonth(c, AliquotMode.WHOLE_MONTH, s,
                List.of(placement("a", kaefer, null, "2026-10-15")));

        assertEquals(480, byMonth.get("2026-09"));
        assertEquals(480, byMonth.get("2026-10"));   // angefangener Monat zählt voll
        assertEquals(0, byMonth.get("2026-11"));
    }

    @Test
    void fullMonthMinutes_returnsMonthWithEveryChildPresent() {
        ObjectId kaefer = new ObjectId();
        ObjectId baeren = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.MOST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480, baeren, 300), tier(2, 25));
        Semester s = semester("2026-10-01T00:00:00Z", "2026-12-31T00:00:00Z");

        int full = service.fullMonthMinutes(c, AliquotMode.PER_DAY, s,
                List.of(placement("a", kaefer, null, null),
                        placement("b", baeren, "2026-11-16", null)));

        assertEquals(705, full);   // Dezember: beide voll da
    }

    @Test
    void fullMonthMinutes_isZeroWhenNoMonthHasEveryChild() {
        ObjectId kaefer = new ObjectId();
        RequiredHours c = cfgPerGroup(RequiredHours.MOST_EXPENSIVE_FIRST,
                java.util.Map.of(kaefer, 480));
        Semester s = semester("2026-10-01T00:00:00Z", "2026-10-31T00:00:00Z");

        int full = service.fullMonthMinutes(c, AliquotMode.PER_DAY, s,
                List.of(placement("a", kaefer, "2026-10-16", null)));

        assertEquals(0, full);
    }
}
