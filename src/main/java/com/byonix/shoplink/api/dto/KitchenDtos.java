package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.api.dto.PosDtos.SaleStaff;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** POS-27 kitchen display: stations and routing (dashboard), tickets and status changes (kitchen screens). */
public class KitchenDtos {
    public record Station(UUID id, String name, int sortOrder, boolean active, List<UUID> categoryIds, List<UUID> productIds) {}

    public record StationRequest(@NotBlank @Size(max = 60) String name, Integer sortOrder, Boolean active) {}

    /** Replaces the station's routes. A product's own routes override its category's. */
    public record RoutesRequest(@NotNull List<UUID> categoryIds, @NotNull List<UUID> productIds) {}

    /** What a kitchen screen shows for one ordered line (as sent; voids and later notes applied). */
    public record TicketItem(UUID lineUid, String name, String variantLabel, List<String> modifiers, int quantity, int voidedQuantity,
                             String voidReason, String note, String course, Integer lineNo, UUID allocationId, UUID workId,
                             String allocationStatus, UUID currentOrderId, String currentTableName) {}

    /** status NEW | ACCEPTED | PREPARING | READY | SERVED; orderStatus OPEN | COMPLETED | MERGED | CANCELLED. */
    public record Ticket(UUID id, UUID orderId, String orderCode, String ticketNumber, String orderType, UUID tableId, String tableName,
                         String waiterName, Integer guestCount, UUID stationId, String stationName, String status, String orderStatus,
                         Instant sentAt, Instant acceptedAt, Instant readyAt, Instant servedAt, int version, List<TicketItem> items) {}

    public record TicketsResponse(Instant serverTime, List<Ticket> tickets) {}

    /**
     * One change made on a kitchen screen (possibly offline). STATUS moves a ticket forward (a move
     * back is ignored: another screen was ahead); RECALL brings a recently served ticket back to READY.
     */
    public record KitchenOp(@NotNull UUID operationId, @NotNull UUID originDeviceId, @NotNull UUID ticketId,
                            @NotNull @Pattern(regexp = "STATUS|RECALL") String type,
                            @Pattern(regexp = "ACCEPTED|PREPARING|READY|SERVED") String toStatus,
                            @NotNull Instant occurredAt, @NotNull @Valid SaleStaff staff) {}

    /** applied = false when the change was a replay, or the ticket was already past it (stale). */
    public record KitchenOpResponse(UUID operationId, boolean replayed, boolean applied, String note, Ticket ticket) {}
}
