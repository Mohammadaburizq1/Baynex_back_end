package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.SecurityEvent;
import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, UUID> {
    Page<SecurityEvent> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<SecurityEvent> findBySeverityInOrderByCreatedAtDesc(java.util.Collection<SecurityEventSeverity> severities, Pageable pageable);

    Page<SecurityEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
