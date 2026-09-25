package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.StoreThemeContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StoreThemeContentRepository extends JpaRepository<StoreThemeContent, UUID> {
    Optional<StoreThemeContent> findByStore_Id(UUID storeId);
}
