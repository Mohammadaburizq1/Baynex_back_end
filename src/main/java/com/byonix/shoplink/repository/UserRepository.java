package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    Optional<User> findByGoogleSub(String googleSub);
    List<User> findByStore_IdAndRoleOrderByCreatedAtAsc(UUID storeId, Role role);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM app_users u
                WHERE regexp_replace(coalesce(u.phone, ''), '[^0-9]', '', 'g') = :digits
                  AND length(:digits) >= 7
            )
            """, nativeQuery = true)
    boolean existsByPhoneDigits(@Param("digits") String digits);

    @Query(value = """
            SELECT * FROM app_users u
            WHERE regexp_replace(coalesce(u.phone, ''), '[^0-9]', '', 'g') = :digits
              AND length(:digits) >= 7
            LIMIT 1
            """, nativeQuery = true)
    Optional<User> findByPhoneDigits(@Param("digits") String digits);

    @Query("""
            select u from User u where u.adminUnlockRequired = true
            or (u.lockedUntil is not null and u.lockedUntil > :now)
            """)
    Page<User> findLockedUsers(@Param("now") Instant now, Pageable pageable);
}
