package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<CustomerOrder, UUID> {
    List<CustomerOrder> findByStore_IdOrderByCreatedAtDesc(UUID storeId);
    Optional<CustomerOrder> findByIdAndStore_Id(UUID id, UUID storeId);
    Optional<CustomerOrder> findByStore_SlugAndOrderCodeIgnoreCaseAndCustomerEmailIgnoreCase(
            String slug, String orderCode, String email);

    Optional<CustomerOrder> findByStore_SlugAndOrderCodeIgnoreCaseAndCustomerPhone(
            String slug, String orderCode, String phone);

    @Query(value = """
            SELECT v.store_id AS storeId, v.store_name AS storeName, v.sale_date AS saleDate,
                   v.total_revenue AS totalRevenue, v.order_count AS orderCount
            FROM daily_store_sales v
            WHERE v.store_id IN (:storeIds)
              AND v.sale_date BETWEEN :from AND :to
            ORDER BY v.sale_date DESC, v.store_name
            """, nativeQuery = true)
    List<DailyStoreSalesProjection> queryDailyStoreSales(@Param("storeIds") List<UUID> storeIds,
                                                         @Param("from") LocalDate from,
                                                         @Param("to") LocalDate to);
}
