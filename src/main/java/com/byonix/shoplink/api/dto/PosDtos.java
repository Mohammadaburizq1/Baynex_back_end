package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.domain.enums.DiscountType;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.domain.enums.PosDeviceStatus;
import com.byonix.shoplink.domain.enums.PosSyncConflictStatus;
import com.byonix.shoplink.domain.enums.PosSyncConflictType;
import com.byonix.shoplink.domain.enums.PosSyncOperationStatus;
import com.byonix.shoplink.domain.enums.StoreStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** POS device registration (POS-02) and the device-scoped catalog sync payload (POS-04). */
public class PosDtos {
    public record CreateDeviceRequest(@NotNull UUID storeId, @NotBlank @Size(max = 80) String name) {}

    /**
     * credentialExpiresAt: when the device's sliding idle credential lapses (null until activated and
     * after revocation). A timestamp only — the credential and code hashes are never part of this.
     */
    public record DeviceResponse(UUID id, UUID storeId, String name, PosDeviceStatus status, String platform,
                                 String appVersion, Instant activationCodeExpiresAt, Instant activatedAt,
                                 Instant lastSeenAt, Instant lastSyncAt, Instant revokedAt, Instant createdAt,
                                 Instant credentialExpiresAt) {}

    /** The only response that ever carries the plaintext activation code. */
    public record IssuedActivationCode(DeviceResponse device, String activationCode, Instant expiresAt) {}

    public record ActivateRequest(@NotBlank @Size(max = 32) String activationCode,
                                  @NotBlank @Size(max = 64) String installationId,
                                  @Size(max = 20) String platform,
                                  @Size(max = 40) String appVersion) {}

    /** What a POS needs to know about its store. Owner/contact/billing data is deliberately absent. */
    public record PosStore(UUID id, String slug, String name, String currency, String timezone, String locale,
                           String templateKey, String categorySlug, StoreStatus status, boolean acceptingOrders,
                           boolean pickupAvailable,
                           // POS-15 receipt header: the store's public contact details (as on its storefront).
                           String phone, String address, String city) {}

    /** The only response that ever carries the plaintext device credential. */
    public record ActivationResponse(UUID deviceId, String deviceName, String deviceCredential,
                                     Instant credentialExpiresAt, PosStore store, Instant serverTime) {}

    public record DeviceSession(UUID deviceId, String deviceName, PosDeviceStatus status, PosStore store,
                                Instant credentialExpiresAt, Instant lastSyncAt, Instant serverTime) {}

    /**
     * Full catalog snapshot for the device's store. {@code catalogVersion} is a content hash: a POS that
     * sends it back as {@code knownVersion} gets {@code unchanged=true} and no body when nothing changed.
     */
    public record CatalogResponse(String catalogVersion, boolean unchanged, Instant generatedAt, PosStore store,
                                  List<CategoryDtos.CategoryResponse> categories,
                                  List<ProductDtos.ProductResponse> products,
                                  List<PosOffer> offers) {}

    /**
     * POS-13: a discount code as the POS may apply it offline — the existing offer rules, nothing
     * more. The live use count is not sent (it changes with every web order); exhausted says whether
     * the limit was already reached when this catalog was built.
     */
    public record PosOffer(UUID id, String code, DiscountType discountType, BigDecimal discountValue,
                           BigDecimal minOrderAmount, Integer maxUses, boolean exhausted,
                           OffsetDateTime startsAt, OffsetDateTime expiresAt, boolean active) {}

    // ── POS-12 customers ──────────────────────────────────────────────────────────────────────

    /**
     * A customer of this store as the dashboard's Customers page knows them: an account that ordered
     * here (customerId set), or a guest contact grouped by phone/email (customerId null). key is
     * stable across syncs. No passwords, tokens, verification or security data.
     */
    public record PosCustomer(String key, UUID customerId, String name, String phone, String email,
                              long orderCount, Instant lastOrderAt) {}

    public record CustomersResponse(String version, boolean unchanged, Instant generatedAt, List<PosCustomer> customers) {}

    // ── POS-14 staff ──────────────────────────────────────────────────────────────────────────

    /** Salted PBKDF2-HMAC-SHA256 of a PIN, for offline verification on the till. Never the PIN. */
    public record PosPin(String salt, String hash, int iterations, Instant expiresAt) {}

    /**
     * Who may use this store's POS: the owner (always a POS manager) and its active staff. posLevel
     * VIEW = cashier, EDIT = POS manager; offersLevel gates applying discount codes without approval.
     */
    public record PosStaffMember(UUID userId, String name, boolean owner, PermissionLevel posLevel,
                                 PermissionLevel ordersLevel, PermissionLevel offersLevel, PosPin pin) {}

    public record StaffResponse(String version, boolean unchanged, Instant generatedAt, List<PosStaffMember> staff) {}

