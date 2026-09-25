package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One axis of variation for a product ("Size", "Color"); up to three per product. */
@Getter
@Setter
@Entity
@Table(name = "product_options")
public class ProductOption extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @OneToMany(mappedBy = "option", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, label ASC")
    private List<ProductOptionValue> values = new ArrayList<>();
}
