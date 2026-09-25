package com.byonix.shoplink.domain.entity;

import com.byonix.shoplink.domain.enums.PosDeviceStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered POS installation bound to one store. Holds only hashes of its activation code and
 * device credential — never the plaintext values.
 */
@Getter
@Setter
@Entity
@Table(name = "pos_devices")
public class PosDevice extends BaseAuditable {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PosDeviceStatus status = PosDeviceStatus.PENDING;

    @Column(name = "activation_code_hash", length = 128)
    private String activationCodeHash;

    @Column(name = "activation_code_expires_at")
    private Instant activationCodeExpiresAt;

    @Column(name = "credential_hash", length = 128)
    private String credentialHash;

    @Column(name = "credential_expires_at")
    private Instant credentialExpiresAt;

    @Column(name = "installation_id", length = 64)
    private String installationId;

    @Column(length = 20)
    private String platform;

    @Column(name = "app_version", length = 40)
    private String appVersion;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;
}
