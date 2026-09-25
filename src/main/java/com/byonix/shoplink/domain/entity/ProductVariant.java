package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One purchasable combination of a product's option values (M / Black). For a product with
 * variants this — not the product row — carries the price, SKU and stock that checkout uses.
 * Stock follows the same convention as {@link Product#getStock()}: null means "not tracked".
 */
@Getter
@Setter
@Entity
@Table(name = "product_variants")
public class ProductVariant extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Ids of the chosen option values in option order, comma-joined — see V28 for why ids. */
    @Column(name = "options_key", nullable = false, length = 140)
    private String optionsKey;

    @Column(length = 120)
    private String sku;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal price;

    @Column(name = "sale_price", precision = 12, scale = 3)
    private BigDecimal salePrice;

    @Column
    private Integer stock;

    // Null = use the store-wide default (app.inventory.default-low-stock-threshold).
    @Column(name = "low_stock_threshold")
    private Integer lowStockThreshold;

    @Column(name = "is_available", nullable = false)
    private boolean available = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @ManyToMany
    @JoinTable(name = "product_variant_values",
            joinColumns = @JoinColumn(name = "variant_id"),
            inverseJoinColumns = @JoinColumn(name = "option_value_id"))
    private Set<ProductOptionValue> optionValues = new HashSet<>();

    /** The values in option order ("M", "Black") — the order the product's options are declared in. */
    public List<ProductOptionValue> orderedValues() {
        return optionValues.stream()
                .sorted(Comparator.comparingInt((ProductOptionValue v) -> v.getOption().getSortOrder())
                        .thenComparing(v -> v.getOption().getName()))
                .toList();
    }

    /** Human label, e.g. "M / Black". */
    public String label() {
        return orderedValues().stream().map(ProductOptionValue::getLabel).collect(Collectors.joining(" / "));
    }

    /** What the customer actually pays for one unit right now. */
    public BigDecimal effectivePrice() {
        return salePrice != null ? salePrice : price;
    }

    /** Purchasable right now: switched on, and either untracked or with units left. */
    public boolean inStock() {
        return available && (stock == null || stock > 0);
    }
}
