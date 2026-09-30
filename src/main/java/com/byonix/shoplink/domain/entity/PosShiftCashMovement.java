package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * POS-24: cash put into or taken out of a till's drawer during a shift, outside any sale (change
 * float, safe drop, petty cash…). Never a fake order. Immutable once written; the id comes from the till.
 */
@Getter
@Setter
@Entity
@Table(name = "pos_shift_cash_movements")
public class PosShiftCashMovement implements org.springframework.data.domain.Persistable<UUID> {
    public enum Type { CASH_IN, CASH_OUT }

    public enum Reason {
        CHANGE_FLOAT, MANAGER_ADJUSTMENT, BANK_DEPOSIT, PETTY_CASH, SAFE_DROP, OTHER;

        /** Which reasons make sense for which direction. */
        public boolean allowedFor(Type type) {
            return switch (this) {
                case CHANGE_FLOAT -> type == Type.CASH_IN;
                case BANK_DEPOSIT, PETTY_CASH, SAFE_DROP -> type == Type.CASH_OUT;
                case MANAGER_ADJUSTMENT, OTHER -> true;
            };
        }
    }

    @Id
    private UUID id;

    @Column(name = "shift_id", nullable = false)
    private UUID shiftId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 10)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Reason reason;

    @Column(length = 300)
    private String note;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal amount;

    @Column(name = "staff_id")
    private UUID staffId;

    @Column(name = "staff_name", nullable = false, length = 160)
    private String staffName;

    @Column(name = "manager_id")
    private UUID managerId;

    @Column(name = "manager_name", length = 160)
    private String managerName;

    @Column(name = "moved_at", nullable = false)
    private Instant movedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean fresh = true;

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
