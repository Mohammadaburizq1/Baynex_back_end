package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.ProductImportSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProductImportSessionRepository extends JpaRepository<ProductImportSession, UUID> {
    /**
     * Claims a previewed import for confirmation. One statement, so of two simultaneous confirms
     * (a double click, a retried request) exactly one gets 1 and runs the import.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ProductImportSession s set s.status = com.byonix.shoplink.domain.entity.ProductImportSession.Status.IMPORTING,
                   s.updatedAt = :now
            where s.id = :id and s.status = com.byonix.shoplink.domain.entity.ProductImportSession.Status.PREVIEWED
            """)
    int claim(@Param("id") UUID id, @Param("now") Instant now);
}
