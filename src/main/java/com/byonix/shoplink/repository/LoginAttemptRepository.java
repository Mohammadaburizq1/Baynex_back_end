package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.LoginAttempt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {
    @Query("select count(a) from LoginAttempt a where a.email = :email and a.success = false and a.createdAt >= :since")
    long countFailedByEmailSince(@Param("email") String email, @Param("since") Instant since);

    @Query("select count(a) from LoginAttempt a where a.ipAddress = :ip and a.success = false and a.createdAt >= :since")
    long countFailedByIpSince(@Param("ip") String ip, @Param("since") Instant since);

    @Query("select count(distinct a.email) from LoginAttempt a where a.ipAddress = :ip and a.success = false and a.createdAt >= :since")
    long countDistinctEmailsFailedByIpSince(@Param("ip") String ip, @Param("since") Instant since);

    Page<LoginAttempt> findByRiskScoreGreaterThanEqualOrderByCreatedAtDesc(int minRisk, Pageable pageable);

    Page<LoginAttempt> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
