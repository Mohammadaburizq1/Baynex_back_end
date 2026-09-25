package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {
    /** A whole page of products' galleries in one query, in display order. */
    List<ProductImage> findByProduct_IdInOrderBySortOrderAsc(Collection<UUID> productIds);
}
