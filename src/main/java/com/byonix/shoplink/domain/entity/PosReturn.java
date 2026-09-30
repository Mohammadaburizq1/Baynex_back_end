package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * POS-23: a return (or the return half of an exchange) made at a till, linked to the original POS
 * order and its items. A financial record of its own — never a negative order. See V39.
 */
@Getter
@Setter
@Entity
@Table(name = "pos_returns")
public class PosReturn {
    public enum Kind { RETURN, EXCHANGE }

    public enum Reason { DAMAGED, WRONG_ITEM, CUSTOMER_CHANGED_MIND, QUALITY_ISSUE, DUPLICATE_SALE, OTHER }

    /** How money went back to the customer. Neither is a card refund processed by khanGates. */
    public enum RefundMethod { CASH, EXTERNAL_TERMINAL }

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "local_return_id", nullable = false)
    private UUID localReturnId;

    @Column(name = "return_number", nullable = false, length = 40)
    private String returnNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(name = "original_order_id", nullable = false)
    private UUID originalOrderId;

    @Column(name = "original_order_code", length = 12)
    private String originalOrderCode;

    @Column(name = "original_receipt_number", length = 40)
    private String originalReceiptNumber;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name", length = 160)
    private String customerName;

    @Column(name = "customer_phone", length = 40)
    private String customerPhone;

    @Column(name = "staff_id")
    private UUID staffId;

    @Column(name = "staff_name", length = 160)
    private String staffName;

    @Column(name = "manager_id")
    private UUID managerId;

    @Column(name = "manager_name", length = 160)
    private String managerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Reason reason;

    @Column(name = "reason_note", length = 300)
    private String reasonNote;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "requested_refund_total", nullable = false, precision = 12, scale = 3)
    private BigDecimal requestedRefundTotal;

    @Column(name = "refund_total", nullable = false, precision = 12, scale = 3)
    private BigDecimal refundTotal;

    @Column(name = "exchange_credit", nullable = false, precision = 12, scale = 3)
    private BigDecimal exchangeCredit = BigDecimal.ZERO;

    @Column(name = "refund_paid_out", nullable = false, precision = 12, scale = 3)
    private BigDecimal refundPaidOut;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", length = 20)
    private RefundMethod refundMethod;

    @Column(name = "exchange_local_order_id")
    private UUID exchangeLocalOrderId;

    /** POS-24: the till shift this return was made in (the till's shift id; no FK — see V41). */
    @Column(name = "shift_id")
    private UUID shiftId;

    /** SYNCED | SYNCED_WITH_CONFLICTS (as the operation). */
    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "returned_at", nullable = false)
    private Instant returnedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "posReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("posLineNo ASC")
    private List<PosReturnItem> items = new ArrayList<>();
}
