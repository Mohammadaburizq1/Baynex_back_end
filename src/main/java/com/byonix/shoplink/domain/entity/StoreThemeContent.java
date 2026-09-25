package com.byonix.shoplink.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "store_theme_content")
public class StoreThemeContent extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false, unique = true)
    private Store store;

    // Opaque to the backend by design — the frontend owns the TemplateContent /
    // ClothingTemplateContent shape, this just persists whatever it sends.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_content", nullable = false, columnDefinition = "jsonb")
    private JsonNode draftContent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "published_content", columnDefinition = "jsonb")
    private JsonNode publishedContent;

    @Column(name = "published_version", nullable = false)
    private int publishedVersion = 0;

    @Column(name = "draft_updated_at")
    private Instant draftUpdatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;
}
