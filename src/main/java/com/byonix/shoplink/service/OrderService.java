package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.AnalyticsDtos;
import com.byonix.shoplink.api.dto.CustomerDtos;
import com.byonix.shoplink.api.dto.OrderDtos;
import com.byonix.shoplink.api.dto.StoreDtos;
import com.byonix.shoplink.domain.entity.*;
import com.byonix.shoplink.domain.enums.OrderStatus;
import com.byonix.shoplink.repository.OfferRepository;
import com.byonix.shoplink.repository.OrderRepository;
import com.byonix.shoplink.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OfferRepository offerRepository;
    private final OfferService offerService;
    private final StoreService storeService;
    private final CurrentUserService currentUser;
    private final MapperService mapper;
    private final DailyStoreSalesSyncService dailyStoreSalesSync;
    private final OrderCodeGenerator orderCodeGenerator;

    @Transactional
    public OrderDtos.OrderResponse createPublicOrder(String slug, OrderDtos.CreateOrderRequest r) {
        Store store = storeService.publicStore(slug);
        CustomerOrder order = new CustomerOrder();
        order.setStore(store);
        order.setCustomer(currentUser.user());
        order.setOrderCode(orderCodeGenerator.generate());
        order.setCustomerName(r.customerName());
        order.setCustomerEmail(r.customerEmail() == null ? null : r.customerEmail().toLowerCase());
        order.setCustomerPhone(r.customerPhone());
        order.setCustomerAddress(r.customerAddress());
        order.setDeliveryMethod(r.deliveryMethod());
        order.setPaymentMethod(r.paymentMethod());
        order.setDeliveryFee(nvl(r.deliveryFee()));
        order.setNotes(r.notes());
        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderDtos.CreateOrderItemRequest itemRequest : r.items()) {
            Product product = productRepository.findByIdAndStore_Id(itemRequest.productId(), store.getId())
                    .orElseThrow(() -> new EntityNotFoundException("Product not found"));
            if (!product.isAvailable()) {
                throw new IllegalArgumentException("Product unavailable");
            }
            // Only products that actually track stock (stock IS NOT NULL) are gated on it — a
            // service or an untracked product is purchasable regardless. The decrement itself is
            // a single atomic UPDATE ... WHERE stock >= qty (see ProductRepository), so two
            // concurrent orders for the last unit can't both succeed; whichever loses the race
            // gets 0 rows affected here and the whole order rolls back, same as any other
            // exception mid-creation in this @Transactional method.
            if (product.getStock() != null
                    && productRepository.decrementStockIfAvailable(product.getId(), itemRequest.quantity()) == 0) {
                throw new IllegalArgumentException("Insufficient stock for \"" + product.getNameEn() + "\"");
            }
            BigDecimal unitPrice = product.getSalePrice() == null ? product.getPrice() : product.getSalePrice();
            BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(itemRequest.quantity()));
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setProductNameSnapshot(product.getNameEn());
            item.setUnitPrice(unitPrice);
            item.setQuantity(itemRequest.quantity());
            item.setTotal(total);
            order.getItems().add(item);
            subtotal = subtotal.add(total);
        }
        order.setSubtotal(subtotal);
        if (r.discountCode() != null && !r.discountCode().isBlank()) {
            OfferService.DiscountResult result = offerService.validateAndComputeDiscount(store, r.discountCode(), subtotal);
            // Atomic guarded UPDATE — same all-or-nothing race protection as the per-item stock
            // decrement above. If another concurrent order just took the last available use of
            // this code, this returns 0 rows affected and the whole order (including any stock
            // already decremented for it) rolls back with it.
            if (offerRepository.incrementUsageIfAvailable(result.offer().getId()) == 0) {
                throw new IllegalArgumentException("This discount code has just reached its usage limit");
            }
            order.setOffer(result.offer());
            order.setDiscount(result.amount());
        }
        order.setTotal(subtotal.add(order.getDeliveryFee()).subtract(order.getDiscount()));
        if (order.getTotal().signum() < 0) {
            throw new IllegalArgumentException("Order total cannot be negative");
        }
        CustomerOrder saved = orderRepository.save(order);
        dailyStoreSalesSync.applyNewOrder(saved);
        return mapper.order(saved);
    }

    public OrderDtos.OrderResponse lookupPublicOrder(String slug, OrderDtos.OrderLookupRequest request) {
        storeService.publicStore(slug);
        String orderCode = request.orderCode().trim().toUpperCase();
        boolean hasEmail = request.email() != null && !request.email().isBlank();
        boolean hasPhone = request.phone() != null && !request.phone().isBlank();

        if (hasEmail == hasPhone) {
            throw new IllegalArgumentException("Provide orderCode with email, or orderCode with phone");
        }

        if (hasEmail) {
            String email = request.email().trim().toLowerCase();
            return orderRepository.findByStore_SlugAndOrderCodeIgnoreCaseAndCustomerEmailIgnoreCase(slug, orderCode, email)
                    .map(mapper::order)
                    .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        }

        return orderRepository.findByStore_SlugAndOrderCodeIgnoreCaseAndCustomerPhone(
                        slug, orderCode, request.phone().trim())
                .map(mapper::order)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
    }

    // storeId is optional — when present, scopes the result to just that store instead of every
    // store this merchant owns. Filtering happens against myStores() (or, for a super admin, a
    // direct lookup) rather than trusting the caller's id blindly, so passing a storeId the
    // caller doesn't own yields an empty list, never another merchant's orders.
    public List<OrderDtos.OrderResponse> dashboardOrders(UUID storeId) {
        if (currentUser.isSuperAdmin()) {
            List<CustomerOrder> orders = storeId != null
                    ? orderRepository.findByStore_IdOrderByCreatedAtDesc(storeId)
                    : orderRepository.findAll();
            return orders.stream().map(mapper::order).toList();
        }
        return storeService.myStores().stream()
                .filter(s -> storeId == null || s.id().equals(storeId))
                .flatMap(s -> orderRepository.findByStore_IdOrderByCreatedAtDesc(s.id()).stream())
                .map(mapper::order).toList();
    }

    public OrderDtos.OrderResponse dashboardOrder(UUID id) {
        CustomerOrder order = orderRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Order not found"));
        ensureAccess(order);
        return mapper.order(order);
    }

    @Transactional
    public OrderDtos.OrderResponse updateStatus(UUID id, OrderDtos.StatusUpdateRequest request) {
        CustomerOrder order = orderRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Order not found"));
        ensureAccess(order);
        OrderStatus previous = order.getStatus();
        order.setStatus(request.status());
        if (request.status() == OrderStatus.CANCELLED && previous != OrderStatus.CANCELLED) {
            dailyStoreSalesSync.applyOrderCancelled(order);
            for (OrderItem item : order.getItems()) {
                // product_id is nullable (ON DELETE SET NULL) — nothing to restore to if the
                // product itself was deleted since the order was placed.
                if (item.getProduct() != null) {
                    productRepository.restoreStock(item.getProduct().getId(), item.getQuantity());
                }
            }
        }
        return mapper.order(order);
    }

    private void ensureAccess(CustomerOrder order) {
        currentUser.ensureStoreAccess(order.getStore());
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final int MAX_ANALYTICS_RANGE_DAYS = 400;

    public List<AnalyticsDtos.DailyStoreSalesRow> dailyStoreSales(LocalDate from, LocalDate to, UUID storeId) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to dates are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' must be on or after 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_ANALYTICS_RANGE_DAYS) {
            throw new IllegalArgumentException("Date range too large (max " + MAX_ANALYTICS_RANGE_DAYS + " days)");
        }

        List<UUID> storeIds = new ArrayList<>();
        if (storeId != null) {
            storeService.accessibleStore(storeId);
            storeIds.add(storeId);
        } else {
            for (StoreDtos.StoreResponse s : storeService.myStores()) {
                storeIds.add(s.id());
            }
        }
        if (storeIds.isEmpty()) {
            return List.of();
        }

        return orderRepository.queryDailyStoreSales(storeIds, from, to).stream()
                .map(p -> new AnalyticsDtos.DailyStoreSalesRow(
                        p.getSaleDate(),
                        p.getStoreId(),
                        p.getStoreName(),
                        p.getTotalRevenue(),
                        p.getOrderCount() == null ? 0L : p.getOrderCount()))
                .toList();
    }

    // Same from/to validation and UTC day-boundary convention as dailyStoreSales (and as
    // DailyStoreSalesSyncService.saleDateUtc) — "the 7th" means the same thing on every chart on
    // the Reports page. limit caps the response rather than returning every product a busy store
    // ever sold; the Reports page only shows a top-N table.
    public List<AnalyticsDtos.TopProductRow> topProducts(LocalDate from, LocalDate to, UUID storeId, int limit) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to dates are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' must be on or after 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_ANALYTICS_RANGE_DAYS) {
            throw new IllegalArgumentException("Date range too large (max " + MAX_ANALYTICS_RANGE_DAYS + " days)");
        }
        storeService.accessibleStore(storeId);

        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        return orderRepository.queryTopProducts(storeId, fromInstant, toExclusive).stream()
                .map(p -> new AnalyticsDtos.TopProductRow(
                        p.getProductId(),
                        p.getName(),
                        p.getCategoryName(),
                        p.getUnitsSold() == null ? 0L : p.getUnitsSold(),
                        p.getRevenue() == null ? BigDecimal.ZERO : p.getRevenue()))
                .limit(limit)
                .toList();
    }

    // Derived from real orders — no Customer entity exists. Same store-access check as every
    // other dashboard list (owner or the store's own MERCHANT_STAFF); see
    // OrderRepository.queryCustomerSummaries for how guest vs. registered orders are grouped.
    public List<CustomerDtos.CustomerSummaryResponse> customerSummaries(UUID storeId) {
        storeService.accessibleStore(storeId);
        return orderRepository.queryCustomerSummaries(storeId).stream()
                .map(p -> new CustomerDtos.CustomerSummaryResponse(
                        p.getCustomerId(),
                        p.getName(),
                        p.getPhone(),
                        p.getEmail(),
                        p.getOrderCount() == null ? 0L : p.getOrderCount(),
                        p.getTotalSpent() == null ? BigDecimal.ZERO : p.getTotalSpent(),
                        p.getFirstOrderAt(),
                        p.getLastOrderAt()))
                .toList();
    }
}
