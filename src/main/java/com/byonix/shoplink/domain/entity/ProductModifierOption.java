package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

/** One choice within a {@link ProductModifierGroup}; adds {@code priceDelta} to the line's unit price. */
@Getter
@Setter
@Entity
@Table(name = "product_modifier_options")
public class ProductModifierOption {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private ProductModifierGroup group;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "price_delta", nullable = false, precision = 12, scale = 3)
    private BigDecimal priceDelta = BigDecimal.ZERO;

    /** A UI hint (start with it ticked) — the server never applies a selection the customer didn't send. */
    @Column(name = "is_preselected", nullable = false)
    private boolean preselected = false;

    @Column(name = "is_available", nullable = false)
    private boolean available = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
