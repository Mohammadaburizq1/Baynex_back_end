package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.PosShiftCashMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PosShiftCashMovementRepository extends JpaRepository<PosShiftCashMovement, UUID> {
    List<PosShiftCashMovement> findByShiftIdAndStoreIdOrderByMovedAtAsc(UUID shiftId, UUID storeId);

    Optional<PosShiftCashMovement> findByOperationId(UUID operationId);
}
