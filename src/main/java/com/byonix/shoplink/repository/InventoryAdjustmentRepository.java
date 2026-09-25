package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.InventoryAdjustment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface InventoryAdjustmentRepository extends JpaRepository<InventoryAdjustment, UUID> {
    // Newest first. createdAt alone can tie (several lines written in one transaction), so id
    // breaks the tie deterministically. Every query is scoped by store as well as by the item.

    @Query("select a from InventoryAdjustment a left join fetch a.createdBy where a.store.id = :storeId order by a.createdAt desc, a.id desc")
    List<InventoryAdjustment> findRecentByStore(@Param("storeId") UUID storeId, Pageable page);

    @Query("select a from InventoryAdjustment a left join fetch a.createdBy where a.store.id = :storeId and a.product.id = :productId order by a.createdAt desc, a.id desc")
    List<InventoryAdjustment> findRecentByProduct(@Param("storeId") UUID storeId, @Param("productId") UUID productId, Pageable page);

    @Query("select a from InventoryAdjustment a left join fetch a.createdBy where a.store.id = :storeId and a.variant.id = :variantId order by a.createdAt desc, a.id desc")
    List<InventoryAdjustment> findRecentByVariant(@Param("storeId") UUID storeId, @Param("variantId") UUID variantId, Pageable page);
}
