package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    List<Product> findByStore_SlugAndAvailableTrueOrderBySortOrderAscNameEnAsc(String slug);
    List<Product> findByStore_IdOrderBySortOrderAscNameEnAsc(UUID storeId);
    List<Product> findByStore_SlugAndFeaturedTrueAndAvailableTrueOrderBySortOrderAscNameEnAsc(String slug);
    Optional<Product> findByStore_SlugAndSlugAndAvailableTrue(String storeSlug, String productSlug);
    Optional<Product> findByIdAndStore_Id(UUID id, UUID storeId);
}
