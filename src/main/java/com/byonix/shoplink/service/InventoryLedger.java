package com.byonix.shoplink.service;

import com.byonix.shoplink.domain.entity.InventoryAdjustment;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.ProductVariant;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.InventoryAdjustmentReason;
import com.byonix.shoplink.repository.InventoryAdjustmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the append-only stock history. Every code path that changes a tracked count records here,
 * in the same transaction as the change, so the history can never disagree with the stock.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryLedger {
    private static final int MAX_NAME = 300;
    private static final AtomicLong LAST_MICROS = new AtomicLong();

    private final InventoryAdjustmentRepository repository;
    private final CurrentUserService currentUser;

    /**
     * @param delta      signed change (negative for a sale). A zero delta is not recorded, except INITIAL.
     * @param stockAfter the count once the change was applied
     * @param reference  the order code for sale/cancellation rows, otherwise null
     */
    @Transactional
    public void record(Store store, Product product, ProductVariant variant, String itemName, int delta, int stockAfter,
                       InventoryAdjustmentReason reason, String reference, String note) {
        if (delta == 0 && reason != InventoryAdjustmentReason.INITIAL) {
            return;
        }
        InventoryAdjustment row = new InventoryAdjustment();
        row.setStore(store);
        row.setProduct(product);
        row.setVariant(variant);
        row.setItemName(itemName.length() > MAX_NAME ? itemName.substring(0, MAX_NAME) : itemName);
        row.setDelta(delta);
        row.setStockAfter(stockAfter);
        row.setReason(reason);
        row.setReference(reference);
        row.setNote(note == null || note.isBlank() ? null : note.trim());
        // Null for a guest checkout sale (M1-06): there is no account to attribute it to, and
        // requiring one rejected every guest order containing a stock-tracked product with 403.
        row.setCreatedBy(currentUser.userOrNull());
        row.setCreatedAt(nextInstant());
        // Written out immediately, not left pending: the stock UPDATEs that surround these calls
        // (ProductRepository/ProductVariantRepository decrement/restore, the offer usage counter)
        // are bulk updates with clearAutomatically, which empty the persistence context without
        // flushing unrelated tables first — a pending ledger row from an earlier line of the same
        // order would be dropped silently.
        repository.saveAndFlush(row);
    }

    /**
     * History is shown newest first, so two rows must never share a timestamp: the system clock can
     * tick as coarsely as a millisecond (Windows) and the only other tiebreaker is a random UUID.
     * Microseconds is what the column stores, so each row gets at least one more than the last.
     */
    static Instant nextInstant() {
        long now = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now());
        long micros = LAST_MICROS.updateAndGet(previous -> Math.max(previous + 1, now));
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }

    /** "Tee (M / Black)" — how a line is named in history. */
    public static String itemName(String productName, String variantLabel) {
        return variantLabel == null || variantLabel.isBlank() ? productName : productName + " (" + variantLabel + ")";
    }
}
