package com.byonix.shoplink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * POS-23: the return rules, identical on the till (lib/pos/features/returns/return_policy.dart) and
 * here. All money at scale 3, HALF_UP — the scale every POS amount already has.
 *
 * <h2>What a returned unit is worth</h2>
 * Never today's price: the sold line's total minus its share of the order discount ("paid value").
 * The order discount is shared across lines in till line order: each line gets
 * {@code discount × lineTotal / subtotal} rounded HALF_UP, and the last line takes what is left, so
 * the shares always add up to the discount exactly.
 *
 * <p>Returning part of a line is priced cumulatively, so a line returned in several steps refunds
 * exactly its paid value in the end (no 0.001 lost or gained): returning {@code q} units when
 * {@code before} were already returned out of {@code sold} is worth
 * {@code round(paid × (before + q) / sold) − round(paid × before / sold)}.
 *
 * <h2>When a manager must approve</h2>
 * A full return (every remaining unit of the sale), money paid back of more than half the sale's
 * total, a return more than {@link #APPROVAL_AFTER_DAYS} days after the sale, or a cashier without edit
 * rights on Orders in the permission grid. For an exchange the goods stay in a sale, so "full" does not
 * apply and "large" is judged on the money actually paid back, not on the goods' value.
 */
public final class PosReturnPolicy {
    public static final int APPROVAL_AFTER_DAYS = 14;
    public static final String APPROVAL_ACTION = "RETURN_APPROVAL";
    private static final int SCALE = 3;

    private PosReturnPolicy() {}

    /** Discount share of each line, in the given (till line) order; sums to {@code discount} exactly. */
    public static List<BigDecimal> discountShares(List<BigDecimal> lineTotals, BigDecimal discount) {
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal subtotal = lineTotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (discount == null || discount.signum() == 0 || subtotal.signum() == 0) {
            lineTotals.forEach(t -> shares.add(BigDecimal.ZERO.setScale(SCALE)));
            return shares;
        }
        BigDecimal given = BigDecimal.ZERO;
        for (int i = 0; i < lineTotals.size(); i++) {
            BigDecimal share = i == lineTotals.size() - 1
                    ? discount.subtract(given)
                    : discount.multiply(lineTotals.get(i)).divide(subtotal, SCALE, RoundingMode.HALF_UP);
            shares.add(share.setScale(SCALE, RoundingMode.HALF_UP));
            given = given.add(share);
        }
        return shares;
    }

    /** Refund for returning {@code quantity} more units of a line (see class comment). */
    public static BigDecimal refundFor(BigDecimal paidLineTotal, int sold, int before, int quantity) {
        if (sold <= 0 || quantity <= 0) return BigDecimal.ZERO.setScale(SCALE);
        return cumulative(paidLineTotal, sold, before + quantity).subtract(cumulative(paidLineTotal, sold, before));
    }

    private static BigDecimal cumulative(BigDecimal paid, int sold, int units) {
        return paid.multiply(BigDecimal.valueOf(units)).divide(BigDecimal.valueOf(sold), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * @param fullReturn  this return takes back every remaining unit of the sale
     * @param paidBack    money handed back to the customer (the whole refund for a return; for an
     *                    exchange only what was not credited to the replacement sale)
     */
    public static boolean requiresApproval(boolean exchange, boolean fullReturn, BigDecimal paidBack, BigDecimal orderTotal,
                                           Duration sinceSale, boolean cashierMayReturn) {
        boolean large = orderTotal.signum() > 0 && paidBack.multiply(BigDecimal.valueOf(2)).compareTo(orderTotal) > 0;
        return (!exchange && fullReturn) || large || sinceSale.compareTo(Duration.ofDays(APPROVAL_AFTER_DAYS)) > 0 || !cashierMayReturn;
    }
}
