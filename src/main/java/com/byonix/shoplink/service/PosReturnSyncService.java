package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.domain.entity.CustomerOrder;
import com.byonix.shoplink.domain.entity.OrderItem;
import com.byonix.shoplink.domain.entity.PosDevice;
import com.byonix.shoplink.domain.entity.PosReturn;
import com.byonix.shoplink.domain.entity.PosReturnItem;
import com.byonix.shoplink.domain.entity.PosSyncConflict;
import com.byonix.shoplink.domain.entity.PosSyncOperation;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.ProductVariant;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.InventoryAdjustmentReason;
import com.byonix.shoplink.domain.enums.OrderSource;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.domain.enums.PosSyncConflictType;
import com.byonix.shoplink.domain.enums.PosSyncOperationStatus;
import com.byonix.shoplink.repository.OrderRepository;
import com.byonix.shoplink.repository.PosDeviceRepository;
import com.byonix.shoplink.repository.PosReturnRepository;
import com.byonix.shoplink.repository.PosSyncConflictRepository;
import com.byonix.shoplink.repository.PosSyncOperationRepository;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.repository.ProductVariantRepository;
import com.byonix.shoplink.repository.StoreRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.pos.PosDevicePrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * POS-23: returns and exchanges made at a till (possibly offline), applied exactly once.
 *
 * <h2>Idempotency</h2>
 * As for sales: the operation id is written first to {@code pos_sync_operations} in the same
 * transaction as the return, its stock movements and ledger rows. A retry of an applied operation
 * (lost response) gets the same return back; nothing is refunded or restocked again. The same local
 * return under another operation id is caught by (device, RETURN_CREATE, local return id).
 *
 * <h2>Validation (the till is not trusted)</h2>
 * Store = the authenticated device's store. The original order must be a POS order of that store
 * (another store's order is answered exactly like a missing one). Every line must be a line of that
 * order, and every refund amount must equal what {@link PosReturnPolicy} computes from the original
 * sale — the historical line total minus its share of the discount — for the units the till says it
 * returned. Anything else is refused (422) and nothing is written.
 *
 * <h2>Two tills returning the same units (deterministic)</h2>
 * Returns are accepted in arrival order. A line never accepts more units than were sold minus the
 * units already accepted, and never more refund than its paid value. When a till asks for more (it
 * returned offline what another till had already returned), only the remaining units are accepted and
 * restocked; the excess and the money the till reports it paid for it are recorded as a
 * RETURN_QUANTITY_EXCEEDED conflict for the manager. The till's record is kept, never silently lost,
 * and nothing is refunded or restocked twice in the books.
 *
 * <h2>Approval</h2>
 * The server re-applies the approval rules of {@link PosReturnPolicy} with its own data. A return that
 * needed a POS manager's approval but has no confirmable one is kept (the money already changed hands
 * at the till) and flagged RETURN_APPROVAL_MISSING.
 */
@Service
@RequiredArgsConstructor
public class PosReturnSyncService {
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final PlatformTransactionManager transactionManager;
    private final PosDeviceRepository deviceRepository;
    private final StoreRepository storeRepository;
    private final PosSyncOperationRepository operationRepository;
    private final PosSyncConflictRepository conflictRepository;
    private final PosReturnRepository returnRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final UserRepository userRepository;
    private final InventoryLedger ledger;
    private final PosStaffService staffService;
    private final CurrentUserService currentUser;
    private final StoreService storeService;
    private final JdbcTemplate jdbc;

    public PosDtos.SyncReturnResponse sync(PosDevicePrincipal principal, PosDtos.SyncReturnRequest request) {
        try {
            return new TransactionTemplate(transactionManager).execute(status -> apply(principal, request));
        } catch (DataIntegrityViolationException raced) {
            // A concurrent upload of this operation committed first; this attempt rolled back entirely.
            return new TransactionTemplate(transactionManager).execute(status -> {
                Store store = storeRepository.findById(uploader(principal).getStore().getId()).orElseThrow();
                return findExisting(request, store).map(op -> replay(op, store, requestHash(request)))
                        .orElseThrow(() -> raced);
            });
        }
    }

