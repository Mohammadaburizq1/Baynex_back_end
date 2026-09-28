package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.domain.entity.CustomerOrder;
import com.byonix.shoplink.domain.entity.OrderItem;
import com.byonix.shoplink.domain.entity.OrderItemModifier;
import com.byonix.shoplink.domain.entity.PosDevice;
import com.byonix.shoplink.domain.entity.PosSyncConflict;
import com.byonix.shoplink.domain.entity.PosSyncOperation;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.ProductVariant;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.DeliveryMethod;
import com.byonix.shoplink.domain.enums.InventoryAdjustmentReason;
import com.byonix.shoplink.domain.enums.OrderSource;
import com.byonix.shoplink.domain.enums.OrderStatus;
import com.byonix.shoplink.domain.enums.PaymentMethod;
import com.byonix.shoplink.domain.enums.PaymentStatus;
import com.byonix.shoplink.domain.enums.PosSyncConflictType;
import com.byonix.shoplink.domain.enums.PosSyncOperationStatus;
import com.byonix.shoplink.repository.OrderRepository;
import com.byonix.shoplink.repository.PosDeviceRepository;
import com.byonix.shoplink.repository.PosSyncConflictRepository;
import com.byonix.shoplink.repository.PosSyncOperationRepository;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.repository.ProductVariantRepository;
import com.byonix.shoplink.repository.StoreRepository;
import com.byonix.shoplink.security.pos.PosDevicePrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * POS-09/10/11: turns an offline POS sale into an ordinary khanGates order, exactly once.
 *
 * <h2>Idempotency</h2>
 * The device's operation id is the primary key of {@code pos_sync_operations}, written first in the
 * same transaction as the order, the stock movements and the daily-sales increment. A retry of an
 * applied operation (the response was lost) finds that row and gets the same order back; nothing
 * is created or moved again. Two concurrent uploads of the same operation serialize on that key: the
 * loser's transaction rolls back entirely and it answers with the winner's result. The same local
 * sale under a different operation id is caught by (device, local order id).
 *
 * <h2>Validation (the device is not trusted)</h2>
 * Store = the authenticated device's store. The sale must name a catalog version this device was
 * actually given (see PosPriceBookService); every product, variant and add-on must be in that price
 * book and every unit price, line total, subtotal and total must match it exactly. Anything else is
 * a permanent rejection (422) and nothing is written.
 *
 * <h2>Policy for completed offline sales (POS-11)</h2>
 * The customer has paid and left, so a validated sale is never rejected because the catalog moved on:
 * <ul>
 *   <li>Price: the sale keeps the price of the snapshot it was rung up on; a later price change is
 *       recorded as PRICE_CHANGED for the merchant, never applied to the sale.</li>
 *   <li>Stock: moved through the same guarded decrement + stock ledger as a web order. When the
 *       central count cannot cover the sale (another device or the website sold the same units), the
 *       count goes to zero — the schema forbids negative stock — and the shortfall is recorded as
 *       OVERSOLD. The central count stays authoritative.</li>
 *   <li>Product/variant disabled after the sale: the sale is kept and stock still moves; recorded.
 *       Deleted: the sale is kept from its snapshot (names, prices), no stock can move; recorded.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PosOrderSyncService {
    static final String WALK_IN_CUSTOMER = "Walk-in customer";
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final PlatformTransactionManager transactionManager;
    private final PosDeviceRepository deviceRepository;
    private final StoreRepository storeRepository;
    private final PosSyncOperationRepository operationRepository;
    private final PosSyncConflictRepository conflictRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final PosPriceBookService priceBooks;
    private final InventoryLedger ledger;
    private final DailyStoreSalesSyncService dailyStoreSalesSync;
    private final OrderCodeGenerator orderCodeGenerator;
    private final com.byonix.shoplink.repository.OfferRepository offerRepository;
    private final com.byonix.shoplink.repository.UserRepository userRepository;
    private final PosStaffService staffService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public PosDtos.SyncOrderResponse sync(PosDevicePrincipal principal, PosDtos.SyncOrderRequest request) {
        try {
            return new TransactionTemplate(transactionManager).execute(status -> apply(principal, request));
        } catch (DataIntegrityViolationException raced) {
            // A concurrent upload of this operation (or of this sale) committed first; this attempt
            // rolled back completely. Answer with what was applied.
            return new TransactionTemplate(transactionManager).execute(status -> {
                Store store = uploaderStore(principal);
                return findExisting(request, store).map(op -> replay(op, store, requestHash(request)))
                        .orElseThrow(() -> raced);
            });
        }
    }

    // ── apply ─────────────────────────────────────────────────────────────────────────────────

    private record Line(PosPriceBookService.BookProduct product, PosPriceBookService.BookVariant variant,
                        List<SelectedAddOn> addOns, BigDecimal basePrice, BigDecimal unitPrice, int quantity,
                        BigDecimal lineTotal) {
        String itemName() {
            return InventoryLedger.itemName(product.name(), variant == null ? null : variant.label());
        }
    }

    private record SelectedAddOn(String groupName, String optionName, BigDecimal priceDelta) {}

    private PosDtos.SyncOrderResponse apply(PosDevicePrincipal principal, PosDtos.SyncOrderRequest r) {
        PosDevice uploader = uploader(principal);
        // Loaded (not a lazy proxy) before any stock UPDATE clears the persistence context.
        Store store = storeRepository.findById(principal.storeId()).orElseThrow();
        store.getName();
        PosDevice origin = r.originDeviceId().equals(uploader.getId()) ? uploader
                : deviceRepository.findById(r.originDeviceId())
                        .filter(d -> d.getStore().getId().equals(store.getId()))
                        .orElseThrow(() -> new PosSyncRejectedException("FOREIGN_DEVICE",
                                "This sale was made on a device that does not belong to this store", HttpStatus.FORBIDDEN));

        String hash = requestHash(r);
        Optional<PosSyncOperation> existing = findExisting(r, store);
        if (existing.isPresent()) {
            return replay(existing.get(), store, hash);
        }

        PosPriceBookService.PriceBook book = priceBooks.deliveredPriceBook(origin.getId(), store.getId(), r.catalogVersion())
                .orElseThrow(() -> new PosSyncRejectedException("CATALOG_VERSION_UNKNOWN",
                        "This sale refers to a catalog version this device was never given"));
        List<Line> lines = validate(r, book);
        Instant soldAt = saleTime(r.soldAt(), origin, Instant.now());
        BigDecimal discount = validateDiscount(r, book, soldAt, lines);

        Instant now = Instant.now();
        PosSyncOperation op = new PosSyncOperation();
        op.setOperationId(r.operationId());
        op.setStoreId(store.getId());
        op.setDeviceId(origin.getId());
        op.setSubmittedByDeviceId(uploader.getId());
        op.setOperationType(PosSyncOperation.TYPE_ORDER);
        op.setEntityId(r.localOrderId());
        op.setRequestHash(hash);
        op.setCatalogVersion(r.catalogVersion());
        op.setStatus(PosSyncOperationStatus.APPLYING);
        op.setCreatedAt(now);
        // Written first: a concurrent duplicate blocks on this key and then fails, instead of both
        // uploads moving stock.
        operationRepository.saveAndFlush(op);

        String orderCode = orderCodeGenerator.generate();
        String ledgerNote = "POS sale " + r.receiptNumber();
        CustomerOrder order = new CustomerOrder();
        List<PosSyncConflict> conflicts = new ArrayList<>();
        Map<String, PosDtos.StockLevel> touched = new LinkedHashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        int lineNo = 0;
        for (Line line : lines) {
            lineNo++;
            Product product = productRepository.findByIdForUpdate(line.product().id())
                    .filter(p -> p.getStore().getId().equals(store.getId()))
                    .orElse(null);
            ProductVariant variant = null;
            StockTarget target = null;
            if (product == null) {
                conflicts.add(conflict(PosSyncConflictType.PRODUCT_DELETED, line,
                        "Deleted after this device's last sync. The sale is kept as sold; no stock could be moved."));
            } else {
                if (!product.isAvailable()) {
                    conflicts.add(conflict(PosSyncConflictType.PRODUCT_UNAVAILABLE, line,
                            "Switched off after this device's last sync. The sale is kept and its stock was moved."));
                }
                BigDecimal currentPrice;
                if (line.variant() != null) {
                    if (!product.isHasVariants()) {
                        conflicts.add(conflict(PosSyncConflictType.PRODUCT_CHANGED, line,
                                "The product no longer has options. The sale is kept; no stock was moved — check the count."));
                        currentPrice = null;
                    } else {
                        variant = variantRepository.findByIdForUpdate(line.variant().id())
                                .filter(v -> v.getProduct().getId().equals(product.getId()) && v.getStore().getId().equals(store.getId()))
                                .orElse(null);
                        if (variant == null) {
                            conflicts.add(conflict(PosSyncConflictType.VARIANT_DELETED, line,
                                    "This option was deleted after this device's last sync. The sale is kept; no stock could be moved."));
                            currentPrice = null;
                        } else {
                            if (!variant.isAvailable()) {
                                conflicts.add(conflict(PosSyncConflictType.VARIANT_UNAVAILABLE, line,
                                        "This option was switched off after this device's last sync. The sale is kept and its stock was moved."));
                            }
                            currentPrice = variant.effectivePrice();
                            target = new StockTarget(product, variant, variant.getStock());
                        }
                    }
                } else if (product.isHasVariants()) {
                    conflicts.add(conflict(PosSyncConflictType.PRODUCT_CHANGED, line,
                            "The product now has options, so its stock is kept per option. The sale is kept; no stock was moved — check the count."));
                    currentPrice = null;
                } else {
                    currentPrice = product.getSalePrice() != null ? product.getSalePrice() : product.getPrice();
                    target = new StockTarget(product, null, product.getStock());
                }
                if (currentPrice != null && currentPrice.compareTo(line.basePrice()) != 0) {
                    PosSyncConflict c = conflict(PosSyncConflictType.PRICE_CHANGED, line,
                            "The price changed after this device's last sync. The sale keeps the price that was charged.");
                    c.setSaleUnitPrice(line.basePrice());
                    c.setCurrentUnitPrice(currentPrice);
                    conflicts.add(c);
                }
            }
            if (target != null && target.stockBefore() != null) {
                moveStock(store, target, line, orderCode, ledgerNote, conflicts);
            }
            if (product != null) {
                touched.putIfAbsent(product.getId() + ":" + (variant == null ? "" : variant.getId()),
                        new PosDtos.StockLevel(product.getId(), variant == null ? null : variant.getId(), null));
            }

            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setVariant(variant);
            // History is the snapshot the cashier sold from, whatever the catalog says today.
            item.setProductNameSnapshot(line.product().name());
            item.setVariantLabel(line.variant() == null ? null : line.variant().label());
            item.setSkuSnapshot(line.variant() != null && line.variant().sku() != null ? line.variant().sku() : line.product().sku());
            item.setUnitPrice(line.unitPrice());
            item.setQuantity(line.quantity());
            item.setTotal(line.lineTotal());
            // POS-23: returns name the line they reverse by the till's line number (items keep payload order).
            item.setPosLineNo(lineNo);
            for (SelectedAddOn addOn : line.addOns()) {
                OrderItemModifier m = new OrderItemModifier();
                m.setOrderItem(item);
                m.setGroupName(addOn.groupName());
                m.setOptionName(addOn.optionName());
                m.setPriceDelta(addOn.priceDelta());
                item.getModifiers().add(m);
            }
            order.getItems().add(item);
            subtotal = subtotal.add(line.lineTotal());
        }

        // POS-13: the discount stays what the till gave (validated above against the device's snapshot);
        // what changed on the server since is recorded, never applied to this sale.
        com.byonix.shoplink.domain.entity.Offer offer = r.offer() == null ? null : applyOfferUsage(r.offer(), book, store, conflicts);
        order.setOffer(offer);

        order.setStore(store);
        order.setCurrency(book.currency());
        order.setOrderCode(orderCode);
        applyCustomer(order, r.customer(), store, conflicts);
        applyStaff(order, r.staff(), store, conflicts);
        order.setDeliveryMethod(DeliveryMethod.PICKUP);
        order.setPaymentMethod(r.paymentMethod() == PosDtos.PosPaymentMethod.CASH ? PaymentMethod.CASH : PaymentMethod.CARD);
        // Settled at the counter: cash in the drawer, or a card terminal the cashier saw approve.
        order.setPaymentStatus(PaymentStatus.PAID);
        // Handed over at the counter; DELIVERED is terminal, so no later status change can re-run
        // stock or daily-sales side effects for a sale that already happened.
        order.setStatus(OrderStatus.DELIVERED);
        order.setSubtotal(subtotal);
        order.setDeliveryFee(BigDecimal.ZERO);
        order.setDiscount(discount);
        order.setTotal(subtotal.subtract(discount));
        if (r.exchange() != null) {
            // POS-23: the replacement sale of an exchange; its credit is checked against the return
            // when both are on the server (PosReturnSyncService), never used to change this sale.
            if (r.exchange().credit().compareTo(order.getTotal()) > 0) {
                throw new PosSyncRejectedException("EXCHANGE_INVALID", "The exchange credit is larger than the sale it paid for");
            }
            order.setPosExchangeCredit(r.exchange().credit());
            order.setPosExchangeLocalReturnId(r.exchange().localReturnId());
        }
        order.setNotes(r.note() == null || r.note().isBlank() ? null : r.note().trim());
        order.setSource(OrderSource.POS);
        order.setPosDevice(origin);
        order.setPosLocalOrderId(r.localOrderId());
        order.setPosReceiptNumber(r.receiptNumber());
        // Reports count the sale on the day it happened, not the day it was uploaded. The device
        // clock is only trusted within bounds.
        order.setCreatedAt(soldAt);
        CustomerOrder saved = orderRepository.saveAndFlush(order);
        dailyStoreSalesSync.applyNewOrder(saved);
        recordOverrides(r, saved, store, origin, conflicts);

        for (PosSyncConflict c : conflicts) {
            c.setStoreId(store.getId());
            c.setDeviceId(origin.getId());
            c.setOperationId(op.getOperationId());
            c.setOrderId(saved.getId());
            c.setOrderCode(orderCode);
            c.setReceiptNumber(r.receiptNumber());
            c.setCreatedAt(now);
        }
        conflictRepository.saveAll(conflicts);

        op.setStatus(conflicts.isEmpty() ? PosSyncOperationStatus.SYNCED : PosSyncOperationStatus.SYNCED_WITH_CONFLICTS);
        op.setOrderId(saved.getId());
        op.setOrderCode(orderCode);
        op.setCompletedAt(Instant.now());
        op = operationRepository.saveAndFlush(op);

        return response(op, false, saved.getTotal(), saved.getCurrency(), touched.values(), conflicts);
    }

    private record StockTarget(Product product, ProductVariant variant, Integer stockBefore) {}

    /**
     * The same guarded decrement and ledger row as a website order. What the central count cannot
     * cover is not invented: the count stops at zero and the difference becomes an OVERSOLD conflict.
     */
    private void moveStock(Store store, StockTarget target, Line line, String orderCode, String note,
                           List<PosSyncConflict> conflicts) {
        int before = target.stockBefore();
        int applied = Math.min(line.quantity(), Math.max(before, 0));
        Integer after = before;
        if (applied > 0) {
            int rows = target.variant() != null
                    ? variantRepository.decrementStockIfAvailable(target.variant().getId(), applied)
                    : productRepository.decrementStockIfAvailable(target.product().getId(), applied);
            if (rows == 0) {
                throw new IllegalStateException("Locked stock row changed during a POS upload");
            }
            after = target.variant() != null
                    ? variantRepository.findStockById(target.variant().getId())
                    : productRepository.findStockById(target.product().getId());
            ledger.record(store, target.product(), target.variant(), line.itemName(), -applied, after,
                    InventoryAdjustmentReason.ORDER_PLACED, orderCode, note);
        }
        if (applied < line.quantity()) {
            PosSyncConflict c = conflict(PosSyncConflictType.OVERSOLD, line,
                    "Sold " + line.quantity() + " offline but the central count had " + Math.max(before, 0)
                            + ". Stock is now " + after + "; " + (line.quantity() - applied)
                            + " unit(s) were sold beyond the count. Recount and correct the stock.");
            c.setRequestedQuantity(line.quantity());
            c.setAppliedQuantity(applied);
            c.setShortfall(line.quantity() - applied);
            c.setStockBefore(before);
            c.setStockAfter(after);
            conflicts.add(c);
        }
    }

    // ── POS-12..14: customer, discount usage, cashier, manager approvals ─────────────────────

    /**
     * A named customer. An account is linked only if it already is this store's customer (it has an
     * order here); its name/phone/email then come from the account, never from the till, so verified
     * identity data is never overwritten. Anything else is kept as the contact typed at the till (a
     * guest customer of this store, grouped by phone/email like any guest order). Store B's customers
     * cannot be attached to Store A's sale: an account that never ordered here is not linked.
     */
    private void applyCustomer(CustomerOrder order, PosDtos.SaleCustomer c, Store store, List<PosSyncConflict> conflicts) {
        if (c == null) {
            order.setCustomerName(WALK_IN_CUSTOMER);
            order.setCustomerPhone("");
            return;
        }
        if (c.customerId() != null) {
            var account = userRepository.findById(c.customerId())
                    .filter(u -> u.getRole() == com.byonix.shoplink.domain.enums.Role.CUSTOMER && u.isActive())
                    .filter(u -> orderRepository.existsByStore_IdAndCustomer_IdAndStatusNot(store.getId(), u.getId(), OrderStatus.CANCELLED));
            if (account.isPresent()) {
                var u = account.get();
                order.setCustomer(u);
                order.setCustomerName(truncate(u.getFullName(), 160));
                order.setCustomerPhone(u.getPhone() == null ? "" : truncate(u.getPhone(), 40));
                order.setCustomerEmail(u.getEmail());
                return;
            }
            conflicts.add(other(PosSyncConflictType.CUSTOMER_UNLINKED, nameOr(c.name(), "Customer"),
                    "The customer account chosen at the till could not be linked to this store. The sale keeps the name and contact shown at the till."));
        }
        order.setCustomerName(nameOr(c.name(), WALK_IN_CUSTOMER));
        order.setCustomerPhone(c.phone() == null ? "" : c.phone().trim());
        order.setCustomerEmail(c.email() == null || c.email().isBlank() ? null : c.email().trim());
    }

    private com.byonix.shoplink.domain.entity.Offer applyOfferUsage(PosDtos.SaleOffer ref, PosPriceBookService.PriceBook book, Store store,
                                                                     List<PosSyncConflict> conflicts) {
        PosPriceBookService.BookOffer snapshot = book.offer(ref.offerId()).orElseThrow();
        var current = offerRepository.findByIdAndStore_Id(ref.offerId(), store.getId());
        if (current.isEmpty()) {
            conflicts.add(other(PosSyncConflictType.DISCOUNT_CHANGED, snapshot.code(),
                    "This discount code was deleted after the sale. The sale keeps the discount it gave."));
            return null;
        }
        var o = current.get();
        boolean changed = !o.isActive() || o.getDiscountType() != snapshot.discountType()
                || o.getDiscountValue().compareTo(snapshot.discountValue()) != 0
                || !java.util.Objects.equals(o.getMinOrderAmount() == null ? null : o.getMinOrderAmount().stripTrailingZeros(),
                        snapshot.minOrderAmount() == null ? null : snapshot.minOrderAmount().stripTrailingZeros());
        if (changed) {
            conflicts.add(other(PosSyncConflictType.DISCOUNT_CHANGED, snapshot.code(),
                    "This discount code was changed or switched off after the sale. The sale keeps the discount it gave."));
        }
        // The same guarded counter as a website order; a limit already reached is recorded, not undone.
        if (offerRepository.incrementUsageIfAvailable(o.getId()) == 0) {
            conflicts.add(other(PosSyncConflictType.DISCOUNT_LIMIT_REACHED, snapshot.code(),
                    "This discount code had already reached its usage limit when the sale was uploaded. The sale keeps the discount it gave."));
        }
        return offerRepository.findById(o.getId()).orElse(null);
    }

    /** The cashier: linked while they are still this store's POS user; the till's name is kept either way. */
    private void applyStaff(CustomerOrder order, PosDtos.SaleStaff s, Store store, List<PosSyncConflict> conflicts) {
        if (s == null) {
            return; // uploaded by an app version from before POS-14
        }
        order.setPosStaffName(truncate(s.name().trim(), 160));
        var member = staffService.currentMember(store, s.userId())
                .filter(m -> m.posLevel() != com.byonix.shoplink.domain.enums.PermissionLevel.NONE);
        if (member.isPresent()) {
            order.setPosStaff(userRepository.findById(s.userId()).orElse(null));
        } else {
            conflicts.add(other(PosSyncConflictType.STAFF_UNAVAILABLE, s.name(),
                    "The cashier is no longer a POS user of this store. The sale is kept with the name shown at the till."));
        }
    }

    /** Every manager approval is stored with the sale; one the grid cannot confirm is also flagged. */
    private void recordOverrides(PosDtos.SyncOrderRequest r, CustomerOrder saved, Store store, PosDevice origin,
                                 List<PosSyncConflict> conflicts) {
        if (r.overrides() == null) return;
        for (PosDtos.SaleOverride o : r.overrides()) {
            boolean verified = staffService.currentMember(store, o.managerId())
                    .filter(m -> m.posLevel() == com.byonix.shoplink.domain.enums.PermissionLevel.EDIT).isPresent();
            jdbc.update("""
                    INSERT INTO pos_manager_overrides (store_id, device_id, order_id, action, acting_staff_id, acting_staff_name,
                        manager_id, manager_name, detail, approved_at, verified)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, store.getId(), origin.getId(), saved.getId(), o.action(),
                    r.staff() == null ? null : existingUser(r.staff().userId()), r.staff() == null ? null : truncate(r.staff().name(), 160),
                    existingUser(o.managerId()), truncate(o.managerName(), 160), o.detail(), java.sql.Timestamp.from(o.approvedAt()), verified);
            if (!verified) {
                conflicts.add(other(PosSyncConflictType.OVERRIDE_UNVERIFIED, o.managerName(),
                        "The approval of " + o.action() + " at the till was given by someone who is not a POS manager of this store now. Check it."));
            }
        }
    }

    private UUID existingUser(UUID id) {
        return id != null && userRepository.existsById(id) ? id : null;
    }

    private static PosSyncConflict other(PosSyncConflictType type, String itemName, String detail) {
        PosSyncConflict c = new PosSyncConflict();
        c.setType(type);
        c.setItemName(truncate(itemName == null || itemName.isBlank() ? type.name() : itemName.trim(), 300));
        c.setDetail(detail);
        return c;
    }

    private static String nameOr(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : truncate(name.trim(), 160);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // ── validation against the device's price book ────────────────────────────────────────────

    private List<Line> validate(PosDtos.SyncOrderRequest r, PosPriceBookService.PriceBook book) {
        if (!book.currency().equalsIgnoreCase(r.currency())) {
            throw new PosSyncRejectedException("CURRENCY_MISMATCH", "The sale's currency does not match the store's catalog");
        }
        if (r.offer() == null && r.discount().signum() != 0) {
            // The discount amount is never taken on the device's word: no code, no discount.
            throw new PosSyncRejectedException("DISCOUNT_MISMATCH", "A discount was sent without the discount code it came from");
        }
        List<Line> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (PosDtos.SyncOrderItem item : r.items()) {
            PosPriceBookService.BookProduct product = book.product(item.productId())
                    .orElseThrow(() -> new PosSyncRejectedException("UNKNOWN_ITEM",
                            "An item in this sale was not in the catalog this device was given"));
            PosPriceBookService.BookVariant variant = null;
            BigDecimal base;
            if (product.hasVariants()) {
                if (item.variantId() == null) {
                    throw new PosSyncRejectedException("INVALID_ITEM", "\"" + product.name() + "\" was sold without choosing an option");
                }
                variant = product.variants().stream().filter(v -> v.id().equals(item.variantId())).findFirst()
                        .orElseThrow(() -> new PosSyncRejectedException("UNKNOWN_ITEM",
                                "An option in this sale was not in the catalog this device was given"));
                if (!variant.available()) {
                    throw new PosSyncRejectedException("INVALID_ITEM", "\"" + product.name() + " (" + variant.label()
                            + ")\" was not available in the catalog this device was given");
                }
                base = variant.price();
            } else {
                if (item.variantId() != null) {
                    throw new PosSyncRejectedException("INVALID_ITEM", "\"" + product.name() + "\" has no options");
                }
                base = product.price();
            }
            List<SelectedAddOn> addOns = addOns(product, item.modifierOptionIds());
            BigDecimal unit = base;
            for (SelectedAddOn a : addOns) unit = unit.add(a.priceDelta());
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(item.quantity()));
            if (unit.compareTo(item.unitPrice()) != 0 || lineTotal.compareTo(item.lineTotal()) != 0) {
                throw new PosSyncRejectedException("PRICE_MISMATCH", "The price of \"" + product.name()
                        + "\" does not match the catalog this sale was made from");
            }
            lines.add(new Line(product, variant, addOns, base, unit, item.quantity(), lineTotal));
            subtotal = subtotal.add(lineTotal);
        }
        if (subtotal.compareTo(r.subtotal()) != 0 || subtotal.subtract(r.discount()).compareTo(r.total()) != 0) {
            throw new PosSyncRejectedException("TOTAL_MISMATCH", "The sale's totals do not add up");
        }
        return lines;
    }

    /**
     * POS-13: the discount is recomputed from the offer as this device's catalog snapshot had it, with
     * the existing offer rules (validity window at the time of sale, minimum order, percentage rounded
     * to 3 decimals half-up, never more than the subtotal). The device's own amount must equal it.
     */
    private BigDecimal validateDiscount(PosDtos.SyncOrderRequest r, PosPriceBookService.PriceBook book, Instant soldAt, List<Line> lines) {
        if (r.offer() == null) {
            return BigDecimal.ZERO;
        }
        PosPriceBookService.BookOffer offer = book.offer(r.offer().offerId())
                .filter(o -> o.code().equalsIgnoreCase(r.offer().code().trim()))
                .orElseThrow(() -> new PosSyncRejectedException("UNKNOWN_DISCOUNT",
                        "This sale used a discount code that was not in the catalog this device was given"));
        java.time.OffsetDateTime at = soldAt.atOffset(java.time.ZoneOffset.UTC);
        if ((offer.startsAt() != null && at.isBefore(offer.startsAt())) || (offer.expiresAt() != null && at.isAfter(offer.expiresAt()))) {
            throw new PosSyncRejectedException("DISCOUNT_NOT_VALID", "The discount code \"" + offer.code() + "\" was not valid at the time of this sale");
        }
        BigDecimal subtotal = lines.stream().map(Line::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (offer.minOrderAmount() != null && subtotal.compareTo(offer.minOrderAmount()) < 0) {
            throw new PosSyncRejectedException("DISCOUNT_NOT_VALID", "This sale is below the minimum order of \"" + offer.code() + "\"");
        }
        BigDecimal amount = OfferService.computeAmount(offer.discountType(), offer.discountValue(), subtotal);
        if (amount.compareTo(r.discount()) != 0) {
            throw new PosSyncRejectedException("DISCOUNT_MISMATCH", "The discount on this sale does not match the code \"" + offer.code() + "\"");
        }
        return amount;
    }

    private static List<SelectedAddOn> addOns(PosPriceBookService.BookProduct product, List<UUID> ids) {
        Set<UUID> requested = new LinkedHashSet<>();
        if (ids != null) {
            for (UUID id : ids) {
                if (!requested.add(id)) {
                    throw new PosSyncRejectedException("INVALID_ITEM", "The same add-on was selected twice for \"" + product.name() + "\"");
                }
            }
        }
        long known = product.groups().stream().flatMap(g -> g.options().stream()).filter(o -> requested.contains(o.id())).count();
        if (known != requested.size()) {
            throw new PosSyncRejectedException("UNKNOWN_ITEM", "An add-on in this sale was not in the catalog this device was given");
        }
        List<SelectedAddOn> chosen = new ArrayList<>();
        for (PosPriceBookService.BookGroup group : product.groups()) {
            List<PosPriceBookService.BookOption> picked = group.options().stream().filter(o -> requested.contains(o.id())).toList();
            if (picked.size() < group.minSelect() || picked.size() > group.maxSelect()) {
                throw new PosSyncRejectedException("INVALID_ITEM", "The add-ons chosen for \"" + product.name()
                        + "\" break the \"" + group.name() + "\" rule");
            }
            for (PosPriceBookService.BookOption option : picked) {
                if (!option.available()) {
                    throw new PosSyncRejectedException("INVALID_ITEM", "\"" + option.name() + "\" was not available");
                }
                chosen.add(new SelectedAddOn(group.name(), option.name(), option.priceDelta()));
            }
        }
        return chosen;
    }

    // ── idempotent replay ─────────────────────────────────────────────────────────────────────

    private Optional<PosSyncOperation> findExisting(PosDtos.SyncOrderRequest r, Store store) {
        Optional<PosSyncOperation> byId = operationRepository.findById(r.operationId());
        if (byId.isPresent()) return byId;
        // The same sale under another operation id: still one order.
        return operationRepository.findByDeviceIdAndOperationTypeAndEntityId(r.originDeviceId(), PosSyncOperation.TYPE_ORDER, r.localOrderId())
                .filter(op -> op.getStoreId().equals(store.getId()));
    }

    private PosDtos.SyncOrderResponse replay(PosSyncOperation op, Store store, String requestHash) {
        if (!op.getStoreId().equals(store.getId()) || !op.getRequestHash().equals(requestHash)) {
            // Never reveal anything about another store's operation, or apply a different sale
            // under an id that was already used.
            throw new PosSyncRejectedException("OPERATION_ID_REUSED",
                    "This operation id was already used for a different sale", HttpStatus.CONFLICT);
        }
        CustomerOrder order = orderRepository.findById(op.getOrderId()).orElseThrow();
        List<PosDtos.StockLevel> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (OrderItem item : order.getItems()) {
            if (item.getProduct() == null) continue;
            UUID variantId = item.getVariant() == null ? null : item.getVariant().getId();
            if (seen.add(item.getProduct().getId() + ":" + variantId)) {
                items.add(new PosDtos.StockLevel(item.getProduct().getId(), variantId, null));
            }
        }
        return response(op, true, order.getTotal(), order.getCurrency(), items,
                conflictRepository.findByOperationIdOrderByCreatedAtAsc(op.getOperationId()));
    }

    private PosDtos.SyncOrderResponse response(PosSyncOperation op, boolean replayed, BigDecimal total, String currency,
                                               Iterable<PosDtos.StockLevel> items, List<PosSyncConflict> conflicts) {
        // Current central counts, read after the change: what the device should now show.
        List<PosDtos.StockLevel> inventory = new ArrayList<>();
        for (PosDtos.StockLevel item : items) {
            Integer stock = item.variantId() != null ? variantRepository.findStockById(item.variantId())
                    : productRepository.findStockById(item.productId());
            inventory.add(new PosDtos.StockLevel(item.productId(), item.variantId(), stock));
        }
        return new PosDtos.SyncOrderResponse(op.getOperationId(), replayed, op.getStatus(), op.getOrderId(), op.getOrderCode(),
                total, currency, op.getCompletedAt(), inventory, conflicts.stream().map(c -> new PosDtos.SyncConflict(
                c.getId(), c.getType(), c.getProductId(), c.getVariantId(), c.getItemName(), c.getRequestedQuantity(),
                c.getAppliedQuantity(), c.getShortfall(), c.getDetail())).toList());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private PosDevice uploader(PosDevicePrincipal principal) {
        PosDevice device = deviceRepository.findById(principal.deviceId()).orElseThrow();
        if (!device.getStore().getId().equals(principal.storeId())) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }
        return device;
    }

    private Store uploaderStore(PosDevicePrincipal principal) {
        return storeRepository.findById(uploader(principal).getStore().getId()).orElseThrow();
    }

    private static PosSyncConflict conflict(PosSyncConflictType type, Line line, String detail) {
        PosSyncConflict c = new PosSyncConflict();
        c.setType(type);
        c.setProductId(line.product().id());
        c.setVariantId(line.variant() == null ? null : line.variant().id());
        c.setItemName(line.itemName().length() > 300 ? line.itemName().substring(0, 300) : line.itemName());
        c.setRequestedQuantity(line.quantity());
        c.setDetail(detail);
        return c;
    }

    private static Instant saleTime(Instant soldAt, PosDevice origin, Instant now) {
        if (soldAt.isAfter(now.plus(MAX_CLOCK_SKEW))) return now;
        Instant floor = origin.getCreatedAt() == null ? null : origin.getCreatedAt().minus(MAX_CLOCK_SKEW);
        if (floor != null && soldAt.isBefore(floor)) return origin.getCreatedAt();
        return soldAt.isAfter(now) ? now : soldAt;
    }

    /** Canonical form of everything that defines the sale (the operation id itself excluded). */
    static String requestHash(PosDtos.SyncOrderRequest r) {
        StringBuilder b = new StringBuilder()
                .append(r.originDeviceId()).append('|').append(r.localOrderId()).append('|').append(r.receiptNumber())
                .append('|').append(r.catalogVersion()).append('|').append(r.soldAt()).append('|')
                .append(r.currency().toUpperCase()).append('|').append(r.paymentMethod()).append('|')
                .append(plain(r.subtotal())).append('|').append(plain(r.discount())).append('|').append(plain(r.total()))
                .append('|').append(Objects.toString(r.note(), ""));
        // POS-12..14 fields only when present, so uploads queued before them hash exactly as before.
        if (r.customer() != null) {
            b.append("|customer:").append(r.customer().customerId()).append(',').append(Objects.toString(r.customer().name(), ""))
                    .append(',').append(Objects.toString(r.customer().phone(), "")).append(',').append(Objects.toString(r.customer().email(), ""));
        }
        if (r.offer() != null) b.append("|offer:").append(r.offer().offerId()).append(',').append(r.offer().code());
        if (r.staff() != null) b.append("|staff:").append(r.staff().userId()).append(',').append(r.staff().name());
        if (r.overrides() != null) {
            for (PosDtos.SaleOverride o : r.overrides()) {
                b.append("|override:").append(o.action()).append(',').append(o.managerId()).append(',').append(o.approvedAt());
            }
        }
        if (r.exchange() != null) b.append("|exchange:").append(r.exchange().localReturnId()).append(',').append(plain(r.exchange().credit()));
        for (PosDtos.SyncOrderItem i : r.items()) {
            b.append("|item:").append(i.productId()).append(',').append(i.variantId()).append(',')
                    .append(i.modifierOptionIds() == null ? "" : i.modifierOptionIds().stream().map(UUID::toString).sorted().toList())
                    .append(',').append(i.quantity()).append(',').append(plain(i.unitPrice())).append(',').append(plain(i.lineTotal()));
        }
        return PosPriceBookService.sha256(b.toString());
    }

    private static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
