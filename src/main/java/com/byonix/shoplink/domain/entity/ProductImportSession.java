package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A bulk product import (Excel/CSV): previewed, then confirmed once. The spreadsheet is not stored —
 * only its hash, the chosen options, the counts and the per-row report (see V40).
 */
@Getter
@Setter
@Entity
@Table(name = "product_import_sessions")
public class ProductImportSession extends BaseAuditable {
    public enum Status { PREVIEWED, IMPORTING, COMPLETED, FAILED }

    /** What to do with a row that matches an existing product (by barcode, then SKU). */
    public enum ExistingStrategy { SKIP, UPDATE, FAIL }

    /** VALID_ROWS_ONLY: every valid row is saved on its own; ALL_OR_NOTHING: any error saves nothing. */
    public enum Mode { VALID_ROWS_ONLY, ALL_OR_NOTHING }

    @Id
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Column(name = "images_file_name", length = 255)
    private String imagesFileName;

    @Column(name = "images_sha256", length = 64)
    private String imagesSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "existing_strategy", nullable = false, length = 20)
    private ExistingStrategy existingStrategy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Mode mode;

    @Column(name = "create_categories", nullable = false)
    private boolean createCategories;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;
    @Column(name = "created_count", nullable = false)
    private int createdCount;
    @Column(name = "updated_count", nullable = false)
    private int updatedCount;
    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;
    @Column(name = "failed_count", nullable = false)
    private int failedCount;
    @Column(name = "images_attached", nullable = false)
    private int imagesAttached;
    @Column(name = "image_failures", nullable = false)
    private int imageFailures;

    @Column(name = "report_json", columnDefinition = "text")
    private String reportJson;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
