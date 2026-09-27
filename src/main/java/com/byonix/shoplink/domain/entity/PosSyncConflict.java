package com.byonix.shoplink.domain.entity;

import com.byonix.shoplink.domain.enums.PosSyncConflictStatus;
import com.byonix.shoplink.domain.enums.PosSyncConflictType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A discrepancy found while applying an offline POS sale (see PosSyncConflictType). Written in the
 * same transaction as the order, and never deleted: the merchant marks it resolved.
 * Product and variant ids are plain columns, not references, because the product may be gone.
 */
@Getter
@Setter
@Entity
@Table(name = "pos_sync_conflicts")
public class PosSyncConflict {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "order_code", length = 12)
    private String orderCode;

    @Column(name = "receipt_number", length = 40)
    private String receiptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_type", nullable = false, length = 30)
    private PosSyncConflictType type;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "variant_id")
    private UUID variantId;

    @Column(name = "item_name", nullable = false, length = 300)
    private String itemName;

    @Column(name = "requested_quantity")
    private Integer requestedQuantity;

    @Column(name = "applied_quantity")
    private Integer appliedQuantity;

    private Integer shortfall;

    @Column(name = "stock_before")
    private Integer stockBefore;

    @Column(name = "stock_after")
    private Integer stockAfter;

    @Column(name = "sale_unit_price", precision = 12, scale = 3)
    private BigDecimal saleUnitPrice;

    @Column(name = "current_unit_price", precision = 12, scale = 3)
    private BigDecimal currentUnitPrice;

    @Column(nullable = false, length = 400)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PosSyncConflictStatus status = PosSyncConflictStatus.OPEN;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    @Column(name = "resolution_note", length = 300)
    private String resolutionNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
