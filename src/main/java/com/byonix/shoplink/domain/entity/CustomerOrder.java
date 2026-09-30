package com.byonix.shoplink.domain.entity;

import com.byonix.shoplink.domain.enums.DeliveryMethod;
import com.byonix.shoplink.domain.enums.OrderSource;
import com.byonix.shoplink.domain.enums.OrderStatus;
import com.byonix.shoplink.domain.enums.PaymentMethod;
import com.byonix.shoplink.domain.enums.PaymentStatus;
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
@Table(name = "customer_orders")
public class CustomerOrder extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_id")
    private Offer offer;

    @Column(name = "order_code", nullable = false, length = 12)
    private String orderCode;

    @Column(name = "customer_name", nullable = false, length = 160)
    private String customerName;
    @Column(name = "customer_email", length = 255)
    private String customerEmail;
    @Column(name = "customer_phone", nullable = false, length = 40)
    private String customerPhone;
    @Column(name = "customer_address", length = 600)
    private String customerAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", nullable = false, length = 30)
    private DeliveryMethod deliveryMethod;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30)
    private PaymentMethod paymentMethod;
    // Independent of paymentMethod (intent) and status (fulfillment) — see PaymentStatus.
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 30)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status = OrderStatus.NEW;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal subtotal = BigDecimal.ZERO;
    @Column(name = "delivery_fee", nullable = false, precision = 12, scale = 3)
    private BigDecimal deliveryFee = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal discount = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal total = BigDecimal.ZERO;
    @Column(length = 3)
    private String currency;
    @Column(length = 1000)
    private String notes;

    // POS-09: a sale rung up on a POS device is an ordinary order with source POS. The device and
    // its local order id make the upload idempotent (unique together); the receipt number is the
    // one printed/shown at the counter before the order had a server code.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderSource source = OrderSource.WEB;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pos_device_id")
    private PosDevice posDevice;
    @Column(name = "pos_local_order_id")
    private UUID posLocalOrderId;
    @Column(name = "pos_receipt_number", length = 40)
    private String posReceiptNumber;
    // POS-14: the cashier who rang the sale up (id + name as it was at the till).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pos_staff_id")
    private User posStaff;
    @Column(name = "pos_staff_name", length = 160)
    private String posStaffName;
    /** POS-23: an exchange sale — how much of its total was paid by the returned goods' value. */
    @Column(name = "pos_exchange_credit", precision = 12, scale = 3)
    private BigDecimal posExchangeCredit;
    @Column(name = "pos_exchange_local_return_id")
    private UUID posExchangeLocalReturnId;
    /** POS-24: the till shift this sale was rung up in (the till's shift id; no FK — see V41). */
    @Column(name = "pos_shift_id")
    private UUID posShiftId;

    // ── POS-26 restaurant orders (null on retail POS sales and web orders) ──
    /** DINE_IN | TAKEAWAY | DELIVERY. */
    @Column(name = "pos_order_type", length = 10)
    private String posOrderType;
    @Column(name = "restaurant_table_id")
    private UUID restaurantTableId;
    @Column(name = "guest_count")
    private Integer guestCount;
    @Column(name = "waiter_id")
    private UUID waiterId;
    @Column(name = "waiter_name", length = 160)
    private String waiterName;
    @Column(name = "original_waiter_id")
    private UUID originalWaiterId;
    @Column(name = "original_waiter_name", length = 160)
    private String originalWaiterName;
    @Column(name = "pos_ticket_number", length = 20)
    private String posTicketNumber;
    @Column(name = "pickup_name", length = 160)
    private String pickupName;
    @Column(name = "delivery_zone_id")
    private UUID deliveryZoneId;
    @Column(name = "pos_order_version", nullable = false)
    private int posOrderVersion;
    @Column(name = "pos_opened_at")
    private java.time.Instant posOpenedAt;
    @Column(name = "pos_closed_at")
    private java.time.Instant posClosedAt;
    @Column(name = "pos_merged_into_order_id")
    private UUID posMergedIntoOrderId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();
}
