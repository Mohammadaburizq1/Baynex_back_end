package com.byonix.shoplink.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bulk product import (Excel/CSV): preview, then confirm. */
public final class ProductImportDtos {
    private ProductImportDtos() {}

    /** level ERROR (the row is not imported) or WARNING (imported, with this caveat). column = the template column, if one applies. */
    public record Issue(String level, String code, String message, String column) {}

    /**
     * One spreadsheet row as the server read it. action: CREATE | UPDATE | SKIP | NONE (not imported
     * because of an error). status: VALID | WARNING | ERROR.
     */
    public record PreviewRow(int row, String nameEn, String nameAr, String sku, String barcode, String category, String categoryResolved,
                             BigDecimal price, BigDecimal salePrice, Integer stock, String imageStatus, int images,
                             String action, String status, UUID matchedProductId, List<Issue> issues) {}

    public record Summary(int totalRows, int valid, int warnings, int errors, int existingMatched, int newProducts,
                          int toCreate, int toUpdate, int toSkip, int images) {}

    /** canImport: false when nothing would be imported (or, in ALL_OR_NOTHING mode, when any row has an error). */
    public record PreviewResponse(UUID sessionId, String fileName, String imagesFileName, String existingStrategy, String mode,
                                  boolean createCategories, String currency, Instant expiresAt, Summary summary, List<String> columns,
                                  List<Issue> fileIssues, List<PreviewRow> rows, List<String> unmatchedImages, boolean canImport) {}

    /** outcome: CREATED | UPDATED | SKIPPED | FAILED. */
    public record ResultRow(int row, String nameEn, String sku, String barcode, String outcome, UUID productId,
                            int imagesAttached, int imageFailures, List<Issue> issues) {}

    /** replayed = this import had already run (a double click or retry); nothing was done again. */
    public record ResultResponse(UUID sessionId, boolean replayed, String status, int created, int updated, int skipped, int failed,
                                 int imagesAttached, int imageFailures, List<ResultRow> rows) {}
}
