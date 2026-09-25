package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.PosDevice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PosDeviceRepository extends JpaRepository<PosDevice, UUID> {
    @EntityGraph(attributePaths = {"store", "store.owner"})
    Optional<PosDevice> findByCredentialHash(String credentialHash);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<PosDevice> findByActivationCodeHash(String activationCodeHash);

    List<PosDevice> findByStore_IdOrderByCreatedAtDesc(UUID storeId);
}
