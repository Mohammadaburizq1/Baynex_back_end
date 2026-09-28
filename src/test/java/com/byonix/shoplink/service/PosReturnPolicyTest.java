package com.byonix.shoplink.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** POS-23 money rules. The same cases are asserted on the till (test/pos/return_policy_test.dart). */
class PosReturnPolicyTest {
    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void discountSharesAlwaysAddUpToTheDiscount() {
        // The spec example: 2 items totalling 20, discount 4 → each paid 8.
        assertThat(PosReturnPolicy.discountShares(List.of(d("10"), d("10")), d("4"))).containsExactly(d("2.000"), d("2.000"));
        // JOD (3 decimals): 15.750 + 0.750, 10% = 1.650.
        List<BigDecimal> jod = PosReturnPolicy.discountShares(List.of(d("15.750"), d("0.750")), d("1.650"));
        assertThat(jod).containsExactly(d("1.575"), d("0.075"));
        // USD-like (2 decimals) with a remainder: three equal lines, 1.00 off.
        List<BigDecimal> usd = PosReturnPolicy.discountShares(List.of(d("3.33"), d("3.33"), d("3.34")), d("1.00"));
        assertThat(sum(usd)).isEqualByComparingTo("1.00");
        assertThat(usd.get(0)).isEqualByComparingTo("0.333");
        // JPY-like (0 decimals).
        List<BigDecimal> jpy = PosReturnPolicy.discountShares(List.of(d("1500"), d("700"), d("300")), d("250"));
        assertThat(sum(jpy)).isEqualByComparingTo("250");
        assertThat(jpy).containsExactly(d("150.000"), d("70.000"), d("30.000"));
        // No discount.
        assertThat(PosReturnPolicy.discountShares(List.of(d("5"), d("6")), BigDecimal.ZERO)).allMatch(s -> s.signum() == 0);
    }

    @Test
    void partialReturnsOfALineAddUpExactlyToItsPaidValue() {
        BigDecimal paid = d("10.000"); // 3 units: 3.333… each
        BigDecimal one = PosReturnPolicy.refundFor(paid, 3, 0, 1);
        BigDecimal two = PosReturnPolicy.refundFor(paid, 3, 1, 1);
        BigDecimal three = PosReturnPolicy.refundFor(paid, 3, 2, 1);
        assertThat(one).isEqualByComparingTo("3.333");
        assertThat(two).isEqualByComparingTo("3.334");
        assertThat(three).isEqualByComparingTo("3.333");
        assertThat(one.add(two).add(three)).isEqualByComparingTo(paid);
        assertThat(PosReturnPolicy.refundFor(d("14.175"), 3, 0, 1)).isEqualByComparingTo("4.725");
        assertThat(PosReturnPolicy.refundFor(d("14.175"), 3, 1, 2)).isEqualByComparingTo("9.450");
        assertThat(PosReturnPolicy.refundFor(d("1500"), 2, 0, 2)).isEqualByComparingTo("1500");
    }

    @Test
    void approvalRules() {
        Duration today = Duration.ofHours(2);
        assertThat(PosReturnPolicy.requiresApproval(false, false, d("2"), d("10"), today, true)).isFalse();
        assertThat(PosReturnPolicy.requiresApproval(false, true, d("2"), d("10"), today, true)).as("full return").isTrue();
        assertThat(PosReturnPolicy.requiresApproval(false, false, d("5.001"), d("10"), today, true)).as("large").isTrue();
        assertThat(PosReturnPolicy.requiresApproval(false, false, d("2"), d("10"), Duration.ofDays(15), true)).as("old").isTrue();
        assertThat(PosReturnPolicy.requiresApproval(false, false, d("2"), d("10"), today, false)).as("cashier").isTrue();
        // Exchange of the whole sale with nothing paid back: no approval.
        assertThat(PosReturnPolicy.requiresApproval(true, true, BigDecimal.ZERO, d("10"), today, true)).isFalse();
    }
}
