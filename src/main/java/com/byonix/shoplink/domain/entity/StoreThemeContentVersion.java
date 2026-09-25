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

/** One immutable snapshot per publish — the rollback trail for {@link StoreThemeContent}. */
@Getter
@Setter
@Entity
@Table(name = "store_theme_content_versions")
public class StoreThemeContentVersion {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode content;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;
}
