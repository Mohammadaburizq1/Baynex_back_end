package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.ProductModifierGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductModifierGroupRepository extends JpaRepository<ProductModifierGroup, UUID> {
    /** Groups with their options, for a whole page of products in one query. */
    @Query("select distinct g from ProductModifierGroup g left join fetch g.options where g.product.id in :productIds")
    List<ProductModifierGroup> findWithOptionsByProductIdIn(@Param("productIds") Collection<UUID> productIds);
}
