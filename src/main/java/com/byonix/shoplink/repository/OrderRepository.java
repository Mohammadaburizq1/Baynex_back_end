package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.entity.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    // No Customer entity — a "customer" here is a distinct identity aggregated from real orders:
    // grouped by customer_id when the order was placed by a logged-in account, falling back to
    // phone (or email, if phone is somehow blank) for any order with no linked account. For a
    // registered customer, name/phone/email are read from their current app_users profile
    // (COALESCE'd first) rather than the order snapshot, so the list reflects who they are now,
    // not what they typed on one specific past order; guest orders fall back to the order's own
    // snapshot fields since there's no profile to read. Cancelled orders are excluded entirely,
    // matching how DailyStoreSalesSyncService already treats cancellation as not-real-revenue.
    @Query(value = """
            SELECT
                o.customer_id AS customerId,
                COALESCE(u.full_name, MAX(o.customer_name)) AS name,
                COALESCE(u.phone, MAX(o.customer_phone)) AS phone,
                COALESCE(u.email, MAX(o.customer_email)) AS email,
                COUNT(*) AS orderCount,
                SUM(o.total) AS totalSpent,
                MIN(o.created_at) AS firstOrderAt,
                MAX(o.created_at) AS lastOrderAt
            FROM customer_orders o
            LEFT JOIN app_users u ON u.id = o.customer_id
            WHERE o.store_id = :storeId
              AND o.status <> 'CANCELLED'
            GROUP BY
                COALESCE(CAST(o.customer_id AS VARCHAR(36)),
                         CONCAT('guest:', COALESCE(NULLIF(TRIM(o.customer_phone), ''), o.customer_email))),
                o.customer_id, u.full_name, u.phone, u.email
            ORDER BY MAX(o.created_at) DESC
            """, nativeQuery = true)
    List<CustomerSummaryProjection> queryCustomerSummaries(@Param("storeId") UUID storeId);

    // Reports page "Top Products" — no separate endpoint needed for "Revenue by Category" either,
    // since categoryName is included here and the frontend re-aggregates this same response by
    // category client-side rather than this ticket adding a second near-identical query.
    // productId/categoryName are null for a line item whose product was since deleted — the name
    // still comes from the order's own snapshot (product_name_snapshot), so historical revenue
    // isn't silently dropped just because the product no longer exists. Cancelled orders are
    // excluded, same convention as queryCustomerSummaries/DailyStoreSalesSyncService.
    @Query(value = """
            SELECT
                oi.product_id AS productId,
                COALESCE(p.name_en, oi.product_name_snapshot) AS name,
                c.name_en AS categoryName,
                SUM(oi.quantity) AS unitsSold,
                SUM(oi.total) AS revenue
            FROM order_items oi
            JOIN customer_orders o ON o.id = oi.order_id
            LEFT JOIN products p ON p.id = oi.product_id
            LEFT JOIN categories c ON c.id = p.category_id
            WHERE o.store_id = :storeId
              AND o.status <> 'CANCELLED'
              AND o.created_at >= :from
              AND o.created_at < :toExclusive
            GROUP BY oi.product_id, COALESCE(p.name_en, oi.product_name_snapshot), c.name_en
            ORDER BY SUM(oi.total) DESC
            """, nativeQuery = true)
    List<TopProductProjection> queryTopProducts(@Param("storeId") UUID storeId,
                                                 @Param("from") Instant from,
                                                 @Param("toExclusive") Instant toExclusive);
}
