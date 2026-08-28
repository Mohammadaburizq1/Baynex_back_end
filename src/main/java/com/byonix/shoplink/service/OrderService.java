package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.AnalyticsDtos;
import com.byonix.shoplink.api.dto.OrderDtos;
import com.byonix.shoplink.api.dto.StoreDtos;
import com.byonix.shoplink.domain.entity.*;
import com.byonix.shoplink.domain.enums.OrderStatus;
import com.byonix.shoplink.repository.OrderRepository;
import com.byonix.shoplink.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        order.setDiscount(nvl(r.discount()));
        order.setNotes(r.notes());
        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderDtos.CreateOrderItemRequest itemRequest : r.items()) {
            Product product = productRepository.findByIdAndStore_Id(itemRequest.productId(), store.getId())
                    .orElseThrow(() -> new EntityNotFoundException("Product not found"));
            if (!product.isAvailable()) {
                throw new IllegalArgumentException("Product unavailable");
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

    public List<OrderDtos.OrderResponse> dashboardOrders() {
        if (currentUser.isSuperAdmin()) {
            return orderRepository.findAll().stream().map(mapper::order).toList();
        }
        return storeService.myStores().stream()
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
        }
        return mapper.order(order);
    }

    private void ensureAccess(CustomerOrder order) {
        if (!currentUser.isSuperAdmin() && !order.getStore().getOwner().getId().equals(currentUser.user().getId())) {
            throw new AccessDeniedException("Access denied");
        }
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
            storeService.ownedStore(storeId);
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
}
