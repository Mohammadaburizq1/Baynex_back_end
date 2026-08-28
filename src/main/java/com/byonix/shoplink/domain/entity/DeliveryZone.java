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
@Table(name = "delivery_zones")
public class DeliveryZone extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 160)
    private String name;

    /** Comma-separated area names — matches how the dashboard UI collects/displays them. */
    @Column(columnDefinition = "text")
    private String areas;

    @Column(name = "min_order", nullable = false, precision = 12, scale = 3)
    private BigDecimal minOrder = BigDecimal.ZERO;

    @Column(name = "delivery_fee", nullable = false, precision = 12, scale = 3)
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    @Column(name = "estimated_time", length = 60)
    private String estimatedTime;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
