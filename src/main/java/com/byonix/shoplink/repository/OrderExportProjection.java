package com.byonix.shoplink.repository;

import com.byonix.shoplink.domain.enums.DeliveryMethod;
import com.byonix.shoplink.domain.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Flat stored values only: exporting does not initialize orders, items or modifiers. */
public interface OrderExportProjection {
    String getOrderCode();
    Instant getCreatedAt();
    OrderStatus getStatus();
    DeliveryMethod getDeliveryMethod();
    String getCustomerName();
    BigDecimal getSubtotal();
    BigDecimal getDiscount();
    BigDecimal getDeliveryFee();
    BigDecimal getTotal();
    String getCurrency();
}
