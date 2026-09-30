package com.byonix.shoplink.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RestaurantAreaRepository extends JpaRepository<com.byonix.shoplink.domain.entity.RestaurantArea, UUID> {
    List<com.byonix.shoplink.domain.entity.RestaurantArea> findByStoreIdOrderBySortOrderAscNameAsc(UUID storeId);
}