    public record SetPinRequest(@NotBlank @Size(max = 200) String currentPassword,
                                @NotBlank @jakarta.validation.constraints.Pattern(regexp = "^[0-9]{4,8}$",
                                        message = "A POS PIN is 4 to 8 digits") String pin) {}

    public record PinStatus(boolean set, Instant setAt, Instant expiresAt) {}

    // ── POS-09 offline order upload ───────────────────────────────────────────────────────────

    /** Payment methods a POS may record offline. Nothing here talks to a payment gateway. */
    public enum PosPaymentMethod {
        CASH,
        /** Card taken on a standalone terminal; the cashier confirmed it was approved. */
        EXTERNAL_CARD
    }

    /**
     * One sold line exactly as the cashier rang it up. unitPrice includes the add-on deltas. The server
     * recomputes both prices from the catalog snapshot the device was given and refuses the upload
     * if they differ; names and SKUs are taken from that snapshot, never from the device.
     */
    public record SyncOrderItem(@NotNull UUID productId, UUID variantId,
                                @Size(max = 30) List<@NotNull UUID> modifierOptionIds,
                                @NotNull @Min(1) @Max(10000) Integer quantity,
                                @NotNull BigDecimal unitPrice, @NotNull BigDecimal lineTotal) {}

    /**
     * An offline sale. operationId is the idempotency key; originDeviceId is the device that made the
     * sale (the caller itself, or the device it replaced after a revocation — same store only). The
     * store is never taken from the payload: it is the authenticated device's store.
     */
    public record SyncOrderRequest(@NotNull UUID operationId, @NotNull UUID originDeviceId, @NotNull UUID localOrderId,
                                   @NotBlank @Size(max = 40) String receiptNumber,
                                   @NotBlank @Size(max = 64) String catalogVersion,
                                   @NotNull Instant soldAt,
                                   @NotBlank @Size(min = 3, max = 3) String currency,
                                   @NotNull PosPaymentMethod paymentMethod,
                                   @NotNull BigDecimal subtotal, @NotNull BigDecimal discount, @NotNull BigDecimal total,
                                   @Size(max = 500) String note,
                                   @NotEmpty @Size(max = 200) List<@Valid SyncOrderItem> items,
                                   // POS-12..14, all optional so uploads queued by older app versions stay valid.
                                   @Valid SaleCustomer customer,
                                   @Valid SaleOffer offer,
                                   @Valid SaleStaff staff,
                                   @Size(max = 20) List<@Valid SaleOverride> overrides,
                                   // POS-23: the replacement sale of an exchange (optional).
                                   @Valid SaleExchange exchange,
                                   // POS-24: the till shift the sale was rung up in (optional: older apps, shifts off).
                                   UUID shiftId) {}

    /** POS-23: part of this sale's total was paid by goods returned in the same exchange. */
    public record SaleExchange(@NotNull UUID localReturnId, @NotNull @jakarta.validation.constraints.Positive BigDecimal credit) {}

    /** customerId = an account from this store's customer list; otherwise a contact typed at the till. */
    public record SaleCustomer(UUID customerId, @Size(max = 160) String name, @Size(max = 40) String phone,
                               @Size(max = 255) String email) {}

    /**
     * The discount code applied at the till. The amount is NOT taken from here: the server recomputes it
     * from the offer as this device's catalog snapshot had it, and the sale's own discount/total must
     * match that exactly.
     */
    public record SaleOffer(@NotNull UUID offerId, @NotBlank @Size(max = 40) String code) {}

    /** The cashier signed in on the till (name as shown there). */
    public record SaleStaff(@NotNull UUID userId, @NotBlank @Size(max = 160) String name) {}

    /** A sensitive action a manager approved with their PIN at the till. */
    public record SaleOverride(@NotBlank @Size(max = 40) String action, @NotNull UUID managerId,
                               @NotBlank @Size(max = 160) String managerName, @NotNull Instant approvedAt,
                               @Size(max = 300) String detail) {}

    /** The server's stock count for an item after the upload (null = not tracked). */
    public record StockLevel(UUID productId, UUID variantId, Integer stock) {}

    public record SyncConflict(UUID id, PosSyncConflictType type, UUID productId, UUID variantId, String itemName,
                               Integer requestedQuantity, Integer appliedQuantity, Integer shortfall, String detail) {}

    /** replayed = this operation id was already applied; nothing was created or moved this time. */
    public record SyncOrderResponse(UUID operationId, boolean replayed, PosSyncOperationStatus status, UUID orderId,
                                    String orderCode, BigDecimal total, String currency, Instant syncedAt,
                                    List<StockLevel> inventory, List<SyncConflict> conflicts) {}

    // ── POS-23: returns and exchanges ────────────────────────────────────────────────────────────

