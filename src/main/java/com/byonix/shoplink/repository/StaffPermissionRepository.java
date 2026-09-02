package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.StaffPermission;
import com.byonix.shoplink.domain.enums.DashboardSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffPermissionRepository extends JpaRepository<StaffPermission, UUID> {
    List<StaffPermission> findByUser_Id(UUID userId);
    Optional<StaffPermission> findByUser_IdAndSection(UUID userId, DashboardSection section);
}
