package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "store_templates")
public class StoreTemplate extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "category_slug", nullable = false, length = 120)
    private String categorySlug;
    @Column(name = "sub_category_slug", length = 120)
    private String subCategorySlug;
    @Column(name = "template_key", nullable = false, unique = true, length = 120)
    private String templateKey;
    @Column(nullable = false, length = 160)
    private String name;
    @Column(length = 800)
    private String description;
    @Column(name = "preview_image_url", length = 500)
    private String previewImageUrl;
    @Column(name = "is_default", nullable = false)
    private boolean defaultTemplate;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