    /**
     * A return (or the return half of an exchange) made at a till, possibly offline. The store is the
     * authenticated device's. Every amount is a claim the server recomputes from the original sale.
     */
    public record SyncReturnRequest(@NotNull UUID operationId, @NotNull UUID originDeviceId, @NotNull UUID localReturnId,
                                    @NotBlank @Size(max = 40) String returnNumber,
                                    @NotNull com.byonix.shoplink.domain.entity.PosReturn.Kind kind,
                                    @NotNull UUID originalOrderId,
                                    @NotNull Instant returnedAt,
                                    @NotBlank @Size(min = 3, max = 3) String currency,
                                    @NotNull com.byonix.shoplink.domain.entity.PosReturn.Reason reason,
                                    @Size(max = 300) String reasonNote,
                                    @NotNull BigDecimal refundTotal,
                                    @NotNull BigDecimal exchangeCredit,
                                    @NotNull BigDecimal refundPaidOut,
                                    com.byonix.shoplink.domain.entity.PosReturn.RefundMethod refundMethod,
                                    UUID exchangeLocalOrderId,
                                    @NotEmpty @Size(max = 200) List<@Valid SyncReturnItem> items,
                                    @Valid SaleStaff staff,
                                    @Size(max = 20) List<@Valid SaleOverride> overrides,
                                    // POS-24: the till shift the return was made in (optional).
                                    UUID shiftId) {}

    /**
     * One returned line. lineNo = the till's line number on the original sale; returnedBefore = units of
     * that line the till knew were already returned (the refund is priced from it, see PosReturnPolicy).
     */
    public record SyncReturnItem(@NotNull @Min(1) Integer lineNo, @NotNull UUID productId, UUID variantId,
                                 @NotNull @Min(1) @Max(10000) Integer quantity,
                                 @NotNull @Min(0) @Max(10000) Integer returnedBefore,
                                 @NotNull BigDecimal refundAmount,
                                 @NotNull com.byonix.shoplink.domain.entity.PosReturnItem.Disposition disposition) {}

    public record ReturnItemResult(Integer lineNo, int requestedQuantity, int acceptedQuantity, BigDecimal refundAmount,
                                   int restockedQuantity) {}

    /** replayed = this operation id was already applied; nothing was refunded or restocked again. */
    public record SyncReturnResponse(UUID operationId, boolean replayed, PosSyncOperationStatus status, UUID returnId,
                                     String originalOrderCode, BigDecimal requestedRefundTotal, BigDecimal refundTotal,
                                     String currency, Instant syncedAt, List<ReturnItemResult> items,
                                     List<StockLevel> inventory, List<SyncConflict> conflicts) {}

    /** Dashboard view of a return (store's own only). */
    public record ReturnSummary(UUID id, String returnNumber, String kind, UUID originalOrderId, String originalOrderCode,
                                String originalReceiptNumber, String customerName, String staffName, String managerName,
                                String reason, String reasonNote, String refundMethod, BigDecimal requestedRefundTotal,
                                BigDecimal refundTotal, BigDecimal exchangeCredit, BigDecimal refundPaidOut, String currency,
                                String status, UUID deviceId, Instant returnedAt, List<ReturnItemResult> items) {}

    // ── POS-24: shifts and drawer cash ─────────────────────────────────────────────────────────

    /**
     * A shift opened at a till, possibly offline. shiftId is generated by the till (sales name it
     * before it is uploaded). The store is the authenticated device's; cashier and device are checked.
     */
    public record OpenShiftRequest(@NotNull UUID operationId, @NotNull UUID originDeviceId, @NotNull UUID shiftId,
                                   @NotBlank @Size(max = 40) String shiftNumber,
                                   @NotNull Instant openedAt,
                                   @NotBlank @Size(min = 3, max = 3) String currency,
                                   @NotNull @jakarta.validation.constraints.PositiveOrZero BigDecimal openingCash,
                                   @NotNull @Valid SaleStaff cashier) {}

    /** Cash put into (CASH_IN) or taken out of (CASH_OUT) the drawer outside a sale. Never an order. */
    public record CashMovementRequest(@NotNull UUID operationId, @NotNull UUID originDeviceId, @NotNull UUID movementId,
                                      @NotNull com.byonix.shoplink.domain.entity.PosShiftCashMovement.Type type,
                                      @NotNull com.byonix.shoplink.domain.entity.PosShiftCashMovement.Reason reason,
                                      @Size(max = 300) String note,
                                      @NotNull @jakarta.validation.constraints.Positive BigDecimal amount,
                                      @NotBlank @Size(min = 3, max = 3) String currency,
                                      @NotNull Instant movedAt,
                                      @NotNull @Valid SaleStaff staff,
                                      @Size(max = 5) List<@Valid SaleOverride> overrides) {}

