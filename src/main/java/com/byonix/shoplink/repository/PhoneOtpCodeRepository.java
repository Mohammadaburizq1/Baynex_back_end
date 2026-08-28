package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.PhoneOtpCode;
import com.byonix.shoplink.domain.enums.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PhoneOtpCodeRepository extends JpaRepository<PhoneOtpCode, UUID> {
    Optional<PhoneOtpCode> findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            UUID userId, OtpPurpose purpose);

    @Modifying
    @Query("delete from PhoneOtpCode c where c.user.id = :userId and c.purpose = :purpose")
    void deleteByUserIdAndPurpose(@Param("userId") UUID userId, @Param("purpose") OtpPurpose purpose);
}
