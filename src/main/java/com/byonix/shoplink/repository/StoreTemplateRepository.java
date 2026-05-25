package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.StoreTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StoreTemplateRepository extends JpaRepository<StoreTemplate, UUID> {
    List<StoreTemplate> findByCategorySlugAndActiveTrueOrderByDefaultTemplateDescNameAsc(String categorySlug);
    List<StoreTemplate> findByActiveTrueOrderByCategorySlugAscNameAsc();
}
