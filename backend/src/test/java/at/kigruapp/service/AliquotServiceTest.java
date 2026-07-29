package at.kigruapp.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliquotServiceTest {

    private final AliquotService service = new AliquotService();

    @Test
    void fromStringDefaultsToNone() {
        assertEquals(AliquotMode.NONE, AliquotMode.fromString(null));
        assertEquals(AliquotMode.NONE, AliquotMode.fromString("garbage"));
        assertEquals(AliquotMode.PER_DAY, AliquotMode.fromString("PER_DAY"));
    }

    @Test
    void noneAndWholeMonth_areBinaryAtMonthLevel() {
        // enters 2026-11-16, present that month -> full month
        assertEquals(0, BigDecimal.ONE.compareTo(
                service.monthFraction(AliquotMode.NONE, "2026-11-16", null, 2026, 11)));
        assertEquals(0, BigDecimal.ONE.compareTo(
                service.monthFraction(AliquotMode.WHOLE_MONTH, "2026-11-16", null, 2026, 11)));
        // month before entry -> 0
        assertEquals(0, BigDecimal.ZERO.compareTo(
                service.monthFraction(AliquotMode.WHOLE_MONTH, "2026-11-16", null, 2026, 10)));
    }

    @Test
    void perDay_proratesEntryMonth() {
        // enter Nov 16 in a 30-day month -> days 16..30 = 15 days -> 15/30 = 0.5
        BigDecimal f = service.monthFraction(AliquotMode.PER_DAY, "2026-11-16", null, 2026, 11);
        assertEquals(0, new BigDecimal("0.5").compareTo(f.stripTrailingZeros()));
    }

    @Test
    void perDay_proratesExitMonth() {
        // exit Feb 10 (28-day month) -> days 1..10 = 10 days -> 10/28
        BigDecimal f = service.monthFraction(AliquotMode.PER_DAY, null, "2027-02-10", 2027, 2);
        assertEquals(new BigDecimal("10").divide(new BigDecimal("28"), 6, java.math.RoundingMode.HALF_UP), f);
    }

    @Test
    void perDay_fullInteriorMonthIsOne() {
        BigDecimal f = service.monthFraction(AliquotMode.PER_DAY, "2026-09-01", "2027-02-28", 2026, 12);
        assertEquals(0, BigDecimal.ONE.compareTo(f));
    }

    @Test
    void perDay_outsideWindowIsZero() {
        BigDecimal f = service.monthFraction(AliquotMode.PER_DAY, "2026-11-01", "2027-01-31", 2026, 10);
        assertTrue(f.signum() == 0);
    }
}
