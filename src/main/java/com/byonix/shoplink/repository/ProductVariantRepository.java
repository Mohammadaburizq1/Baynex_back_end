package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.ProductVariant;
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

public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {
    /** Variants with their option values (and each value's option), for many products in one query. */
    @Query("""
            select distinct v from ProductVariant v
            left join fetch v.optionValues ov
            left join fetch ov.option
            where v.product.id in :productIds
            """)
    List<ProductVariant> findWithValuesByProductIdIn(@Param("productIds") Collection<UUID> productIds);

    /** Row-locking load for stock adjustments — see ProductRepository.findByIdForUpdate. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.id = :id")
    Optional<ProductVariant> findByIdForUpdate(@Param("id") UUID id);

    /** The current stored count (null = untracked) — a scalar read, unaffected by what the session has cached. */
    @Query("select v.stock from ProductVariant v where v.id = :id")
    Integer findStockById(@Param("id") UUID id);

    /** Tracked variants at or under their threshold (their own, else {@code defaultThreshold}), with what's needed to name them. */
    @Query("""
            select distinct v from ProductVariant v
            join fetch v.product
            left join fetch v.optionValues ov
            left join fetch ov.option
            where v.store.id = :storeId and v.stock is not null
              and v.stock <= coalesce(v.lowStockThreshold, :defaultThreshold)
            """)
    List<ProductVariant> findLowStock(@Param("storeId") UUID storeId, @Param("defaultThreshold") int defaultThreshold);

    /** Scoped to both the product and the store — a variant id from anywhere else simply isn't found. */
    Optional<ProductVariant> findByIdAndProduct_IdAndStore_Id(UUID id, UUID productId, UUID storeId);

    boolean existsByStore_IdAndSkuIgnoreCase(UUID storeId, String sku);

    /** Lower-cased SKUs from {@code skus} already used by variants of any OTHER product in the store. */
    @Query("""
            select lower(v.sku) from ProductVariant v
            where v.store.id = :storeId and v.product.id <> :productId and lower(v.sku) in :skus
            """)
    List<String> findSkusUsedByOtherProducts(@Param("storeId") UUID storeId,
                                             @Param("productId") UUID productId,
                                             @Param("skus") Collection<String> skus);

    // Same atomic guarded UPDATE as ProductRepository.decrementStockIfAvailable: the DB serializes
    // concurrent updates to the row, so two simultaneous orders for the last unit can't both
    // succeed. 0 rows affected = not enough stock; the caller rolls the whole order back.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ProductVariant v SET v.stock = v.stock - :qty WHERE v.id = :variantId AND v.stock >= :qty")
    int decrementStockIfAvailable(@Param("variantId") UUID variantId, @Param("qty") int qty);

    // Symmetric restore on order cancellation — 0 rows for an untracked (null-stock) variant.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ProductVariant v SET v.stock = v.stock + :qty WHERE v.id = :variantId AND v.stock IS NOT NULL")
    int restoreStock(@Param("variantId") UUID variantId, @Param("qty") int qty);
}
