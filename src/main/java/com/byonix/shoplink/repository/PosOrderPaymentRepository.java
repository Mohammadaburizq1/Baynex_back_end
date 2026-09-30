package com.byonix.shoplink.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PosOrderPaymentRepository extends JpaRepository<com.byonix.shoplink.domain.entity.PosOrderPayment, UUID> {
    List<com.byonix.shoplink.domain.entity.PosOrderPayment> findByOrderIdOrderByPaidAtAscIdAsc(UUID orderId);

    List<com.byonix.shoplink.domain.entity.PosOrderPayment> findByOrderIdInOrderByPaidAtAscIdAsc(Collection<UUID> orderIds);
}
