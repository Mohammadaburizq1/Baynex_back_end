package com.byonix.shoplink.service;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class KitchenAllocationPolicyTest {
    @Test
    void partialAndFullMovesConserveEveryQuantity() {
        for (int original=1; original<=100; original++) {
            for (int moved=1; moved<=original; moved++) {
                var parts=KitchenAllocationPolicy.take(List.of(original),moved);
                assertThat(parts.get(0).taken()).isEqualTo(moved);
                assertThat(parts.get(0).remaining()).isEqualTo(original-moved);
                assertThat(parts.get(0).taken()+parts.get(0).remaining()).isEqualTo(original);
            }
        }
    }

    @Test
    void repeatedSplitsAndVoidsNeverCreateOrLoseUnits() {
        List<Integer> leaves=new ArrayList<>(List.of(5));
        int voided=0;
        for (int take : List.of(2,1,1,1)) {
            var portions=KitchenAllocationPolicy.take(leaves,take);
            leaves=new ArrayList<>();
            for (var p : portions) { voided+=p.taken(); if (p.remaining()>0) leaves.add(p.remaining()); }
            assertThat(leaves.stream().mapToInt(Integer::intValue).sum()+voided).isEqualTo(5);
        }
        assertThat(leaves).isEmpty();
        assertThat(voided).isEqualTo(5);
    }

    @Test
    void overAllocationAndInvalidQuantitiesAreRejected() {
        assertThatThrownBy(() -> KitchenAllocationPolicy.take(List.of(1),2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KitchenAllocationPolicy.take(List.of(2),0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KitchenAllocationPolicy.take(List.of(0,2),1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KitchenAllocationPolicy.take(List.of(),1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectionAcrossDifferentStatusLeavesIsDeterministicAndDoesNotCollapseThem() {
        assertThat(KitchenAllocationPolicy.take(List.of(1,3,2),3)).containsExactly(
                new KitchenAllocationPolicy.Portion(1,0), new KitchenAllocationPolicy.Portion(2,1),new KitchenAllocationPolicy.Portion(0,2));
    }
}
