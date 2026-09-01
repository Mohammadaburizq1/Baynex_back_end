package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.StaffInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffInviteRepository extends JpaRepository<StaffInvite, UUID> {
    Optional<StaffInvite> findByTokenHash(String tokenHash);
    List<StaffInvite> findByStore_IdAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(UUID storeId, Instant now);
    void deleteByStore_IdAndEmailIgnoreCaseAndConsumedAtIsNull(UUID storeId, String email);
}
