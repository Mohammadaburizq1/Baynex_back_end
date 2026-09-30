package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "order_items")
public class OrderItem extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    // Nullable for lines on products without variants, and after a variant is later deleted
    // (ON DELETE SET NULL) — the label/SKU snapshots below are what history reads from.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(name = "product_name_snapshot", nullable = false, length = 180)
    private String productNameSnapshot;
    @Column(name = "variant_label", length = 200)
    private String variantLabel;
    @Column(name = "sku_snapshot", length = 120)
    private String skuSnapshot;
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 3)
    private BigDecimal unitPrice;
    @Column(nullable = false)
    private int quantity;
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal total;
    /** POS-23: the till's line number (1…n) of a POS sale; null for web orders and pre-V39 POS orders. */
    @Column(name = "pos_line_no")
    private Integer posLineNo;

    // ── POS-26 restaurant lines. quantity/total above are what is still ordered (after voids). ──
    @Column(name = "pos_line_uid")
    private UUID posLineUid;
    @Column(name = "item_note", length = 300)
    private String itemNote;
    /** STARTER | MAIN | DESSERT | DRINK (optional; foundation for the kitchen, POS-27). */
    @Column(length = 10)
    private String course;
    @Column(name = "voided_quantity", nullable = false)
    private int voidedQuantity;
    @Column(name = "added_by_id")
    private UUID addedById;
    @Column(name = "added_by_name", length = 160)
    private String addedByName;
    @Column(name = "added_at")
    private java.time.Instant addedAt;
    @Column(name = "added_device_id")
    private UUID addedDeviceId;
    @Column(name = "sent_version")
    private Integer sentVersion;
    @Column(name = "line_version", nullable = false)
    private int lineVersion = 1;

    // The add-ons chosen for this line. unitPrice above already includes their deltas.
    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItemModifier> modifiers = new ArrayList<>();
}
