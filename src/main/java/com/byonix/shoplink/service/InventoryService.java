package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.InventoryDtos;
import com.byonix.shoplink.domain.entity.InventoryAdjustment;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.ProductVariant;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.InventoryAdjustmentReason;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.domain.enums.ProductType;
import com.byonix.shoplink.repository.InventoryAdjustmentRepository;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.repository.ProductVariantRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What's in stock, what's running out, and controlled changes to the count.
 *
 * Counts only change through: a sale/cancellation (OrderService), a merchant's adjustment here, or
 * the initial count on creation — each writing an InventoryLedger row. Editing a product or variant
 * never sets stock, so a stale editor can't overwrite units sold in the meantime.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {
    private static final int MAX_HISTORY = 200;

    private final StoreService storeService;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final InventoryAdjustmentRepository adjustmentRepository;
    private final InventoryLedger ledger;
    private final CurrentUserService currentUser;

    @Value("${app.inventory.default-low-stock-threshold:5}")
    private int defaultLowStockThreshold;

    // ── reads ──────────────────────────────────────────────────────────────────────────────────

    /** Every stockable item in the store: products without variants, and each variant of those that have them. */
    public List<InventoryDtos.InventoryRow> list(UUID storeId) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.PRODUCTS, PermissionLevel.VIEW);

        List<Product> products = productRepository.findByStore_IdOrderBySortOrderAscNameEnAsc(storeId);
        List<UUID> withVariants = products.stream().filter(Product::isHasVariants).map(Product::getId).toList();
        Map<UUID, List<ProductVariant>> variantsByProduct = new HashMap<>();
        if (!withVariants.isEmpty()) {
            for (ProductVariant v : variantRepository.findWithValuesByProductIdIn(withVariants)) {
                variantsByProduct.computeIfAbsent(v.getProduct().getId(), k -> new ArrayList<>()).add(v);
            }
        }

        List<InventoryDtos.InventoryRow> rows = new ArrayList<>();
        for (Product p : products) {
            if (p.getProductType() == ProductType.SERVICE) {
                continue; // a service has no units to count
            }
            if (p.isHasVariants()) {
                variantsByProduct.getOrDefault(p.getId(), List.of()).stream()
                        .sorted(Comparator.comparingInt(ProductVariant::getSortOrder).thenComparing(ProductVariant::label))
                        .forEach(v -> rows.add(row(p, v)));
            } else {
                rows.add(row(p, null));
            }
        }
        return rows;
    }

    /** Tracked items at or under their threshold, emptiest first, with the counts the sidebar badge needs. */
    public InventoryDtos.AlertsResponse alerts(UUID storeId) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.PRODUCTS, PermissionLevel.VIEW);

        List<InventoryDtos.InventoryRow> items = new ArrayList<>();
        productRepository.findLowStock(storeId, defaultLowStockThreshold).forEach(p -> items.add(row(p, null)));
        variantRepository.findLowStock(storeId, defaultLowStockThreshold).forEach(v -> items.add(row(v.getProduct(), v)));
        items.sort(Comparator.comparingInt((InventoryDtos.InventoryRow r) -> r.stock() == null ? Integer.MAX_VALUE : r.stock())
                .thenComparing(InventoryDtos.InventoryRow::name));
        int out = (int) items.stream().filter(r -> r.status() == InventoryDtos.StockStatus.OUT).count();
        return new InventoryDtos.AlertsResponse(items.size() - out, out, items);
    }

    /** Newest first. Optionally narrowed to one product or one variant; always scoped to the store. */
    public List<InventoryDtos.HistoryEntry> history(UUID storeId, UUID productId, UUID variantId, int limit) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.PRODUCTS, PermissionLevel.VIEW);
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, MAX_HISTORY)));
        List<InventoryAdjustment> rows = variantId != null
                ? adjustmentRepository.findRecentByVariant(storeId, variantId, page)
                : productId != null
                        ? adjustmentRepository.findRecentByProduct(storeId, productId, page)
                        : adjustmentRepository.findRecentByStore(storeId, page);
        return rows.stream().map(this::entry).toList();
    }

    // ── changes ────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public InventoryDtos.InventoryRow adjust(InventoryDtos.AdjustRequest r) {
        if (!r.reason().isManual()) {
            throw new IllegalArgumentException("Choose a reason: restock, correction, damaged or returned");
        }
        // Lock the row before reading the count, so a sale running at the same moment queues behind
        // this change (and the before/after we record is exact) instead of interleaving with it.
        Product product = productRepository.findByIdForUpdate(r.productId())
                .orElseThrow(() -> new EntityNotFoundException("Product not found"));
        currentUser.ensureSectionAccess(product.getStore(), DashboardSection.PRODUCTS, PermissionLevel.EDIT);
        if (product.getProductType() == ProductType.SERVICE) {
            throw new IllegalArgumentException("Services don't have stock to count");
        }

        ProductVariant variant = null;
        Integer before;
        if (product.isHasVariants()) {
            if (r.variantId() == null) {
                throw new IllegalArgumentException("Choose a variant — this product's stock is kept per variant");
            }
            variant = variantRepository.findByIdForUpdate(r.variantId())
                    .filter(v -> v.getProduct().getId().equals(product.getId())
                            && v.getStore().getId().equals(product.getStore().getId()))
                    .orElseThrow(() -> new EntityNotFoundException("Variant not found"));
            before = variant.getStock();
        } else {
            if (r.variantId() != null) {
                throw new EntityNotFoundException("Variant not found");
            }
            before = product.getStock();
        }

        int after;
        if (r.mode() == InventoryDtos.AdjustMode.SET) {
            if (r.quantity() < 0) {
                throw new IllegalArgumentException("Stock can't be negative");
            }
            after = r.quantity();
        } else {
            if (r.quantity() == 0) {
                throw new IllegalArgumentException("Enter an amount to add or remove");
            }
            if (before == null) {
                throw new IllegalArgumentException("Stock isn't being counted for this item yet — set a starting count first");
            }
            after = before + r.quantity();
            if (after < 0) {
                throw new IllegalArgumentException("That would take stock below zero (currently " + before + ")");
            }
        }

        if (variant != null) {
            variant.setStock(after);
        } else {
            product.setStock(after);
        }
        String name = InventoryLedger.itemName(product.getNameEn(), variant == null ? null : variant.label());
        ledger.record(product.getStore(), product, variant, name, after - (before == null ? 0 : before), after,
                r.reason(), null, r.note());
        return row(product, variant);
    }

    // ── mapping ────────────────────────────────────────────────────────────────────────────────

    private InventoryDtos.InventoryRow row(Product p, ProductVariant v) {
        Integer stock = v != null ? v.getStock() : p.getStock();
        Integer override = v != null ? v.getLowStockThreshold() : p.getLowStockThreshold();
        int threshold = override != null ? override : defaultLowStockThreshold;
        boolean available = p.isAvailable() && (v == null || v.isAvailable());
        return new InventoryDtos.InventoryRow(p.getId(), v == null ? null : v.getId(), p.getNameEn(),
                v == null ? null : v.label(), v != null ? v.getSku() : p.getSku(), stock, override, threshold,
                status(stock, threshold), available);
    }

    static InventoryDtos.StockStatus status(Integer stock, int threshold) {
        if (stock == null) {
            return InventoryDtos.StockStatus.UNTRACKED;
        }
        if (stock <= 0) {
            return InventoryDtos.StockStatus.OUT;
        }
        return stock <= threshold ? InventoryDtos.StockStatus.LOW : InventoryDtos.StockStatus.OK;
    }

    private InventoryDtos.HistoryEntry entry(InventoryAdjustment a) {
        // A customer placing an order is identified by the order code, not by name — the merchant
        // sees who did the merchant-side changes.
        String by = a.getReason() == InventoryAdjustmentReason.ORDER_PLACED || a.getCreatedBy() == null
                ? null : a.getCreatedBy().getFullName();
        return new InventoryDtos.HistoryEntry(a.getId(), a.getProduct().getId(),
                a.getVariant() == null ? null : a.getVariant().getId(), a.getItemName(), a.getDelta(), a.getStockAfter(),
                a.getReason(), a.getReference(), a.getNote(), by, a.getCreatedAt());
    }
}
