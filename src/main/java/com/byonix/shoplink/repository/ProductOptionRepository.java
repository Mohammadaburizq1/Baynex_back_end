package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductOptionRepository extends JpaRepository<ProductOption, UUID> {
    /** Options with their values, for a whole page of products in one query (no per-product round trip). */
    @Query("select distinct o from ProductOption o left join fetch o.values where o.product.id in :productIds")
    List<ProductOption> findWithValuesByProductIdIn(@Param("productIds") Collection<UUID> productIds);
}
