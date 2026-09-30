package com.byonix.shoplink.service;

import com.byonix.shoplink.domain.entity.PosShiftCashMovement;

import java.math.BigDecimal;

/**
 * POS-24: the shift rules, identical on the till (lib/pos/features/shifts/shift_policy.dart) and here.
 *
 * <h2>Expected drawer cash</h2>
 * {@code opening + cash sales + cash in − cash refunds − cash out}. A cash sale counts only what the
 * customer handed over: its total minus the exchange credit of goods returned in the same exchange. A
 * cash refund counts what left the drawer: the return's paid-out part (an exchange's credit never
 * leaves the drawer). Card-terminal sales and refunds never touch the drawer.
 *
 * <h2>When a POS manager must approve</h2>
 * <ul>
 *   <li>Cash out, unless the person taking it is a POS manager (no silent drawer reductions).</li>
 *   <li>Cash in above {@link #CASH_IN_APPROVAL_ABOVE}, unless the person is a POS manager.</li>
 *   <li>Closing with a variance beyond ±{@link #VARIANCE_APPROVAL_ABOVE}, or closing someone else's
 *       shift, unless the person closing is a POS manager.</li>
 * </ul>
 * Thresholds are in the store's currency (the POS currencies so far are three-decimal JOD-like ones).
 */
public final class PosShiftPolicy {
    public static final BigDecimal VARIANCE_APPROVAL_ABOVE = new BigDecimal("1.000");
    public static final BigDecimal CASH_IN_APPROVAL_ABOVE = new BigDecimal("100.000");
    public static final String CASH_MOVEMENT_APPROVAL = "CASH_MOVEMENT_APPROVAL";
    public static final String CLOSE_APPROVAL = "SHIFT_CLOSE_APPROVAL";
    /** An open shift older than this is flagged (never closed automatically). */
    public static final java.time.Duration LONG_OPEN = java.time.Duration.ofHours(24);

    private PosShiftPolicy() {}

    public static BigDecimal expectedCash(BigDecimal opening, BigDecimal cashSales, BigDecimal cashIn,
                                          BigDecimal cashRefunds, BigDecimal cashOut) {
        return opening.add(cashSales).add(cashIn).subtract(cashRefunds).subtract(cashOut);
    }

    public static boolean movementNeedsApproval(PosShiftCashMovement.Type type, BigDecimal amount, boolean actorIsManager) {
        if (actorIsManager) return false;
        return type == PosShiftCashMovement.Type.CASH_OUT || amount.compareTo(CASH_IN_APPROVAL_ABOVE) > 0;
    }

    public static boolean closeNeedsApproval(BigDecimal variance, boolean closerIsCashier, boolean closerIsManager) {
        if (closerIsManager) return false;
        return variance.abs().compareTo(VARIANCE_APPROVAL_ABOVE) > 0 || !closerIsCashier;
    }
}
