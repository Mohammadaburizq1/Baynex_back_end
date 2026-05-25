package com.byonix.shoplink.service;

import com.byonix.shoplink.domain.entity.CustomerOrder;
import com.byonix.shoplink.domain.enums.OrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Keeps {@code daily_store_sales} in sync when orders are placed or cancelled.
 */
@Service
public class DailyStoreSalesSyncService {
    private final JdbcTemplate jdbc;

    public DailyStoreSalesSyncService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void applyNewOrder(CustomerOrder order) {
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }
        upsertDelta(order.getStore().getId(), saleDateUtc(order), order.getStore().getName(),
                order.getTotal(), 1);
    }

    @Transactional
    public void applyOrderCancelled(CustomerOrder order) {
        upsertDelta(order.getStore().getId(), saleDateUtc(order), order.getStore().getName(),
                order.getTotal().negate(), -1);
    }

    private static LocalDate saleDateUtc(CustomerOrder order) {
        return LocalDate.ofInstant(order.getCreatedAt(), ZoneOffset.UTC);
    }

    private void upsertDelta(UUID storeId, LocalDate saleDate, String storeName, BigDecimal deltaRevenue, long deltaCount) {
        jdbc.update("""
                INSERT INTO daily_store_sales (store_id, sale_date, store_name, total_revenue, order_count)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (store_id, sale_date) DO UPDATE SET
                    total_revenue = GREATEST(0::numeric, daily_store_sales.total_revenue + EXCLUDED.total_revenue),
                    order_count = GREATEST(0::bigint, daily_store_sales.order_count + EXCLUDED.order_count),
                    store_name = EXCLUDED.store_name
                """,
                storeId, saleDate, storeName, deltaRevenue, deltaCount);
    }
}
