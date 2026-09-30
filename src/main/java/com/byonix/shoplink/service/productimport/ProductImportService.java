package com.byonix.shoplink.service.productimport;

import com.byonix.shoplink.api.dto.CategoryDtos;
import com.byonix.shoplink.api.dto.ImageDtos;
import com.byonix.shoplink.api.dto.InventoryDtos;
import com.byonix.shoplink.api.dto.ProductDtos;
import com.byonix.shoplink.api.dto.ProductImportDtos.Issue;
import com.byonix.shoplink.api.dto.ProductImportDtos.PreviewResponse;
import com.byonix.shoplink.api.dto.ProductImportDtos.PreviewRow;
import com.byonix.shoplink.api.dto.ProductImportDtos.ResultResponse;
import com.byonix.shoplink.api.dto.ProductImportDtos.ResultRow;
import com.byonix.shoplink.api.dto.ProductImportDtos.Summary;
import com.byonix.shoplink.common.ConflictException;
import com.byonix.shoplink.domain.entity.Category;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.ProductImage;
import com.byonix.shoplink.domain.entity.ProductImportSession;
import com.byonix.shoplink.domain.entity.ProductImportSession.ExistingStrategy;
import com.byonix.shoplink.domain.entity.ProductImportSession.Mode;
import com.byonix.shoplink.domain.entity.ProductImportSession.Status;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.CategoryType;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.InventoryAdjustmentReason;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.domain.enums.ProductType;
import com.byonix.shoplink.media.MediaStorage;
import com.byonix.shoplink.repository.CategoryRepository;
import com.byonix.shoplink.repository.ProductImageRepository;
import com.byonix.shoplink.repository.ProductImportSessionRepository;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.repository.ProductVariantRepository;
import com.byonix.shoplink.service.CatalogService;
import com.byonix.shoplink.service.CurrentUserService;
import com.byonix.shoplink.service.InventoryService;
import com.byonix.shoplink.service.ProductImageService;
import com.byonix.shoplink.service.StoreService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Bulk product import from a spreadsheet: preview → confirm.
 *
 * Imported rows become ordinary products through the same {@link CatalogService#createProduct} /
 * {@link CatalogService#updateProduct} calls the product editor uses (slug, SKU, barcode and category
 * rules, store access, the stock ledger), with the same Bean Validation constraints the controller
 * applies, so a spreadsheet cannot do anything the editor could not. Pictures go through
 * {@link ProductImageService} (URLs are stored, never fetched) and, for a ZIP, {@link MediaStorage}.
 *
 * The server is authoritative on both steps: confirm re-reads the same file (checked by SHA-256) and
 * re-validates every row against the database as it is at that moment. Nothing from the preview the
 * browser holds is trusted.
 */
@Slf4j
@Service
public class ProductImportService {
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    public static final int MAX_ROWS = 2000;
    static final int MAX_IMAGES_PER_PRODUCT = 12; // ProductImageService.MAX_IMAGES
    static final int URL_COLUMNS = 3;
    static final Duration SESSION_TTL = Duration.ofHours(2);
    private static final Pattern IMAGE_URL = Pattern.compile("^https?://.{3,480}$"); // ImageDtos.ImageRequest
    private static final Pattern BARCODE = Pattern.compile("^[!-~]{1,64}$"); // ProductDtos.ProductRequest
    private static final Pattern DECIMAL = Pattern.compile("^-?(\\d+(\\.\\d*)?|\\.\\d+)$");
    private static final Pattern INTEGER = Pattern.compile("^-?\\d+(\\.0+)?$");

    private final StoreService storeService;
    private final CurrentUserService currentUser;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository imageRepository;
    private final ProductImportSessionRepository sessions;
    private final CatalogService catalogService;
    private final ProductImageService imageService;
    private final InventoryService inventoryService;
    private final MediaStorage mediaStorage;
    private final Validator validator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final String publicBaseUrl;

    public ProductImportService(StoreService storeService, CurrentUserService currentUser, ProductRepository productRepository,
                                ProductVariantRepository variantRepository, CategoryRepository categoryRepository,
                                ProductImageRepository imageRepository, ProductImportSessionRepository sessions,
                                CatalogService catalogService, ProductImageService imageService, InventoryService inventoryService,
                                MediaStorage mediaStorage, Validator validator, ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager,
                                @Value("${app.media.public-base-url}") String publicBaseUrl) {
        this.storeService = storeService;
        this.currentUser = currentUser;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.categoryRepository = categoryRepository;
        this.imageRepository = imageRepository;
        this.sessions = sessions;
        this.catalogService = catalogService;
        this.imageService = imageService;
        this.inventoryService = inventoryService;
        this.mediaStorage = mediaStorage;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    // ══ Template ══════════════════════════════════════════════════════════════════════════════════

    private static final List<String> TEMPLATE_HEADER = Arrays.stream(ImportColumns.values()).map(ImportColumns::template).toList();

    public byte[] template() {
        currentUser.requireMerchantOrAdmin();
        Set<Integer> text = Set.of(ImportColumns.SKU.ordinal(), ImportColumns.BARCODE.ordinal());
        List<Integer> widths = List.of(26, 22, 30, 16, 16, 18, 10, 11, 8, 20, 10, 10, 14, 10, 40, 40, 40);
        List<List<String>> example = List.of(
                List.of("Coca Cola 330ml", "كوكا كولا ٣٣٠ مل", "Chilled can", "Drinks", "COKE-330", "5449000000996", "0.750", "", "48", "10", "yes", "no", "PRODUCT", "", "https://example.com/coke.jpg", "", ""),
                List.of("Still Water 500ml", "مياه ٥٠٠ مل", "", "Drinks", "WATER-500", "6251234567890", "0.350", "0.300", "120", "", "yes", "no", "PRODUCT", "", "", "", ""),
                List.of("Classic Burger", "برجر كلاسيك", "Beef, cheese, pickles", "Mains", "BURGER-1", "", "5.250", "", "", "", "yes", "yes", "FOOD_ITEM", "", "", "", ""));
        List<List<String>> help = new ArrayList<>();
        help.add(List.of("name_en", "Required for new products", "English name, up to 180 characters. Also accepted: name, product_name."));
        help.add(List.of("name_ar", "Optional", "Arabic name. Also accepted: arabic_name."));
        help.add(List.of("description", "Optional", "Up to 2000 characters."));
        help.add(List.of("category", "Optional", "An existing category name of this store (or Parent > Child). Not found: the product stays uncategorized, unless you tick \"Create missing categories\"."));
        help.add(List.of("sku", "Optional", "Your item code; unique in your store (products and variants). Also accepted: item_code."));
        help.add(List.of("barcode", "Optional", "EAN/UPC/any code the POS scans, 1-64 characters, no spaces; unique in your store. Keep this column formatted as Text. Also accepted: ean, upc."));
        help.add(List.of("price", "Required for new products", "Store currency, up to 3 decimals, dot as decimal separator (5.250). Also accepted: regular_price."));
        help.add(List.of("sale_price", "Optional", "Discounted price. Also accepted: discount_price."));
        help.add(List.of("stock", "Optional", "Whole number, 0 or more. Empty = stock not tracked. Ignored for SERVICE. Also accepted: quantity, qty."));
        help.add(List.of("low_stock_threshold", "Optional", "Whole number; empty = the store default."));
        help.add(List.of("available", "Optional", "yes / no (default yes)."));
        help.add(List.of("featured", "Optional", "yes / no (default no)."));
        help.add(List.of("product_type", "Optional", "PRODUCT (default), FOOD_ITEM or SERVICE."));
        help.add(List.of("currency", "Optional", "If filled, must be your store's currency; prices are always in the store currency."));
        help.add(List.of("image_url, image_2_url, image_3_url", "Optional", "https:// links to pictures; the first is the main picture. Links are saved as given (not downloaded)."));
        help.add(List.of("Pictures ZIP", "Optional", "Upload a .zip with files named after the SKU (COKE-330.jpg, COKE-330_2.jpg …) or, without an SKU, the barcode. JPEG, PNG, WebP or GIF, up to 5 MB each."));
        help.add(List.of("Existing products", "", "A row matches an existing product by barcode, then SKU (never by name). You choose: skip it, update it, or treat it as an error."));
        help.add(List.of("Updating", "", "Only filled cells change a product; an empty cell keeps the current value. Stock is set through the stock history (as a correction)."));
        return XlsxWriter.write(List.of(
                new XlsxWriter.Sheet("Products", TEMPLATE_HEADER, List.of(), text, widths),
                new XlsxWriter.Sheet("Example", TEMPLATE_HEADER, example, text, widths),
                new XlsxWriter.Sheet("Instructions", List.of("Column", "Required?", "What to put in it"), help, Set.of(), List.of(34, 26, 110))));
    }

    // ══ Preview ═══════════════════════════════════════════════════════════════════════════════════

    public PreviewResponse preview(UUID storeId, MultipartFile file, MultipartFile imagesZip, ExistingStrategy strategy, Mode mode, boolean createCategories) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.PRODUCTS, PermissionLevel.EDIT);
        Upload upload = Upload.of(file, imagesZip);
        ExistingStrategy s = strategy == null ? ExistingStrategy.SKIP : strategy;
        Mode m = mode == null ? Mode.VALID_ROWS_ONLY : mode;
        Plan plan = readTx.execute(st -> plan(store.getId(), upload, s, createCategories));
        List<PreviewRow> rows = plan.rows.stream().map(RowPlan::preview).toList();
        Summary summary = summary(plan);
        boolean canImport = summary.toCreate() + summary.toUpdate() > 0 && (m == Mode.VALID_ROWS_ONLY || summary.errors() == 0);

        ProductImportSession session = new ProductImportSession();
        session.setId(UUID.randomUUID());
        session.setStore(store);
        session.setUserId(currentUser.user().getId());
        session.setFileName(upload.fileName);
        session.setFileSha256(upload.fileHash);
        session.setImagesFileName(upload.imagesName);
        session.setImagesSha256(upload.imagesHash);
        session.setExistingStrategy(s);
        session.setMode(m);
        session.setCreateCategories(createCategories);
        session.setStatus(Status.PREVIEWED);
        session.setTotalRows(plan.rows.size());
        session.setExpiresAt(Instant.now().plus(SESSION_TTL));
        session.setReportJson(json(new ReportData(reportLines(rows), null)));
        tx.executeWithoutResult(st -> sessions.save(session));
        return new PreviewResponse(session.getId(), upload.fileName, upload.imagesName, s.name(), m.name(), createCategories, plan.currency,
                session.getExpiresAt(), summary, plan.columns, plan.fileIssues, rows, plan.images.unmatched(), canImport);
    }

    private static Summary summary(Plan plan) {
        int valid = 0, warnings = 0, errors = 0, matched = 0, create = 0, update = 0, skip = 0, images = 0;
        for (RowPlan r : plan.rows) {
            switch (r.status()) {
                case "ERROR" -> errors++;
                case "WARNING" -> warnings++;
                default -> valid++;
            }
            if (r.match != null) matched++;
            switch (r.action()) {
                case "CREATE" -> create++;
                case "UPDATE" -> update++;
                case "SKIP" -> skip++;
                default -> { }
            }
            if (!r.hasError() && !"SKIP".equals(r.action())) images += r.imageCount();
        }
        return new Summary(plan.rows.size(), valid, warnings, errors, matched, create, create, update, skip, images);
    }

    // ══ Confirm ═══════════════════════════════════════════════════════════════════════════════════

    public ResultResponse confirm(UUID sessionId, MultipartFile file, MultipartFile imagesZip) {
        ProductImportSession session = readTx.execute(st -> sessions.findById(sessionId).orElse(null));
        if (session == null) throw new EntityNotFoundException("Import not found");
        Store store = storeService.accessibleStore(storeIdOf(session));
        currentUser.ensureSectionAccess(store, DashboardSection.PRODUCTS, PermissionLevel.EDIT);
        if (!session.getUserId().equals(currentUser.user().getId())) throw new AccessDeniedException("Access denied");

        if (session.getStatus() != Status.PREVIEWED) return replay(session);
        if (session.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("This preview has expired. Upload the file again to check it.");
        }
        Upload upload = Upload.of(file, imagesZip);
        if (!upload.fileHash.equals(session.getFileSha256()) || !Objects.equals(upload.imagesHash, session.getImagesSha256())) {
            throw new IllegalArgumentException("These are not the files you previewed. Preview them again before importing.");
        }
        // Exactly one confirmation runs: the others (double click, retried request) see it claimed.
        Integer claimed = tx.execute(st -> sessions.claim(sessionId, Instant.now()));
        if (claimed == null || claimed == 0) {
            ProductImportSession now = readTx.execute(st -> sessions.findById(sessionId).orElseThrow());
            if (now.getStatus() == Status.IMPORTING) throw new ConflictException("This import is already running");
            return replay(now);
        }

        ResultResponse result;
        try {
            Plan plan = readTx.execute(st -> plan(store.getId(), upload, session.getExistingStrategy(), session.isCreateCategories()));
            result = session.getMode() == Mode.ALL_OR_NOTHING ? runAllOrNothing(store, session, plan) : runValidRows(store, session, plan);
        } catch (RuntimeException e) {
            log.warn("Product import {} failed: {}", sessionId, e.getClass().getSimpleName());
            finish(sessionId, Status.FAILED, null);
            throw e;
        }
        finish(sessionId, "FAILED".equals(result.status()) ? Status.FAILED : Status.COMPLETED, result);
        log.info("Product import {} store={} created={} updated={} skipped={} failed={} images={}",
                sessionId, store.getId(), result.created(), result.updated(), result.skipped(), result.failed(), result.imagesAttached());
        return result;
    }

    private UUID storeIdOf(ProductImportSession session) {
        return readTx.execute(st -> sessions.findById(session.getId()).orElseThrow().getStore().getId());
    }

    private ResultResponse replay(ProductImportSession session) {
        ReportData data = session.getReportJson() == null ? null : objectMapper.readValue(session.getReportJson(), ReportData.class);
        if (data != null && data.result() != null) {
            ResultResponse r = data.result();
            return new ResultResponse(r.sessionId(), true, r.status(), r.created(), r.updated(), r.skipped(), r.failed(), r.imagesAttached(), r.imageFailures(), r.rows());
        }
        if (session.getStatus() == Status.IMPORTING) throw new ConflictException("This import is already running");
        throw new IllegalArgumentException("This import did not finish. Upload the file again to check it.");
    }

    private void finish(UUID sessionId, Status status, ResultResponse result) {
        tx.executeWithoutResult(st -> {
            ProductImportSession s = sessions.findById(sessionId).orElseThrow();
            s.setStatus(status);
            s.setCompletedAt(Instant.now());
            if (result != null) {
                s.setCreatedCount(result.created());
                s.setUpdatedCount(result.updated());
                s.setSkippedCount(result.skipped());
                s.setFailedCount(result.failed());
                s.setImagesAttached(result.imagesAttached());
                s.setImageFailures(result.imageFailures());
                s.setReportJson(json(new ReportData(resultLines(result.rows()), result)));
            }
        });
    }

    /** Every valid row is saved in its own transaction: one failing row never undoes the others. */
    private ResultResponse runValidRows(Store store, ProductImportSession session, Plan plan) {
        Map<String, UUID> createdCategories = new HashMap<>();
        List<ResultRow> out = new ArrayList<>();
        for (RowPlan r : plan.rows) {
            if (r.hasError()) {
                out.add(r.result("FAILED", null, 0, r.zipFailures()));
            } else if ("SKIP".equals(r.action())) {
                out.add(r.result("SKIPPED", r.match.getId(), 0, 0));
            } else {
                try {
                    out.add(tx.execute(st -> apply(store, session, r, createdCategories)));
                } catch (RuntimeException e) {
                    r.issues.add(error("SAVE_FAILED", safeMessage(e), null));
                    out.add(r.result("FAILED", null, 0, r.zipFailures()));
                }
            }
        }
        return result(session, out, "COMPLETED");
    }

    /** Nothing is saved unless every row is valid and every row saves. */
    private ResultResponse runAllOrNothing(Store store, ProductImportSession session, Plan plan) {
        if (plan.rows.stream().anyMatch(RowPlan::hasError)) {
            List<ResultRow> out = new ArrayList<>();
            for (RowPlan r : plan.rows) {
                if (!r.hasError()) r.issues.add(error("NOT_IMPORTED", "Not imported: other rows have errors and \"all or nothing\" was chosen", null));
                out.add(r.result("FAILED", null, 0, 0));
            }
            return result(session, out, "FAILED");
        }
        Map<String, UUID> createdCategories = new HashMap<>();
        int[] at = {-1};
        try {
            List<ResultRow> out = tx.execute(st -> {
                List<ResultRow> rows = new ArrayList<>();
                for (int i = 0; i < plan.rows.size(); i++) {
                    at[0] = i;
                    RowPlan r = plan.rows.get(i);
                    rows.add("SKIP".equals(r.action()) ? r.result("SKIPPED", r.match.getId(), 0, 0) : apply(store, session, r, createdCategories));
                }
                return rows;
            });
            return result(session, out, "COMPLETED");
        } catch (RuntimeException e) {
            List<ResultRow> out = new ArrayList<>();
            for (int i = 0; i < plan.rows.size(); i++) {
                RowPlan r = plan.rows.get(i);
                r.issues.add(i == at[0]
                        ? error("SAVE_FAILED", safeMessage(e), null)
                        : error("NOT_IMPORTED", "Not imported: row " + plan.rows.get(Math.max(at[0], 0)).row + " failed and \"all or nothing\" was chosen", null));
                out.add(r.result("FAILED", null, 0, 0));
            }
            return result(session, out, "FAILED");
        }
    }

    private ResultResponse result(ProductImportSession session, List<ResultRow> rows, String status) {
        int created = 0, updated = 0, skipped = 0, failed = 0, images = 0, imageFailures = 0;
        for (ResultRow r : rows) {
            switch (r.outcome()) {
                case "CREATED" -> created++;
                case "UPDATED" -> updated++;
                case "SKIPPED" -> skipped++;
                default -> failed++;
            }
            images += r.imagesAttached();
            imageFailures += r.imageFailures();
        }
        return new ResultResponse(session.getId(), false, status, created, updated, skipped, failed, images, imageFailures, rows);
    }

    /** Saves one row (inside the caller's transaction) through the product editor's own service calls. */
    private ResultRow apply(Store store, ProductImportSession session, RowPlan r, Map<String, UUID> createdCategories) {
        UUID categoryId = r.category == null ? null : r.category.getId();
        if (r.categoryToCreate != null) categoryId = createCategoryPath(store, r.categoryToCreate, createdCategories);
        UUID productId;
        boolean created = "CREATE".equals(r.action());
        if (created) {
            ProductDtos.ProductRequest req = r.createRequest(store.getId(), categoryId);
            validate(req);
            productId = catalogService.createProduct(req).id();
        } else {
            Product p = productRepository.findByIdAndStore_Id(r.match.getId(), store.getId())
                    .orElseThrow(() -> new EntityNotFoundException("The matched product no longer exists"));
            ProductDtos.ProductRequest req = r.updateRequest(p, categoryId);
            validate(req);
            catalogService.updateProduct(p.getId(), req);
            productId = p.getId();
            if (r.stock != null && r.effectiveType(p) != ProductType.SERVICE && !r.stock.equals(p.getStock())) {
                // After creation stock only moves through the ledger: an import sets it as a correction.
                inventoryService.adjust(new InventoryDtos.AdjustRequest(p.getId(), null, InventoryDtos.AdjustMode.SET, r.stock,
                        InventoryAdjustmentReason.CORRECTION, truncate("Bulk import " + session.getFileName() + ", row " + r.row, 300)));
            }
        }
        // Pictures: links as given, ZIP files saved through the media storage. A picture that fails is
        // reported; it never undoes the product.
        int attached = 0;
        int failures = r.zipFailures();
        if (r.imageCount() > 0) {
            List<ImageDtos.ImageRequest> images = new ArrayList<>();
            r.imageUrls.forEach(u -> images.add(new ImageDtos.ImageRequest(null, u, null)));
            for (ImportImages.ZipImage z : r.zipImages) {
                if (z.type() == null || z.tooLarge()) continue;
                try {
                    String name = mediaStorage.save(store.getId(), z.type(), z.bytes());
                    images.add(new ImageDtos.ImageRequest(null, publicBaseUrl + "/media/" + store.getId() + "/" + name, null));
                } catch (IOException | RuntimeException e) {
                    failures++;
                    r.issues.add(warning("IMAGE_SAVE_FAILED", "The picture " + z.fileName() + " could not be stored", null));
                }
            }
            List<ImageDtos.ImageRequest> capped = images.size() > MAX_IMAGES_PER_PRODUCT ? images.subList(0, MAX_IMAGES_PER_PRODUCT) : images;
            List<String> current = imageRepository.findByProduct_IdInOrderBySortOrderAsc(List.of(productId)).stream().map(ProductImage::getUrl).toList();
            List<String> wanted = capped.stream().map(ImageDtos.ImageRequest::url).toList();
            if (!capped.isEmpty() && !current.equals(wanted)) {
                imageService.save(productId, new ImageDtos.SaveImagesRequest(new ArrayList<>(capped)));
                attached = capped.size();
            }
        }
        return r.result(created ? "CREATED" : "UPDATED", productId, attached, failures);
    }

    private UUID createCategoryPath(Store store, List<String> path, Map<String, UUID> cache) {
        UUID parent = null;
        StringBuilder key = new StringBuilder();
        for (String segment : path) {
            key.append(segment.toLowerCase(Locale.ROOT)).append('>');
            UUID known = cache.get(key.toString());
            if (known == null) {
                UUID parentId = parent;
                known = categoryRepository.findByStore_IdOrderBySortOrderAscNameEnAsc(store.getId()).stream()
                        .filter(c -> c.getNameEn().equalsIgnoreCase(segment)
                                && Objects.equals(c.getParent() == null ? null : c.getParent().getId(), parentId))
                        .map(Category::getId).findFirst().orElse(null);
                if (known == null) {
                    String slug = uniqueSlug(slugify(segment, "category"), s -> categoryRepository.existsByStore_IdAndSlug(store.getId(), s), Set.of());
                    known = catalogService.createCategory(new CategoryDtos.CategoryRequest(store.getId(), parent, segment, null, slug, null, null, null,
                            0, true, CategoryType.PRODUCT)).id();
                }
                cache.put(key.toString(), known);
            }
            parent = known;
        }
        return parent;
    }

    private void validate(Object request) {
        Set<ConstraintViolation<Object>> v = validator.validate(request);
        if (!v.isEmpty()) {
            ConstraintViolation<Object> first = v.iterator().next();
            throw new IllegalArgumentException(first.getPropertyPath() + ": " + first.getMessage());
        }
    }

    private static String safeMessage(RuntimeException e) {
        if (e instanceof ConflictException || e instanceof IllegalArgumentException || e instanceof EntityNotFoundException) {
            return e.getMessage();
        }
        if (e instanceof AccessDeniedException) return "You are not allowed to change this product";
        return "This row could not be saved";
    }

    // ══ Error report ══════════════════════════════════════════════════════════════════════════════

    public record ReportFile(String fileName, String contentType, byte[] bytes) {}

    public ReportFile report(UUID sessionId, String format) {
        ProductImportSession session = readTx.execute(st -> sessions.findById(sessionId).orElse(null));
        if (session == null) throw new EntityNotFoundException("Import not found");
        Store store = storeService.accessibleStore(storeIdOf(session));
        currentUser.ensureSectionAccess(store, DashboardSection.PRODUCTS, PermissionLevel.VIEW);
        ReportData data = objectMapper.readValue(session.getReportJson(), ReportData.class);
        List<String> header = List.of("row", "sku", "barcode", "name", "status", "error_code", "error_message");
        List<List<String>> lines = data.lines().stream()
                .map(l -> Arrays.asList(String.valueOf(l.row()), l.sku(), l.barcode(), l.name(), l.status(), l.code(), l.message())).toList();
        String base = "import-report-" + session.getId().toString().substring(0, 8);
        if ("xlsx".equalsIgnoreCase(format)) {
            return new ReportFile(base + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    XlsxWriter.write(List.of(new XlsxWriter.Sheet("Report", header, lines, Set.of(1, 2), List.of(6, 18, 18, 30, 10, 26, 80)))));
        }
        StringBuilder csv = new StringBuilder("﻿"); // BOM: Excel then reads Arabic names correctly
        csv.append(String.join(",", header)).append("\r\n");
        for (List<String> l : lines) {
            StringJoiner j = new StringJoiner(",");
            l.forEach(v -> j.add(csvCell(v)));
            csv.append(j).append("\r\n");
        }
        return new ReportFile(base + ".csv", "text/csv; charset=utf-8", csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Quoted, and a leading = + - @ neutralised so a spreadsheet never runs a cell as a formula (CSV injection). */
    static String csvCell(String v) {
        if (v == null) return "";
        String s = !v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 ? "'" + v : v;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    public record ReportLine(int row, String sku, String barcode, String name, String status, String code, String message) {}

    public record ReportData(List<ReportLine> lines, ResultResponse result) {}

    private static List<ReportLine> reportLines(List<PreviewRow> rows) {
        List<ReportLine> out = new ArrayList<>();
        for (PreviewRow r : rows) {
            if (r.issues().isEmpty()) {
                out.add(new ReportLine(r.row(), r.sku(), r.barcode(), r.nameEn(), r.status(), "", r.action()));
            }
            for (Issue i : r.issues()) out.add(new ReportLine(r.row(), r.sku(), r.barcode(), r.nameEn(), i.level(), i.code(), i.message()));
        }
        return out;
    }

    private static List<ReportLine> resultLines(List<ResultRow> rows) {
        List<ReportLine> out = new ArrayList<>();
        for (ResultRow r : rows) {
            if (r.issues().isEmpty()) out.add(new ReportLine(r.row(), r.sku(), r.barcode(), r.nameEn(), r.outcome(), "", ""));
            for (Issue i : r.issues()) out.add(new ReportLine(r.row(), r.sku(), r.barcode(), r.nameEn(), r.outcome(), i.code(), i.message()));
        }
        return out;
    }

    private String json(Object o) {
        return objectMapper.writeValueAsString(o);
    }

    // ══ Planning: parse → validate → match (identical for preview and confirm) ════════════════════════

    private record Upload(String fileName, byte[] file, String fileHash, String imagesName, byte[] images, String imagesHash) {
        static Upload of(MultipartFile file, MultipartFile zip) {
            if (file == null || file.isEmpty()) throw new ImportFileException("NO_FILE", "Choose the spreadsheet to import");
            if (file.getSize() > MAX_FILE_BYTES) {
                throw new ImportFileException("FILE_TOO_LARGE", "The spreadsheet can be up to " + (MAX_FILE_BYTES / 1024 / 1024) + " MB");
            }
            try {
                byte[] bytes = file.getBytes();
                byte[] images = zip == null || zip.isEmpty() ? null : zip.getBytes();
                return new Upload(cleanName(file.getOriginalFilename(), "products.xlsx"), bytes, sha256(bytes),
                        images == null ? null : cleanName(zip.getOriginalFilename(), "images.zip"), images, images == null ? null : sha256(images));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static String cleanName(String name, String fallback) {
        if (name == null || name.isBlank()) return fallback;
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        return n.isEmpty() ? fallback : truncate(n, 200);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Plan {
        String currency;
        List<String> columns = new ArrayList<>();
        List<Issue> fileIssues = new ArrayList<>();
        List<RowPlan> rows = new ArrayList<>();
        ImportImages images = ImportImages.none();
    }

    private Plan plan(UUID storeId, Upload upload, ExistingStrategy strategy, boolean createCategories) {
        Store store = storeService.accessibleStore(storeId);
        Plan plan = new Plan();
        plan.currency = store.getCurrency();
        List<SpreadsheetReader.Row> rows = SpreadsheetReader.read(upload.fileName, upload.file, MAX_ROWS);
        if (rows.isEmpty()) throw new ImportFileException("EMPTY_FILE", "The file is empty");
        ImportColumns.Mapping mapping = ImportColumns.map(rows.get(0).cells());
        plan.columns.addAll(mapping.recognised());
        for (String ignored : mapping.ignored()) {
            plan.fileIssues.add(warning("COLUMN_IGNORED", "The column \"" + ignored + "\" is not an import column and was ignored", null));
        }
        if (rows.size() == 1) throw new ImportFileException("NO_ROWS", "The file has column headers but no product rows");
        if (upload.images != null) plan.images = ImportImages.read(upload.imagesName, upload.images);

        for (SpreadsheetReader.Row row : rows.subList(1, rows.size())) plan.rows.add(parseRow(row, mapping, store));
        markDuplicates(plan.rows);

        // What the store has now (this store only: every lookup below is by store id).
        List<Product> existing = productRepository.findByStore_IdOrderBySortOrderAscNameEnAsc(storeId);
        Map<String, Product> byBarcode = new HashMap<>();
        Map<String, Product> bySku = new HashMap<>();
        Set<String> slugs = new HashSet<>();
        for (Product p : existing) {
            if (p.getBarcode() != null) byBarcode.put(p.getBarcode(), p);
            if (p.getSku() != null) bySku.put(p.getSku().toLowerCase(Locale.ROOT), p);
            slugs.add(p.getSlug());
        }
        Set<String> variantBarcodes = new HashSet<>(variantRepository.findBarcodesByStore(storeId));
        Set<String> variantSkus = new HashSet<>(variantRepository.findLowerSkusByStore(storeId));
        List<Category> categories = categoryRepository.findByStore_IdOrderBySortOrderAscNameEnAsc(storeId).stream()
                .filter(c -> c.getCategoryType() == CategoryType.PRODUCT).toList();

        for (RowPlan r : plan.rows) {
            match(r, byBarcode, bySku, variantBarcodes, variantSkus, strategy);
            resolveCategory(r, categories, createCategories);
            attachImages(r, plan.images);
            r.checkRules();
            if ("CREATE".equals(r.action()) && !r.hasError()) {
                String base = slugify(r.nameEn, null);
                if (base == null) base = slugify(r.sku != null ? r.sku : r.barcode, "product");
                r.slug = uniqueSlug(base, slugs::contains, Set.of());
                slugs.add(r.slug);
            }
            // The same constraints the product API enforces on a request (sizes, patterns, ≥ 0).
            if (!r.hasError() && !"SKIP".equals(r.action())) {
                ProductDtos.ProductRequest req = "CREATE".equals(r.action())
                        ? r.createRequest(storeId, r.category == null ? null : r.category.getId())
                        : r.updateRequest(r.match, r.category == null ? null : r.category.getId());
                for (ConstraintViolation<ProductDtos.ProductRequest> v : validator.validate(req)) {
                    r.issues.add(error("INVALID_FIELD", v.getPropertyPath() + ": " + v.getMessage(), null));
                }
            }
        }
        List<String> unmatched = plan.images.unmatched();
        if (!unmatched.isEmpty()) {
            plan.fileIssues.add(warning("IMAGES_UNMATCHED", unmatched.size() + " file(s) in the ZIP match no SKU or barcode and will not be used", null));
        }
        return plan;
    }

    private RowPlan parseRow(SpreadsheetReader.Row row, ImportColumns.Mapping m, Store store) {
        RowPlan r = new RowPlan(row.number());
        Function3 cell = c -> {
            Integer i = m.of(c);
            String v = i == null ? null : row.cell(i);
            return v == null || v.isBlank() ? null : v.trim();
        };
        r.nameEn = limited(r, cell.get(ImportColumns.NAME_EN), 180, ImportColumns.NAME_EN);
        r.nameAr = limited(r, cell.get(ImportColumns.NAME_AR), 180, ImportColumns.NAME_AR);
        r.description = limited(r, cell.get(ImportColumns.DESCRIPTION), 2000, ImportColumns.DESCRIPTION);
        r.categoryText = cell.get(ImportColumns.CATEGORY);
        r.sku = limited(r, cell.get(ImportColumns.SKU), 120, ImportColumns.SKU);
        String barcode = cell.get(ImportColumns.BARCODE);
        if (barcode != null && !BARCODE.matcher(barcode).matches()) {
            r.issues.add(error("INVALID_BARCODE", "A barcode is 1-64 printable characters without spaces", "barcode"));
        } else {
            r.barcode = barcode;
        }
        r.price = money(r, cell.get(ImportColumns.PRICE), "price");
        r.salePrice = money(r, cell.get(ImportColumns.SALE_PRICE), "sale_price");
        r.stock = whole(r, cell.get(ImportColumns.STOCK), "stock");
        r.lowStock = whole(r, cell.get(ImportColumns.LOW_STOCK_THRESHOLD), "low_stock_threshold");
        r.available = yesNo(r, cell.get(ImportColumns.AVAILABLE), "available");
        r.featured = yesNo(r, cell.get(ImportColumns.FEATURED), "featured");
        String type = cell.get(ImportColumns.PRODUCT_TYPE);
        if (type != null) {
            try {
                r.type = ProductType.valueOf(type.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s\\-]+", "_"));
            } catch (IllegalArgumentException e) {
                r.issues.add(error("UNKNOWN_PRODUCT_TYPE", "\"" + type + "\" is not a product type (PRODUCT, FOOD_ITEM or SERVICE)", "product_type"));
            }
        }
        String currency = cell.get(ImportColumns.CURRENCY);
        if (currency != null && !currency.equalsIgnoreCase(store.getCurrency())) {
            r.issues.add(error("CURRENCY_MISMATCH", "Prices are in the store currency (" + store.getCurrency() + "), not " + currency
                    + ". Convert the prices or leave currency empty.", "currency"));
        }
        ImportColumns[] imageColumns = {ImportColumns.IMAGE_1, ImportColumns.IMAGE_2, ImportColumns.IMAGE_3};
        for (ImportColumns c : imageColumns) {
            String url = cell.get(c);
            if (url == null) continue;
            if (validImageUrl(url)) {
                r.imageUrls.add(url);
            } else {
                r.issues.add(error("INVALID_IMAGE_URL", "\"" + truncate(url, 80) + "\" is not a valid http(s) picture link", c.template()));
            }
        }
        return r;
    }

    @FunctionalInterface
    private interface Function3 {
        String get(ImportColumns c);
    }

    /** http/https only, a real host, no credentials or whitespace, and within the product image rules. */
    static boolean validImageUrl(String url) {
        if (!IMAGE_URL.matcher(url).matches() || url.chars().anyMatch(Character::isWhitespace)) return false;
        try {
            URI u = new URI(url);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null && !u.getHost().isBlank() && u.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String limited(RowPlan r, String v, int max, ImportColumns c) {
        if (v != null && v.length() > max) {
            r.issues.add(error("TOO_LONG", c.template() + " is longer than " + max + " characters", c.template()));
            return null;
        }
        return v;
    }

    /** Exact decimal (never a double): ≥ 0, at most 3 decimals, fits NUMERIC(12,3). */
    static BigDecimal money(RowPlan r, String raw, String column) {
        if (raw == null) return null;
        String s = raw.replace(" ", "").replace(" ", "");
        if (!DECIMAL.matcher(s).matches()) {
            r.issues.add(error("INVALID_DECIMAL", column + " \"" + truncate(raw, 30) + "\" is not a number (use a dot for decimals, e.g. 5.250)", column));
            return null;
        }
        BigDecimal d = new BigDecimal(s).stripTrailingZeros();
        if (d.scale() < 0) d = d.setScale(0);
        if (d.signum() < 0) {
            r.issues.add(error("NEGATIVE_PRICE", column + " cannot be negative", column));
            return null;
        }
        if (d.scale() > 3) {
            r.issues.add(error("TOO_MANY_DECIMALS", column + " has more than 3 decimals", column));
            return null;
        }
        if (d.precision() - d.scale() > 9) {
            r.issues.add(error("PRICE_TOO_LARGE", column + " is too large", column));
            return null;
        }
        return d.setScale(3);
    }

    static Integer whole(RowPlan r, String raw, String column) {
        if (raw == null) return null;
        String s = raw.replace(" ", "");
        if (!INTEGER.matcher(s).matches()) {
            r.issues.add(error("INVALID_NUMBER", column + " \"" + truncate(raw, 30) + "\" must be a whole number", column));
            return null;
        }
        String whole = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        if (whole.startsWith("-")) {
            r.issues.add(error("stock".equals(column) ? "NEGATIVE_STOCK" : "NEGATIVE_NUMBER", column + " cannot be negative", column));
            return null;
        }
        if (whole.length() > 9) {
            r.issues.add(error("NUMBER_TOO_LARGE", column + " is too large", column));
            return null;
        }
        return Integer.parseInt(whole);
    }

    static Boolean yesNo(RowPlan r, String raw, String column) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true", "1", "نعم" -> true;
            case "no", "n", "false", "0", "لا" -> false;
            default -> {
                r.issues.add(error("INVALID_BOOLEAN", column + " must be yes or no", column));
                yield null;
            }
        };
    }

    /** A barcode or SKU twice in the file: both rows are errors, so neither half of the duplicate is imported. */
    private static void markDuplicates(List<RowPlan> rows) {
        Map<String, List<RowPlan>> barcodes = new LinkedHashMap<>();
        Map<String, List<RowPlan>> skus = new LinkedHashMap<>();
        for (RowPlan r : rows) {
            if (r.barcode != null) barcodes.computeIfAbsent(r.barcode, k -> new ArrayList<>()).add(r);
            if (r.sku != null) skus.computeIfAbsent(r.sku.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(r);
        }
        duplicateIssues(barcodes, "DUPLICATE_BARCODE_IN_FILE", "Barcode", "barcode");
        duplicateIssues(skus, "DUPLICATE_SKU_IN_FILE", "SKU", "sku");
    }

    private static void duplicateIssues(Map<String, List<RowPlan>> groups, String code, String label, String column) {
        for (Map.Entry<String, List<RowPlan>> e : groups.entrySet()) {
            if (e.getValue().size() < 2) continue;
            String rowsText = e.getValue().stream().map(r -> String.valueOf(r.row)).reduce((a, b) -> a + ", " + b).orElse("");
            for (RowPlan r : e.getValue()) {
                r.issues.add(error(code, label + " \"" + e.getKey() + "\" appears on rows " + rowsText + " of this file", column));
            }
        }
    }

    /** Barcode first, then SKU; never by name. */
    private static void match(RowPlan r, Map<String, Product> byBarcode, Map<String, Product> bySku, Set<String> variantBarcodes,
                              Set<String> variantSkus, ExistingStrategy strategy) {
        Product viaBarcode = r.barcode == null ? null : byBarcode.get(r.barcode);
        Product viaSku = r.sku == null ? null : bySku.get(r.sku.toLowerCase(Locale.ROOT));
        if (r.barcode != null && viaBarcode == null && variantBarcodes.contains(r.barcode)) {
            r.issues.add(error("BARCODE_USED_BY_VARIANT", "Barcode " + r.barcode + " belongs to a product option (variant); variants are edited in the product editor", "barcode"));
        }
        if (r.sku != null && viaSku == null && variantSkus.contains(r.sku.toLowerCase(Locale.ROOT))) {
            r.issues.add(error("SKU_USED_BY_VARIANT", "SKU " + r.sku + " belongs to a product option (variant); variants are edited in the product editor", "sku"));
        }
        if (viaBarcode != null && viaSku != null && !viaBarcode.getId().equals(viaSku.getId())) {
            r.issues.add(error("MATCH_CONFLICT", "Barcode " + r.barcode + " is \"" + viaBarcode.getNameEn() + "\" but SKU " + r.sku + " is \""
                    + viaSku.getNameEn() + "\"", null));
            return;
        }
        r.match = viaBarcode != null ? viaBarcode : viaSku;
        r.matchedBy = viaBarcode != null ? "barcode" : viaSku != null ? "SKU" : null;
        if (r.match == null) {
            r.planned = "CREATE";
        } else {
            switch (strategy) {
                case SKIP -> {
                    r.planned = "SKIP";
                    r.issues.add(new Issue("INFO", "EXISTING_SKIPPED", "Matches existing product \"" + r.match.getNameEn() + "\" by " + r.matchedBy + " — skipped", null));
                }
                case UPDATE -> r.planned = "UPDATE";
                case FAIL -> r.issues.add(error("EXISTING_PRODUCT", "Matches existing product \"" + r.match.getNameEn() + "\" by " + r.matchedBy, null));
            }
        }
    }

    private static void resolveCategory(RowPlan r, List<Category> categories, boolean create) {
        if (r.categoryText == null) return;
        List<String> path = Arrays.stream(r.categoryText.split(">")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (path.isEmpty()) return;
        if (path.stream().anyMatch(s -> s.length() > 160)) {
            r.issues.add(error("INVALID_CATEGORY", "A category name is longer than 160 characters", "category"));
            return;
        }
        List<Category> found;
        if (path.size() == 1) {
            String name = path.get(0);
            found = categories.stream().filter(c -> c.getNameEn().equalsIgnoreCase(name) || name.equals(c.getNameAr())).toList();
            if (found.size() > 1) {
                r.issues.add(error("AMBIGUOUS_CATEGORY", "Several categories are called \"" + name + "\". Write the full path, e.g. Parent > " + name, "category"));
                return;
            }
        } else {
            Category parent = null;
            found = List.of();
            for (int i = 0; i < path.size(); i++) {
                String seg = path.get(i);
                Category p = parent;
                List<Category> level = categories.stream().filter(c -> c.getNameEn().equalsIgnoreCase(seg)
                        && (p == null ? c.getParent() == null : c.getParent() != null && c.getParent().getId().equals(p.getId()))).toList();
                if (level.size() != 1) {
                    found = List.of();
                    break;
                }
                parent = level.get(0);
                if (i == path.size() - 1) found = List.of(parent);
            }
        }
        if (found.size() == 1) {
            r.category = found.get(0);
            r.categoryResolved = found.get(0).getNameEn();
        } else if (create) {
            r.categoryToCreate = path;
            r.categoryResolved = String.join(" > ", path) + " (new)";
            r.issues.add(warning("CATEGORY_WILL_BE_CREATED", "Category \"" + String.join(" > ", path) + "\" will be created", "category"));
        } else {
            r.issues.add(warning("CATEGORY_NOT_FOUND", "No category \"" + r.categoryText + "\" in this store — "
                    + ("UPDATE".equals(r.planned) ? "the product keeps its current category" : "the product will be uncategorized"), "category"));
        }
    }

    private static void attachImages(RowPlan r, ImportImages images) {
        if (images.isEmpty() || "SKIP".equals(r.planned)) return;
        ImportImages.Match m = images.match(r.sku, r.barcode, MAX_IMAGES_PER_PRODUCT);
        for (String dup : m.duplicates()) {
            r.issues.add(warning("IMAGE_DUPLICATE_NAME", "Several ZIP files share the name of " + dup + "; only the first is used", null));
        }
        for (ImportImages.ZipImage z : m.images()) {
            if (z.tooLarge()) {
                r.issues.add(warning("IMAGE_TOO_LARGE", "The picture " + z.fileName() + " is over 5 MB and will not be attached", null));
            } else if (z.type() == null) {
                r.issues.add(warning("IMAGE_NOT_AN_IMAGE", "The file " + z.fileName() + " is not a JPEG, PNG, WebP or GIF picture and will not be attached", null));
            }
            r.zipImages.add(z);
        }
        int total = r.imageUrls.size() + r.usableZip();
        if (total > MAX_IMAGES_PER_PRODUCT) {
            r.issues.add(warning("TOO_MANY_IMAGES", "A product can have " + MAX_IMAGES_PER_PRODUCT + " pictures; the rest are not attached", null));
        }
    }

    static String slugify(String text, String fallback) {
        if (text == null) return fallback;
        String s = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 100) s = s.substring(0, 100).replaceAll("-+$", "");
        return s.isEmpty() ? fallback : s;
    }

    private static String uniqueSlug(String base, java.util.function.Predicate<String> taken, Set<String> alsoTaken) {
        String slug = base;
        for (int n = 2; taken.test(slug) || alsoTaken.contains(slug); n++) slug = base + "-" + n;
        return slug;
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    static Issue error(String code, String message, String column) {
        return new Issue("ERROR", code, message, column);
    }

    static Issue warning(String code, String message, String column) {
        return new Issue("WARNING", code, message, column);
    }

    /** One row: what the file says (null = cell empty), what it matched, and what will happen. */
    static final class RowPlan {
        final int row;
        String nameEn, nameAr, description, categoryText, sku, barcode, slug, categoryResolved, matchedBy, planned;
        BigDecimal price, salePrice;
        Integer stock, lowStock;
        Boolean available, featured;
        ProductType type;
        Product match;
        Category category;
        List<String> categoryToCreate;
        final List<String> imageUrls = new ArrayList<>();
        final List<ImportImages.ZipImage> zipImages = new ArrayList<>();
        final List<Issue> issues = new ArrayList<>();

        RowPlan(int row) {
            this.row = row;
        }

        boolean hasError() {
            return issues.stream().anyMatch(i -> "ERROR".equals(i.level()));
        }

        String status() {
            if (hasError()) return "ERROR";
            return issues.stream().anyMatch(i -> "WARNING".equals(i.level())) ? "WARNING" : "VALID";
        }

        String action() {
            return hasError() || planned == null ? "NONE" : planned;
        }

        int usableZip() {
            return (int) zipImages.stream().filter(z -> z.type() != null && !z.tooLarge()).count();
        }

        int zipFailures() {
            return (int) zipImages.stream().filter(z -> z.type() == null || z.tooLarge()).count();
        }

        int imageCount() {
            return Math.min(MAX_IMAGES_PER_PRODUCT, imageUrls.size() + usableZip());
        }

        ProductType effectiveType(Product existing) {
            return type != null ? type : existing != null ? existing.getProductType() : ProductType.PRODUCT;
        }

        /** Rules that need the match: required fields for a new product, variant products, services, sale price. */
        void checkRules() {
            if (hasError()) return;
            if ("CREATE".equals(planned)) {
                if (nameEn == null) issues.add(error("MISSING_NAME", "name_en is required for a new product", "name_en"));
                if (price == null) issues.add(error("MISSING_PRICE", "price is required for a new product", "price"));
            }
            if ("UPDATE".equals(planned) && match.isHasVariants() && (price != null || salePrice != null || stock != null)) {
                issues.add(error("VARIANT_PRODUCT", "\"" + match.getNameEn() + "\" is sold in options (variants): change its prices and stock per option in the product editor", null));
            }
            if (stock != null && effectiveType(match) == ProductType.SERVICE && ("CREATE".equals(planned) || "UPDATE".equals(planned))) {
                issues.add(warning("STOCK_IGNORED", "Services do not track stock; the stock value is ignored", "stock"));
            }
            BigDecimal effectivePrice = price != null ? price : match != null ? match.getPrice() : null;
            if (salePrice != null && effectivePrice != null && salePrice.compareTo(effectivePrice) >= 0) {
                issues.add(warning("SALE_PRICE_NOT_LOWER", "sale_price is not lower than price", "sale_price"));
            }
        }

        ProductDtos.ProductRequest createRequest(UUID storeId, UUID categoryId) {
            ProductType t = type == null ? ProductType.PRODUCT : type;
            return new ProductDtos.ProductRequest(storeId, categoryId, nameEn, nameAr, slug == null ? "pending" : slug, description, price, salePrice,
                    null, null, null, sku, t, available, featured, 0, t == ProductType.SERVICE ? null : stock, lowStock, barcode == null ? "" : barcode);
        }

        /** The product as it is, with only the filled cells changed (an empty cell never clears a value). */
        ProductDtos.ProductRequest updateRequest(Product p, UUID categoryId) {
            UUID category = categoryId != null ? categoryId : p.getCategory() == null ? null : p.getCategory().getId();
            return new ProductDtos.ProductRequest(p.getStore().getId(), category,
                    nameEn != null ? nameEn : p.getNameEn(), nameAr != null ? nameAr : p.getNameAr(), p.getSlug(),
                    description != null ? description : p.getDescription(), price != null ? price : p.getPrice(),
                    salePrice != null ? salePrice : p.getSalePrice(), p.getCurrency(), null, p.getGalleryJson(),
                    sku != null ? sku : p.getSku(), type != null ? type : p.getProductType(),
                    available != null ? available : p.isAvailable(), featured != null ? featured : p.isFeatured(), p.getSortOrder(),
                    null, lowStock != null ? lowStock : p.getLowStockThreshold(), barcode /* null = keep */);
        }

        PreviewRow preview() {
            String images = imageCount() == 0 ? "none"
                    : (imageUrls.isEmpty() ? "" : imageUrls.size() + " link" + (imageUrls.size() == 1 ? "" : "s"))
                    + (!imageUrls.isEmpty() && usableZip() > 0 ? " + " : "")
                    + (usableZip() == 0 ? "" : usableZip() + " from ZIP");
            return new PreviewRow(row, nameEn != null ? nameEn : match != null ? match.getNameEn() : null, nameAr, sku, barcode, categoryText,
                    categoryResolved, price, salePrice, stock, images, imageCount(), action(), status(), match == null ? null : match.getId(), List.copyOf(issues));
        }

        ResultRow result(String outcome, UUID productId, int attached, int imageFailures) {
            return new ResultRow(row, nameEn != null ? nameEn : match != null ? match.getNameEn() : null, sku, barcode, outcome, productId,
                    attached, imageFailures, List.copyOf(issues));
        }
    }

}
