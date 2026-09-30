package com.byonix.shoplink.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** POS-26 split math (the till's restaurant_policy.dart does exactly the same). */
class RestaurantPolicyTest {
    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void equalSharesAreExactAndTheLeftoverGoesToTheFirstShares() {
        assertThat(RestaurantPolicy.equalShares(d("30.000"), 3)).containsExactly(d("10.000"), d("10.000"), d("10.000"));
        List<BigDecimal> ten = RestaurantPolicy.equalShares(d("10.000"), 3);
        assertThat(ten).containsExactly(d("3.334"), d("3.333"), d("3.333"));
        assertThat(sum(ten)).isEqualByComparingTo("10.000");
        List<BigDecimal> odd = RestaurantPolicy.equalShares(d("0.005"), 4);
        assertThat(odd).containsExactly(d("0.002"), d("0.001"), d("0.001"), d("0.001"));
        assertThat(sum(RestaurantPolicy.equalShares(d("18.750"), 7))).isEqualByComparingTo("18.750");
    }

    @Test
    void itemSharesCarryTheirPartOfTheDiscountSoTheGuestsPayExactlyTheDiscountedTotal() {
        // Burger 5 + Pizza 7 + Coke 1 + Coke 1 = 14.000 subtotal … spec example with a 2.000 discount on 20.000:
        List<BigDecimal> lines = List.of(d("5.000"), d("7.000"), d("1.000"), d("1.000"), d("6.000"));
        List<BigDecimal> shares = RestaurantPolicy.lineShares(lines, d("2.000"));
        assertThat(sum(shares)).isEqualByComparingTo("18.000");
        assertThat(shares.get(0)).isEqualByComparingTo("4.500");  // 5 − 0.5
        assertThat(shares.get(1)).isEqualByComparingTo("6.300");  // 7 − 0.7
        // Guest 1 (burger + coke) and guest 2 (pizza + coke + the rest) pay 18.000 together, no more, no less.
        BigDecimal guest1 = shares.get(0).add(shares.get(2));
        BigDecimal guest2 = shares.get(1).add(shares.get(3)).add(shares.get(4));
        assertThat(guest1.add(guest2)).isEqualByComparingTo("18.000");
    }
}
