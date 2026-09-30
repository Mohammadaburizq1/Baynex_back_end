package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.api.dto.RestaurantDtos;
import com.byonix.shoplink.domain.entity.CustomerOrder;
import com.byonix.shoplink.domain.entity.Offer;
import com.byonix.shoplink.domain.entity.OrderItem;
import com.byonix.shoplink.domain.entity.OrderItemModifier;
import com.byonix.shoplink.domain.entity.PosDevice;
import com.byonix.shoplink.domain.entity.PosOrderPayment;
import com.byonix.shoplink.domain.entity.PosSyncConflict;
import com.byonix.shoplink.domain.entity.PosSyncOperation;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.ProductVariant;
import com.byonix.shoplink.domain.entity.RestaurantTable;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.DeliveryMethod;
import com.byonix.shoplink.domain.enums.InventoryAdjustmentReason;
import com.byonix.shoplink.domain.enums.OrderSource;
import com.byonix.shoplink.domain.enums.OrderStatus;
import com.byonix.shoplink.domain.enums.PaymentMethod;
import com.byonix.shoplink.domain.enums.PaymentStatus;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.domain.enums.PosSyncConflictType;
import com.byonix.shoplink.domain.enums.PosSyncOperationStatus;
import com.byonix.shoplink.repository.DeliveryZoneRepository;
import com.byonix.shoplink.repository.OfferRepository;
import com.byonix.shoplink.repository.OrderRepository;
import com.byonix.shoplink.repository.PosDeviceRepository;
import com.byonix.shoplink.repository.PosOrderPaymentRepository;
import com.byonix.shoplink.repository.PosShiftRepository;
import com.byonix.shoplink.repository.PosSyncConflictRepository;
import com.byonix.shoplink.repository.PosSyncOperationRepository;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.repository.ProductVariantRepository;
import com.byonix.shoplink.repository.RestaurantTableRepository;
import com.byonix.shoplink.repository.StoreRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.pos.PosDevicePrincipal;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * POS-26: restaurant orders, changed from one or more tills (possibly offline), every change exactly once.
 *
 * <h2>One order system</h2>
 * A restaurant order is an ordinary POS {@code customer_orders} row. While it is open it has status
 * CONFIRMED; it is DELIVERED/PAID when it closes, and a merged-away order is CANCELLED with a pointer to
 * the order that absorbed it. Lines are ordinary order items (the same price-book validation and stock
 * ledger as a completed POS sale, see {@link PosOrderSyncService#validateItem}/{@code commitLine}).
 *
 * <h2>Operations, not snapshots</h2>
 * A till never uploads a whole order to overwrite the server's. It uploads operations (open, add items,
 * void a line, move, merge, pay, close…), each with its own operation id — the primary key of
 * {@code pos_sync_operations}, written first in the same transaction — so a retry never applies twice.
 * Lines carry the id the till gave them, so additions from several tills merge; the same line added
 * twice is added once. Every applied operation raises the order's version.
 *
 * <h2>Conflicts are kept, never lost</h2>
 * What was ordered or paid at a till already happened. The server applies what it safely can and
 * records the rest for review: two tills changing the same line (line version), a table opened twice,
 * a payment beyond the bill, a close with a balance still due (the order stays open), a change to a
 * closed order (items reopen it), an approval that cannot be confirmed.
 *
 * <h2>Money and stock</h2>
 * Stock leaves when a line is sent (not at payment), through the ledger; a restocked void puts it back
 * (POS_VOID). Payments are explicit rows; the cash ones belong to the paying till's shift drawer.
 */
@Service
@RequiredArgsConstructor
public class PosRestaurantService {
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration DEFAULT_PULL_WINDOW = Duration.ofHours(24);

    private final PlatformTransactionManager transactionManager;
    private final EntityManager entityManager;
    private final PosDeviceRepository deviceRepository;
    private final StoreRepository storeRepository;
    private final PosSyncOperationRepository operationRepository;
    private final PosSyncConflictRepository conflictRepository;
    private final OrderRepository orderRepository;
    private final RestaurantTableRepository tableRepository;
    private final PosOrderPaymentRepository paymentRepository;
    private final PosShiftRepository shiftRepository;
    private final DeliveryZoneRepository zoneRepository;
    private final OfferRepository offerRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final UserRepository userRepository;
    private final PosPriceBookService priceBooks;
    private final PosOrderSyncService orderSync;
    private final PosStaffService staffService;
    private final InventoryLedger ledger;
    private final DailyStoreSalesSyncService dailyStoreSalesSync;
    private final OrderCodeGenerator orderCodeGenerator;
    private final JdbcTemplate jdbc;
    private final KitchenService kitchen;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    // ── entry points ──────────────────────────────────────────────────────────────────────────

    public RestaurantDtos.OpResponse apply(PosDevicePrincipal principal, RestaurantDtos.OpRequest request) {
        try {
            return new TransactionTemplate(transactionManager).execute(status -> applyOnce(principal, request));
        } catch (DataIntegrityViolationException raced) {
            // A concurrent upload of this operation committed first; this attempt rolled back entirely.
            return new TransactionTemplate(transactionManager).execute(status -> {
                Store store = storeRepository.findById(uploader(principal).getStore().getId()).orElseThrow();
                return findExisting(request, store).map(op -> replay(op, store, request))
                        .orElseThrow(() -> new PosSyncRejectedException("RESTAURANT_CONFLICT",
                                "Another change to this order was applied at the same time. Sync again.", HttpStatus.CONFLICT));
            });
        }
    }

    /** Open restaurant orders of the device's store, and every restaurant order changed since [since]. */
    @Transactional(readOnly = true)
    public RestaurantDtos.StateResponse state(PosDevicePrincipal principal, Instant since) {
        Store store = storeRepository.findById(uploader(principal).getStore().getId()).orElseThrow();
        Instant now = Instant.now();
        Instant from = since == null ? now.minus(DEFAULT_PULL_WINDOW) : since.minus(MAX_CLOCK_SKEW);
        List<CustomerOrder> orders = orderRepository.findRestaurantOrdersOpenOrChangedSince(store.getId(), from);
        Map<UUID, List<PosOrderPayment>> payments = new HashMap<>();
        if (!orders.isEmpty()) {
            for (PosOrderPayment p : paymentRepository.findByOrderIdInOrderByPaidAtAscIdAsc(orders.stream().map(CustomerOrder::getId).toList())) {
                payments.computeIfAbsent(p.getOrderId(), k -> new ArrayList<>()).add(p);
            }
        }
        Map<UUID, UUID> uids = new HashMap<>();
        orders.forEach(o -> uids.put(o.getId(), o.getPosLocalOrderId()));
        return new RestaurantDtos.StateResponse(now, orders.stream()
                .map(o -> state(o, payments.getOrDefault(o.getId(), List.of()), uids)).toList());
    }

    // ── apply ─────────────────────────────────────────────────────────────────────────────────

    private record Ctx(PosDevice uploader, Store store, PosDevice origin, RestaurantDtos.OpRequest r, Instant at,
                       List<PosSyncConflict> conflicts, Map<String, PosDtos.StockLevel> touched) {}

    private RestaurantDtos.OpResponse applyOnce(PosDevicePrincipal principal, RestaurantDtos.OpRequest r) {
        PosDevice uploader = uploader(principal);
        Store store = storeRepository.findById(principal.storeId()).orElseThrow();
        store.getName();
        PosDevice origin = r.originDeviceId().equals(uploader.getId()) ? uploader
                : deviceRepository.findById(r.originDeviceId()).filter(d -> d.getStore().getId().equals(store.getId()))
                        .orElseThrow(() -> new PosSyncRejectedException("FOREIGN_DEVICE",
                                "This change was made on a device that does not belong to this store", HttpStatus.FORBIDDEN));
        Optional<PosSyncOperation> existing = findExisting(r, store);
        if (existing.isPresent()) return replay(existing.get(), store, r);

        Instant now = Instant.now();
        PosSyncOperation op = new PosSyncOperation();
        op.setOperationId(r.operationId());
        op.setStoreId(store.getId());
        op.setDeviceId(origin.getId());
        op.setSubmittedByDeviceId(uploader.getId());
        op.setOperationType(operationType(r));
        op.setEntityId(entityId(r));
        op.setRequestHash(requestHash(r));
        op.setCatalogVersion(r.catalogVersion() == null || r.catalogVersion().isBlank() ? "-" : r.catalogVersion());
        op.setStatus(PosSyncOperationStatus.APPLYING);
        op.setCreatedAt(now);
        // Written first: a concurrent duplicate blocks on this key and then fails, instead of both applying.
        operationRepository.saveAndFlush(op);

        Ctx c = new Ctx(uploader, store, origin, r, clamp(r.occurredAt(), origin, now), new ArrayList<>(), new LinkedHashMap<>());
        if (staffService.currentMember(store, r.staff().userId()).filter(m -> m.posLevel() != PermissionLevel.NONE).isEmpty()) {
            c.conflicts().add(conflict(PosSyncConflictType.STAFF_UNAVAILABLE, r.staff().name(),
                    "This change was made by someone who is not a POS user of this store now. It is kept - check it."));
        }
        CustomerOrder order;
        CustomerOrder other = null;
        switch (r.type()) {
            case OPEN -> order = open(c, r.orderId(), require(r.open(), "open"));
            case ADD_ITEMS -> order = addItems(c);
            case VOID_LINE -> order = voidLine(c, require(r.voidLine(), "voidLine"));
            case UPDATE_LINE -> order = updateLine(c, require(r.lineUpdate(), "lineUpdate"));
            case UPDATE_DETAILS -> order = updateDetails(c, require(r.details(), "details"));
            case MOVE_TABLE -> order = moveTable(c, require(r.move(), "move"));
            case MERGE -> {
                CustomerOrder[] pair = merge(c, require(r.merge(), "merge"));
                order = pair[0];
                other = pair[1];
            }
            case TRANSFER_ITEMS -> {
                CustomerOrder[] pair = transfer(c, require(r.transfer(), "transfer"));
                order = pair[0];
                other = pair[1];
            }
            case APPLY_DISCOUNT -> order = applyDiscount(c, require(r.discount(), "discount"));
            case REMOVE_DISCOUNT -> order = removeDiscount(c);
            case ADD_PAYMENT -> order = addPayment(c, require(r.payment(), "payment"));
            case CLOSE -> order = close(c);
            default -> throw new PosSyncRejectedException("RESTAURANT_INVALID", "Unknown restaurant operation");
        }

        entityManager.flush();
        for (PosSyncConflict conflict : c.conflicts()) {
            conflict.setStoreId(store.getId());
            conflict.setDeviceId(origin.getId());
            conflict.setOperationId(op.getOperationId());
            conflict.setOrderId(order.getId());
            conflict.setOrderCode(order.getOrderCode());
            conflict.setReceiptNumber(order.getPosReceiptNumber());
            conflict.setCreatedAt(now);
        }
        conflictRepository.saveAll(c.conflicts());
        op.setStatus(c.conflicts().isEmpty() ? PosSyncOperationStatus.SYNCED : PosSyncOperationStatus.SYNCED_WITH_CONFLICTS);
        op.setOrderId(order.getId());
        op.setOrderCode(order.getOrderCode());
        op.setCompletedAt(Instant.now());
        operationRepository.saveAndFlush(op);
        return response(op, false, order, other, c.touched().values(), c.conflicts());
    }

    // ── OPEN ──────────────────────────────────────────────────────────────────────────────────

    private CustomerOrder open(Ctx c, UUID uid, RestaurantDtos.OpenPayload p) {
        if (!orderRepository.findRestaurantOrderIds(c.store().getId(), uid).isEmpty()
                || orderRepository.findByPosDevice_IdAndPosLocalOrderId(c.origin().getId(), uid).isPresent()) {
            throw new PosSyncRejectedException("RESTAURANT_ORDER_EXISTS", "This order id is already in use", HttpStatus.CONFLICT);
        }
        if (!c.store().getCurrency().equalsIgnoreCase(p.currency())) {
            throw new PosSyncRejectedException("CURRENCY_MISMATCH", "The order's currency is not the store's currency");
        }
        boolean dineIn = "DINE_IN".equals(p.orderType());
        if (dineIn != (p.tableId() != null)) {
            throw new PosSyncRejectedException("RESTAURANT_INVALID", "A dine-in order needs a table; takeaway and delivery have none");
        }
        RestaurantTable table = dineIn ? ownTable(c, p.tableId()) : null;
        if ("DELIVERY".equals(p.orderType())) {
            boolean contact = p.customer() != null && (p.customer().customerId() != null
                    || (p.customer().phone() != null && !p.customer().phone().isBlank()));
            if (!contact || p.deliveryAddress() == null || p.deliveryAddress().isBlank()) {
                throw new PosSyncRejectedException("RESTAURANT_INVALID", "A delivery needs the customer's phone and the delivery address");
            }
        }
        BigDecimal fee = p.deliveryFee() == null ? BigDecimal.ZERO : p.deliveryFee();
        PosShiftService.checkAmount(fee, c.store().getCurrency(), "The delivery fee");
        if (fee.signum() > 0 && !"DELIVERY".equals(p.orderType())) {
            throw new PosSyncRejectedException("RESTAURANT_INVALID", "Only a delivery has a delivery fee");
        }
        if (p.deliveryZoneId() != null) {
            zoneRepository.findByIdAndStore_Id(p.deliveryZoneId(), c.store().getId()).ifPresentOrElse(z -> {
                if (z.getDeliveryFee().compareTo(fee) != 0) {
                    c.conflicts().add(conflict(PosSyncConflictType.PRICE_CHANGED, "Delivery fee " + z.getName(),
                            "The zone's delivery fee is " + money(z.getDeliveryFee()) + " now; the till charged " + money(fee) + ". The order keeps the till's fee."));
                }
            }, () -> {
                throw new PosSyncRejectedException("RESTAURANT_INVALID", "The delivery zone is not one of this store's");
            });
        }
        if (table != null && !orderRepository.findOpenOrdersOnTable(c.store().getId(), table.getId()).isEmpty()) {
            c.conflicts().add(conflict(PosSyncConflictType.RESTAURANT_TABLE_OCCUPIED, table.getName(),
                    "Table " + table.getName() + " already had an open order when this one was opened (two tills offline?). Both are kept - merge or move one."));
        }

        CustomerOrder o = new CustomerOrder();
        o.setStore(c.store());
        o.setCurrency(c.store().getCurrency());
        o.setOrderCode(orderCodeGenerator.generate());
        orderSync.applyCustomer(o, p.customer(), c.store(), c.conflicts());
        o.setDeliveryMethod("DELIVERY".equals(p.orderType()) ? DeliveryMethod.DELIVERY : DeliveryMethod.PICKUP);
        o.setCustomerAddress(blank(p.deliveryAddress()) ? null : p.deliveryAddress().trim());
        o.setPaymentMethod(PaymentMethod.CASH); // placeholder while open; the payments are the truth
        o.setPaymentStatus(PaymentStatus.UNPAID);
        o.setStatus(OrderStatus.CONFIRMED);
        o.setSubtotal(BigDecimal.ZERO);
        o.setDiscount(BigDecimal.ZERO);
        o.setDeliveryFee(fee.setScale(3, RoundingMode.UNNECESSARY));
        o.setTotal(o.getDeliveryFee());
        o.setNotes(blank(p.note()) ? null : p.note().trim());
        o.setSource(OrderSource.POS);
        o.setPosDevice(c.origin());
        o.setPosLocalOrderId(uid);
        o.setPosReceiptNumber(p.receiptNumber().trim());
        o.setPosStaff(userRepository.findById(c.r().staff().userId()).orElse(null));
        o.setPosStaffName(truncate(c.r().staff().name().trim(), 160));
        o.setPosOrderType(p.orderType());
        o.setRestaurantTableId(table == null ? null : table.getId());
        o.setGuestCount(p.guestCount());
        PosDtos.SaleStaff waiter = p.waiter() != null ? p.waiter() : c.r().staff();
        o.setWaiterId(existingUser(waiter.userId()));
        o.setWaiterName(truncate(waiter.name().trim(), 160));
        o.setOriginalWaiterId(o.getWaiterId());
        o.setOriginalWaiterName(o.getWaiterName());
        o.setPosTicketNumber(blank(p.ticketNumber()) ? null : p.ticketNumber().trim());
        o.setPickupName(blank(p.pickupName()) ? null : p.pickupName().trim());
        o.setDeliveryZoneId(p.deliveryZoneId());
        o.setPosOrderVersion(1);
        o.setPosOpenedAt(c.at());
        o.setCreatedAt(c.at());
        CustomerOrder saved = orderRepository.saveAndFlush(o);
        event(c, saved, "OPENED", null, table == null ? null : table.getId(), Map.of("orderType", p.orderType(),
                "guestCount", String.valueOf(p.guestCount()), "waiter", saved.getWaiterName()), null);
        return saved;
    }

    // ── ADD_ITEMS ─────────────────────────────────────────────────────────────────────────────

    private CustomerOrder addItems(Ctx c) {
        List<RestaurantDtos.NewLine> items = c.r().items();
        if (items == null || items.isEmpty()) throw new PosSyncRejectedException("RESTAURANT_INVALID", "No items to add");
        CustomerOrder o = order(c);
        reopenIfClosed(c, o, "Items were added to an order that was already closed");
        String version = c.r().catalogVersion();
        PosPriceBookService.PriceBook book = version == null ? null
                : priceBooks.deliveredPriceBook(c.origin().getId(), c.store().getId(), version).orElse(null);
        if (book == null) {
            throw new PosSyncRejectedException("CATALOG_VERSION_UNKNOWN", "These items refer to a catalog version this device was never given");
        }
        if (!book.currency().equalsIgnoreCase(o.getCurrency())) {
            throw new PosSyncRejectedException("CURRENCY_MISMATCH", "The items' currency does not match the order");
        }
        // Validate every line before any stock moves: one invalid item refuses the whole operation.
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        List<RestaurantDtos.NewLine> fresh = new ArrayList<>();
        List<PosOrderSyncService.Line> valid = new ArrayList<>();
        for (RestaurantDtos.NewLine n : items) {
            // The same line sent again (another operation id, or twice in one request): added once.
            if (!seen.add(n.lineUid()) || lineExists(n.lineUid())) continue;
            fresh.add(n);
            valid.add(PosOrderSyncService.validateItem(book, new PosDtos.SyncOrderItem(n.productId(), n.variantId(),
                    n.modifierOptionIds(), n.quantity(), n.unitPrice(), n.lineTotal())));
        }
        // The guarded stock UPDATEs clear the persistence context: flush first, commit stock, then reload the order.
        entityManager.flush();
        UUID orderId = o.getId();
        String orderCode = o.getOrderCode(), ledgerNote = "POS table order " + o.getPosReceiptNumber();
        List<PosOrderSyncService.Resolved> resolvedLines = new ArrayList<>();
        for (PosOrderSyncService.Line line : valid) {
            resolvedLines.add(orderSync.commitLine(c.store(), line, orderCode, ledgerNote, c.conflicts(), c.touched()));
        }
        o = orderRepository.findByIdForUpdate(orderId).orElseThrow();
        int newVersion = o.getPosOrderVersion() + 1;
        int lineNo = nextLineNo(o);
        List<Map<String, Object>> added = new ArrayList<>();
        List<OrderItem> sentItems = new ArrayList<>();
        for (int i = 0; i < fresh.size(); i++) {
            RestaurantDtos.NewLine n = fresh.get(i);
            OrderItem item = PosOrderSyncService.newItem(o, valid.get(i), resolvedLines.get(i));
            item.setPosLineNo(lineNo++);
            item.setPosLineUid(n.lineUid());
            item.setItemNote(blank(n.note()) ? null : n.note().trim());
            item.setCourse(n.course());
            item.setAddedById(existingUser(c.r().staff().userId()));
            item.setAddedByName(truncate(c.r().staff().name().trim(), 160));
            item.setAddedAt(c.at());
            item.setAddedDeviceId(c.origin().getId());
            item.setSentVersion(newVersion);
            o.getItems().add(item);
            sentItems.add(item);
            added.add(Map.of("lineUid", n.lineUid().toString(), "name", item.getProductNameSnapshot(), "quantity", n.quantity(),
                    "lineTotal", money(n.lineTotal())));
        }
        recompute(o);
        bump(o);
        if (!added.isEmpty()) {
            event(c, o, "ITEMS_ADDED", null, null, Map.of("lines", added), null);
            // POS-27: the new lines (and only them) go to the kitchen, one ticket per station.
            entityManager.flush();
            kitchen.fire(c.store(), o, newVersion, sentItems, actor(c));
        }
        return o;
    }

    // ── VOID_LINE / UPDATE_LINE ───────────────────────────────────────────────────────────────

    private CustomerOrder voidLine(Ctx c, RestaurantDtos.VoidPayload v) {
        CustomerOrder o = order(c);
        OrderItem line = line(o, v.lineUid());
        if (line == null) {
            c.conflicts().add(conflict(PosSyncConflictType.RESTAURANT_LINE_CONFLICT, "Void",
                    "A void arrived for a line that is no longer on this order (moved or merged elsewhere). Nothing was voided - check it."));
            bump(o);
            return o;
        }
        if (v.reason() == RestaurantDtos.VoidReason.OTHER && blank(v.note())) {
            throw new PosSyncRejectedException("RESTAURANT_INVALID", "A void with reason OTHER needs a note");
        }
        String name = InventoryLedger.itemName(line.getProductNameSnapshot(), line.getVariantLabel());
        int applied = Math.min(v.quantity(), line.getQuantity());
        if ((v.baseLineVersion() != null && v.baseLineVersion() != line.getLineVersion()) || applied < v.quantity()) {
            c.conflicts().add(conflict(PosSyncConflictType.RESTAURANT_LINE_CONFLICT, name, "Voided " + v.quantity() + " of \"" + name
                    + "\" at the till, but the line had changed on another till (" + line.getQuantity() + " left). " + applied
                    + " voided - check the order."));
        }
        PosDtos.SaleOverride approval = checkApproval(c, RestaurantPolicy.VOID_APPROVAL, name + " void");
        if (applied > 0) {
            int restocked = 0;
            if (v.restock()) {
                // The guarded stock UPDATE clears the persistence context: flush first, then reload.
                entityManager.flush();
                UUID orderId = o.getId();
                restocked = restock(c, line, applied, o);
                o = orderRepository.findByIdForUpdate(orderId).orElseThrow();
                line = line(o, v.lineUid());
            }
            line.setQuantity(line.getQuantity() - applied);
            line.setVoidedQuantity(line.getVoidedQuantity() + applied);
            line.setTotal(line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())));
            line.setLineVersion(line.getLineVersion() + 1);
            recompute(o);
            overpaidCheck(c, o);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("lineUid", v.lineUid().toString());
            detail.put("name", name);
            detail.put("quantity", applied);
            detail.put("reason", v.reason().name());
            detail.put("note", v.note() == null ? "" : v.note());
            detail.put("restocked", restocked);
            detail.put("unitPrice", money(line.getUnitPrice()));
            event(c, o, "ITEM_VOIDED", null, null, detail, approval);
            // POS-27: the kitchen sees VOID and the reason on the ticket that carried the line.
            kitchen.voided(c.store().getId(), line.getId(), applied, v.reason().name(), approval == null ? null : existingUser(approval.managerId()), actor(c));
        }
        bump(o);
        return o;
    }

    private CustomerOrder updateLine(Ctx c, RestaurantDtos.LineUpdate u) {
        CustomerOrder o = order(c);
        OrderItem line = line(o, u.lineUid());
        String name = line == null ? "Line" : InventoryLedger.itemName(line.getProductNameSnapshot(), line.getVariantLabel());
        if (line == null || (u.baseLineVersion() != null && u.baseLineVersion() != line.getLineVersion()) || line.getQuantity() == 0) {
            // Two tills changed the same line: this change is not applied; the manager decides.
            c.conflicts().add(conflict(PosSyncConflictType.RESTAURANT_LINE_CONFLICT, name, "The note/course change to \"" + name
                    + "\" was not applied: another till changed or voided this line first. Check the order."));
            bump(o);
            return o;
        }
        line.setItemNote(blank(u.note()) ? null : u.note().trim());
        line.setCourse(u.course());
        line.setLineVersion(line.getLineVersion() + 1);
        kitchen.lineUpdated(c.store().getId(), line.getId(), line.getItemNote(), line.getCourse(), actor(c));
        bump(o);
        event(c, o, "LINE_UPDATED", null, null, Map.of("lineUid", u.lineUid().toString(), "note", u.note() == null ? "" : u.note(),
                "course", u.course() == null ? "" : u.course()), null);
        return o;
    }

    // ── UPDATE_DETAILS ────────────────────────────────────────────────────────────────────────

    private CustomerOrder updateDetails(Ctx c, RestaurantDtos.DetailsPayload d) {
        CustomerOrder o = order(c);
        Map<String, Object> detail = new LinkedHashMap<>();
        if (d.guestCount() != null) {
            detail.put("guestCount", d.guestCount());
            o.setGuestCount(d.guestCount());
        }
        if (d.note() != null) {
            detail.put("note", d.note());
            o.setNotes(blank(d.note()) ? null : d.note().trim());
        }
        if (d.pickupName() != null) o.setPickupName(blank(d.pickupName()) ? null : d.pickupName().trim());
        if (d.deliveryAddress() != null) o.setCustomerAddress(blank(d.deliveryAddress()) ? null : d.deliveryAddress().trim());
        if (d.customer() != null) orderSync.applyCustomer(o, d.customer(), c.store(), c.conflicts());
        if (d.waiter() != null && !Objects.equals(d.waiter().userId(), o.getWaiterId())) {
            PosDtos.SaleOverride approval = checkApproval(c, RestaurantPolicy.WAITER_APPROVAL, "Waiter change");
            String from = o.getWaiterName();
            o.setWaiterId(existingUser(d.waiter().userId()));
            o.setWaiterName(truncate(d.waiter().name().trim(), 160));
            event(c, o, "WAITER_ASSIGNED", null, null, Map.of("from", from == null ? "" : from, "to", o.getWaiterName()), approval);
        }
        bump(o);
        if (!detail.isEmpty()) event(c, o, "DETAILS_UPDATED", null, null, detail, null);
        return o;
    }

    // ── MOVE_TABLE ────────────────────────────────────────────────────────────────────────────

    private CustomerOrder moveTable(Ctx c, RestaurantDtos.MovePayload m) {
        CustomerOrder o = order(c);
        if (!"DINE_IN".equals(o.getPosOrderType())) throw new PosSyncRejectedException("RESTAURANT_INVALID", "Only a dine-in order is on a table");
        RestaurantTable to = ownTable(c, m.toTableId());
        UUID from = o.getRestaurantTableId();
        if (!Objects.equals(from, m.fromTableId())) {
            c.conflicts().add(conflict(PosSyncConflictType.RESTAURANT_LINE_CONFLICT, to.getName(),
                    "This order had already been moved on another till; it is now moved to " + to.getName() + " as asked. Check it."));
        }
        if (orderRepository.findOpenOrdersOnTable(c.store().getId(), to.getId()).stream().anyMatch(x -> !x.getId().equals(o.getId()))) {
            c.conflicts().add(conflict(PosSyncConflictType.RESTAURANT_TABLE_OCCUPIED, to.getName(),
                    "Table " + to.getName() + " already had an open order (another till). Both are kept - merge or move one."));
        }
        o.setRestaurantTableId(to.getId());
        bump(o);
        event(c, o, "TABLE_MOVED", from, to.getId(), Map.of("to", to.getName()), null);
        return o;
    }

    // ── MERGE ─────────────────────────────────────────────────────────────────────────────────

    /** Source's lines and payments move into the op's order; the source keeps its row (merged, CANCELLED). */
    private CustomerOrder[] merge(Ctx c, RestaurantDtos.MergePayload m) {
        CustomerOrder target = order(c);
        CustomerOrder source = lockByUid(c.store(), m.sourceOrderId());
        if (source == null) throw new PosSyncRejectedException("RESTAURANT_ORDER_UNKNOWN", "The order to merge is not on khanGates");
        if (source.getId().equals(target.getId())) {
            // Already merged into it (another till did the same merge): nothing more to move.
            bump(target);
            return new CustomerOrder[]{target, null};
        }
        if (source.getStatus() != OrderStatus.CONFIRMED) {
            c.conflicts().add(conflict(PosSyncConflictType.RESTAURANT_ORDER_CLOSED, source.getOrderCode(),
                    "The order to merge was already closed; nothing was merged. Check both orders."));
            bump(target);
            return new CustomerOrder[]{target, source};
        }
        reopenIfClosed(c, target, "Another table was merged into an order that was already closed");
        PosDtos.SaleOverride approval = checkApproval(c, RestaurantPolicy.MERGE_APPROVAL, "Merge tables");
        entityManager.flush();
        int offset = nextLineNo(target) - 1;
        jdbc.update("UPDATE order_items SET order_id = ?, pos_line_no = COALESCE(pos_line_no, 0) + ? WHERE order_id = ?",
                target.getId(), offset, source.getId());
        jdbc.update("UPDATE pos_order_payments SET order_id = ? WHERE order_id = ?", target.getId(), source.getId());
        kitchen.merged(source.getId(), target.getId());
        UUID targetId = target.getId(), sourceId = source.getId();
        Offer sourceOffer = source.getOffer();
        entityManager.clear();
        target = orderRepository.findByIdForUpdate(targetId).orElseThrow();
        source = orderRepository.findByIdForUpdate(sourceId).orElseThrow();
        if (target.getOffer() == null && sourceOffer != null) target.setOffer(offerRepository.findById(sourceOffer.getId()).orElse(null));
        source.setStatus(OrderStatus.CANCELLED);
        source.setPosMergedIntoOrderId(target.getId());
        source.setOffer(null);
        recompute(source);
        recompute(target);
        bump(source);
        bump(target);
        event(c, target, "TABLES_MERGED", source.getRestaurantTableId(), target.getRestaurantTableId(),
                Map.of("sourceOrder", source.getOrderCode()), approval);
        event(c, source, "MERGED_INTO", source.getRestaurantTableId(), target.getRestaurantTableId(),
                Map.of("targetOrder", target.getOrderCode()), approval);
        return new CustomerOrder[]{target, source};
    }

    // ── TRANSFER_ITEMS ────────────────────────────────────────────────────────────────────────

    private CustomerOrder[] transfer(Ctx c, RestaurantDtos.TransferPayload t) {
        CustomerOrder source = order(c);
        CustomerOrder target = lockByUid(c.store(), t.targetOrderId());
        if (target == null) {
            if (t.openTarget() == null) throw new PosSyncRejectedException("RESTAURANT_ORDER_UNKNOWN", "The target order is not on khanGates");
            target = open(c, t.targetOrderId(), t.openTarget());
        }
        if (target.getId().equals(source.getId())) throw new PosSyncRejectedException("RESTAURANT_INVALID", "Items cannot move to the same order");
        reopenIfClosed(c, target, "Items were moved to an order that was already closed");
        int lineNo = nextLineNo(target);
        List<Map<String, Object>> moved = new ArrayList<>();
        for (RestaurantDtos.TransferLine tl : t.lines()) {
            if (lineExists(tl.newLineUid())) continue; // moved already (retry under another operation id)
            OrderItem from = line(source, tl.lineUid());
            int q = from == null ? 0 : Math.min(tl.quantity(), from.getQuantity());
            if (q < tl.quantity()) {
                c.conflicts().add(conflict(PosSyncConflictType.RESTAURANT_LINE_CONFLICT, from == null ? "Move" : from.getProductNameSnapshot(),
                        "Moved " + tl.quantity() + " at the till but only " + q + " were still on the order (another till changed it). " + q + " moved."));
            }
            if (q == 0) continue;
            OrderItem to = new OrderItem();
            to.setOrder(target);
            to.setProduct(from.getProduct());
            to.setVariant(from.getVariant());
            to.setProductNameSnapshot(from.getProductNameSnapshot());
            to.setVariantLabel(from.getVariantLabel());
            to.setSkuSnapshot(from.getSkuSnapshot());
            // The original price snapshot moves with the goods; nothing is repriced.
            to.setUnitPrice(from.getUnitPrice());
            to.setQuantity(q);
            to.setTotal(from.getUnitPrice().multiply(BigDecimal.valueOf(q)));
            to.setPosLineNo(lineNo++);
            to.setPosLineUid(tl.newLineUid());
            to.setItemNote(from.getItemNote());
            to.setCourse(from.getCourse());
            to.setAddedById(from.getAddedById());
            to.setAddedByName(from.getAddedByName());
            to.setAddedAt(from.getAddedAt());
            to.setAddedDeviceId(from.getAddedDeviceId());
            to.setSentVersion(from.getSentVersion());
            for (OrderItemModifier fm : from.getModifiers()) {
                OrderItemModifier mm = new OrderItemModifier();
                mm.setOrderItem(to);
                mm.setGroupName(fm.getGroupName());
                mm.setOptionName(fm.getOptionName());
                mm.setPriceDelta(fm.getPriceDelta());
                to.getModifiers().add(mm);
            }
            target.getItems().add(to);
            from.setQuantity(from.getQuantity() - q);
            from.setTotal(from.getUnitPrice().multiply(BigDecimal.valueOf(from.getQuantity())));
            from.setLineVersion(from.getLineVersion() + 1);
            // Flush the new financial identity before moving its original kitchen allocation.
            entityManager.flush();
            kitchen.moved(c.store().getId(), from.getId(), to.getId(), q, actor(c));
            moved.add(Map.of("from", tl.lineUid().toString(), "to", tl.newLineUid().toString(), "name", from.getProductNameSnapshot(), "quantity", q));
        }
        recompute(source);
        recompute(target);
        overpaidCheck(c, source);
        bump(source);
        bump(target);
        event(c, source, "ITEMS_MOVED_OUT", source.getRestaurantTableId(), target.getRestaurantTableId(),
                Map.of("lines", moved, "targetOrder", target.getOrderCode()), null);
        event(c, target, "ITEMS_MOVED_IN", source.getRestaurantTableId(), target.getRestaurantTableId(),
                Map.of("lines", moved, "sourceOrder", source.getOrderCode()), null);
        return new CustomerOrder[]{source, target};
    }

    // ── discount ──────────────────────────────────────────────────────────────────────────────

    private CustomerOrder applyDiscount(Ctx c, RestaurantDtos.DiscountPayload d) {
        CustomerOrder o = order(c);
        PosPriceBookService.PriceBook book = priceBooks.deliveredPriceBook(c.origin().getId(), c.store().getId(), d.catalogVersion())
                .orElseThrow(() -> new PosSyncRejectedException("CATALOG_VERSION_UNKNOWN", "This discount refers to a catalog version this device was never given"));
        PosPriceBookService.BookOffer snapshot = book.offer(d.offerId()).filter(x -> x.code().equalsIgnoreCase(d.code().trim()))
                .orElseThrow(() -> new PosSyncRejectedException("UNKNOWN_DISCOUNT", "This discount code was not in the catalog this device was given"));
        Optional<Offer> current = offerRepository.findByIdAndStore_Id(d.offerId(), c.store().getId());
        if (current.isEmpty() || !current.get().isActive()) {
            c.conflicts().add(conflict(PosSyncConflictType.DISCOUNT_CHANGED, snapshot.code(),
                    "This discount code was deleted or switched off; it was not applied to the order."));
        } else {
            o.setOffer(current.get());
            recompute(o);
            overpaidCheck(c, o);
            event(c, o, "DISCOUNT_APPLIED", null, null, Map.of("code", snapshot.code(), "discount", money(o.getDiscount())), null);
        }
        bump(o);
        return o;
    }

    private CustomerOrder removeDiscount(Ctx c) {
        CustomerOrder o = order(c);
        if (o.getOffer() != null) {
            String code = o.getOffer().getCode();
            o.setOffer(null);
            recompute(o);
            event(c, o, "DISCOUNT_REMOVED", null, null, Map.of("code", code), null);
        }
        bump(o);
        return o;
    }

    // ── ADD_PAYMENT ───────────────────────────────────────────────────────────────────────────

    private CustomerOrder addPayment(Ctx c, RestaurantDtos.PaymentPayload p) {
        CustomerOrder o = order(c);
        PosShiftService.checkAmount(p.amount(), o.getCurrency(), "The payment");
        PosShiftService.checkShiftLink(shiftRepository, p.shiftId(), c.store().getId(), c.origin().getId());
        if (paymentRepository.existsById(p.paymentId())) {
            throw new PosSyncRejectedException("PAYMENT_ID_REUSED", "This payment id is already in use", HttpStatus.CONFLICT);
        }
        PosOrderPayment pay = new PosOrderPayment();
        pay.setId(p.paymentId());
        pay.setStoreId(c.store().getId());
        pay.setOrderId(o.getId());
        pay.setOperationId(c.r().operationId());
        pay.setAmount(p.amount().setScale(3, RoundingMode.UNNECESSARY));
        pay.setMethod(p.method());
        pay.setSplitMode(p.splitMode());
        pay.setAllocation(p.allocation());
        pay.setStaffId(existingUser(c.r().staff().userId()));
        pay.setStaffName(truncate(c.r().staff().name().trim(), 160));
        pay.setShiftId(p.shiftId());
        pay.setDeviceId(c.origin().getId());
        pay.setPaidAt(c.at());
        pay.setCreatedAt(Instant.now());
        paymentRepository.saveAndFlush(pay);
        // The money was taken at the till: it is always recorded; paying beyond the bill is flagged.
        overpaidCheck(c, o);
        bump(o);
        event(c, o, "PAYMENT_ADDED", null, null, Map.of("paymentId", p.paymentId().toString(), "amount", money(pay.getAmount()),
                "method", p.method().name(), "splitMode", p.splitMode().name()), null);
        return o;
    }

    // ── CLOSE ─────────────────────────────────────────────────────────────────────────────────

    private CustomerOrder close(Ctx c) {
        CustomerOrder o = order(c);
        if (o.getStatus() != OrderStatus.CONFIRMED) {
            bump(o); // closed already (another till): nothing more to do
            return o;
        }
        BigDecimal paid = paid(o);
        if (paid.compareTo(o.getTotal()) < 0) {
            // Never free a table with a balance due: the order stays open for the rest to be paid.
            c.conflicts().add(conflict(PosSyncConflictType.RESTAURANT_BALANCE_DUE, o.getOrderCode(),
                    "The till closed this order, but khanGates sees " + money(o.getTotal().subtract(paid)) + " " + o.getCurrency()
                            + " still to pay (another till added items or a payment is missing). The order stays open."));
            bump(o);
            return o;
        }
        boolean closedBefore = count("SELECT count(*) FROM pos_restaurant_events WHERE order_id = ? AND event_type = 'CLOSED'", o.getId()) > 0;
        if (o.getOffer() != null && !closedBefore) {
            // The guarded usage UPDATE clears the persistence context: flush first, then reload the order.
            entityManager.flush();
            UUID orderId = o.getId();
            String code = o.getOffer().getCode();
            if (offerRepository.incrementUsageIfAvailable(o.getOffer().getId()) == 0) {
                c.conflicts().add(conflict(PosSyncConflictType.DISCOUNT_LIMIT_REACHED, code,
                        "This discount code had already reached its usage limit when the order closed. The order keeps the discount it gave."));
            }
            o = orderRepository.findByIdForUpdate(orderId).orElseThrow();
        }
        List<PosOrderPayment> payments = paymentRepository.findByOrderIdOrderByPaidAtAscIdAsc(o.getId());
        o.setPaymentMethod(payments.stream().anyMatch(p -> p.getMethod() == PosOrderPayment.Method.EXTERNAL_TERMINAL)
                ? PaymentMethod.CARD : PaymentMethod.CASH);
        o.setPaymentStatus(PaymentStatus.PAID);
        o.setStatus(OrderStatus.DELIVERED);
        o.setPosClosedAt(c.at());
        bump(o);
        entityManager.flush();
        dailyStoreSalesSync.applyNewOrder(o);
        event(c, o, "CLOSED", null, null, Map.of("total", money(o.getTotal()), "paid", money(paid)), null);
        return o;
    }

    // ── shared ────────────────────────────────────────────────────────────────────────────────

    /** The op's order, locked; a merged-away order resolves to the order that absorbed it. */
    private CustomerOrder order(Ctx c) {
        CustomerOrder o = lockByUid(c.store(), c.r().orderId());
        if (o == null) {
            throw new PosSyncRejectedException("RESTAURANT_ORDER_UNKNOWN", "This order is not on khanGates. Its opening must be uploaded first.");
        }
        return o;
    }

    private CustomerOrder lockByUid(Store store, UUID uid) {
        List<UUID> ids = orderRepository.findRestaurantOrderIds(store.getId(), uid);
        if (ids.isEmpty()) return null;
        CustomerOrder o = orderRepository.findByIdForUpdate(ids.get(0)).orElseThrow();
        for (int hops = 0; o.getPosMergedIntoOrderId() != null && hops < 10; hops++) {
            o = orderRepository.findByIdForUpdate(o.getPosMergedIntoOrderId()).orElseThrow();
        }
        if (!o.getStore().getId().equals(store.getId())) return null; // never another store's
        return o;
    }

    /** A change that adds to a closed order reopens it (the items were ordered); the manager is told. */
    private void reopenIfClosed(Ctx c, CustomerOrder o, String why) {
        if (o.getStatus() != OrderStatus.DELIVERED) return;
        dailyStoreSalesSync.applyOrderCancelled(o);
        o.setStatus(OrderStatus.CONFIRMED);
        o.setPaymentStatus(PaymentStatus.UNPAID);
        o.setPosClosedAt(null);
        c.conflicts().add(conflict(PosSyncConflictType.RESTAURANT_ORDER_CLOSED, o.getOrderCode(),
                why + " (two tills offline?). It is open again until the rest is paid."));
    }

    private void overpaidCheck(Ctx c, CustomerOrder o) {
        BigDecimal paid = paid(o);
        if (paid.compareTo(o.getTotal()) > 0) {
            c.conflicts().add(conflict(PosSyncConflictType.RESTAURANT_OVERPAID, o.getOrderCode(), "Payments (" + money(paid)
                    + ") are more than the bill (" + money(o.getTotal()) + ") by " + money(paid.subtract(o.getTotal())) + " "
                    + o.getCurrency() + ". Check the drawer and refund the difference."));
        }
    }

    private void recompute(CustomerOrder o) {
        BigDecimal subtotal = o.getItems().stream().map(OrderItem::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = BigDecimal.ZERO;
        Offer f = o.getOffer();
        if (f != null && (f.getMinOrderAmount() == null || subtotal.compareTo(f.getMinOrderAmount()) >= 0)) {
            discount = OfferService.computeAmount(f.getDiscountType(), f.getDiscountValue(), subtotal).min(subtotal);
        }
        o.setSubtotal(subtotal.setScale(3, RoundingMode.HALF_UP));
        o.setDiscount(discount.setScale(3, RoundingMode.HALF_UP));
        o.setTotal(subtotal.subtract(discount).add(o.getDeliveryFee()).max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP));
    }

    private static void bump(CustomerOrder o) {
        o.setPosOrderVersion(o.getPosOrderVersion() + 1);
    }

    private BigDecimal paid(CustomerOrder o) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM pos_order_payments WHERE order_id = ?", BigDecimal.class, o.getId());
        return v == null ? BigDecimal.ZERO : v;
    }

    private static int nextLineNo(CustomerOrder o) {
        return o.getItems().stream().map(OrderItem::getPosLineNo).filter(Objects::nonNull).max(Integer::compare).orElse(0) + 1;
    }

    private static OrderItem line(CustomerOrder o, UUID uid) {
        return o.getItems().stream().filter(i -> uid.equals(i.getPosLineUid())).findFirst().orElse(null);
    }

    private boolean lineExists(UUID uid) {
        return count("SELECT count(*) FROM order_items WHERE pos_line_uid = ?", uid) > 0;
    }

    private long count(String sql, Object arg) {
        Long n = jdbc.queryForObject(sql, Long.class, arg);
        return n == null ? 0 : n;
    }

    private RestaurantTable ownTable(Ctx c, UUID tableId) {
        return tableRepository.findById(tableId).filter(t -> t.getStoreId().equals(c.store().getId()))
                .orElseThrow(() -> new PosSyncRejectedException("RESTAURANT_TABLE_UNKNOWN", "This table is not one of this store's"));
    }

    /** Puts voided units back on sale through the ledger (POS_VOID). Returns how many. */
    private int restock(Ctx c, OrderItem line, int quantity, CustomerOrder o) {
        Product product = line.getProduct() == null ? null
                : productRepository.findByIdForUpdate(line.getProduct().getId()).filter(p -> p.getStore().getId().equals(c.store().getId())).orElse(null);
        if (product == null) return 0;
        String name = InventoryLedger.itemName(line.getProductNameSnapshot(), line.getVariantLabel());
        String note = "POS void " + o.getPosReceiptNumber();
        if (line.getVariant() != null) {
            UUID variantId = line.getVariant().getId();
            ProductVariant variant = variantRepository.findByIdForUpdate(variantId).orElse(null);
            if (variant == null || variant.getStock() == null || variantRepository.restoreStock(variantId, quantity) == 0) return 0;
            ledger.record(c.store(), product, variant, name, quantity, variantRepository.findStockById(variantId),
                    InventoryAdjustmentReason.POS_VOID, o.getOrderCode(), note);
            c.touched().putIfAbsent(product.getId() + ":" + variantId, new PosDtos.StockLevel(product.getId(), variantId, null));
        } else {
            if (product.getStock() == null || productRepository.restoreStock(product.getId(), quantity) == 0) return 0;
            ledger.record(c.store(), product, null, name, quantity, productRepository.findStockById(product.getId()),
                    InventoryAdjustmentReason.POS_VOID, o.getOrderCode(), note);
            c.touched().putIfAbsent(product.getId() + ":", new PosDtos.StockLevel(product.getId(), null, null));
        }
        return quantity;
    }

    /** The approval of [action] if the actor is not a POS manager; a missing/unverified one is flagged, never refused. */
    private PosDtos.SaleOverride checkApproval(Ctx c, String action, String what) {
        boolean actorManager = staffService.currentMember(c.store(), c.r().staff().userId()).filter(PosRestaurantService::isManager).isPresent();
        PosDtos.SaleOverride o = c.r().overrides() == null ? null
                : c.r().overrides().stream().filter(x -> action.equals(x.action())).findFirst().orElse(null);
        boolean verified = o != null && staffService.currentMember(c.store(), o.managerId()).filter(PosRestaurantService::isManager).isPresent();
        if (!actorManager && !verified) {
            c.conflicts().add(conflict(PosSyncConflictType.RESTAURANT_APPROVAL_MISSING, what, o == null
                    ? what + " needed a POS manager's approval and had none. It is kept - check it."
                    : "The approval of \"" + what + "\" was given by someone who is not a POS manager of this store now. It is kept - check it."));
        }
        return o;
    }

    private static boolean isManager(PosDtos.PosStaffMember m) {
        return m.owner() || m.posLevel() == PermissionLevel.EDIT;
    }

    /** Audit row (and the approval it carried, if any). */
    private void event(Ctx c, CustomerOrder o, String type, UUID fromTable, UUID toTable, Map<String, ?> detail, PosDtos.SaleOverride approval) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pos_restaurant_events (id, store_id, order_id, operation_id, device_id, event_type, staff_id, staff_name,
                    manager_id, manager_name, from_table_id, to_table_id, detail, occurred_at, order_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, c.store().getId(), o.getId(), c.r().operationId(), c.origin().getId(), type, existingUser(c.r().staff().userId()),
                truncate(c.r().staff().name(), 160), approval == null ? null : existingUser(approval.managerId()),
                approval == null ? null : truncate(approval.managerName(), 160), fromTable, toTable,
                detail == null ? null : objectMapper.writeValueAsString(detail), java.sql.Timestamp.from(c.at()), o.getPosOrderVersion());
        if (approval != null) {
            boolean verified = staffService.currentMember(c.store(), approval.managerId()).filter(PosRestaurantService::isManager).isPresent();
            jdbc.update("""
                    INSERT INTO pos_manager_overrides (store_id, device_id, order_id, restaurant_event_id, action, acting_staff_id,
                        acting_staff_name, manager_id, manager_name, detail, approved_at, verified)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, c.store().getId(), c.origin().getId(), o.getId(), id, approval.action(), existingUser(c.r().staff().userId()),
                    truncate(c.r().staff().name(), 160), existingUser(approval.managerId()), truncate(approval.managerName(), 160),
                    approval.detail(), java.sql.Timestamp.from(approval.approvedAt()), verified);
        }
    }

    // ── idempotency ───────────────────────────────────────────────────────────────────────────

    private static String operationType(RestaurantDtos.OpRequest r) {
        return "RESTAURANT_" + r.type().name();
    }

    /**
     * OPEN happens once per order; a payment once per payment id; everything else once per operation.
     * CLOSE is per operation: a close refused for a balance due must not block the later close, and
     * closing a closed order is a no-op anyway.
     */
    private static UUID entityId(RestaurantDtos.OpRequest r) {
        return switch (r.type()) {
            case OPEN -> r.orderId();
            case ADD_PAYMENT -> r.payment() == null ? r.operationId() : r.payment().paymentId();
            default -> r.operationId();
        };
    }

    private Optional<PosSyncOperation> findExisting(RestaurantDtos.OpRequest r, Store store) {
        Optional<PosSyncOperation> byId = operationRepository.findById(r.operationId());
        if (byId.isPresent()) return byId;
        return operationRepository.findByDeviceIdAndOperationTypeAndEntityId(r.originDeviceId(), operationType(r), entityId(r))
                .filter(op -> op.getStoreId().equals(store.getId()));
    }

    private RestaurantDtos.OpResponse replay(PosSyncOperation op, Store store, RestaurantDtos.OpRequest r) {
        boolean sameContent = op.getRequestHash().equals(requestHash(r));
        if (!sameContent && !op.getOperationId().equals(r.operationId()) && samePayment(op, r)) {
            sameContent = true; // the same payment re-sent under another operation (e.g. re-queued): taken once
        }
        if (!op.getStoreId().equals(store.getId()) || !operationType(r).equals(op.getOperationType()) || !sameContent) {
            throw new PosSyncRejectedException("OPERATION_ID_REUSED", "This operation id was already used for a different change", HttpStatus.CONFLICT);
        }
        CustomerOrder order = orderRepository.findById(op.getOrderId()).orElseThrow();
        while (order.getPosMergedIntoOrderId() != null) order = orderRepository.findById(order.getPosMergedIntoOrderId()).orElseThrow();
        return response(op, true, order, null, List.of(), conflictRepository.findByOperationIdOrderByCreatedAtAsc(op.getOperationId()));
    }

    /** Whether [r] pays exactly what the payment recorded by [op] paid (same id, amount and method). */
    private boolean samePayment(PosSyncOperation op, RestaurantDtos.OpRequest r) {
        if (r.type() != RestaurantDtos.OpType.ADD_PAYMENT || r.payment() == null) return false;
        return paymentRepository.findById(r.payment().paymentId())
                .filter(p -> p.getOperationId().equals(op.getOperationId()))
                .filter(p -> p.getAmount().compareTo(r.payment().amount()) == 0 && p.getMethod() == r.payment().method())
                .isPresent();
    }

    /** Canonical form of the whole request, the operation id excluded. */
    String requestHash(RestaurantDtos.OpRequest r) {
        tools.jackson.databind.node.ObjectNode node = objectMapper.valueToTree(r);
        node.remove("operationId");
        return PosPriceBookService.sha256(objectMapper.writeValueAsString(node));
    }

    // ── responses ─────────────────────────────────────────────────────────────────────────────

    private RestaurantDtos.OpResponse response(PosSyncOperation op, boolean replayed, CustomerOrder order, CustomerOrder other,
                                               Iterable<PosDtos.StockLevel> touched, List<PosSyncConflict> conflicts) {
        Map<UUID, UUID> uids = new HashMap<>();
        return new RestaurantDtos.OpResponse(op.getOperationId(), replayed, op.getStatus(),
                state(order, paymentRepository.findByOrderIdOrderByPaidAtAscIdAsc(order.getId()), uids),
                other == null ? null : state(other, paymentRepository.findByOrderIdOrderByPaidAtAscIdAsc(other.getId()), uids),
                orderSync.stockLevels(touched),
                conflicts.stream().map(c -> new PosDtos.SyncConflict(c.getId(), c.getType(), c.getProductId(), c.getVariantId(), c.getItemName(),
                        c.getRequestedQuantity(), c.getAppliedQuantity(), c.getShortfall(), c.getDetail())).toList());
    }

    static String stateName(String status, boolean merged) {
        return switch (status) {
            case "CONFIRMED" -> "OPEN";
            case "DELIVERED" -> "COMPLETED";
            default -> merged ? "MERGED" : "CANCELLED";
        };
    }

    private RestaurantDtos.OrderState state(CustomerOrder o, List<PosOrderPayment> payments, Map<UUID, UUID> uidCache) {
        BigDecimal paid = payments.stream().map(PosOrderPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(3, RoundingMode.HALF_UP);
        UUID mergedInto = o.getPosMergedIntoOrderId() == null ? null
                : uidCache.computeIfAbsent(o.getPosMergedIntoOrderId(), id -> orderRepository.findById(id).map(CustomerOrder::getPosLocalOrderId).orElse(null));
        List<RestaurantDtos.LineState> lines = o.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getPosLineNo, Comparator.nullsLast(Integer::compare)))
                .map(i -> new RestaurantDtos.LineState(i.getPosLineUid(), i.getPosLineNo(), i.getProduct() == null ? null : i.getProduct().getId(),
                        i.getVariant() == null ? null : i.getVariant().getId(), i.getProductNameSnapshot(), i.getVariantLabel(), i.getSkuSnapshot(),
                        i.getModifiers().stream().map(m -> new RestaurantDtos.Modifier(m.getGroupName(), m.getOptionName(), m.getPriceDelta())).toList(),
                        i.getUnitPrice(), i.getQuantity(), i.getVoidedQuantity(), i.getTotal(), i.getItemNote(), i.getCourse(), i.getAddedByName(),
                        i.getAddedAt(), i.getLineVersion(), i.getSentVersion()))
                .toList();
        return new RestaurantDtos.OrderState(o.getPosLocalOrderId(), o.getId(), o.getOrderCode(), o.getPosReceiptNumber(), o.getPosTicketNumber(),
                o.getPosOrderType(), stateName(o.getStatus().name(), o.getPosMergedIntoOrderId() != null), o.getPosOrderVersion(),
                o.getRestaurantTableId(), o.getGuestCount(), o.getWaiterId(), o.getWaiterName(), o.getOriginalWaiterName(),
                o.getCustomer() == null ? null : o.getCustomer().getId(), o.getCustomerName(), o.getCustomerPhone(), o.getCustomerEmail(),
                o.getCustomerAddress(), o.getPickupName(), o.getDeliveryZoneId(), o.getNotes(), o.getCurrency(), o.getSubtotal(), o.getDiscount(),
                o.getDeliveryFee(), o.getTotal(), paid, o.getTotal().subtract(paid), o.getOffer() == null ? null : o.getOffer().getId(),
                o.getOffer() == null ? null : o.getOffer().getCode(), o.getPosDevice() == null ? null : o.getPosDevice().getId(),
                o.getPosOpenedAt(), o.getPosClosedAt(), mergedInto, lines,
                payments.stream().map(p -> new RestaurantDtos.PaymentState(p.getId(), p.getAmount(), p.getMethod().name(), p.getSplitMode().name(),
                        p.getAllocation(), p.getStaffName(), p.getShiftId(), p.getDeviceId(), p.getPaidAt())).toList());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private PosDevice uploader(PosDevicePrincipal principal) {
        PosDevice device = deviceRepository.findById(principal.deviceId()).orElseThrow();
        if (!device.getStore().getId().equals(principal.storeId())) throw new AccessDeniedException("Access denied");
        return device;
    }

    private KitchenService.Actor actor(Ctx c) {
        return new KitchenService.Actor(c.r().operationId(), c.origin().getId(), existingUser(c.r().staff().userId()), c.r().staff().name(), c.at());
    }

    private static <T> T require(T payload, String name) {
        if (payload == null) throw new PosSyncRejectedException("RESTAURANT_INVALID", "The \"" + name + "\" details are missing");
        return payload;
    }

    private static Instant clamp(Instant at, PosDevice origin, Instant now) {
        if (at.isAfter(now)) return now;
        Instant floor = origin.getCreatedAt() == null ? null : origin.getCreatedAt().minus(MAX_CLOCK_SKEW);
        return floor != null && at.isBefore(floor) ? floor : at;
    }

    private UUID existingUser(UUID id) {
        return id != null && userRepository.existsById(id) ? id : null;
    }

    private static PosSyncConflict conflict(PosSyncConflictType type, String itemName, String detail) {
        PosSyncConflict c = new PosSyncConflict();
        c.setType(type);
        String name = itemName == null || itemName.isBlank() ? type.name() : itemName.trim();
        c.setItemName(name.length() > 300 ? name.substring(0, 300) : name);
        c.setDetail(detail.length() > 400 ? detail.substring(0, 400) : detail);
        return c;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String money(BigDecimal v) {
        return v.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }
}
