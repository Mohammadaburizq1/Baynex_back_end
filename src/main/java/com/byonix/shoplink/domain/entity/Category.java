package com.byonix.shoplink.domain.entity;

import com.byonix.shoplink.domain.enums.CategoryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "categories")
public class Category extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(name = "name_en", nullable = false, length = 160)
    private String nameEn;
    @Column(name = "name_ar", length = 160)
    private String nameAr;
    @Column(nullable = false, length = 120)
    private String slug;
    @Column(length = 800)
    private String description;
    @Column(length = 120)
    private String icon;
    @Column(name = "image_url", length = 500)
    private String imageUrl;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
    @Enumerated(EnumType.STRING)
    @Column(name = "category_type", nullable = false, length = 30)
    private CategoryType categoryType;
}
