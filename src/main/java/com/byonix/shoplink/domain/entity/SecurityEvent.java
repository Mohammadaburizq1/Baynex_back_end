package com.byonix.shoplink.domain.entity;

import com.byonix.shoplink.domain.enums.SecurityEventSeverity;
import com.byonix.shoplink.domain.enums.SecurityEventType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "security_events")
public class SecurityEvent {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private SecurityEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SecurityEventSeverity severity;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "details_json", columnDefinition = "TEXT")
    private String detailsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
