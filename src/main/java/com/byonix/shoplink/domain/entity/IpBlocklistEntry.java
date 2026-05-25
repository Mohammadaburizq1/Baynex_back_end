package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "ip_blocklist")
public class IpBlocklistEntry {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "ip_address", nullable = false, length = 64)
    private String ipAddress;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @Column(nullable = false)
    private boolean permanent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by_admin_id")
    private UUID createdByAdminId;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
