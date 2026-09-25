package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.AnalyticsDtos;
import com.byonix.shoplink.api.dto.CustomerDtos;
import com.byonix.shoplink.api.dto.OrderDtos;
import com.byonix.shoplink.api.dto.StoreDtos;
import com.byonix.shoplink.domain.entity.*;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.DeliveryMethod;
import com.byonix.shoplink.domain.enums.InventoryAdjustmentReason;
import com.byonix.shoplink.domain.enums.OrderStatus;
import com.byonix.shoplink.domain.enums.PaymentMethod;
import com.byonix.shoplink.domain.enums.PaymentStatus;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.repository.OfferRepository;
import com.byonix.shoplink.repository.OrderExportProjection;
import com.byonix.shoplink.repository.OrderRepository;
import com.byonix.shoplink.repository.ProductModifierGroupRepository;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.repository.ProductVariantRepository;
import com.byonix.shoplink.repository.DeliveryZoneRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
@Transactional(readOnly = true)
public class OrderService {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductModifierGroupRepository modifierGroupRepository;
    private final InventoryLedger ledger;
    private final OfferRepository offerRepository;
    private final OfferService offerService;
    private final StoreService storeService;
    private final CurrentUserService currentUser;
    private final MapperService mapper;
    private final DailyStoreSalesSyncService dailyStoreSalesSync;
    private final OrderCodeGenerator orderCodeGenerator;
    private final BusinessHoursService businessHoursService;
    private final DeliveryZoneRepository deliveryZoneRepository;

    /** Compatibility constructor for focused transition tests that do not exercise checkout. */
    public OrderService(OrderRepository orderRepository, ProductRepository productRepository,
                         ProductVariantRepository variantRepository, ProductModifierGroupRepository modifierGroupRepository,
                         InventoryLedger ledger, OfferRepository offerRepository, OfferService offerService,
                         StoreService storeService, CurrentUserService currentUser, MapperService mapper,
                         DailyStoreSalesSyncService dailyStoreSalesSync, OrderCodeGenerator orderCodeGenerator,
                         BusinessHoursService businessHoursService) {
        this(orderRepository, productRepository, variantRepository, modifierGroupRepository, ledger, offerRepository,
                offerService, storeService, currentUser, mapper, dailyStoreSalesSync, orderCodeGenerator,
                businessHoursService, null);
    }

