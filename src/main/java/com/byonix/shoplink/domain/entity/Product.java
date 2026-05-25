package com.byonix.shoplink.domain.entity;

import com.byonix.shoplink.domain.enums.ProductType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "products")
public class Product extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "name_en", nullable = false, length = 180)
    private String nameEn;
    @Column(name = "name_ar", length = 180)
    private String nameAr;
    @Column(nullable = false, length = 140)
    private String slug;
    @Column(length = 2000)
    private String description;
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal price;
    @Column(name = "sale_price", precision = 12, scale = 3)
    private BigDecimal salePrice;
    @Column(nullable = false, length = 3)
    private String currency = "JOD";
    @Column(name = "image_url", length = 500)
    private String imageUrl;
    @Column(name = "gallery_json", columnDefinition = "text")
    private String galleryJson;
    @Column(length = 120)
    private String sku;
    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 30)
    private ProductType productType = ProductType.PRODUCT;
    @Column(name = "is_available", nullable = false)
    private boolean available = true;
    @Column(name = "is_featured", nullable = false)
    private boolean featured = false;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
