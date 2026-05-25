package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {
}
