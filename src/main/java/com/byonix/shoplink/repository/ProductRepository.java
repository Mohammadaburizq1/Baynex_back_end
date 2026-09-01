package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
