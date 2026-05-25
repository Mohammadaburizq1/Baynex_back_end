package com.byonix.shoplink.domain.entity;

import com.byonix.shoplink.domain.enums.LoginFailureReason;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "login_attempts")
public class LoginAttempt {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "ip_address", nullable = false, length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(nullable = false)
    private boolean success;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 64)
    private LoginFailureReason failureReason;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(length = 120)
    private String country;

    @Column(length = 120)
    private String city;

    @Column(name = "device_fingerprint", length = 255)
    private String deviceFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
