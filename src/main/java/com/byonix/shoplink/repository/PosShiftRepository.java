package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.PosShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PosShiftRepository extends JpaRepository<PosShift, UUID> {
    List<PosShift> findByStoreIdOrderByOpenedAtDesc(UUID storeId);

    Optional<PosShift> findFirstByDeviceIdAndStatus(UUID deviceId, PosShift.Status status);

    /** Serializes a close/force-close with concurrent uploads for the same shift. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PosShift s where s.id = :id")
    Optional<PosShift> findByIdForUpdate(@Param("id") UUID id);
}
