package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.PosReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PosReturnRepository extends JpaRepository<PosReturn, UUID> {
    Optional<PosReturn> findByOperationId(UUID operationId);

    List<PosReturn> findByStoreIdAndOriginalOrderIdOrderByCreatedAtAsc(UUID storeId, UUID originalOrderId);

    List<PosReturn> findByStoreIdOrderByCreatedAtDesc(UUID storeId);

    /** Units of an order item already accepted as returned (all tills, all earlier returns). */
    @Query("select coalesce(sum(i.quantity), 0) from PosReturnItem i where i.orderItemId = :orderItemId")
    int acceptedQuantity(@Param("orderItemId") UUID orderItemId);

    /** Refund already accepted for an order item. */
    @Query("select coalesce(sum(i.refundAmount), 0) from PosReturnItem i where i.orderItemId = :orderItemId")
    java.math.BigDecimal acceptedRefund(@Param("orderItemId") UUID orderItemId);
}
