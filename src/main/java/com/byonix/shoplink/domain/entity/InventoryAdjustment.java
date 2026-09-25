package com.byonix.shoplink.domain.entity;

import com.byonix.shoplink.domain.enums.InventoryAdjustmentReason;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/** One immutable line of a store's stock history. Never updated after it is written. */
@Getter
@Setter
@Entity
@Table(name = "inventory_adjustments")
public class InventoryAdjustment extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    /** "Tee (M / Black)" as it was called at the time. */
    @Column(name = "item_name", nullable = false, length = 300)
    private String itemName;

    @Column(nullable = false)
    private int delta;

    @Column(name = "stock_after", nullable = false)
    private int stockAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InventoryAdjustmentReason reason;

    /** The order code for sale / cancellation rows. */
    @Column(length = 60)
    private String reference;

    @Column(length = 300)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;
}