    /**
     * The drawer's figures as the till computed them. Each is a claim: the server checks that they add
     * up, then recomputes its own from the records it has and keeps both.
     */
    public record ShiftTotals(@NotNull BigDecimal openingCash, @NotNull BigDecimal cashSales, @NotNull BigDecimal terminalSales,
                              @NotNull BigDecimal cashRefunds, @NotNull BigDecimal terminalRefunds,
                              @NotNull BigDecimal cashIn, @NotNull BigDecimal cashOut, @NotNull BigDecimal expectedCash,
                              @NotNull @Min(0) Long orderCount, @NotNull @Min(0) Long returnCount) {}

    /** The cashier counted the drawer and closed the shift at the till (possibly offline). */
    public record CloseShiftRequest(@NotNull UUID operationId, @NotNull UUID originDeviceId,
                                    @NotNull Instant closedAt,
                                    @NotBlank @Size(min = 3, max = 3) String currency,
                                    @NotNull @jakarta.validation.constraints.PositiveOrZero BigDecimal countedCash,
                                    @NotNull BigDecimal variance,
                                    @NotNull @Valid ShiftTotals deviceTotals,
                                    @Size(max = 300) String note,
                                    @NotNull @Valid SaleStaff closedBy,
                                    @Size(max = 5) List<@Valid SaleOverride> overrides) {}

    /**
     * Answer to every shift upload. serverTotals = the server's own drawer figures now; for a close,
     * reconciliation says whether the till's expected cash matched them (MATCHED / MISMATCH).
     */
    public record ShiftSyncResponse(UUID operationId, boolean replayed, PosSyncOperationStatus status, UUID shiftId,
                                    UUID movementId, String shiftStatus, ShiftTotals serverTotals, BigDecimal countedCash,
                                    BigDecimal variance, String reconciliation, Instant syncedAt, List<SyncConflict> conflicts) {}

    /**
     * Dashboard row. totals are live (recomputed from every synced record now); expectedCashAtClose /
     * varianceAtClose are the server's figures when the close arrived; deviceExpectedCash / deviceVariance
     * are what the till showed the cashier. lateRecords = records linked to the shift arrived after it closed.
     */
    public record ShiftSummary(UUID id, String shiftNumber, UUID deviceId, String deviceName, UUID cashierId, String cashierName,
                               String currency, String status, Instant openedAt, Instant closedAt, ShiftTotals totals,
                               BigDecimal countedCash, BigDecimal expectedCashAtClose, BigDecimal varianceAtClose,
                               BigDecimal deviceExpectedCash, BigDecimal deviceVariance, BigDecimal variance,
                               String reconciliation, boolean lateRecords, boolean longOpen,
                               String closedByName, String closingManagerName, String closeNote,
                               String forceClosedByName, String forceCloseNote) {}

    public record ShiftOrderLine(UUID orderId, String orderCode, String receiptNumber, String paymentMethod, BigDecimal total,
                                 BigDecimal exchangeCredit, BigDecimal drawerAmount, String staffName, Instant soldAt) {}

    public record ShiftReturnLine(UUID returnId, String returnNumber, String kind, String originalOrderCode, String refundMethod,
                                  BigDecimal refundPaidOut, BigDecimal exchangeCredit, String staffName, Instant returnedAt) {}

    public record ShiftMovementLine(UUID id, String type, String reason, String note, BigDecimal amount, String staffName,
                                    String managerName, Instant movedAt) {}

    public record ShiftApprovalLine(String action, String managerName, String actingStaffName, String detail,
                                    boolean verified, Instant approvedAt) {}

    public record ShiftDetail(ShiftSummary shift, List<ShiftOrderLine> orders, List<ShiftReturnLine> returns,
                              List<ShiftMovementLine> movements, List<ShiftApprovalLine> approvals,
                              List<ConflictResponse> conflicts) {}

    public record ForceCloseShiftRequest(@NotBlank @Size(max = 300) String note) {}

    // ── dashboard: conflicts to review ────────────────────────────────────────────────────────

    public record ConflictResponse(UUID id, UUID storeId, UUID deviceId, String deviceName, UUID orderId, String orderCode,
                                   String receiptNumber, PosSyncConflictType type, UUID productId, UUID variantId,
                                   String itemName, Integer requestedQuantity, Integer appliedQuantity, Integer shortfall,
                                   Integer stockBefore, Integer stockAfter, BigDecimal saleUnitPrice,
                                   BigDecimal currentUnitPrice, String detail, PosSyncConflictStatus status,
                                   Instant createdAt, Instant resolvedAt, String resolutionNote) {}

    public record ConflictSummary(long open, List<ConflictResponse> conflicts) {}

    public record ResolveConflictRequest(@Size(max = 300) String note) {}
}
