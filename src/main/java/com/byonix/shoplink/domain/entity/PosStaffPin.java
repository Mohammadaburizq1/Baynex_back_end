package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** POS-14: a person's POS PIN as a salted PBKDF2 hash (never the PIN). One per user. */
@Getter
@Setter
@Entity
@Table(name = "pos_staff_pins")
public class PosStaffPin {
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "pin_salt", nullable = false, length = 64)
    private String pinSalt;

    @Column(name = "pin_hash", nullable = false, length = 128)
    private String pinHash;

    @Column(nullable = false)
    private int iterations;

    @Column(name = "set_at", nullable = false)
    private Instant setAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
