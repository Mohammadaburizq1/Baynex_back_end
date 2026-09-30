package com.byonix.shoplink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * POS-26: restaurant rules, identical on the till (lib/pos/features/restaurant/restaurant_policy.dart).
 * Money at scale 3.
 *
 * <ul>
 *   <li>Voiding a sent line, merging tables and reassigning the waiter need a POS manager (or the
 *       person doing it is one). The server keeps what happened either way and flags a missing approval.</li>
 *   <li>Equal split of an amount into n shares: every share is the amount divided by n rounded down to
 *       0.001, and the 0.001 units left over go one each to the first shares — deterministic, exact.</li>
 *   <li>A line's share of the bill is its total minus its part of the order discount (shared like
 *       returns, {@link PosReturnPolicy#discountShares}); part of a line is priced cumulatively, so
 *       paying a line in pieces adds up to exactly its share.</li>
 * </ul>
 */
public final class RestaurantPolicy {
    public static final String VOID_APPROVAL = "RESTAURANT_VOID_APPROVAL";
    public static final String MERGE_APPROVAL = "RESTAURANT_MERGE_APPROVAL";
    public static final String WAITER_APPROVAL = "RESTAURANT_WAITER_APPROVAL";
    private static final int SCALE = 3;

    private RestaurantPolicy() {}

    public static List<BigDecimal> equalShares(BigDecimal amount, int n) {
        if (n < 1) throw new IllegalArgumentException("At least one share");
        BigDecimal unit = new BigDecimal("0.001");
        BigDecimal base = amount.divide(BigDecimal.valueOf(n), SCALE, RoundingMode.DOWN);
        int leftover = amount.subtract(base.multiply(BigDecimal.valueOf(n))).divide(unit, 0, RoundingMode.UNNECESSARY).intValueExact();
        List<BigDecimal> shares = new ArrayList<>();
        for (int i = 0; i < n; i++) shares.add(i < leftover ? base.add(unit) : base);
        return shares;
    }

    /** Share of the bill of each line (line total − its part of the discount), in line order. */
    public static List<BigDecimal> lineShares(List<BigDecimal> lineTotals, BigDecimal discount) {
        List<BigDecimal> discountShares = PosReturnPolicy.discountShares(lineTotals, discount);
        List<BigDecimal> result = new ArrayList<>();
        for (int i = 0; i < lineTotals.size(); i++) {
            result.add(lineTotals.get(i).subtract(discountShares.get(i)).setScale(SCALE, RoundingMode.HALF_UP));
        }
        return result;
    }
}
