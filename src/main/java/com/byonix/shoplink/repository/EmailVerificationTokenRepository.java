package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from EmailVerificationToken t where t.user.id = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
