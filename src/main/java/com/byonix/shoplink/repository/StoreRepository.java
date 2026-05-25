package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.StoreStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoreRepository extends JpaRepository<Store, UUID> {
    Optional<Store> findBySlugAndStatus(String slug, StoreStatus status);
    Optional<Store> findBySlug(String slug);
    List<Store> findByOwnerId(UUID ownerId);
    boolean existsBySlug(String slug);
}
