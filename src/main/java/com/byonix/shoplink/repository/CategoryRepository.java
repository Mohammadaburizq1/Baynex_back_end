package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.Category;
import com.byonix.shoplink.domain.enums.CategoryType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByCategoryTypeAndParentIsNullAndActiveTrueOrderBySortOrderAscNameEnAsc(CategoryType type);
    List<Category> findByParent_SlugAndActiveTrueOrderBySortOrderAscNameEnAsc(String parentSlug);
    List<Category> findByStore_IdOrderBySortOrderAscNameEnAsc(UUID storeId);
    List<Category> findByStore_SlugAndActiveTrueOrderBySortOrderAscNameEnAsc(String storeSlug);
    Optional<Category> findByIdAndStore_Id(UUID id, UUID storeId);
    boolean existsByStore_IdAndSlug(UUID storeId, String slug);
    boolean existsByStore_IdAndSlugAndIdNot(UUID storeId, String slug, UUID id);
}
