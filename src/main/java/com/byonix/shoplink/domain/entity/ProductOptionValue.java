package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/** One choice on a {@link ProductOption} ("M" on Size). */
@Getter
@Setter
@Entity
@Table(name = "product_option_values")
public class ProductOptionValue {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id", nullable = false)
    private ProductOption option;

    @Column(nullable = false, length = 80)
    private String label;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
