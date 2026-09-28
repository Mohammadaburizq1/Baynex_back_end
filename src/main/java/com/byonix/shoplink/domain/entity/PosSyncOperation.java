package com.byonix.shoplink.domain.entity;

import com.byonix.shoplink.domain.enums.PosSyncOperationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The idempotency record of one uploaded POS operation. The primary key is the operation id the
 * device generated offline, so a retry of the same upload finds this row instead of creating a
 * second order, a second stock movement or a second daily-sales increment.
 */
@Getter
@Setter
@Entity
@Table(name = "pos_sync_operations")
public class PosSyncOperation implements org.springframework.data.domain.Persistable<UUID> {
    public static final String TYPE_ORDER = "ORDER_CREATE";
    /** POS-23: a return or exchange; entity_id = the till's local return id. */
    public static final String TYPE_RETURN = "RETURN_CREATE";

    @Id
    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    /** The device that made the sale. */
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    /** The device that uploaded it: the same one, or its re-activated replacement (recovery). */
    @Column(name = "submitted_by_device_id")
    private UUID submittedByDeviceId;

    @Column(name = "operation_type", nullable = false, length = 40)
    private String operationType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    /** SHA-256 of the canonical request, so a reused operation id with a different body is refused. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "catalog_version", nullable = false, length = 64)
    private String catalogVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PosSyncOperationStatus status;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "order_code", length = 12)
    private String orderCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * The id is assigned by the device, so Spring Data cannot tell new from existing by a null id. A
     * new record must always be INSERTed: a merge could silently turn a racing duplicate upload into
     * an UPDATE of the operation that won.
     */
    @Transient
    private boolean fresh = true;

    @Override
    public UUID getId() {
        return operationId;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        fresh = false;
    }
}
