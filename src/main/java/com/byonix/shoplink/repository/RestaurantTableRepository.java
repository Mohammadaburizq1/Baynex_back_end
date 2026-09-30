package com.byonix.shoplink.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RestaurantTableRepository extends JpaRepository<com.byonix.shoplink.domain.entity.RestaurantTable, UUID> {
    List<com.byonix.shoplink.domain.entity.RestaurantTable> findByStoreIdOrderBySortOrderAscNameAsc(UUID storeId);
}
