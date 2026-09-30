package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * POS-26: one payment toward a restaurant bill (cash or the external card terminal; no gateway). A bill
 * can have many, on several tills and shifts. The id comes from the till; written once, never edited.
 */
@Getter
@Setter
@Entity
@Table(name = "pos_order_payments")
public class PosOrderPayment implements org.springframework.data.domain.Persistable<UUID> {
    public enum Method { CASH, EXTERNAL_TERMINAL }

    public enum SplitMode { FULL, ITEMS, EQUAL, CUSTOM }

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Method method;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_mode", nullable = false, length = 10)
    private SplitMode splitMode;

    /** JSON: the line uids/quantities (ITEMS) or "share k of n" (EQUAL) this payment covered. */
    @Column(columnDefinition = "text")
    private String allocation;

    @Column(name = "staff_id")
    private UUID staffId;

    @Column(name = "staff_name", nullable = false, length = 160)
    private String staffName;

    @Column(name = "shift_id")
    private UUID shiftId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean fresh = true;

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        fresh = false;
    }
}
