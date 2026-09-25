package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A set of add-on choices offered on a product ("Extras"), with the rules for picking from it:
 * at least {@code minSelect} (0 = optional) and at most {@code maxSelect} options.
 */
@Getter
@Setter
@Entity
@Table(name = "product_modifier_groups")
public class ProductModifierGroup extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "min_select", nullable = false)
    private int minSelect = 0;

    @Column(name = "max_select", nullable = false)
    private int maxSelect = 1;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, name ASC")
    private List<ProductModifierOption> options = new ArrayList<>();
}
