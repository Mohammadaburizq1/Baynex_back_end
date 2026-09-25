package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    List<Product> findByStore_SlugAndAvailableTrueOrderBySortOrderAscNameEnAsc(String slug);
    List<Product> findByStore_IdOrderBySortOrderAscNameEnAsc(UUID storeId);
    List<Product> findByStore_SlugAndFeaturedTrueAndAvailableTrueOrderBySortOrderAscNameEnAsc(String slug);
    Optional<Product> findByStore_SlugAndSlugAndAvailableTrue(String storeSlug, String productSlug);
    Optional<Product> findByIdAndStore_Id(UUID id, UUID storeId);
    long countByStore_Id(UUID storeId);
    boolean existsByStore_IdAndSlug(UUID storeId, String slug);
    boolean existsByStore_IdAndSlugAndIdNot(UUID storeId, String slug, UUID id);
    boolean existsByStore_IdAndSkuIgnoreCase(UUID storeId, String sku);
    boolean existsByStore_IdAndSkuIgnoreCaseAndIdNot(UUID storeId, String sku, UUID id);

    /**
     * Row-locking load for stock adjustments: the lock is held to the end of the transaction, so a
     * concurrent sale (an atomic UPDATE on this row) waits and then runs against the adjusted count —
     * the before/after recorded in the ledger is exact, and neither change can be lost.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    /** The current stored count (null = untracked) — a scalar read, unaffected by what the session has cached. */
    @Query("select p.stock from Product p where p.id = :id")
    Integer findStockById(@Param("id") UUID id);

    /** Tracked, variant-less products at or under their threshold (their own, else {@code defaultThreshold}), emptiest first. */
    @Query("""
            select p from Product p
            where p.store.id = :storeId and p.hasVariants = false and p.stock is not null
              and p.stock <= coalesce(p.lowStockThreshold, :defaultThreshold)
            order by p.stock asc, p.nameEn asc
            """)
    List<Product> findLowStock(@Param("storeId") UUID storeId, @Param("defaultThreshold") int defaultThreshold);

    /** Lower-cased SKUs from {@code skus} already used as the SKU of any OTHER product in the store. */
    @Query("""
            select lower(p.sku) from Product p
            where p.store.id = :storeId and p.id <> :productId and lower(p.sku) in :skus
            """)
    List<String> findSkusUsedByOtherProducts(@Param("storeId") UUID storeId,
                                             @Param("productId") UUID productId,
                                             @Param("skus") Collection<String> skus);

    // Atomic, single-statement decrement guarded by the same WHERE clause that reads the current
    // value — the DB serializes concurrent UPDATEs to the same row, so two simultaneous orders
    // for the last unit of stock can't both succeed (unlike a Java-side read-then-write, which
    // would race). Returns 0 rows affected when stock is insufficient; the caller decides whether
    // to check this at all based on whether the product tracks stock (stock IS NOT NULL) in the
    // first place. Mirrors the atomic-UPDATE-with-guard discipline DailyStoreSalesSyncService
    // already uses for the same reason — no @Version/optimistic-locking retry loop needed.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Product p SET p.stock = p.stock - :qty WHERE p.id = :productId AND p.stock >= :qty")
    int decrementStockIfAvailable(@Param("productId") UUID productId, @Param("qty") int qty);

    // Symmetric restore on order cancellation — a no-op (0 rows affected) for products that
    // don't track stock, which is fine since there's nothing to restore.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Product p SET p.stock = p.stock + :qty WHERE p.id = :productId AND p.stock IS NOT NULL")
    int restoreStock(@Param("productId") UUID productId, @Param("qty") int qty);
}
