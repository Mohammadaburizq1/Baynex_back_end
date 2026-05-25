package com.byonix.shoplink.domain.entity;

import com.byonix.shoplink.domain.enums.AdminAuditAction;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "admin_audit_logs")
public class AdminAuditLog {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "admin_user_id", nullable = false)
    private UUID adminUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 80)
    private AdminAuditAction action;

    @Column(name = "target_user_id")
    private UUID targetUserId;

    @Column(name = "target_ip", length = 64)
    private String targetIp;

    @Column(name = "details_json", columnDefinition = "TEXT")
    private String detailsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
