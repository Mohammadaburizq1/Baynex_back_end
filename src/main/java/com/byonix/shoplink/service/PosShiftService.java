package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.PosDtos;
import com.byonix.shoplink.common.ConflictException;
import com.byonix.shoplink.domain.entity.PosDevice;
import com.byonix.shoplink.domain.entity.PosShift;
import com.byonix.shoplink.domain.entity.PosShiftCashMovement;
import com.byonix.shoplink.domain.entity.PosSyncConflict;
import com.byonix.shoplink.domain.entity.PosSyncOperation;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.domain.enums.PosSyncConflictType;
import com.byonix.shoplink.domain.enums.PosSyncOperationStatus;
import com.byonix.shoplink.repository.PosDeviceRepository;
import com.byonix.shoplink.repository.PosShiftCashMovementRepository;
import com.byonix.shoplink.repository.PosShiftRepository;
import com.byonix.shoplink.repository.PosSyncConflictRepository;
import com.byonix.shoplink.repository.PosSyncOperationRepository;
import com.byonix.shoplink.repository.StoreRepository;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.pos.PosDevicePrincipal;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * POS-24: cashier shifts and drawer cash, uploaded from the till (possibly offline) exactly once.
 *
 * <h2>Idempotency</h2>
 * Every shift upload has the till's operation id, written first to {@code pos_sync_operations} in the
 * same transaction as its effect (SHIFT_OPEN / SHIFT_CASH_MOVEMENT / SHIFT_CLOSE). A retry of an
 * applied operation returns the same result and changes nothing; the same open, movement or close
 * under a new operation id is caught by (device, type, shift or movement id). One shift is opened
 * once, a movement counted once, a shift closed once.
 *
 * <h2>Authority</h2>
 * The store is the authenticated device's; the shift belongs to the device that opened it and only
 * that device (or its re-activated replacement, uploading on its behalf) can move or close it. The
 * cashier and approvers are checked against the permission grid now. The drawer's expected cash is
 * never taken from the till: it is recomputed from the synced sales, returns and movements linked to
 * the shift <em>and</em> made on its device ({@link #totals}). At close, the till's own figure is kept
 * next to the server's; a difference is recorded as a SHIFT_RECONCILIATION_MISMATCH conflict.
 *
 * <h2>Offline order</h2>
 * The till uploads oldest first and stops at a transient failure, so a shift's sales and movements
 * normally reach the server before its close. Records that arrive later anyway (e.g. a refused sale
 * retried by a manager) are still linked: live totals always include them, and the dashboard shows
 * that the shift received records after it closed. Nothing is lost and nothing is rewritten.
 */
@Service
@RequiredArgsConstructor
public class PosShiftService {
    public static final String TYPE_OPEN = "SHIFT_OPEN";
    public static final String TYPE_MOVEMENT = "SHIFT_CASH_MOVEMENT";
    public static final String TYPE_CLOSE = "SHIFT_CLOSE";
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.999");
    private static final int LIST_LIMIT = 200;

    private final PlatformTransactionManager transactionManager;
    private final PosDeviceRepository deviceRepository;
    private final StoreRepository storeRepository;
    private final PosSyncOperationRepository operationRepository;
    private final PosSyncConflictRepository conflictRepository;
    private final PosShiftRepository shiftRepository;
    private final PosShiftCashMovementRepository movementRepository;
    private final UserRepository userRepository;
    private final PosStaffService staffService;
    private final CurrentUserService currentUser;
    private final StoreService storeService;
    private final JdbcTemplate jdbc;

    /**
     * A sale or return names the shift it was made in. The shift may not be on the server yet (it is
     * uploaded before its sales, but may have been refused); if it is, it must be this store's and this
     * device's — anything else is a tampered upload. Totals only ever count rows of the shift's own store
     * and device, so an unknown id can never pull a record into another drawer.
     */
    static void checkShiftLink(PosShiftRepository shifts, UUID shiftId, UUID storeId, UUID deviceId) {
        if (shiftId == null) return;
        shifts.findById(shiftId).ifPresent(s -> {
            if (!s.getStoreId().equals(storeId) || !Objects.equals(s.getDeviceId(), deviceId)) {
                throw new PosSyncRejectedException("SHIFT_INVALID", "This record names a shift of another till");
            }
        });
    }

    // ── device uploads ────────────────────────────────────────────────────────────────────────

    public PosDtos.ShiftSyncResponse open(PosDevicePrincipal principal, PosDtos.OpenShiftRequest r) {
        return run(principal, () -> applyOpen(principal, r), store -> findExisting(r.operationId(), TYPE_OPEN, r.originDeviceId(), r.shiftId(), store)
                .map(op -> replay(op, store, TYPE_OPEN, openHash(r))));
    }

    public PosDtos.ShiftSyncResponse cashMovement(PosDevicePrincipal principal, UUID shiftId, PosDtos.CashMovementRequest r) {
        return run(principal, () -> applyMovement(principal, shiftId, r), store -> findExisting(r.operationId(), TYPE_MOVEMENT, r.originDeviceId(), r.movementId(), store)
                .map(op -> replay(op, store, TYPE_MOVEMENT, movementHash(shiftId, r))));
    }

    public PosDtos.ShiftSyncResponse close(PosDevicePrincipal principal, UUID shiftId, PosDtos.CloseShiftRequest r) {
        return run(principal, () -> applyClose(principal, shiftId, r), store -> findExisting(r.operationId(), TYPE_CLOSE, r.originDeviceId(), shiftId, store)
                .map(op -> replay(op, store, TYPE_CLOSE, closeHash(shiftId, r))));
    }

    /** Applies in one transaction; a concurrent duplicate that committed first is answered with its result. */
    private PosDtos.ShiftSyncResponse run(PosDevicePrincipal principal, Supplier<PosDtos.ShiftSyncResponse> apply,
                                          java.util.function.Function<Store, Optional<PosDtos.ShiftSyncResponse>> afterRace) {
        try {
            return new TransactionTemplate(transactionManager).execute(status -> apply.get());
        } catch (DataIntegrityViolationException raced) {
            return new TransactionTemplate(transactionManager).execute(status -> {
                Store store = storeRepository.findById(uploader(principal).getStore().getId()).orElseThrow();
                return afterRace.apply(store).orElseThrow(() -> new PosSyncRejectedException("SHIFT_CONFLICT",
                        "Another shift upload for this till was applied at the same time. Sync again.", HttpStatus.CONFLICT));
            });
        }
    }

    private record Ctx(PosDevice uploader, Store store, PosDevice origin) {}

    private Ctx context(PosDevicePrincipal principal, UUID originDeviceId) {
        PosDevice uploader = uploader(principal);
        Store store = storeRepository.findById(principal.storeId()).orElseThrow();
        PosDevice origin = originDeviceId.equals(uploader.getId()) ? uploader
                : deviceRepository.findById(originDeviceId)
                        .filter(d -> d.getStore().getId().equals(store.getId()))
                        .orElseThrow(() -> new PosSyncRejectedException("FOREIGN_DEVICE",
                                "This shift belongs to a device that is not part of this store", HttpStatus.FORBIDDEN));
        return new Ctx(uploader, store, origin);
    }

    private PosDtos.ShiftSyncResponse applyOpen(PosDevicePrincipal principal, PosDtos.OpenShiftRequest r) {
        Ctx c = context(principal, r.originDeviceId());
        String hash = openHash(r);
        Optional<PosSyncOperation> existing = findExisting(r.operationId(), TYPE_OPEN, r.originDeviceId(), r.shiftId(), c.store());
        if (existing.isPresent()) return replay(existing.get(), c.store(), TYPE_OPEN, hash);

        if (!c.store().getCurrency().equalsIgnoreCase(r.currency())) {
            throw new PosSyncRejectedException("CURRENCY_MISMATCH", "The shift's currency is not the store's currency");
        }
        checkAmount(r.openingCash(), r.currency(), "The opening cash");
        if (shiftRepository.existsById(r.shiftId())) {
            // The id is taken by another store's or device's shift: never merge into it or reveal it.
            throw new PosSyncRejectedException("SHIFT_ID_REUSED", "This shift id is already in use", HttpStatus.CONFLICT);
        }
        Optional<PosShift> alreadyOpen = shiftRepository.findFirstByDeviceIdAndStatus(c.origin().getId(), PosShift.Status.OPEN);
        if (alreadyOpen.isPresent()) {
            throw new PosSyncRejectedException("SHIFT_ALREADY_OPEN", "Shift " + alreadyOpen.get().getShiftNumber()
                    + " is still open for this till on khanGates. Close it at the till, or force-close it in the dashboard, then retry.",
                    HttpStatus.CONFLICT);
        }

        Instant now = Instant.now();
        PosSyncOperation op = newOperation(r.operationId(), c, TYPE_OPEN, r.shiftId(), openHash(r), now);
        List<PosSyncConflict> conflicts = new ArrayList<>();
        if (staffService.currentMember(c.store(), r.cashier().userId()).filter(m -> m.posLevel() != PermissionLevel.NONE).isEmpty()) {
            conflicts.add(conflict(PosSyncConflictType.STAFF_UNAVAILABLE, r.cashier().name(),
                    "The cashier who opened this shift is not a POS user of this store now. The shift is kept with the name shown at the till."));
        }

        PosShift shift = new PosShift();
        shift.setId(r.shiftId());
        shift.setStoreId(c.store().getId());
        shift.setDeviceId(c.origin().getId());
        shift.setShiftNumber(r.shiftNumber().trim());
        shift.setCashierId(existingUser(r.cashier().userId()));
        shift.setCashierName(truncate(r.cashier().name().trim(), 160));
        shift.setCurrency(r.currency().toUpperCase());
        shift.setOpenedAt(clamp(r.openedAt(), floorFor(c.origin()), now));
        shift.setOpeningCash(r.openingCash().setScale(3, RoundingMode.UNNECESSARY));
        shift.setStatus(PosShift.Status.OPEN);
        shift.setCreatedAt(now);
        shift.setUpdatedAt(now);
        shiftRepository.saveAndFlush(shift);

        return finish(op, c, shift, null, conflicts, null);
    }

    private PosDtos.ShiftSyncResponse applyMovement(PosDevicePrincipal principal, UUID shiftId, PosDtos.CashMovementRequest r) {
        Ctx c = context(principal, r.originDeviceId());
        String hash = movementHash(shiftId, r);
        Optional<PosSyncOperation> existing = findExisting(r.operationId(), TYPE_MOVEMENT, r.originDeviceId(), r.movementId(), c.store());
        if (existing.isPresent()) return replay(existing.get(), c.store(), TYPE_MOVEMENT, hash);

        PosShift shift = ownShift(shiftId, c);
        if (!shift.getCurrency().equalsIgnoreCase(r.currency())) {
            throw new PosSyncRejectedException("CURRENCY_MISMATCH", "The cash movement's currency is not the shift's currency");
        }
        checkAmount(r.amount(), r.currency(), "The amount");
        if (!r.reason().allowedFor(r.type())) {
            throw new PosSyncRejectedException("MOVEMENT_INVALID", "Reason " + r.reason() + " does not apply to " + r.type());
        }
        if (r.reason() == PosShiftCashMovement.Reason.OTHER && (r.note() == null || r.note().isBlank())) {
            throw new PosSyncRejectedException("MOVEMENT_INVALID", "A cash movement with reason OTHER needs a note");
        }
        if (movementRepository.existsById(r.movementId())) {
            throw new PosSyncRejectedException("MOVEMENT_ID_REUSED", "This cash movement id is already in use", HttpStatus.CONFLICT);
        }

        Instant now = Instant.now();
        PosSyncOperation op = newOperation(r.operationId(), c, TYPE_MOVEMENT, r.movementId(), hash, now);
        List<PosSyncConflict> conflicts = new ArrayList<>();
        Optional<PosDtos.PosStaffMember> actor = staffService.currentMember(c.store(), r.staff().userId())
                .filter(m -> m.posLevel() != PermissionLevel.NONE);
        if (actor.isEmpty()) {
            conflicts.add(conflict(PosSyncConflictType.STAFF_UNAVAILABLE, r.staff().name(),
                    "The person who recorded this cash movement is not a POS user of this store now. It is kept - check it."));
        }
        boolean needsApproval = PosShiftPolicy.movementNeedsApproval(r.type(), r.amount(), actor.map(PosShiftService::isManager).orElse(false));
        Approval approval = approval(c.store(), r.overrides(), PosShiftPolicy.CASH_MOVEMENT_APPROVAL);
        if (needsApproval && !approval.verified()) {
            conflicts.add(conflict(PosSyncConflictType.SHIFT_APPROVAL_MISSING, shift.getShiftNumber(), approval.override() == null
                    ? "A " + r.type() + " of " + r.amount().toPlainString() + " " + shift.getCurrency() + " needed a POS manager's approval and had none. It is kept - check the drawer."
                    : "The approval of this " + r.type() + " was given by someone who is not a POS manager of this store now. It is kept - check it."));
        }

        PosShiftCashMovement m = new PosShiftCashMovement();
        m.setId(r.movementId());
        m.setShiftId(shift.getId());
        m.setStoreId(c.store().getId());
        m.setDeviceId(c.origin().getId());
        m.setOperationId(op.getOperationId());
        m.setType(r.type());
        m.setReason(r.reason());
        m.setNote(r.note() == null || r.note().isBlank() ? null : truncate(r.note().trim(), 300));
        m.setAmount(r.amount().setScale(3, RoundingMode.UNNECESSARY));
        m.setStaffId(existingUser(r.staff().userId()));
        m.setStaffName(truncate(r.staff().name().trim(), 160));
        if (approval.override() != null) {
            m.setManagerId(existingUser(approval.override().managerId()));
            m.setManagerName(truncate(approval.override().managerName(), 160));
        }
        // A movement belongs to the shift's time: never before it opened, never in the future.
        m.setMovedAt(clamp(r.movedAt(), shift.getOpenedAt(), now));
        m.setCreatedAt(now);
        movementRepository.saveAndFlush(m);
        recordOverrides(c, shift.getId(), m.getId(), r.staff(), r.overrides());
        shift.setUpdatedAt(now);

        return finish(op, c, shift, m.getId(), conflicts, null);
    }

    private PosDtos.ShiftSyncResponse applyClose(PosDevicePrincipal principal, UUID shiftId, PosDtos.CloseShiftRequest r) {
        Ctx c = context(principal, r.originDeviceId());
        String hash = closeHash(shiftId, r);
        Optional<PosSyncOperation> existing = findExisting(r.operationId(), TYPE_CLOSE, r.originDeviceId(), shiftId, c.store());
        if (existing.isPresent()) return replay(existing.get(), c.store(), TYPE_CLOSE, hash);

        PosShift shift = ownShift(shiftId, c);
        if (!shift.getCurrency().equalsIgnoreCase(r.currency())) {
            throw new PosSyncRejectedException("CURRENCY_MISMATCH", "The close's currency is not the shift's currency");
        }
        checkAmount(r.countedCash(), r.currency(), "The counted cash");
        PosDtos.ShiftTotals claimed = r.deviceTotals();
        // The till's figures must be self-consistent and start from this shift's opening cash.
        BigDecimal claimedExpected = PosShiftPolicy.expectedCash(claimed.openingCash(), claimed.cashSales(), claimed.cashIn(),
                claimed.cashRefunds(), claimed.cashOut());
        if (claimed.openingCash().compareTo(shift.getOpeningCash()) != 0 || claimedExpected.compareTo(claimed.expectedCash()) != 0
                || r.countedCash().subtract(claimed.expectedCash()).compareTo(r.variance()) != 0
                || Stream.of(claimed.cashSales(), claimed.terminalSales(), claimed.cashRefunds(), claimed.terminalRefunds(),
                        claimed.cashIn(), claimed.cashOut()).anyMatch(v -> v.signum() < 0)) {
            throw new PosSyncRejectedException("SHIFT_TOTALS_INVALID", "The shift's closing figures do not add up");
        }
        checkAmount(claimed.expectedCash(), r.currency(), "The expected cash");
        if (shift.getStatus() != PosShift.Status.OPEN && shift.getStatus() != PosShift.Status.FORCE_CLOSED) {
            // Closed by an earlier close of this very shift under another id: caught above as a replay.
            throw new PosSyncRejectedException("SHIFT_ALREADY_CLOSED", "This shift is already closed", HttpStatus.CONFLICT);
        }

        Instant now = Instant.now();
        PosSyncOperation op = newOperation(r.operationId(), c, TYPE_CLOSE, shift.getId(), hash, now);
        List<PosSyncConflict> conflicts = new ArrayList<>();
        PosDtos.ShiftTotals server = totals(List.of(shift)).get(shift.getId());
        BigDecimal serverVariance = r.countedCash().subtract(server.expectedCash()).setScale(3, RoundingMode.UNNECESSARY);
        boolean matched = server.expectedCash().compareTo(claimed.expectedCash()) == 0;
        if (!matched) {
            BigDecimal diff = claimed.expectedCash().subtract(server.expectedCash());
            conflicts.add(conflict(PosSyncConflictType.SHIFT_RECONCILIATION_MISMATCH, shift.getShiftNumber(),
                    "LOCAL_EXPECTED " + money(claimed.expectedCash()) + ", SERVER_EXPECTED " + money(server.expectedCash())
                            + ", difference " + money(diff) + " " + shift.getCurrency()
                            + ". The till counted records khanGates does not have (or the reverse): check refused or missing uploads."));
        }

        Optional<PosDtos.PosStaffMember> closer = staffService.currentMember(c.store(), r.closedBy().userId())
                .filter(m -> m.posLevel() != PermissionLevel.NONE);
        if (closer.isEmpty()) {
            conflicts.add(conflict(PosSyncConflictType.STAFF_UNAVAILABLE, r.closedBy().name(),
                    "The person who closed this shift is not a POS user of this store now. The close is kept - check it."));
        }
        boolean closerIsCashier = shift.getCashierId() != null && shift.getCashierId().equals(r.closedBy().userId());
        boolean needsApproval = PosShiftPolicy.closeNeedsApproval(r.variance(), closerIsCashier, closer.map(PosShiftService::isManager).orElse(false));
        Approval approval = approval(c.store(), r.overrides(), PosShiftPolicy.CLOSE_APPROVAL);
        if (needsApproval && !approval.verified()) {
            conflicts.add(conflict(PosSyncConflictType.SHIFT_APPROVAL_MISSING, shift.getShiftNumber(), approval.override() == null
                    ? "This shift was closed with a variance of " + money(r.variance()) + " " + shift.getCurrency()
                    + (closerIsCashier ? "" : " by someone other than its cashier") + " and no POS manager's approval. It is kept - check it."
                    : "The approval of this close was given by someone who is not a POS manager of this store now. It is kept - check it."));
        }

        if (shift.getStatus() == PosShift.Status.OPEN) {
            shift.setStatus(serverVariance.signum() == 0 ? PosShift.Status.CLOSED : PosShift.Status.CLOSED_WITH_VARIANCE);
            shift.setClosedAt(clamp(r.closedAt(), shift.getOpenedAt(), now));
            shift.setExpectedCash(server.expectedCash());
            shift.setVariance(serverVariance);
        }
        // A shift force-closed on the dashboard keeps that status; the till's count still fills in what it lacked.
        shift.setClosedById(existingUser(r.closedBy().userId()));
        shift.setClosedByName(truncate(r.closedBy().name().trim(), 160));
        shift.setCountedCash(r.countedCash().setScale(3, RoundingMode.UNNECESSARY));
        shift.setDeviceExpectedCash(claimed.expectedCash().setScale(3, RoundingMode.UNNECESSARY));
        shift.setDeviceVariance(r.variance().setScale(3, RoundingMode.UNNECESSARY));
        if (approval.override() != null) {
            shift.setClosingManagerId(existingUser(approval.override().managerId()));
            shift.setClosingManagerName(truncate(approval.override().managerName(), 160));
        } else if (closer.map(PosShiftService::isManager).orElse(false)) {
            shift.setClosingManagerId(existingUser(r.closedBy().userId()));
            shift.setClosingManagerName(truncate(r.closedBy().name().trim(), 160));
        }
        shift.setCloseNote(r.note() == null || r.note().isBlank() ? null : truncate(r.note().trim(), 300));
        shift.setUpdatedAt(now);
        shiftRepository.saveAndFlush(shift);
        recordOverrides(c, shift.getId(), null, r.closedBy(), r.overrides());

        return finish(op, c, shift, null, conflicts, matched ? "MATCHED" : "MISMATCH");
    }

    // ── shared upload plumbing ────────────────────────────────────────────────────────────────

    private PosShift ownShift(UUID shiftId, Ctx c) {
        return shiftRepository.findByIdForUpdate(shiftId)
                .filter(s -> s.getStoreId().equals(c.store().getId()) && Objects.equals(s.getDeviceId(), c.origin().getId()))
                // Another store's or device's shift is answered exactly like a missing one.
                .orElseThrow(() -> new PosSyncRejectedException("SHIFT_UNKNOWN",
                        "This shift is not on khanGates for this till. Its opening must be uploaded first."));
    }

    private PosSyncOperation newOperation(UUID operationId, Ctx c, String type, UUID entityId, String hash, Instant now) {
        PosSyncOperation op = new PosSyncOperation();
        op.setOperationId(operationId);
        op.setStoreId(c.store().getId());
        op.setDeviceId(c.origin().getId());
        op.setSubmittedByDeviceId(c.uploader().getId());
        op.setOperationType(type);
        op.setEntityId(entityId);
        op.setRequestHash(hash);
        op.setCatalogVersion("-"); // shifts are not priced from a catalog
        op.setStatus(PosSyncOperationStatus.APPLYING);
        op.setCreatedAt(now);
        // Written first: a concurrent duplicate blocks on this key and then fails, instead of both applying.
        return operationRepository.saveAndFlush(op);
    }

    private PosDtos.ShiftSyncResponse finish(PosSyncOperation op, Ctx c, PosShift shift, UUID movementId,
                                             List<PosSyncConflict> conflicts, String reconciliation) {
        Instant now = Instant.now();
        for (PosSyncConflict conflict : conflicts) {
            conflict.setStoreId(c.store().getId());
            conflict.setDeviceId(c.origin().getId());
            conflict.setOperationId(op.getOperationId());
            conflict.setReceiptNumber(shift.getShiftNumber());
            conflict.setCreatedAt(now);
        }
        conflictRepository.saveAll(conflicts);
        op.setStatus(conflicts.isEmpty() ? PosSyncOperationStatus.SYNCED : PosSyncOperationStatus.SYNCED_WITH_CONFLICTS);
        op.setCompletedAt(now);
        operationRepository.saveAndFlush(op);
        return response(op, false, shift, movementId, conflicts, reconciliation);
    }

    private Optional<PosSyncOperation> findExisting(UUID operationId, String type, UUID originDeviceId, UUID entityId, Store store) {
        Optional<PosSyncOperation> byId = operationRepository.findById(operationId);
        if (byId.isPresent()) return byId;
        return operationRepository.findByDeviceIdAndOperationTypeAndEntityId(originDeviceId, type, entityId)
                .filter(op -> op.getStoreId().equals(store.getId()));
    }

    private PosDtos.ShiftSyncResponse replay(PosSyncOperation op, Store store, String type, String hash) {
        if (!op.getStoreId().equals(store.getId()) || !type.equals(op.getOperationType()) || !op.getRequestHash().equals(hash)) {
            throw new PosSyncRejectedException("OPERATION_ID_REUSED",
                    "This operation id was already used for a different upload", HttpStatus.CONFLICT);
        }
        UUID shiftId = TYPE_MOVEMENT.equals(type)
                ? movementRepository.findByOperationId(op.getOperationId()).orElseThrow().getShiftId()
                : op.getEntityId();
        PosShift shift = shiftRepository.findById(shiftId).orElseThrow();
        String reconciliation = TYPE_CLOSE.equals(type) && shift.getDeviceExpectedCash() != null && shift.getExpectedCash() != null
                ? (shift.getDeviceExpectedCash().compareTo(shift.getExpectedCash()) == 0 ? "MATCHED" : "MISMATCH") : null;
        return response(op, true, shift, TYPE_MOVEMENT.equals(type) ? op.getEntityId() : null,
                conflictRepository.findByOperationIdOrderByCreatedAtAsc(op.getOperationId()), reconciliation);
    }

    private PosDtos.ShiftSyncResponse response(PosSyncOperation op, boolean replayed, PosShift shift, UUID movementId,
                                               List<PosSyncConflict> conflicts, String reconciliation) {
        PosDtos.ShiftTotals totals = totals(List.of(shift)).get(shift.getId());
        return new PosDtos.ShiftSyncResponse(op.getOperationId(), replayed, op.getStatus(), shift.getId(), movementId,
                shift.getStatus().name(), totals, shift.getCountedCash(), shift.getVariance(), reconciliation, op.getCompletedAt(),
                conflicts.stream().map(c -> new PosDtos.SyncConflict(c.getId(), c.getType(), c.getProductId(), c.getVariantId(),
                        c.getItemName(), c.getRequestedQuantity(), c.getAppliedQuantity(), c.getShortfall(), c.getDetail())).toList());
    }

    private record Approval(PosDtos.SaleOverride override, boolean verified) {}

    /** The approval of [action] among the overrides, and whether its approver is a POS manager now. */
    private Approval approval(Store store, List<PosDtos.SaleOverride> overrides, String action) {
        PosDtos.SaleOverride o = overrides == null ? null
                : overrides.stream().filter(x -> action.equals(x.action())).findFirst().orElse(null);
        if (o == null) return new Approval(null, false);
        return new Approval(o, staffService.currentMember(store, o.managerId()).filter(PosShiftService::isManager).isPresent());
    }

    /** Every approval is audited with the shift (and movement) it covers, verified or not. */
    private void recordOverrides(Ctx c, UUID shiftId, UUID movementId, PosDtos.SaleStaff acting, List<PosDtos.SaleOverride> overrides) {
        if (overrides == null) return;
        for (PosDtos.SaleOverride o : overrides) {
            boolean verified = staffService.currentMember(c.store(), o.managerId()).filter(PosShiftService::isManager).isPresent();
            jdbc.update("""
                    INSERT INTO pos_manager_overrides (store_id, device_id, shift_id, cash_movement_id, action, acting_staff_id,
                        acting_staff_name, manager_id, manager_name, detail, approved_at, verified)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, c.store().getId(), c.origin().getId(), shiftId, movementId, o.action(), existingUser(acting.userId()),
                    truncate(acting.name(), 160), existingUser(o.managerId()), truncate(o.managerName(), 160), o.detail(),
                    java.sql.Timestamp.from(o.approvedAt()), verified);
        }
    }

    // ── totals (the server's own figures) ─────────────────────────────────────────────────────

    /**
     * The drawer figures of each shift from the records on the server now: POS sales and returns linked
     * to the shift AND made on its device in its store, and its cash movements. See PosShiftPolicy.
     */
    public Map<UUID, PosDtos.ShiftTotals> totals(Collection<PosShift> shifts) {
        Map<UUID, PosDtos.ShiftTotals> result = new HashMap<>();
        if (shifts.isEmpty()) return result;
        Object[] ids = shifts.stream().map(PosShift::getId).toArray();
        record Sales(BigDecimal cash, BigDecimal terminal, long count) {}
        record Refunds(BigDecimal cash, BigDecimal terminal, long count) {}
        record Moves(BigDecimal in, BigDecimal out) {}
        Map<UUID, Sales> sales = new HashMap<>();
        Map<UUID, Refunds> refunds = new HashMap<>();
        Map<UUID, Moves> moves = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT s.id,
                           COALESCE(SUM(CASE WHEN o.payment_method = 'CASH' THEN o.total - COALESCE(o.pos_exchange_credit, 0) ELSE 0 END), 0) AS cash,
                           COALESCE(SUM(CASE WHEN o.payment_method <> 'CASH' THEN o.total - COALESCE(o.pos_exchange_credit, 0) ELSE 0 END), 0) AS terminal,
                           COUNT(o.id) AS n
                    FROM pos_shifts s
                    JOIN customer_orders o ON o.pos_shift_id = s.id AND o.store_id = s.store_id AND o.pos_device_id = s.device_id
                         AND o.source = 'POS' AND o.status <> 'CANCELLED' AND o.pos_order_type IS NULL
                    WHERE s.id = ANY(?) GROUP BY s.id
                    """);
            ps.setArray(1, uuidArray(con, ids));
            return ps;
        }, rs -> {
            sales.put(rs.getObject(1, UUID.class), new Sales(rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getLong(4)));
        });
        // POS-26: a restaurant bill is paid in parts, possibly on several tills and shifts. Each payment
        // counts in the drawer of the shift that took it, when it was taken (not when the table closes).
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT s.id,
                           COALESCE(SUM(CASE WHEN p.method = 'CASH' THEN p.amount ELSE 0 END), 0),
                           COALESCE(SUM(CASE WHEN p.method <> 'CASH' THEN p.amount ELSE 0 END), 0),
                           COUNT(DISTINCT p.order_id)
                    FROM pos_shifts s
                    JOIN pos_order_payments p ON p.shift_id = s.id AND p.store_id = s.store_id AND p.device_id = s.device_id
                    WHERE s.id = ANY(?) GROUP BY s.id
                    """);
            ps.setArray(1, uuidArray(con, ids));
            return ps;
        }, rs -> {
            UUID id = rs.getObject(1, UUID.class);
            Sales before = sales.getOrDefault(id, new Sales(BigDecimal.ZERO, BigDecimal.ZERO, 0));
            sales.put(id, new Sales(before.cash().add(rs.getBigDecimal(2)), before.terminal().add(rs.getBigDecimal(3)), before.count() + rs.getLong(4)));
        });
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT s.id,
                           COALESCE(SUM(CASE WHEN r.refund_method = 'CASH' THEN r.refund_paid_out ELSE 0 END), 0),
                           COALESCE(SUM(CASE WHEN r.refund_method = 'EXTERNAL_TERMINAL' THEN r.refund_paid_out ELSE 0 END), 0),
                           COUNT(r.id)
                    FROM pos_shifts s
                    JOIN pos_returns r ON r.shift_id = s.id AND r.store_id = s.store_id AND r.device_id = s.device_id
                    WHERE s.id = ANY(?) GROUP BY s.id
                    """);
            ps.setArray(1, uuidArray(con, ids));
            return ps;
        }, rs -> {
            refunds.put(rs.getObject(1, UUID.class), new Refunds(rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getLong(4)));
        });
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT s.id,
                           COALESCE(SUM(CASE WHEN m.movement_type = 'CASH_IN' THEN m.amount ELSE 0 END), 0),
                           COALESCE(SUM(CASE WHEN m.movement_type = 'CASH_OUT' THEN m.amount ELSE 0 END), 0)
                    FROM pos_shifts s
                    JOIN pos_shift_cash_movements m ON m.shift_id = s.id AND m.store_id = s.store_id AND m.device_id = s.device_id
                    WHERE s.id = ANY(?) GROUP BY s.id
                    """);
            ps.setArray(1, uuidArray(con, ids));
            return ps;
        }, rs -> {
            moves.put(rs.getObject(1, UUID.class), new Moves(rs.getBigDecimal(2), rs.getBigDecimal(3)));
        });
        for (PosShift s : shifts) {
            Sales sa = sales.getOrDefault(s.getId(), new Sales(BigDecimal.ZERO, BigDecimal.ZERO, 0));
            Refunds re = refunds.getOrDefault(s.getId(), new Refunds(BigDecimal.ZERO, BigDecimal.ZERO, 0));
            Moves mo = moves.getOrDefault(s.getId(), new Moves(BigDecimal.ZERO, BigDecimal.ZERO));
            BigDecimal expected = PosShiftPolicy.expectedCash(s.getOpeningCash(), sa.cash(), mo.in(), re.cash(), mo.out());
            result.put(s.getId(), new PosDtos.ShiftTotals(m3(s.getOpeningCash()), m3(sa.cash()), m3(sa.terminal()), m3(re.cash()),
                    m3(re.terminal()), m3(mo.in()), m3(mo.out()), m3(expected), sa.count(), re.count()));
        }
        return result;
    }

    private static Array uuidArray(java.sql.Connection con, Object[] ids) throws java.sql.SQLException {
        return con.createArrayOf("uuid", ids);
    }

    // ── dashboard ─────────────────────────────────────────────────────────────────────────────

    /**
     * The store's shifts, newest first (at most 200). status: OPEN | CLOSED | VARIANCE (a closed shift
     * whose count differs from the expected cash now, or whose till and server figures disagree).
     * from/to are days in the store's time zone, on the opening time.
     */
    @Transactional(readOnly = true)
    public List<PosDtos.ShiftSummary> list(UUID storeId, String status, UUID cashierId, UUID deviceId, LocalDate from, LocalDate to) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.ORDERS, PermissionLevel.VIEW);
        ZoneId zone = zoneOf(store);
        Instant start = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        List<PosShift> rows = shiftRepository.findByStoreIdOrderByOpenedAtDesc(storeId).stream()
                .filter(s -> cashierId == null || cashierId.equals(s.getCashierId()))
                .filter(s -> deviceId == null || deviceId.equals(s.getDeviceId()))
                .filter(s -> start == null || !s.getOpenedAt().isBefore(start))
                .filter(s -> end == null || s.getOpenedAt().isBefore(end))
                .filter(s -> status == null || !"OPEN".equals(status) || s.isOpen())
                .filter(s -> status == null || !"CLOSED".equals(status) || !s.isOpen())
                .toList();
        List<PosDtos.ShiftSummary> summaries = summaries(rows);
        if ("VARIANCE".equals(status)) {
            summaries = summaries.stream().filter(s -> !"OPEN".equals(s.status())
                    && ((s.variance() != null && s.variance().signum() != 0) || "MISMATCH".equals(s.reconciliation()))).toList();
        }
        return summaries.size() > LIST_LIMIT ? summaries.subList(0, LIST_LIMIT) : summaries;
    }

    @Transactional(readOnly = true)
    public PosDtos.ShiftDetail detail(UUID id) {
        PosShift shift = shiftRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Shift not found"));
        Store store = storeService.accessibleStore(shift.getStoreId());
        currentUser.ensureSectionAccess(store, DashboardSection.ORDERS, PermissionLevel.VIEW);
        return detailOf(shift);
    }

    /**
     * A POS manager closes a shift the till never closed (its data was lost, the till is gone). Nothing
     * was counted, so there is no variance; the server's expected cash at this moment is kept.
     */
    @Transactional
    public PosDtos.ShiftDetail forceClose(UUID id, PosDtos.ForceCloseShiftRequest request) {
        PosShift shift = shiftRepository.findByIdForUpdate(id).orElseThrow(() -> new EntityNotFoundException("Shift not found"));
        Store store = storeService.accessibleStore(shift.getStoreId());
        currentUser.ensureSectionAccess(store, DashboardSection.POS, PermissionLevel.EDIT);
        if (!shift.isOpen()) {
            throw new ConflictException("Only an open shift can be force-closed");
        }
        var user = currentUser.user();
        Instant now = Instant.now();
        shift.setStatus(PosShift.Status.FORCE_CLOSED);
        shift.setClosedAt(now);
        shift.setExpectedCash(totals(List.of(shift)).get(shift.getId()).expectedCash());
        shift.setForceClosedById(user.getId());
        shift.setForceClosedByName(truncate(user.getFullName(), 160));
        shift.setForceCloseNote(truncate(request.note().trim(), 300));
        shift.setUpdatedAt(now);
        shiftRepository.saveAndFlush(shift);
        return detailOf(shift);
    }

    private List<PosDtos.ShiftSummary> summaries(List<PosShift> shifts) {
        Map<UUID, PosDtos.ShiftTotals> totals = totals(shifts);
        Map<UUID, String> deviceNames = new HashMap<>();
        deviceRepository.findAllById(shifts.stream().map(PosShift::getDeviceId).filter(Objects::nonNull).distinct().toList())
                .forEach(d -> deviceNames.put(d.getId(), d.getName()));
        Instant now = Instant.now();
        return shifts.stream().map(s -> summary(s, totals.get(s.getId()), deviceNames.get(s.getDeviceId()), now)).toList();
    }

    private static PosDtos.ShiftSummary summary(PosShift s, PosDtos.ShiftTotals t, String deviceName, Instant now) {
        BigDecimal liveVariance = s.getCountedCash() == null ? null : s.getCountedCash().subtract(t.expectedCash()).setScale(3, RoundingMode.UNNECESSARY);
        String reconciliation = s.isOpen() ? "OPEN"
                : s.getDeviceExpectedCash() == null ? "NOT_COUNTED"
                : s.getDeviceExpectedCash().compareTo(t.expectedCash()) == 0 ? "MATCHED" : "MISMATCH";
        boolean late = !s.isOpen() && s.getExpectedCash() != null && s.getExpectedCash().compareTo(t.expectedCash()) != 0;
        boolean longOpen = s.isOpen() && s.getOpenedAt().isBefore(now.minus(PosShiftPolicy.LONG_OPEN));
        return new PosDtos.ShiftSummary(s.getId(), s.getShiftNumber(), s.getDeviceId(), deviceName, s.getCashierId(), s.getCashierName(),
                s.getCurrency(), s.getStatus().name(), s.getOpenedAt(), s.getClosedAt(), t, s.getCountedCash(), s.getExpectedCash(),
                s.getVariance(), s.getDeviceExpectedCash(), s.getDeviceVariance(), liveVariance, reconciliation, late, longOpen,
                s.getClosedByName(), s.getClosingManagerName(), s.getCloseNote(), s.getForceClosedByName(), s.getForceCloseNote());
    }

    private PosDtos.ShiftDetail detailOf(PosShift shift) {
        PosDtos.ShiftSummary summary = summaries(List.of(shift)).get(0);
        List<PosDtos.ShiftOrderLine> orders = jdbc.query("""
                SELECT id, order_code, pos_receipt_number, payment_method, total, pos_exchange_credit, pos_staff_name, created_at
                FROM customer_orders
                WHERE pos_shift_id = ? AND store_id = ? AND pos_device_id = ? AND source = 'POS' AND status <> 'CANCELLED'
                  AND pos_order_type IS NULL
                UNION ALL
                -- POS-26: restaurant payments taken in this shift, one row each (total = the payment)
                SELECT o.id, o.order_code, o.pos_receipt_number,
                       CASE WHEN p.method = 'CASH' THEN 'CASH' ELSE 'CARD' END, p.amount, NULL, p.staff_name, p.paid_at
                FROM pos_order_payments p JOIN customer_orders o ON o.id = p.order_id
                WHERE p.shift_id = ? AND p.store_id = ? AND p.device_id = ?
                ORDER BY 8, 1
                """, (rs, i) -> {
            BigDecimal total = rs.getBigDecimal("total");
            BigDecimal credit = rs.getBigDecimal("pos_exchange_credit");
            return new PosDtos.ShiftOrderLine(rs.getObject("id", UUID.class), rs.getString("order_code"), rs.getString("pos_receipt_number"),
                    "CASH".equals(rs.getString("payment_method")) ? "CASH" : "EXTERNAL_CARD", total, credit,
                    total.subtract(credit == null ? BigDecimal.ZERO : credit), rs.getString("pos_staff_name"),
                    rs.getTimestamp("created_at").toInstant());
        }, shift.getId(), shift.getStoreId(), shift.getDeviceId(), shift.getId(), shift.getStoreId(), shift.getDeviceId());
        List<PosDtos.ShiftReturnLine> returns = jdbc.query("""
                SELECT id, return_number, kind, original_order_code, refund_method, refund_paid_out, exchange_credit, staff_name, returned_at
                FROM pos_returns WHERE shift_id = ? AND store_id = ? AND device_id = ? ORDER BY returned_at, id
                """, (rs, i) -> new PosDtos.ShiftReturnLine(rs.getObject("id", UUID.class), rs.getString("return_number"), rs.getString("kind"),
                rs.getString("original_order_code"), rs.getString("refund_method"), rs.getBigDecimal("refund_paid_out"),
                rs.getBigDecimal("exchange_credit"), rs.getString("staff_name"), rs.getTimestamp("returned_at").toInstant()),
                shift.getId(), shift.getStoreId(), shift.getDeviceId());
        List<PosShiftCashMovement> moves = movementRepository.findByShiftIdAndStoreIdOrderByMovedAtAsc(shift.getId(), shift.getStoreId());
        List<PosDtos.ShiftMovementLine> movements = moves.stream().map(m -> new PosDtos.ShiftMovementLine(m.getId(), m.getType().name(),
                m.getReason().name(), m.getNote(), m.getAmount(), m.getStaffName(), m.getManagerName(), m.getMovedAt())).toList();
        List<PosDtos.ShiftApprovalLine> approvals = jdbc.query("""
                SELECT action, manager_name, acting_staff_name, detail, verified, approved_at FROM pos_manager_overrides
                WHERE store_id = ? AND (shift_id = ? OR cash_movement_id IN (SELECT id FROM pos_shift_cash_movements WHERE shift_id = ?))
                ORDER BY approved_at
                """, (rs, i) -> new PosDtos.ShiftApprovalLine(rs.getString("action"), rs.getString("manager_name"),
                rs.getString("acting_staff_name"), rs.getString("detail"), rs.getBoolean("verified"), rs.getTimestamp("approved_at").toInstant()),
                shift.getStoreId(), shift.getId(), shift.getId());
        List<UUID> entityIds = new ArrayList<>(moves.stream().map(PosShiftCashMovement::getId).toList());
        entityIds.add(shift.getId());
        List<UUID> conflictIds = jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT c.id FROM pos_sync_conflicts c JOIN pos_sync_operations op ON op.operation_id = c.operation_id
                    WHERE c.store_id = ? AND op.operation_type IN ('SHIFT_OPEN', 'SHIFT_CASH_MOVEMENT', 'SHIFT_CLOSE') AND op.entity_id = ANY(?)
                    ORDER BY c.created_at
                    """);
            ps.setObject(1, shift.getStoreId());
            ps.setArray(2, uuidArray(con, entityIds.toArray()));
            return ps;
        }, (rs, i) -> rs.getObject(1, UUID.class));
        String deviceName = summary.deviceName();
        List<PosDtos.ConflictResponse> conflicts = conflictRepository.findAllById(conflictIds).stream()
                .map(c -> PosSyncConflictService.response(c, ignored -> deviceName)).toList();
        return new PosDtos.ShiftDetail(summary, orders, returns, movements, approvals, conflicts);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private static boolean isManager(PosDtos.PosStaffMember m) {
        return m.owner() || m.posLevel() == PermissionLevel.EDIT;
    }

    private PosDevice uploader(PosDevicePrincipal principal) {
        PosDevice device = deviceRepository.findById(principal.deviceId()).orElseThrow();
        if (!device.getStore().getId().equals(principal.storeId())) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }
        return device;
    }

    private static Instant floorFor(PosDevice origin) {
        return origin.getCreatedAt() == null ? null : origin.getCreatedAt().minus(MAX_CLOCK_SKEW);
    }

    /** The till's clock is trusted only within bounds: not before [floor], not in the future. */
    private static Instant clamp(Instant at, Instant floor, Instant now) {
        if (at.isAfter(now)) return now;
        if (floor != null && at.isBefore(floor)) return floor;
        return at;
    }

    /** Exact money only: never more decimals than the currency has (JOD 3), never absurdly large. */
    static void checkAmount(BigDecimal value, String currency, String what) {
        int digits = fractionDigits(currency);
        if (value.stripTrailingZeros().scale() > digits || value.abs().compareTo(MAX_AMOUNT) > 0) {
            throw new PosSyncRejectedException("AMOUNT_INVALID", what + " is not a valid " + currency.toUpperCase() + " amount");
        }
    }

    static int fractionDigits(String currency) {
        try {
            int d = Currency.getInstance(currency.toUpperCase()).getDefaultFractionDigits();
            return d < 0 ? 3 : Math.min(d, 3);
        } catch (IllegalArgumentException unknown) {
            return 3;
        }
    }

    private static ZoneId zoneOf(Store store) {
        try {
            return ZoneId.of(store.getTimezone());
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
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

    private static BigDecimal m3(BigDecimal v) {
        return v.setScale(3, RoundingMode.UNNECESSARY);
    }

    private static String money(BigDecimal v) {
        return v.setScale(3, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static void overrides(StringBuilder b, List<PosDtos.SaleOverride> overrides) {
        if (overrides == null) return;
        for (PosDtos.SaleOverride o : overrides) {
            b.append("|override:").append(o.action()).append(',').append(o.managerId()).append(',').append(o.approvedAt());
        }
    }

    /** Canonical form of everything that defines the upload (the operation id itself excluded). */
    static String openHash(PosDtos.OpenShiftRequest r) {
        return PosPriceBookService.sha256(new StringBuilder("open|").append(r.originDeviceId()).append('|').append(r.shiftId())
                .append('|').append(r.shiftNumber()).append('|').append(r.openedAt()).append('|').append(r.currency().toUpperCase())
                .append('|').append(plain(r.openingCash())).append('|').append(r.cashier().userId()).append(',').append(r.cashier().name())
                .toString());
    }

    static String movementHash(UUID shiftId, PosDtos.CashMovementRequest r) {
        StringBuilder b = new StringBuilder("move|").append(r.originDeviceId()).append('|').append(shiftId).append('|').append(r.movementId())
                .append('|').append(r.type()).append('|').append(r.reason()).append('|').append(Objects.toString(r.note(), ""))
                .append('|').append(plain(r.amount())).append('|').append(r.currency().toUpperCase()).append('|').append(r.movedAt())
                .append('|').append(r.staff().userId()).append(',').append(r.staff().name());
        overrides(b, r.overrides());
        return PosPriceBookService.sha256(b.toString());
    }

    static String closeHash(UUID shiftId, PosDtos.CloseShiftRequest r) {
        PosDtos.ShiftTotals t = r.deviceTotals();
        StringBuilder b = new StringBuilder("close|").append(r.originDeviceId()).append('|').append(shiftId).append('|').append(r.closedAt())
                .append('|').append(r.currency().toUpperCase()).append('|').append(plain(r.countedCash())).append('|').append(plain(r.variance()))
                .append('|').append(plain(t.openingCash())).append(',').append(plain(t.cashSales())).append(',').append(plain(t.terminalSales()))
                .append(',').append(plain(t.cashRefunds())).append(',').append(plain(t.terminalRefunds())).append(',').append(plain(t.cashIn()))
                .append(',').append(plain(t.cashOut())).append(',').append(plain(t.expectedCash())).append(',').append(t.orderCount())
                .append(',').append(t.returnCount()).append('|').append(Objects.toString(r.note(), "")).append('|')
                .append(r.closedBy().userId()).append(',').append(r.closedBy().name());
        overrides(b, r.overrides());
        return PosPriceBookService.sha256(b.toString());
    }
}
