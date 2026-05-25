package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
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

    @Column(name = "product_name_snapshot", nullable = false, length = 180)
    private String productNameSnapshot;
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 3)
    private BigDecimal unitPrice;
    @Column(nullable = false)
    private int quantity;
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal total;
}
