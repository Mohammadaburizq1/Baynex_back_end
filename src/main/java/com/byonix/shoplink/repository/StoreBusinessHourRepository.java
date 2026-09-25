package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.StoreBusinessHour;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StoreBusinessHourRepository extends JpaRepository<StoreBusinessHour, UUID> {
    List<StoreBusinessHour> findByStore_IdOrderByDayOfWeekAsc(UUID storeId);
}
