package com.byonix.shoplink.service;

import java.util.ArrayList;
import java.util.List;

/** Integer quantities, allocated deterministically; never clamp an internal lineage mismatch. */
public final class KitchenAllocationPolicy {
    private KitchenAllocationPolicy() {}
    public record Portion(int taken, int remaining) {}

    public static List<Portion> take(List<Integer> quantities, int requested) {
        if (requested <= 0 || quantities.stream().anyMatch(q -> q <= 0)
                || quantities.stream().mapToLong(Integer::longValue).sum() < requested) {
            throw new IllegalArgumentException("Insufficient kitchen allocation quantity");
        }
        int left = requested;
        List<Portion> result = new ArrayList<>();
        for (int quantity : quantities) {
            int taken = Math.min(left, quantity);
            result.add(new Portion(taken, quantity - taken));
            left -= taken;
        }
        return result;
    }
}
