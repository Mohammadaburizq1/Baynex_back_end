package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);

    @Query("""
            select u from User u where u.adminUnlockRequired = true
            or (u.lockedUntil is not null and u.lockedUntil > :now)
            """)
    Page<User> findLockedUsers(@Param("now") Instant now, Pageable pageable);
}
