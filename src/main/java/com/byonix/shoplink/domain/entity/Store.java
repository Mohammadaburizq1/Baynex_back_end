package com.byonix.shoplink.domain.entity;

import com.byonix.shoplink.domain.enums.StoreStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "stores")
public class Store extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(length = 1200)
    private String description;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;
    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;
    @Column(length = 40)
    private String phone;
    @Column(name = "whatsapp_number", length = 40)
    private String whatsappNumber;
    @Column(length = 255)
    private String email;
    @Column(length = 500)
    private String address;
    @Column(length = 120)
    private String city;
    @Column(length = 120)
    private String country;
    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;
    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;
    @Column(name = "primary_color", length = 20)
    private String primaryColor;
    @Column(name = "secondary_color", length = 20)
    private String secondaryColor;
    @Column(name = "category_slug", nullable = false, length = 120)
    private String categorySlug;
    @Column(name = "sub_category_slug", length = 120)
    private String subCategorySlug;
    @Column(name = "template_key", length = 120)
    private String templateKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StoreStatus status = StoreStatus.DRAFT;

    @Column(name = "free_delivery_threshold", precision = 12, scale = 3)
    private BigDecimal freeDeliveryThreshold;
    @Column(name = "default_estimated_time", length = 60)
    private String defaultEstimatedTime;
    @Column(name = "pickup_available", nullable = false)
    private boolean pickupAvailable = true;
}
