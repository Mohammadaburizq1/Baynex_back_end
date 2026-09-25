package com.byonix.shoplink.domain.entity;

import com.byonix.shoplink.domain.enums.DeliveryMethod;
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

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();
}