    @Transactional
    public OrderDtos.OrderResponse createPublicOrder(String slug, OrderDtos.CreateOrderRequest r) {
        Store store = storeService.publicStore(slug);
        businessHoursService.assertCanAcceptOrder(store);
        CustomerOrder order = new CustomerOrder();
        order.setStore(store);
        order.setCustomer(currentUser.customerOrNull());
        order.setCurrency(store.getCurrency());
        order.setOrderCode(orderCodeGenerator.generate());
        order.setCustomerName(r.customerName());
        order.setCustomerEmail(r.customerEmail() == null ? null : r.customerEmail().toLowerCase());
        order.setCustomerPhone(r.customerPhone());
        order.setCustomerAddress(r.customerAddress());
        order.setDeliveryMethod(r.deliveryMethod());
        order.setPaymentMethod(r.paymentMethod());
        order.setPaymentStatus(initialPaymentStatus(r.paymentMethod()));
        if (r.deliveryMethod() == null) {
            // Bean validation rejects this for real HTTP requests; retain the service-level
            // compatibility used by focused guard tests that exercise acceptance first.
            order.setDeliveryFee(nvl(r.deliveryFee()));
        } else if (r.deliveryMethod() == DeliveryMethod.PICKUP) {
            if (!store.isPickupAvailable()) throw new IllegalArgumentException("Pickup is not available");
            order.setCustomerAddress(null);
            order.setDeliveryFee(BigDecimal.ZERO);
        } else {
            if (r.customerAddress() == null || r.customerAddress().isBlank()) {
                throw new IllegalArgumentException("Delivery address is required");
            }
            if (r.deliveryZoneId() == null) throw new IllegalArgumentException("Delivery zone is required");
            var zone = deliveryZoneRepository.findByIdAndStore_Id(r.deliveryZoneId(), store.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Delivery zone is not available for this store"));
            if (!zone.isActive()) throw new IllegalArgumentException("Delivery zone is not available");
            order.setDeliveryFee(zone.getDeliveryFee());
        }
        order.setNotes(r.notes());
        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderDtos.CreateOrderItemRequest itemRequest : r.items()) {
            Product product = productRepository.findByIdAndStore_Id(itemRequest.productId(), store.getId())
                    .orElseThrow(() -> new EntityNotFoundException("Product not found"));
            if (!product.isAvailable()) {
                throw new IllegalArgumentException("Product unavailable");
            }
            // Resolve everything the customer chose (variant, add-ons) and validate ALL of it before
            // touching stock, so a bad add-on can't leave units decremented for a line that then fails.
            //
            // Everything read off `product`/`variant` for the order line is captured up front too:
            // the stock UPDATE below clears the persistence context, after which lazy properties (a
            // variant's option values) are no longer loadable.
            ProductVariant variant = null;
            String variantLabel = null;
            String skuSnapshot = product.getSku();
            BigDecimal unitPrice;
            if (product.isHasVariants()) {
                if (itemRequest.variantId() == null) {
                    throw new IllegalArgumentException("Please choose an option for \"" + product.getNameEn() + "\"");
                }
                // Scoped to this product AND this store: a variant id from anywhere else is "not found".
                variant = variantRepository.findByIdAndProduct_IdAndStore_Id(itemRequest.variantId(), product.getId(), store.getId())
                        .orElseThrow(() -> new EntityNotFoundException("Variant not found"));
                variantLabel = variant.label();
                if (!variant.isAvailable()) {
                    throw new IllegalArgumentException("\"" + product.getNameEn() + " (" + variantLabel + ")\" is unavailable");
                }
                unitPrice = variant.effectivePrice();
                if (variant.getSku() != null) {
                    skuSnapshot = variant.getSku();
                }
            } else {
                if (itemRequest.variantId() != null) {
                    throw new IllegalArgumentException("\"" + product.getNameEn() + "\" has no options to choose from");
                }
                unitPrice = product.getSalePrice() == null ? product.getPrice() : product.getSalePrice();
            }
            List<SelectedModifier> modifiers = resolveModifiers(product, itemRequest.modifierOptionIds());
            for (SelectedModifier m : modifiers) {
                unitPrice = unitPrice.add(m.priceDelta());
            }

            // Only stock that is actually tracked (stock IS NOT NULL) gates a purchase — a service
            // or an untracked product/variant is purchasable regardless. The decrement itself is a
            // single atomic UPDATE ... WHERE stock >= qty (see ProductRepository /
            // ProductVariantRepository), so two concurrent orders for the last unit can't both
            // succeed; whichever loses the race gets 0 rows affected here and the whole order rolls
            // back, same as any other exception mid-creation in this @Transactional method.
            // Every successful decrement is written to the stock ledger in this same transaction.
            String stockItemName = InventoryLedger.itemName(product.getNameEn(), variantLabel);
            if (variant != null) {
                if (variant.getStock() != null) {
                    if (variantRepository.decrementStockIfAvailable(variant.getId(), itemRequest.quantity()) == 0) {
                        throw new IllegalArgumentException("Insufficient stock for \"" + stockItemName + "\"");
                    }
                    ledger.record(store, product, variant, stockItemName, -itemRequest.quantity(),
                            variantRepository.findStockById(variant.getId()), InventoryAdjustmentReason.ORDER_PLACED,
                            order.getOrderCode(), null);
                }
            } else if (product.getStock() != null) {
                if (productRepository.decrementStockIfAvailable(product.getId(), itemRequest.quantity()) == 0) {
                    throw new IllegalArgumentException("Insufficient stock for \"" + stockItemName + "\"");
                }
                ledger.record(store, product, null, stockItemName, -itemRequest.quantity(),
                        productRepository.findStockById(product.getId()), InventoryAdjustmentReason.ORDER_PLACED,
                        order.getOrderCode(), null);
            }
            BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(itemRequest.quantity()));
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setVariant(variant);
            item.setVariantLabel(variantLabel);
            item.setSkuSnapshot(skuSnapshot);
            item.setProductNameSnapshot(product.getNameEn());
            item.setUnitPrice(unitPrice);
            item.setQuantity(itemRequest.quantity());
            item.setTotal(total);
            for (SelectedModifier m : modifiers) {
                OrderItemModifier snapshot = new OrderItemModifier();
                snapshot.setOrderItem(item);
                snapshot.setGroupName(m.groupName());
                snapshot.setOptionName(m.optionName());
                snapshot.setPriceDelta(m.priceDelta());
                item.getModifiers().add(snapshot);
            }
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
        if (r.deliveryMethod() == DeliveryMethod.DELIVERY) {
            var zone = deliveryZoneRepository.findByIdAndStore_Id(r.deliveryZoneId(), store.getId()).orElseThrow();
            if (zone.getMinOrder() != null && subtotal.compareTo(zone.getMinOrder()) < 0) {
                throw new IllegalArgumentException("Delivery minimum order is " + zone.getMinOrder());
            }
            if (store.getFreeDeliveryThreshold() != null && store.getFreeDeliveryThreshold().signum() > 0
                    && subtotal.compareTo(store.getFreeDeliveryThreshold()) >= 0) {
                order.setDeliveryFee(BigDecimal.ZERO);
            }
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
        currentUser.ensureListSectionAccess(DashboardSection.ORDERS, PermissionLevel.VIEW);
        return storeService.myStores().stream()
                .filter(s -> storeId == null || s.id().equals(storeId))
                .flatMap(s -> orderRepository.findByStore_IdOrderByCreatedAtDesc(s.id()).stream())
                .map(mapper::order).toList();
    }

    public List<OrderDtos.OrderResponse> customerOrders(int page, int size) {
        User customer = currentUser.user();
        if (customer.getRole() != com.byonix.shoplink.domain.enums.Role.CUSTOMER) {
            throw new org.springframework.security.access.AccessDeniedException("Customer access required");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
        return orderRepository.findByCustomer_Id(customer.getId(),
                        org.springframework.data.domain.PageRequest.of(page, size,
                                org.springframework.data.domain.Sort.by("createdAt").descending()
                                        .and(org.springframework.data.domain.Sort.by("id").descending()))).stream()
                .map(mapper::order).toList();
    }

    public OrderDtos.OrderResponse customerOrder(UUID id) {
        User customer = currentUser.user();
        if (customer.getRole() != com.byonix.shoplink.domain.enums.Role.CUSTOMER) {
            throw new org.springframework.security.access.AccessDeniedException("Customer access required");
        }
        CustomerOrder order = orderRepository.findByIdAndCustomer_Id(id, customer.getId())
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        return mapper.order(order);
    }

    public OrderDtos.OrderResponse dashboardOrder(UUID id) {
        CustomerOrder order = orderRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Order not found"));
        currentUser.ensureSectionAccess(order.getStore(), DashboardSection.ORDERS, PermissionLevel.VIEW);
        return mapper.order(order);
    }

    @Transactional
    public OrderDtos.OrderResponse updateStatus(UUID id, OrderDtos.StatusUpdateRequest request) {
        CustomerOrder order = orderRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Order not found"));
        currentUser.ensureSectionAccess(order.getStore(), DashboardSection.ORDERS, PermissionLevel.EDIT);
        OrderStatus previous = order.getStatus();
        if (request.status() != previous) {
            validateTransition(previous, request.status());
        }
        order.setStatus(request.status());
        if (request.status() == OrderStatus.CANCELLED && previous != OrderStatus.CANCELLED) {
            dailyStoreSalesSync.applyOrderCancelled(order);

            // Work out what goes back where BEFORE any stock UPDATE runs. Each restoreStock is a bulk
            // UPDATE with clearAutomatically: it empties the persistence context, which would both
            // drop the status change above (not flushed yet — the UPDATE touches other tables, so
            // Hibernate doesn't flush it first) and detach this order. So: collect, write the status
            // out, restore, then re-read the order fresh to build the response.
            Store store = order.getStore();
            String orderCode = order.getOrderCode();
            List<StockRestore> restores = new ArrayList<>();
            for (OrderItem item : order.getItems()) {
                // A variant line took its stock from the variant, never from the product — put it
                // back there. If that variant has since been deleted (variant_id is ON DELETE SET
                // NULL; the label snapshot survives) there is nothing to restore to, and it must NOT
                // fall through to the product, whose own stock this line never touched.
                if (item.getVariantLabel() != null) {
                    if (item.getVariant() != null) {
                        restores.add(new StockRestore(item.getProduct(), item.getVariant(), item.getQuantity(),
                                InventoryLedger.itemName(item.getProductNameSnapshot(), item.getVariantLabel())));
                    }
                    continue;
                }
                // product_id is nullable (ON DELETE SET NULL) — nothing to restore to if the
                // product itself was deleted since the order was placed.
                if (item.getProduct() != null) {
                    restores.add(new StockRestore(item.getProduct(), null, item.getQuantity(), item.getProductNameSnapshot()));
                }
            }
            orderRepository.flush();
            for (StockRestore restore : restores) {
                boolean tracked = restore.variant() != null
                        ? variantRepository.restoreStock(restore.variant().getId(), restore.quantity()) > 0
                        : productRepository.restoreStock(restore.product().getId(), restore.quantity()) > 0;
                if (tracked) {
                    Integer after = restore.variant() != null
                            ? variantRepository.findStockById(restore.variant().getId())
                            : productRepository.findStockById(restore.product().getId());
                    ledger.record(store, restore.product(), restore.variant(), restore.itemName(), restore.quantity(),
                            after, InventoryAdjustmentReason.ORDER_CANCELLED, orderCode, null);
                }
            }
            return mapper.order(orderRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Order not found")));
        }
        return mapper.order(order);
    }

    // The fulfillment sequence a non-cancelled order moves through. Skipping stages forward is
    // allowed (e.g. NEW straight to DELIVERED for a walk-in sale rung up after the fact) — only
    // going backward, or changing anything after DELIVERED/CANCELLED, is rejected. Those two are
    // terminal because each already ran a side effect (stock decrement, or restock + daily-sales
    // reversal on cancel) that a further status change would desync: e.g. un-cancelling back to
    // CONFIRMED must not silently leave stock restored without re-decrementing it.
    private static final List<OrderStatus> FORWARD_ORDER = List.of(
            OrderStatus.NEW, OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.DELIVERED);

    // Package-private (not private) so OrderServiceStatusTransitionTest can exercise it directly
    // without mocking the rest of updateStatus's side effects.
    void validateTransition(OrderStatus from, OrderStatus to) {
        if (from == OrderStatus.DELIVERED || from == OrderStatus.CANCELLED) {
            throw new IllegalArgumentException("Order is already " + from + " and its status can no longer be changed");
        }
        if (to == OrderStatus.CANCELLED) {
            return;
        }
        if (FORWARD_ORDER.indexOf(to) <= FORWARD_ORDER.indexOf(from)) {
            throw new IllegalArgumentException("Cannot move an order from " + from + " back to " + to);
        }
    }

    // Manual bookkeeping — no processor callback drives this (see PaymentStatus). Kept looser than
    // validateTransition on purpose: staff correcting their own mistake (e.g. marked PAID by
    // accident, or a charge that looked PENDING actually FAILED) is a normal, expected use of this
    // endpoint, not an error. The only two rules that actually matter: REFUNDED is terminal (a
    // fully refunded order is closed — a new charge is a new order), and you cannot refund money
    // that was never recorded as received (PAID or already PARTIALLY_REFUNDED).
    @Transactional
    public OrderDtos.OrderResponse updatePaymentStatus(UUID id, OrderDtos.PaymentStatusUpdateRequest request) {
        CustomerOrder order = orderRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Order not found"));
        currentUser.ensureSectionAccess(order.getStore(), DashboardSection.ORDERS, PermissionLevel.EDIT);
        PaymentStatus previous = order.getPaymentStatus();
        if (request.status() != previous) {
            validatePaymentTransition(previous, request.status());
        }
        order.setPaymentStatus(request.status());
        return mapper.order(order);
    }

    void validatePaymentTransition(PaymentStatus from, PaymentStatus to) {
        if (from == PaymentStatus.REFUNDED) {
            throw new IllegalArgumentException("Order is already fully refunded and its payment status can no longer be changed");
        }
        boolean refunding = to == PaymentStatus.REFUNDED || to == PaymentStatus.PARTIALLY_REFUNDED;
        boolean everReceived = from == PaymentStatus.PAID || from == PaymentStatus.PARTIALLY_REFUNDED;
        if (refunding && !everReceived) {
            throw new IllegalArgumentException("Cannot refund an order that was never marked paid");
        }
    }

    /** One line's stock to put back: the variant if the line was a variant line, else the product. */
    private record StockRestore(Product product, ProductVariant variant, int quantity, String itemName) {}

    private record SelectedModifier(String groupName, String optionName, BigDecimal priceDelta) {}

    /**
     * The add-ons a customer picked for one product, checked against that product's own groups: an
     * id that isn't one of this product's options (another product's, another store's, made up) is
     * "not found"; each group's min/max and each option's availability are enforced. Nothing is
     * ever selected on the customer's behalf.
     */
    private List<SelectedModifier> resolveModifiers(Product product, List<UUID> requestedIds) {
        Set<UUID> requested = new LinkedHashSet<>();
        if (requestedIds != null) {
            for (UUID id : requestedIds) {
                if (!requested.add(id)) {
                    throw new IllegalArgumentException("The same add-on was selected twice for \"" + product.getNameEn() + "\"");
                }
            }
        }
        List<ProductModifierGroup> groups = modifierGroupRepository.findWithOptionsByProductIdIn(List.of(product.getId()));
        Set<UUID> known = new HashSet<>();
        groups.forEach(g -> g.getOptions().forEach(o -> known.add(o.getId())));
        for (UUID id : requested) {
            if (!known.contains(id)) {
                throw new EntityNotFoundException("Add-on not found");
            }
        }
        List<SelectedModifier> chosen = new ArrayList<>();
        List<ProductModifierGroup> ordered = groups.stream()
                .sorted(Comparator.comparingInt(ProductModifierGroup::getSortOrder).thenComparing(ProductModifierGroup::getName))
                .toList();
        for (ProductModifierGroup group : ordered) {
            List<ProductModifierOption> picked = group.getOptions().stream()
                    .filter(o -> requested.contains(o.getId()))
                    .sorted(Comparator.comparingInt(ProductModifierOption::getSortOrder))
                    .toList();
            if (picked.size() < group.getMinSelect()) {
                throw new IllegalArgumentException("Choose at least " + group.getMinSelect() + " for \"" + group.getName()
                        + "\" on \"" + product.getNameEn() + "\"");
            }
            if (picked.size() > group.getMaxSelect()) {
                throw new IllegalArgumentException("Choose at most " + group.getMaxSelect() + " for \"" + group.getName()
                        + "\" on \"" + product.getNameEn() + "\"");
            }
            for (ProductModifierOption option : picked) {
                if (!option.isAvailable()) {
                    throw new IllegalArgumentException("\"" + option.getName() + "\" is unavailable");
                }
                chosen.add(new SelectedModifier(group.getName(), option.getName(), option.getPriceDelta()));
            }
        }
        return chosen;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // No payment gateway is wired up (see PaymentStatus) — this is just a sensible starting
    // bookkeeping state per method, which staff then move forward by hand via updatePaymentStatus.
    // CASH and WHATSAPP_ONLY are settled outside the system (on delivery/pickup, or arranged in
    // chat), so there's nothing "pending" about them yet; CARD implies a charge was meant to
    // happen at checkout, so it starts PENDING rather than UNPAID until staff confirm it actually
    // went through.
    private static PaymentStatus initialPaymentStatus(PaymentMethod method) {
        return method == PaymentMethod.CARD ? PaymentStatus.PENDING : PaymentStatus.UNPAID;
    }

    private static final int MAX_ANALYTICS_RANGE_DAYS = 400;

    public List<AnalyticsDtos.DailyStoreSalesRow> dailyStoreSales(LocalDate from, LocalDate to, UUID storeId) {
        validateReportRange(from, to);

        List<UUID> storeIds = new ArrayList<>();
        if (storeId != null) {
            reportStore(storeId);
            storeIds.add(storeId);
        } else {
            currentUser.ensureListSectionAccess(DashboardSection.REPORTS, PermissionLevel.VIEW);
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
    // the Reports page. limit caps the response in the query rather than loading every product a
    // busy store ever sold; the Reports page only shows a top-N table.
    public List<AnalyticsDtos.TopProductRow> topProducts(LocalDate from, LocalDate to, UUID storeId, int limit) {
        validateReportRange(from, to);
        if (limit < 1 || limit > MAX_TOP_PRODUCTS) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_TOP_PRODUCTS);
        }
        reportStore(storeId);

        return orderRepository.queryTopProducts(storeId, utcStart(from), utcStart(to.plusDays(1)), PageRequest.of(0, limit))
                .stream()
                .map(p -> new AnalyticsDtos.TopProductRow(
                        p.getProductId(),
                        p.getName(),
                        p.getCategoryName(),
                        p.getUnitsSold() == null ? 0L : p.getUnitsSold(),
                        p.getRevenue() == null ? BigDecimal.ZERO : p.getRevenue()))
                .toList();
    }

    // The Reports page's CSV: one row per order behind the numbers the page shows — same store
    // and permission check, same UTC date range, and cancelled orders excluded just as
    // daily_store_sales excludes them, so the file's totals reconcile with the page. Every value
    // is the order's own stored snapshot (including its currency, blank for pre-M1-02 orders);
    // nothing is recomputed from current prices, zones or Store.currency.
    public AnalyticsDtos.CsvFile ordersCsv(LocalDate from, LocalDate to, UUID storeId) {
        validateReportRange(from, to);
        reportStore(storeId);
        List<OrderExportProjection> rows = orderRepository.queryReportOrders(
                storeId, utcStart(from), utcStart(to.plusDays(1)), PageRequest.of(0, MAX_EXPORT_ROWS + 1));
        // Refuse rather than hand over a silently truncated file whose totals don't match the page.
        if (rows.size() > MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException("Too many orders to export (max " + MAX_EXPORT_ROWS
                    + "). Choose a shorter period.");
        }
        return new AnalyticsDtos.CsvFile("khangates-orders-" + from + "-to-" + to + ".csv", ReportCsv.orders(rows));
    }

    private static final int MAX_TOP_PRODUCTS = 100;
    private static final int MAX_EXPORT_ROWS = 50_000;

    private static void validateReportRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to dates are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' must be on or after 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_ANALYTICS_RANGE_DAYS) {
            throw new IllegalArgumentException("Date range too large (max " + MAX_ANALYTICS_RANGE_DAYS + " days)");
        }
    }

    // Owner, or this store's own staff with REPORTS view — never trusts storeId on its own.
    private void reportStore(UUID storeId) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.REPORTS, PermissionLevel.VIEW);
    }

    private static Instant utcStart(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    // Derived from real orders — no Customer entity exists. Same store-access check as every
    // other dashboard list (owner or the store's own MERCHANT_STAFF); see
    // OrderRepository.queryCustomerSummaries for how guest vs. registered orders are grouped.
    public List<CustomerDtos.CustomerSummaryResponse> customerSummaries(UUID storeId) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.CUSTOMERS, PermissionLevel.VIEW);
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
