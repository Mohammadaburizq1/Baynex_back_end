package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "store_business_hours", uniqueConstraints = @UniqueConstraint(name = "uq_store_business_hours_day", columnNames = {"store_id", "day_of_week"}))
public class StoreBusinessHour extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    @Column(name = "is_closed", nullable = false)
    private boolean closed;

    @Column(name = "open_24_hours", nullable = false)
    private boolean open24Hours;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;
}
