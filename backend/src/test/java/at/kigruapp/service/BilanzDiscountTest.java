package at.kigruapp.service;

import at.kigruapp.entity.KostenDiscount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BilanzDiscountTest {

    private final BilanzCalculationService svc = new BilanzCalculationService();

    private KostenDiscount cfg(String order, int[]... tiers) {
        KostenDiscount c = new KostenDiscount();
        c.order = order;
        c.tiers = new java.util.ArrayList<>();
        for (int[] t : tiers) {
            KostenDiscount.Tier tier = new KostenDiscount.Tier();
            tier.fromChild = t[0];
            tier.percent = t[1];
            c.tiers.add(tier);
        }
        return c;
    }

    private BilanzCalculationService.ChildBase cb(String id, String base) {
        BilanzCalculationService.ChildBase b = new BilanzCalculationService.ChildBase();
        b.childId = id;
        b.base = new BigDecimal(base);
        return b;
    }

    @Test
    void mostExpensiveFirst_topChildFullPrice() {
        KostenDiscount c = cfg("MOST_EXPENSIVE_FIRST", new int[]{2, 50}, new int[]{3, 100});
        List<BilanzCalculationService.ChildBase> present =
                List.of(cb("a", "100"), cb("b", "80"), cb("c", "60"));
        assertEquals(0, new BigDecimal("1.0000").compareTo(svc.discountFactor(c, "a", present))); // 1st
        assertEquals(0, new BigDecimal("0.5000").compareTo(svc.discountFactor(c, "b", present))); // 2nd -50%
        assertEquals(0, new BigDecimal("0.0000").compareTo(svc.discountFactor(c, "c", present))); // 3rd -100%
    }

    @Test
    void leastExpensiveFirst_reversesRanking() {
        KostenDiscount c = cfg("LEAST_EXPENSIVE_FIRST", new int[]{2, 50});
        List<BilanzCalculationService.ChildBase> present = List.of(cb("a", "100"), cb("b", "80"));
        assertEquals(0, new BigDecimal("1.0000").compareTo(svc.discountFactor(c, "b", present))); // cheapest = 1st
        assertEquals(0, new BigDecimal("0.5000").compareTo(svc.discountFactor(c, "a", present)));
    }

    @Test
    void noTiers_alwaysFullPrice() {
        KostenDiscount c = cfg("MOST_EXPENSIVE_FIRST");
        List<BilanzCalculationService.ChildBase> present = List.of(cb("a", "100"), cb("b", "80"));
        assertEquals(0, BigDecimal.ONE.compareTo(svc.discountFactor(c, "b", present)));
    }

    @Test
    void nullConfig_fullPrice() {
        assertEquals(0, BigDecimal.ONE.compareTo(svc.discountFactor(null, "a", List.of())));
    }

    @Test
    void discountResult_reportsOneBasedOrdinal() {
        KostenDiscount c = cfg("MOST_EXPENSIVE_FIRST", new int[]{2, 50});
        List<BilanzCalculationService.ChildBase> present =
                List.of(cb("a", "100"), cb("b", "80"), cb("c", "60"));
        assertEquals(1, svc.discountResult(c, "a", present).ordinal()); // most expensive -> 1st
        assertEquals(2, svc.discountResult(c, "b", present).ordinal());
        assertEquals(3, svc.discountResult(c, "c", present).ordinal());
        assertEquals(0, new java.math.BigDecimal("0.5000")
                .compareTo(svc.discountResult(c, "b", present).factor()));
    }

    @Test
    void discountResult_nullConfigOrdinalZero() {
        assertEquals(0, svc.discountResult(null, "a", List.of()).ordinal());
    }
}
