package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.IpBlocklistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IpBlocklistRepository extends JpaRepository<IpBlocklistEntry, UUID> {
    @Query("""
            select e from IpBlocklistEntry e where e.ipAddress = :ip
            and (e.permanent = true or e.blockedUntil is null or e.blockedUntil > :now)
            """)
    Optional<IpBlocklistEntry> findActiveBlock(@Param("ip") String ip, @Param("now") Instant now);
}
