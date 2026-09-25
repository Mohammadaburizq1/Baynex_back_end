package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.StoreThemeContentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoreThemeContentVersionRepository extends JpaRepository<StoreThemeContentVersion, UUID> {
    List<StoreThemeContentVersion> findByStore_IdOrderByVersionDesc(UUID storeId);
    Optional<StoreThemeContentVersion> findByStore_IdAndVersion(UUID storeId, int version);
}
