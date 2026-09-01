package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.Offer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferRepository extends JpaRepository<Offer, UUID> {
    List<Offer> findByStore_IdOrderByCreatedAtDesc(UUID storeId);
    Optional<Offer> findByIdAndStore_Id(UUID id, UUID storeId);
    Optional<Offer> findByStore_IdAndCodeIgnoreCase(UUID storeId, String code);
    boolean existsByStore_IdAndCodeIgnoreCaseAndIdNot(UUID storeId, String code, UUID id);
    boolean existsByStore_IdAndCodeIgnoreCase(UUID storeId, String code);

    // Same atomic-UPDATE-with-guard discipline as ProductRepository.decrementStockIfAvailable:
    // two concurrent orders both trying to redeem the last use of a limited code must not both
    // succeed. Returns 0 rows affected when the code is already exhausted.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Offer o SET o.timesUsed = o.timesUsed + 1 WHERE o.id = :id AND (o.maxUses IS NULL OR o.timesUsed < o.maxUses)")
    int incrementUsageIfAvailable(@Param("id") UUID id);
}
