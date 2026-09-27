package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.PosSyncOperation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PosSyncOperationRepository extends JpaRepository<PosSyncOperation, UUID> {
    Optional<PosSyncOperation> findByDeviceIdAndOperationTypeAndEntityId(UUID deviceId, String operationType, UUID entityId);
}
