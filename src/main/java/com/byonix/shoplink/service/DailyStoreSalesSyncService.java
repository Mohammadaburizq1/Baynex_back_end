package com.byonix.shoplink.service;

import com.byonix.shoplink.domain.entity.CustomerOrder;
import com.byonix.shoplink.domain.enums.OrderStatus;
import org.springframework.dao.DataIntegrityViolationException;
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

    // Plain INSERT, falling back to UPDATE on the (store_id, sale_date) primary key conflict —
    // portable across Postgres and H2, unlike the Postgres-only "INSERT ... ON CONFLICT DO
    // UPDATE" upsert this replaced (H2 has no equivalent for updating with an expression that
    // references the pre-existing row, only whole-row replace). Relies on the primary key
    // constraint to make the fallback path race-safe under concurrent inserts for the same
    // store+day: only one INSERT can win, and the other lands in the catch block and updates.
    private void upsertDelta(UUID storeId, LocalDate saleDate, String storeName, BigDecimal deltaRevenue, long deltaCount) {
        try {
            jdbc.update("""
                    INSERT INTO daily_store_sales (store_id, sale_date, store_name, total_revenue, order_count)
                    VALUES (?, ?, ?, GREATEST(0, ?), GREATEST(0, ?))
                    """,
                    storeId, saleDate, storeName, deltaRevenue, deltaCount);
        } catch (DataIntegrityViolationException e) {
            int updated = jdbc.update("""
                    UPDATE daily_store_sales
                    SET total_revenue = GREATEST(0, total_revenue + ?),
                        order_count = GREATEST(0, order_count + ?),
                        store_name = ?
                    WHERE store_id = ? AND sale_date = ?
                    """,
                    deltaRevenue, deltaCount, storeName, storeId, saleDate);
            if (updated == 0) {
                throw e;
            }
        }
    }
}
