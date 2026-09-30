package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/** POS-26: a floor area of a restaurant (Main Hall, Terrace…). Store scoped; deactivated, never deleted. */
@Getter
@Setter
@Entity
@Table(name = "restaurant_areas")
public class RestaurantArea extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;
}
