package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

/** POS-23: one returned line — which sold order item, how many, at what (historical) value. */
@Getter
@Setter
@Entity
@Table(name = "pos_return_items")
public class PosReturnItem {
    /** What happens to the goods. Only RESTOCK makes them sellable again. */
    public enum Disposition { RESTOCK, DAMAGED, DO_NOT_RESTOCK }

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "return_id", nullable = false)
    private PosReturn posReturn;

    @Column(name = "order_item_id", nullable = false)
    private UUID orderItemId;

    @Column(name = "pos_line_no")
    private Integer posLineNo;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "variant_id")
    private UUID variantId;

    @Column(name = "item_name", nullable = false, length = 300)
    private String itemName;

    @Column(name = "requested_quantity", nullable = false)
    private int requestedQuantity;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 3)
    private BigDecimal unitPrice;

    @Column(name = "line_paid_total", nullable = false, precision = 12, scale = 3)
    private BigDecimal linePaidTotal;

    @Column(name = "requested_refund", nullable = false, precision = 12, scale = 3)
    private BigDecimal requestedRefund;

    @Column(name = "refund_amount", nullable = false, precision = 12, scale = 3)
    private BigDecimal refundAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Disposition disposition;

    @Column(name = "restocked_quantity", nullable = false)
    private int restockedQuantity;
}
