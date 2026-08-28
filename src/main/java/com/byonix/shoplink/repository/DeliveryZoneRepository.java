package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.DeliveryZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryZoneRepository extends JpaRepository<DeliveryZone, UUID> {
    List<DeliveryZone> findByStore_IdOrderBySortOrderAsc(UUID storeId);
    Optional<DeliveryZone> findByIdAndStore_Id(UUID id, UUID storeId);
}