    /** What one requested line resolved to on the original order. */
    private record Planned(PosDtos.SyncReturnItem request, OrderItem item, int lineNo, int sold, BigDecimal paid) {}

    private PosDtos.SyncReturnResponse apply(PosDevicePrincipal principal, PosDtos.SyncReturnRequest r) {
        PosDevice uploader = uploader(principal);
        Store store = storeRepository.findById(principal.storeId()).orElseThrow();
        store.getName();
        PosDevice origin = r.originDeviceId().equals(uploader.getId()) ? uploader
                : deviceRepository.findById(r.originDeviceId())
                        .filter(d -> d.getStore().getId().equals(store.getId()))
                        .orElseThrow(() -> new PosSyncRejectedException("FOREIGN_DEVICE",
                                "This return was made on a device that does not belong to this store", HttpStatus.FORBIDDEN));

        String hash = requestHash(r);
        Optional<PosSyncOperation> existing = findExisting(r, store);
        if (existing.isPresent()) {
            return replay(existing.get(), store, hash);
        }

        // ── validate against the original sale (nothing written yet) ─────────────────────────
        CustomerOrder order = orderRepository.findById(r.originalOrderId())
                .filter(o -> o.getStore().getId().equals(store.getId()) && o.getSource() == OrderSource.POS)
                .orElseThrow(() -> new PosSyncRejectedException("RETURN_ORDER_UNKNOWN",
                        "The sale being returned is not a POS sale of this store"));
        if (!order.getCurrency().equalsIgnoreCase(r.currency())) {
            throw new PosSyncRejectedException("CURRENCY_MISMATCH", "The return's currency does not match the original sale");
        }
        if (r.reason() == PosReturn.Reason.OTHER && (r.reasonNote() == null || r.reasonNote().isBlank())) {
            throw new PosSyncRejectedException("RETURN_INVALID", "A return with reason OTHER needs a note");
        }
        boolean exchange = r.kind() == PosReturn.Kind.EXCHANGE;
        if (exchange != (r.exchangeLocalOrderId() != null) || (!exchange && r.exchangeCredit().signum() != 0)
                || r.exchangeCredit().signum() < 0 || r.refundPaidOut().signum() < 0) {
            throw new PosSyncRejectedException("RETURN_INVALID", "The exchange details of this return are not consistent");
        }
        if (r.exchangeCredit().add(r.refundPaidOut()).compareTo(r.refundTotal()) != 0) {
            throw new PosSyncRejectedException("RETURN_AMOUNT_MISMATCH", "The refund does not add up (credit + paid out ≠ total)");
        }
        if (r.refundPaidOut().signum() > 0 && r.refundMethod() == null) {
            throw new PosSyncRejectedException("RETURN_INVALID", "A refund paid out needs a refund method");
        }

        List<OrderItem> orderLines = linesInTillOrder(order);
        List<BigDecimal> shares = PosReturnPolicy.discountShares(orderLines.stream().map(OrderItem::getTotal).toList(), order.getDiscount());
        List<Planned> planned = new ArrayList<>();
        Set<Integer> seenLines = new HashSet<>();
        BigDecimal claimed = BigDecimal.ZERO;
        for (PosDtos.SyncReturnItem ri : r.items()) {
            if (!seenLines.add(ri.lineNo())) {
                throw new PosSyncRejectedException("RETURN_INVALID", "The same sale line appears twice in this return");
            }
            int index = indexOfLine(orderLines, ri);
            OrderItem item = orderLines.get(index);
            int sold = item.getQuantity();
            if (ri.returnedBefore() + ri.quantity() > sold) {
                throw new PosSyncRejectedException("RETURN_INVALID", "More units returned than were sold of \"" + item.getProductNameSnapshot() + "\"");
            }
            BigDecimal paid = item.getTotal().subtract(shares.get(index));
            BigDecimal expected = PosReturnPolicy.refundFor(paid, sold, ri.returnedBefore(), ri.quantity());
            if (expected.compareTo(ri.refundAmount()) != 0) {
                throw new PosSyncRejectedException("RETURN_AMOUNT_MISMATCH",
                        "The refund for \"" + item.getProductNameSnapshot() + "\" does not match the original sale");
            }
            planned.add(new Planned(ri, item, index + 1, sold, paid));
            claimed = claimed.add(ri.refundAmount());
        }
        if (claimed.compareTo(r.refundTotal()) != 0) {
            throw new PosSyncRejectedException("RETURN_AMOUNT_MISMATCH", "The return total does not match its lines");
        }

        // Everything the rest needs from the order, read now: the stock updates below detach entities.
        UUID orderId = order.getId();
        String orderCode = order.getOrderCode();
        BigDecimal orderTotal = order.getTotal();
        Instant soldAt = order.getCreatedAt();
        UUID customerId = order.getCustomer() == null ? null : order.getCustomer().getId();
        String customerName = order.getCustomerName();
        String customerPhone = order.getCustomerPhone();
        String originalReceipt = order.getPosReceiptNumber();
        Map<UUID, Integer> acceptedBefore = new LinkedHashMap<>();
        Map<UUID, BigDecimal> refundedBefore = new LinkedHashMap<>();
        for (OrderItem line : orderLines) {
            acceptedBefore.put(line.getId(), returnRepository.acceptedQuantity(line.getId()));
            refundedBefore.put(line.getId(), returnRepository.acceptedRefund(line.getId()));
        }

        // ── write: operation first (a concurrent duplicate fails on this key) ────────────────
        Instant now = Instant.now();
        Instant returnedAt = clamp(r.returnedAt(), soldAt, now);
        PosSyncOperation op = new PosSyncOperation();
        op.setOperationId(r.operationId());
        op.setStoreId(store.getId());
        op.setDeviceId(origin.getId());
        op.setSubmittedByDeviceId(uploader.getId());
        op.setOperationType(PosSyncOperation.TYPE_RETURN);
        op.setEntityId(r.localReturnId());
        op.setRequestHash(hash);
        op.setCatalogVersion("-"); // returns are priced from the original sale, not a catalog
        op.setStatus(PosSyncOperationStatus.APPLYING);
        op.setOrderId(orderId);
        op.setOrderCode(orderCode);
        op.setCreatedAt(now);
        operationRepository.saveAndFlush(op);

        // ── decide everything before any stock moves (stock updates detach loaded entities) ───
        List<PosSyncConflict> conflicts = new ArrayList<>();
        record Decision(Planned p, int acceptedQty, BigDecimal refund, String itemName, UUID productId, UUID variantId) {}
        List<Decision> decisions = new ArrayList<>();
        BigDecimal accepted = BigDecimal.ZERO;
        for (Planned p : planned) {
            UUID itemId = p.item().getId();
            int before = acceptedBefore.get(itemId);
            int requested = p.request().quantity();
            int acceptedQty = Math.min(requested, Math.max(0, p.sold() - before));
            BigDecimal room = p.paid().subtract(refundedBefore.get(itemId)).max(BigDecimal.ZERO);
            BigDecimal refund = acceptedQty == requested
                    ? p.request().refundAmount()
                    : PosReturnPolicy.refundFor(p.paid(), p.sold(), before, acceptedQty);
            if (refund.compareTo(room) > 0) refund = room;
            String itemName = InventoryLedger.itemName(p.item().getProductNameSnapshot(), p.item().getVariantLabel());
            UUID productId = p.item().getProduct() == null ? p.request().productId() : p.item().getProduct().getId();
            UUID variantId = p.item().getVariant() == null ? p.request().variantId() : p.item().getVariant().getId();
            if (acceptedQty < requested || refund.compareTo(p.request().refundAmount()) < 0) {
                PosSyncConflict c = conflict(PosSyncConflictType.RETURN_QUANTITY_EXCEEDED, itemName,
                        "Returned " + requested + " at the till but only " + acceptedQty + " of " + p.sold()
                                + " were still returnable (another return already took them back). Accepted "
                                + acceptedQty + "; the till reported refunding " + p.request().refundAmount().toPlainString()
                                + ", " + p.request().refundAmount().subtract(refund).toPlainString() + " of it beyond what was returnable. Check the cash.");
                c.setRequestedQuantity(requested);
                c.setAppliedQuantity(acceptedQty);
                c.setShortfall(requested - acceptedQty);
                c.setProductId(productId);
                c.setVariantId(variantId);
                conflicts.add(c);
            }
            decisions.add(new Decision(p, acceptedQty, refund, itemName, productId, variantId));
            accepted = accepted.add(refund);
        }

        boolean fullReturn = true;
        for (OrderItem line : orderLines) {
            int after = acceptedBefore.get(line.getId()) + decisions.stream()
                    .filter(d -> d.p().item().getId().equals(line.getId())).mapToInt(Decision::acceptedQty).sum();
            if (after < line.getQuantity()) fullReturn = false;
        }
        Optional<PosDtos.PosStaffMember> cashier = r.staff() == null ? Optional.empty()
                : staffService.currentMember(store, r.staff().userId()).filter(m -> m.posLevel() != PermissionLevel.NONE);
        if (r.staff() != null && cashier.isEmpty()) {
            conflicts.add(conflict(PosSyncConflictType.STAFF_UNAVAILABLE, r.staff().name(),
                    "The cashier is no longer a POS user of this store. The return is kept with the name shown at the till."));
        }
        boolean cashierMayReturn = cashier.map(m -> m.owner() || m.posLevel() == PermissionLevel.EDIT || m.ordersLevel() == PermissionLevel.EDIT).orElse(false);
        BigDecimal paidBack = exchange ? r.refundPaidOut().min(accepted) : accepted;
        boolean needsApproval = PosReturnPolicy.requiresApproval(exchange, fullReturn, paidBack, orderTotal,
                Duration.between(soldAt, returnedAt), cashierMayReturn);
        List<PosDtos.SaleOverride> overrides = r.overrides() == null ? List.of() : r.overrides();
        Map<PosDtos.SaleOverride, Boolean> verified = new LinkedHashMap<>();
        for (PosDtos.SaleOverride o : overrides) {
            verified.put(o, staffService.currentMember(store, o.managerId()).filter(m -> m.owner() || m.posLevel() == PermissionLevel.EDIT).isPresent());
        }
        PosDtos.SaleOverride approval = overrides.stream().filter(o -> PosReturnPolicy.APPROVAL_ACTION.equals(o.action())).findFirst().orElse(null);
        boolean approvalVerified = approval != null && verified.get(approval);
        if (needsApproval && !approvalVerified) {
            conflicts.add(conflict(PosSyncConflictType.RETURN_APPROVAL_MISSING, r.returnNumber(), approval == null
                    ? "This return needed a POS manager's approval (full or large return, an old sale, or a cashier without the Orders right) and had none. It is kept - check it."
                    : "The approval on this return was given by someone who is not a POS manager of this store now. It is kept - check it."));
        }
        if (exchange) {
            orderRepository.findByPosDevice_IdAndPosLocalOrderId(origin.getId(), r.exchangeLocalOrderId())
                    .filter(sale -> sale.getPosExchangeCredit() == null || sale.getPosExchangeCredit().compareTo(r.exchangeCredit()) != 0)
                    .ifPresent(sale -> conflicts.add(conflict(PosSyncConflictType.EXCHANGE_MISMATCH, r.returnNumber(),
                            "The replacement sale " + sale.getOrderCode() + " records a different exchange credit than this return. Both are kept - check them.")));
        }
        UUID staffUserId = r.staff() == null ? null : existingUser(r.staff().userId());
        UUID managerUserId = approval == null ? null : existingUser(approval.managerId());
        Map<PosDtos.SaleOverride, UUID> overrideManagers = new LinkedHashMap<>();
        for (PosDtos.SaleOverride o : overrides) overrideManagers.put(o, existingUser(o.managerId()));

        // ── stock: exactly once, through the ledger ──────────────────────────────────────────
        PosReturn ret = new PosReturn();
        InventoryAdjustmentReason ledgerReason = exchange ? InventoryAdjustmentReason.POS_EXCHANGE_RETURN : InventoryAdjustmentReason.POS_RETURN;
        String ledgerNote = (exchange ? "POS exchange " : "POS return ") + r.returnNumber();
        Map<String, PosDtos.StockLevel> touched = new LinkedHashMap<>();
        for (Decision d : decisions) {
            Planned p = d.p();
            int restocked = 0;
            if (d.acceptedQty() > 0 && p.request().disposition() == PosReturnItem.Disposition.RESTOCK) {
                restocked = restock(store, d.productId(), d.variantId(), p.item().getProduct() != null, d.itemName(), d.acceptedQty(),
                        ledgerReason, orderCode, ledgerNote);
            }
            touched.putIfAbsent(d.productId() + ":" + d.variantId(), new PosDtos.StockLevel(d.productId(), d.variantId(), null));
            PosReturnItem ri = new PosReturnItem();
            ri.setPosReturn(ret);
            ri.setOrderItemId(p.item().getId());
            ri.setPosLineNo(p.lineNo());
            ri.setProductId(d.productId());
            ri.setVariantId(d.variantId());
            ri.setItemName(d.itemName().length() > 300 ? d.itemName().substring(0, 300) : d.itemName());
            ri.setRequestedQuantity(p.request().quantity());
            ri.setQuantity(d.acceptedQty());
            ri.setUnitPrice(p.item().getUnitPrice());
            ri.setLinePaidTotal(p.paid());
            ri.setRequestedRefund(p.request().refundAmount());
            ri.setRefundAmount(d.refund());
            ri.setDisposition(p.request().disposition());
            ri.setRestockedQuantity(restocked);
            ret.getItems().add(ri);
        }

        ret.setStoreId(store.getId());
        ret.setDeviceId(origin.getId());
        ret.setOperationId(op.getOperationId());
        ret.setLocalReturnId(r.localReturnId());
        ret.setReturnNumber(r.returnNumber());
        ret.setKind(r.kind());
        ret.setOriginalOrderId(orderId);
        ret.setOriginalOrderCode(orderCode);
        ret.setOriginalReceiptNumber(originalReceipt);
        ret.setCustomerId(customerId);
        ret.setCustomerName(customerName);
        ret.setCustomerPhone(customerPhone);
        if (r.staff() != null) {
            ret.setStaffId(staffUserId);
            ret.setStaffName(truncate(r.staff().name(), 160));
        }
        if (approval != null) {
            ret.setManagerId(managerUserId);
            ret.setManagerName(truncate(approval.managerName(), 160));
        }
        ret.setReason(r.reason());
        ret.setReasonNote(r.reasonNote() == null || r.reasonNote().isBlank() ? null : truncate(r.reasonNote().trim(), 300));
        ret.setCurrency(r.currency().toUpperCase());
        ret.setRequestedRefundTotal(r.refundTotal());
        ret.setRefundTotal(accepted);
        ret.setExchangeCredit(r.exchangeCredit());
        ret.setRefundPaidOut(r.refundPaidOut());
        ret.setRefundMethod(r.refundPaidOut().signum() > 0 ? r.refundMethod() : null);
        ret.setExchangeLocalOrderId(r.exchangeLocalOrderId());
        ret.setStatus((conflicts.isEmpty() ? PosSyncOperationStatus.SYNCED : PosSyncOperationStatus.SYNCED_WITH_CONFLICTS).name());
        ret.setReturnedAt(returnedAt);
        ret.setCreatedAt(now);
        PosReturn saved = returnRepository.saveAndFlush(ret);

        for (PosDtos.SaleOverride o : overrides) {
            jdbc.update("""
                    INSERT INTO pos_manager_overrides (store_id, device_id, order_id, return_id, action, acting_staff_id,
                        acting_staff_name, manager_id, manager_name, detail, approved_at, verified)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, store.getId(), origin.getId(), orderId, saved.getId(), o.action(), staffUserId,
                    r.staff() == null ? null : truncate(r.staff().name(), 160), overrideManagers.get(o), truncate(o.managerName(), 160),
                    o.detail(), java.sql.Timestamp.from(o.approvedAt()), verified.get(o));
        }

        for (PosSyncConflict c : conflicts) {
            c.setStoreId(store.getId());
            c.setDeviceId(origin.getId());
            c.setOperationId(op.getOperationId());
            c.setOrderId(orderId);
            c.setOrderCode(orderCode);
            c.setReceiptNumber(r.returnNumber());
            c.setCreatedAt(now);
        }
        conflictRepository.saveAll(conflicts);

        op.setStatus(conflicts.isEmpty() ? PosSyncOperationStatus.SYNCED : PosSyncOperationStatus.SYNCED_WITH_CONFLICTS);
        op.setCompletedAt(Instant.now());
        op = operationRepository.saveAndFlush(op);
        return response(op, saved, false, touched.values(), conflicts);
    }

    /** Puts accepted units back on sale through the ledger. Returns how many were restocked. */
    private int restock(Store store, UUID productId, UUID variantId, boolean productStillExists, String itemName, int quantity,
                        InventoryAdjustmentReason reason, String orderCode, String note) {
        if (!productStillExists || productId == null) return 0; // deleted since the sale: nothing to put back on sale
        Product product = productRepository.findByIdForUpdate(productId).filter(p -> p.getStore().getId().equals(store.getId())).orElse(null);
        if (product == null) return 0;
        if (variantId != null) {
            ProductVariant variant = variantRepository.findByIdForUpdate(variantId).orElse(null);
            if (variant == null || variant.getStock() == null) return 0;
            if (variantRepository.restoreStock(variantId, quantity) == 0) return 0;
            ledger.record(store, product, variant, itemName, quantity, variantRepository.findStockById(variantId), reason, orderCode, note);
        } else {
            if (product.getStock() == null) return 0; // untracked stock
            if (productRepository.restoreStock(productId, quantity) == 0) return 0;
            ledger.record(store, product, null, itemName, quantity, productRepository.findStockById(productId), reason, orderCode, note);
        }
        return quantity;
    }

    /**
     * The order's lines in till order: by pos_line_no (V39+). POS orders uploaded before V39 have no
     * line numbers; their lines are ordered by product/variant id, which is enough to identify them
     * but not to share a discount in the till's order — such an order is returnable only without one.
     */
    private static List<OrderItem> linesInTillOrder(CustomerOrder order) {
        List<OrderItem> lines = new ArrayList<>(order.getItems());
        boolean numbered = lines.stream().allMatch(i -> i.getPosLineNo() != null);
        if (numbered) {
            lines.sort(Comparator.comparing(OrderItem::getPosLineNo));
            return lines;
        }
        if (order.getDiscount() != null && order.getDiscount().signum() != 0) {
            throw new PosSyncRejectedException("RETURN_ORDER_TOO_OLD",
                    "This discounted sale was uploaded before returns were supported and cannot be returned at the till. Handle it in the dashboard.");
        }
        lines.sort(Comparator.comparing((OrderItem i) -> i.getProduct() == null ? "" : i.getProduct().getId().toString())
                .thenComparing(i -> i.getVariant() == null ? "" : i.getVariant().getId().toString()));
        return lines;
    }

    private static int indexOfLine(List<OrderItem> lines, PosDtos.SyncReturnItem ri) {
        boolean numbered = lines.stream().allMatch(i -> i.getPosLineNo() != null);
        for (int i = 0; i < lines.size(); i++) {
            OrderItem line = lines.get(i);
            boolean sameProduct = line.getProduct() == null || line.getProduct().getId().equals(ri.productId());
            boolean sameVariant = line.getVariant() == null ? ri.variantId() == null || line.getProduct() == null
                    : line.getVariant().getId().equals(ri.variantId());
            if (numbered ? line.getPosLineNo().equals(ri.lineNo()) && sameProduct && sameVariant
                    : line.getProduct() != null && sameProduct && sameVariant) {
                if (!numbered && lines.stream().filter(l -> l.getProduct() != null && l.getProduct().getId().equals(ri.productId())
                        && Objects.equals(l.getVariant() == null ? null : l.getVariant().getId(), ri.variantId())).count() > 1) {
                    break; // ambiguous without line numbers
                }
                return i;
            }
        }
        throw new PosSyncRejectedException("RETURN_ITEM_UNKNOWN", "A returned line is not part of the original sale");
    }

    // ── idempotent replay ─────────────────────────────────────────────────────────────────────

    private Optional<PosSyncOperation> findExisting(PosDtos.SyncReturnRequest r, Store store) {
        Optional<PosSyncOperation> byId = operationRepository.findById(r.operationId());
        if (byId.isPresent()) return byId;
        return operationRepository.findByDeviceIdAndOperationTypeAndEntityId(r.originDeviceId(), PosSyncOperation.TYPE_RETURN, r.localReturnId())
                .filter(op -> op.getStoreId().equals(store.getId()));
    }

    private PosDtos.SyncReturnResponse replay(PosSyncOperation op, Store store, String requestHash) {
        if (!op.getStoreId().equals(store.getId()) || !PosSyncOperation.TYPE_RETURN.equals(op.getOperationType())
                || !op.getRequestHash().equals(requestHash)) {
            throw new PosSyncRejectedException("OPERATION_ID_REUSED",
                    "This operation id was already used for a different upload", HttpStatus.CONFLICT);
        }
        PosReturn ret = returnRepository.findByOperationId(op.getOperationId()).orElseThrow();
        List<PosDtos.StockLevel> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PosReturnItem i : ret.getItems()) {
            if (i.getProductId() != null && seen.add(i.getProductId() + ":" + i.getVariantId())) {
                items.add(new PosDtos.StockLevel(i.getProductId(), i.getVariantId(), null));
            }
        }
        return response(op, ret, true, items, conflictRepository.findByOperationIdOrderByCreatedAtAsc(op.getOperationId()));
    }

    private PosDtos.SyncReturnResponse response(PosSyncOperation op, PosReturn ret, boolean replayed,
                                                Iterable<PosDtos.StockLevel> items, List<PosSyncConflict> conflicts) {
        List<PosDtos.StockLevel> inventory = new ArrayList<>();
        for (PosDtos.StockLevel item : items) {
            Integer stock = item.variantId() != null ? variantRepository.findStockById(item.variantId())
                    : item.productId() == null ? null : productRepository.findStockById(item.productId());
            inventory.add(new PosDtos.StockLevel(item.productId(), item.variantId(), stock));
        }
        return new PosDtos.SyncReturnResponse(op.getOperationId(), replayed, op.getStatus(), ret.getId(), ret.getOriginalOrderCode(),
                ret.getRequestedRefundTotal(), ret.getRefundTotal(), ret.getCurrency(), op.getCompletedAt(), itemResults(ret), inventory,
                conflicts.stream().map(c -> new PosDtos.SyncConflict(c.getId(), c.getType(), c.getProductId(), c.getVariantId(),
                        c.getItemName(), c.getRequestedQuantity(), c.getAppliedQuantity(), c.getShortfall(), c.getDetail())).toList());
    }

    private static List<PosDtos.ReturnItemResult> itemResults(PosReturn ret) {
        return ret.getItems().stream().map(i -> new PosDtos.ReturnItemResult(i.getPosLineNo(), i.getRequestedQuantity(),
                i.getQuantity(), i.getRefundAmount(), i.getRestockedQuantity())).toList();
    }

    // ── dashboard ─────────────────────────────────────────────────────────────────────────────

    /** The store's POS returns (optionally of one order), for anyone who may view Orders. */
    @Transactional(readOnly = true)
    public List<PosDtos.ReturnSummary> list(UUID storeId, UUID orderId) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, com.byonix.shoplink.domain.enums.DashboardSection.ORDERS, PermissionLevel.VIEW);
        List<PosReturn> rows = orderId == null ? returnRepository.findByStoreIdOrderByCreatedAtDesc(storeId)
                : returnRepository.findByStoreIdAndOriginalOrderIdOrderByCreatedAtAsc(storeId, orderId);
        return rows.stream().map(r -> new PosDtos.ReturnSummary(r.getId(), r.getReturnNumber(), r.getKind().name(),
                r.getOriginalOrderId(), r.getOriginalOrderCode(), r.getOriginalReceiptNumber(), r.getCustomerName(),
                r.getStaffName(), r.getManagerName(), r.getReason().name(), r.getReasonNote(),
                r.getRefundMethod() == null ? null : r.getRefundMethod().name(), r.getRequestedRefundTotal(), r.getRefundTotal(),
                r.getExchangeCredit(), r.getRefundPaidOut(), r.getCurrency(), r.getStatus(), r.getDeviceId(), r.getReturnedAt(),
                itemResults(r))).toList();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private PosDevice uploader(PosDevicePrincipal principal) {
        PosDevice device = deviceRepository.findById(principal.deviceId()).orElseThrow();
        if (!device.getStore().getId().equals(principal.storeId())) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }
        return device;
    }

    /** The till's clock is trusted only within bounds: not before the sale, not in the future. */
    private static Instant clamp(Instant at, Instant soldAt, Instant now) {
        if (at.isAfter(now.plus(MAX_CLOCK_SKEW)) || at.isAfter(now)) return now;
        if (soldAt != null && at.isBefore(soldAt)) return soldAt;
        return at;
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

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Canonical form of everything that defines the return (the operation id itself excluded). */
    static String requestHash(PosDtos.SyncReturnRequest r) {
        StringBuilder b = new StringBuilder()
                .append(r.originDeviceId()).append('|').append(r.localReturnId()).append('|').append(r.returnNumber())
                .append('|').append(r.kind()).append('|').append(r.originalOrderId()).append('|').append(r.returnedAt())
                .append('|').append(r.currency().toUpperCase()).append('|').append(r.reason()).append('|')
                .append(Objects.toString(r.reasonNote(), "")).append('|').append(plain(r.refundTotal())).append('|')
                .append(plain(r.exchangeCredit())).append('|').append(plain(r.refundPaidOut())).append('|').append(r.refundMethod())
                .append('|').append(r.exchangeLocalOrderId());
        if (r.staff() != null) b.append("|staff:").append(r.staff().userId()).append(',').append(r.staff().name());
        if (r.overrides() != null) {
            for (PosDtos.SaleOverride o : r.overrides()) {
                b.append("|override:").append(o.action()).append(',').append(o.managerId()).append(',').append(o.approvedAt());
            }
        }
        for (PosDtos.SyncReturnItem i : r.items()) {
            b.append("|item:").append(i.lineNo()).append(',').append(i.productId()).append(',').append(i.variantId()).append(',')
                    .append(i.quantity()).append(',').append(i.returnedBefore()).append(',').append(plain(i.refundAmount()))
                    .append(',').append(i.disposition());
        }
        return PosPriceBookService.sha256(b.toString());
    }

    private static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
