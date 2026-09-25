package com.byonix.shoplink.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An add-on the customer chose on an order line, copied as plain text and a number at purchase
 * time (no link back to the catalogue's add-on) so later edits or deletions never change history.
 */
@Getter
@Setter
@Entity
@Table(name = "order_item_modifiers")
public class OrderItemModifier {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @Column(name = "group_name", nullable = false, length = 80)
    private String groupName;

    @Column(name = "option_name", nullable = false, length = 80)
    private String optionName;

    @Column(name = "price_delta", nullable = false, precision = 12, scale = 3)
    private BigDecimal priceDelta = BigDecimal.ZERO;
}
