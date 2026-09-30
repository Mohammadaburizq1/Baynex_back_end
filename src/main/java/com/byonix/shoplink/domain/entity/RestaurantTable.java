package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/**
 * POS-26: a table. Identity is the id, never the name (T1 can be renamed). Its status is not stored:
 * it is derived from the open restaurant orders on it. Deactivated, never deleted once used.
 */
@Getter
@Setter
@Entity
@Table(name = "restaurant_tables")
public class RestaurantTable extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "area_id", nullable = false)
    private UUID areaId;

    @Column(nullable = false, length = 40)
    private String name;

    private Integer capacity;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;
}
