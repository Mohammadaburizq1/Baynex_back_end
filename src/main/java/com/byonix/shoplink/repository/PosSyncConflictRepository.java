package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.PosSyncConflict;
import com.byonix.shoplink.domain.enums.PosSyncConflictStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PosSyncConflictRepository extends JpaRepository<PosSyncConflict, UUID> {
    List<PosSyncConflict> findByOperationIdOrderByCreatedAtAsc(UUID operationId);

    List<PosSyncConflict> findByStoreIdOrderByCreatedAtDesc(UUID storeId);

    List<PosSyncConflict> findByStoreIdAndStatusOrderByCreatedAtDesc(UUID storeId, PosSyncConflictStatus status);

    long countByStoreIdAndStatus(UUID storeId, PosSyncConflictStatus status);
}
