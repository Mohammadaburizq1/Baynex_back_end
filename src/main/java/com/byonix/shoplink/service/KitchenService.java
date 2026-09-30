package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.KitchenDtos;
import com.byonix.shoplink.common.ConflictException;
import com.byonix.shoplink.domain.entity.CustomerOrder;
import com.byonix.shoplink.domain.entity.OrderItem;
import com.byonix.shoplink.domain.entity.OrderItemModifier;
import com.byonix.shoplink.domain.entity.PosDevice;
import com.byonix.shoplink.domain.entity.PosSyncOperation;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.domain.enums.PosSyncOperationStatus;
import com.byonix.shoplink.repository.PosDeviceRepository;
import com.byonix.shoplink.repository.PosSyncOperationRepository;
import com.byonix.shoplink.security.pos.PosDevicePrincipal;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * POS-27: the kitchen display, fed by the POS-26 restaurant item flow.
 *
 * <h2>Tickets come from sends, not from a second order</h2>
 * When an ADD_ITEMS operation puts new lines on a restaurant order, {@link #fire} groups those lines by
 * the station they route to (product routes, else the category's, else the general ticket) and records
 * one ticket per station for that send. A later send makes new tickets; nothing sent before is fired
 * again. A void, a note change or a merge on the order updates the kitchen's view of the same lines.
 *
 * <h2>Status from the kitchen screens</h2>
 * NEW → ACCEPTED → PREPARING → READY → SERVED (bumped). A screen may skip steps; a change that would move a
 * ticket back is ignored (another screen was already ahead) and recorded as STALE, so two kitchen screens
 * converge. RECALL brings a ticket served in the last {@link #RECALL_WINDOW} back to READY. Every change is
 * an operation with its own id (pos_sync_operations): a retry never applies twice. Tickets are never
 * deleted; served ones are history.
 */
@Service
@RequiredArgsConstructor
public class KitchenService {
    static final List<String> FLOW = List.of("NEW", "ACCEPTED", "PREPARING", "READY", "SERVED");
    static final Duration RECALL_WINDOW = Duration.ofHours(2);
    private static final Duration HISTORY = Duration.ofHours(12);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final UUID NO_STATION = new UUID(0, 0);

    private final JdbcTemplate jdbc;
    private final KitchenAllocationService allocations;
    private final PosStaffService staffService;
    private final StoreService storeService;
    private final CurrentUserService currentUser;
    private final PosDeviceRepository deviceRepository;
    private final PosSyncOperationRepository operationRepository;
    private final PlatformTransactionManager transactionManager;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /** Who changed the kitchen's view (an operation from a till or a kitchen screen). */
    public record Actor(UUID operationId, UUID deviceId, UUID staffId, String staffName, Instant at) {}

    // ── from POS-26 (inside the restaurant operation's transaction) ──────────────────────────────

    /** The lines one send added to [order] become tickets, one per station they route to. */
    public void fire(Store store, CustomerOrder order, int sentVersion, List<OrderItem> sent, Actor actor) {
        if (sent.isEmpty()) return;
        List<UUID> productIds = sent.stream().map(i -> i.getProduct() == null ? null : i.getProduct().getId()).filter(Objects::nonNull).distinct().toList();
        Map<UUID, Set<UUID>> byProduct = routes("""
                SELECT sp.product_id, sp.station_id FROM kitchen_station_products sp
                JOIN kitchen_stations s ON s.id = sp.station_id AND s.active AND s.store_id = ?
                WHERE sp.product_id = ANY(?)
                """, store.getId(), productIds);
        Map<UUID, Set<UUID>> byCategory = routes("""
                SELECT p.id, kc.station_id FROM products p
                JOIN kitchen_station_categories kc ON kc.category_id = p.category_id
                JOIN kitchen_stations s ON s.id = kc.station_id AND s.active AND s.store_id = ?
                WHERE p.id = ANY(?)
                """, store.getId(), productIds);
        Map<UUID, List<OrderItem>> perStation = new LinkedHashMap<>();
        for (OrderItem item : sent) {
            UUID pid = item.getProduct() == null ? null : item.getProduct().getId();
            Set<UUID> stations = pid != null && byProduct.containsKey(pid) ? byProduct.get(pid)
                    : pid != null && byCategory.containsKey(pid) ? byCategory.get(pid) : Set.of(NO_STATION);
            for (UUID s : stations) perStation.computeIfAbsent(s, k -> new ArrayList<>()).add(item);
        }
        Map<UUID, String> names = new HashMap<>();
        jdbc.query("SELECT id, name FROM kitchen_stations WHERE store_id = ?", rs -> {
            names.put(rs.getObject(1, UUID.class), rs.getString(2));
        }, store.getId());
        for (Map.Entry<UUID, List<OrderItem>> e : perStation.entrySet()) {
            UUID station = NO_STATION.equals(e.getKey()) ? null : e.getKey();
            List<UUID> ids = jdbc.queryForList("""
                    INSERT INTO kitchen_tickets (store_id, order_id, origin_order_id, sent_version, station_id, station_name, status, sent_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'NEW', ?)
                    ON CONFLICT DO NOTHING RETURNING id
                    """, UUID.class, store.getId(), order.getId(), order.getId(), sentVersion, station, station == null ? null : names.get(station),
                    Timestamp.from(actor.at()));
            if (ids.isEmpty()) continue; // this send was already fired (cannot normally happen: sends are idempotent)
            UUID ticket = ids.get(0);
            for (OrderItem i : e.getValue()) {
                UUID work = jdbc.queryForObject("""
                        INSERT INTO kitchen_ticket_items (ticket_id, order_item_id, line_uid, product_name, variant_label, modifiers, quantity, note, course, line_no)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                        """, UUID.class, ticket, i.getId(), i.getPosLineUid(), i.getProductNameSnapshot(), i.getVariantLabel(),
                        objectMapper.writeValueAsString(i.getModifiers().stream().map(OrderItemModifier::getOptionName).toList()),
                        i.getQuantity(), i.getItemNote(), i.getCourse(), i.getPosLineNo());
                allocations.sent(work, i.getId(), i.getQuantity(), i.getItemNote(), i.getCourse(), actor);
            }
            event(store.getId(), ticket, "CREATED", null, "NEW", actor, e.getValue().size() + " line(s)");
        }
    }

    /** A sent line was voided at the till: the kitchen sees VOID and why; its history stays. */
    public void voided(UUID storeId, UUID orderItemId, int quantity, String reason, UUID manager, Actor actor) {
        allocations.voided(storeId, orderItemId, quantity, reason, manager, actor);
    }

    /** A sent line's note or course changed at the till. */
    public void lineUpdated(UUID storeId, UUID orderItemId, String note, String course, Actor actor) {
        allocations.instructions(storeId, orderItemId, note, course, actor);
    }

    public void moved(UUID storeId, UUID from, UUID to, int quantity, Actor actor) {
        allocations.move(storeId, from, to, quantity, actor);
    }

    /** Tables were merged: the tickets follow their lines to the order that absorbed them. */
    public void merged(UUID sourceOrderId, UUID targetOrderId) {
        jdbc.update("UPDATE kitchen_tickets SET order_id = ?, version = version + 1, updated_at = now() WHERE order_id = ?", targetOrderId, sourceOrderId);
    }

    // ── from the kitchen screens ─────────────────────────────────────────────────────────────────

    public KitchenDtos.KitchenOpResponse apply(PosDevicePrincipal principal, KitchenDtos.KitchenOp r) {
        try {
            return new TransactionTemplate(transactionManager).execute(s -> applyOnce(principal, r));
        } catch (DataIntegrityViolationException raced) {
            return new TransactionTemplate(transactionManager).execute(s -> {
                PosDevice uploader = uploader(principal);
                PosSyncOperation op = operationRepository.findById(r.operationId()).orElseThrow(() -> new PosSyncRejectedException("KITCHEN_CONFLICT",
                        "Another change to this ticket was applied at the same time. Sync again.", HttpStatus.CONFLICT));
                return replay(op, uploader.getStore().getId(), r);
            });
        }
    }

    private KitchenDtos.KitchenOpResponse applyOnce(PosDevicePrincipal principal, KitchenDtos.KitchenOp r) {
        PosDevice uploader = uploader(principal);
        UUID storeId = uploader.getStore().getId();
        PosDevice origin = r.originDeviceId().equals(uploader.getId()) ? uploader
                : deviceRepository.findById(r.originDeviceId()).filter(d -> d.getStore().getId().equals(storeId))
                        .orElseThrow(() -> new PosSyncRejectedException("FOREIGN_DEVICE", "This change was made on a device that does not belong to this store", HttpStatus.FORBIDDEN));
        var existing = operationRepository.findById(r.operationId());
        if (existing.isPresent()) return replay(existing.get(), storeId, r);
        if (staffService.currentMember(uploader.getStore(), r.staff().userId())
                .filter(m -> m.posLevel() != PermissionLevel.NONE).isEmpty()) {
            throw new PosSyncRejectedException("KITCHEN_STAFF_UNAVAILABLE", "This staff member cannot operate this store's kitchen", HttpStatus.FORBIDDEN);
        }
        if ("STATUS".equals(r.type()) && r.toStatus() == null) throw new PosSyncRejectedException("KITCHEN_INVALID", "A status change needs the new status");

        Instant now = Instant.now();
        PosSyncOperation op = new PosSyncOperation();
        op.setOperationId(r.operationId());
        op.setStoreId(storeId);
        op.setDeviceId(origin.getId());
        op.setSubmittedByDeviceId(uploader.getId());
        op.setOperationType("KITCHEN_" + r.type());
        op.setEntityId(r.operationId());
        op.setRequestHash(hash(r));
        op.setCatalogVersion("-");
        op.setStatus(PosSyncOperationStatus.APPLYING);
        op.setCreatedAt(now);
        operationRepository.saveAndFlush(op);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status, served_at FROM kitchen_tickets WHERE id = ? AND store_id = ? FOR UPDATE",
                r.ticketId(), storeId);
        if (rows.isEmpty()) throw new PosSyncRejectedException("KITCHEN_TICKET_UNKNOWN", "This kitchen ticket is not one of this store's");
        String current = (String) rows.get(0).get("status");
        Instant at = r.occurredAt().isAfter(now) ? now : r.occurredAt();
        Actor actor = new Actor(op.getOperationId(), origin.getId(), existingUser(r.staff().userId()), truncate(r.staff().name(), 160), at);
        boolean applied;
        String note = null;
        if ("RECALL".equals(r.type())) {
            Timestamp served = (Timestamp) rows.get(0).get("served_at");
            if (!"SERVED".equals(current)) {
                applied = false;
                note = "The ticket is not bumped; nothing to recall";
            } else if (served != null && served.toInstant().isBefore(now.minus(RECALL_WINDOW))) {
                throw new PosSyncRejectedException("KITCHEN_RECALL_TOO_LATE", "Only tickets bumped in the last " + RECALL_WINDOW.toHours() + " hours can be recalled");
            } else {
                jdbc.update("UPDATE kitchen_tickets SET status = 'READY', served_at = NULL, version = version + 1, updated_at = now() WHERE id = ?", r.ticketId());
                allocations.status(r.ticketId(), "READY", true, actor);
                event(storeId, r.ticketId(), "RECALL", current, "READY", actor, null);
                applied = true;
            }
        } else {
            int from = FLOW.indexOf(current), to = FLOW.indexOf(r.toStatus());
            if (to > from) {
                jdbc.update("""
                        UPDATE kitchen_tickets SET status = ?, version = version + 1, updated_at = now(),
                          accepted_at = CASE WHEN accepted_at IS NULL AND ? >= 1 THEN ? ELSE accepted_at END,
                          ready_at = CASE WHEN ? >= 3 THEN COALESCE(ready_at, ?) ELSE ready_at END,
                          served_at = CASE WHEN ? = 4 THEN ? ELSE served_at END
                        WHERE id = ?
                        """, r.toStatus(), to, Timestamp.from(at), to, Timestamp.from(at), to, Timestamp.from(at), r.ticketId());
                allocations.status(r.ticketId(), r.toStatus(), false, actor);
                event(storeId, r.ticketId(), "STATUS", current, r.toStatus(), actor, null);
                applied = true;
            } else {
                // Equal: another screen already did it. Behind: another screen was ahead. Either way the
                // screens converge on the furthest state; a move back is recorded, never applied.
                applied = false;
                note = to == from ? "Already " + current : "Already " + current + " on another screen";
                if (to < from) event(storeId, r.ticketId(), "STALE", current, r.toStatus(), actor, note);
            }
        }
        op.setStatus(PosSyncOperationStatus.SYNCED);
        op.setCompletedAt(Instant.now());
        operationRepository.saveAndFlush(op);
        return new KitchenDtos.KitchenOpResponse(r.operationId(), false, applied, note, ticket(storeId, r.ticketId()));
    }

    private KitchenDtos.KitchenOpResponse replay(PosSyncOperation op, UUID storeId, KitchenDtos.KitchenOp r) {
        if (!op.getStoreId().equals(storeId) || !op.getRequestHash().equals(hash(r))) {
            throw new PosSyncRejectedException("OPERATION_ID_REUSED", "This operation id was already used for a different change", HttpStatus.CONFLICT);
        }
        return new KitchenDtos.KitchenOpResponse(op.getOperationId(), true, false, "Already applied", ticket(storeId, r.ticketId()));
    }

    /** Open tickets of the device's store, and every ticket changed since [since] (history of the last 12 h on a first pull). */
    @Transactional(readOnly = true)
    public KitchenDtos.TicketsResponse tickets(PosDevicePrincipal principal, Instant since) {
        UUID storeId = uploader(principal).getStore().getId();
        Instant now = Instant.now();
        Instant from = since == null ? now.minus(HISTORY) : since.minus(MAX_CLOCK_SKEW);
        return new KitchenDtos.TicketsResponse(now, query("t.store_id = ? AND (t.status <> 'SERVED' OR t.updated_at > ?)", storeId, Timestamp.from(from)));
    }

    private KitchenDtos.Ticket ticket(UUID storeId, UUID ticketId) {
        return query("t.store_id = ? AND t.id = ?", storeId, ticketId).stream().findFirst()
                .orElseThrow(() -> new PosSyncRejectedException("KITCHEN_TICKET_UNKNOWN", "This kitchen ticket is not one of this store's"));
    }

    private List<KitchenDtos.Ticket> query(String where, Object... args) {
        List<KitchenDtos.Ticket> heads = jdbc.query("""
                SELECT t.id, t.station_id, t.station_name, t.status, t.sent_at, t.accepted_at, t.ready_at, t.served_at, t.version,
                       o.pos_local_order_id, o.order_code, o.pos_ticket_number, o.pos_order_type, o.restaurant_table_id, rt.name AS table_name,
                       o.waiter_name, o.guest_count, o.status AS order_status, o.pos_merged_into_order_id
                FROM kitchen_tickets t
                JOIN customer_orders o ON o.id = t.order_id
                LEFT JOIN restaurant_tables rt ON rt.id = o.restaurant_table_id
                WHERE %s ORDER BY t.sent_at, t.id
                """.formatted(where), (rs, n) -> new KitchenDtos.Ticket(rs.getObject("id", UUID.class), rs.getObject("pos_local_order_id", UUID.class),
                rs.getString("order_code"), rs.getString("pos_ticket_number"), rs.getString("pos_order_type"), rs.getObject("restaurant_table_id", UUID.class),
                rs.getString("table_name"), rs.getString("waiter_name"), (Integer) rs.getObject("guest_count"), rs.getObject("station_id", UUID.class),
                rs.getString("station_name"), rs.getString("status"),
                PosRestaurantService.stateName(rs.getString("order_status"), rs.getObject("pos_merged_into_order_id") != null),
                ts(rs.getTimestamp("sent_at")), ts(rs.getTimestamp("accepted_at")), ts(rs.getTimestamp("ready_at")), ts(rs.getTimestamp("served_at")),
                rs.getInt("version"), new ArrayList<>()), args);
        if (heads.isEmpty()) return heads;
        Map<UUID, KitchenDtos.Ticket> byId = new LinkedHashMap<>();
        heads.forEach(h -> byId.put(h.id(), h));
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT w.ticket_id, i.pos_line_uid AS line_uid, w.product_name, w.variant_label, w.modifiers,
                           a.quantity, CASE WHEN a.voided THEN a.quantity ELSE 0 END AS voided_quantity, a.void_reason,
                           a.note, a.course, w.line_no, a.id AS allocation_id, w.id AS work_id, a.status AS allocation_status,
                           o.pos_local_order_id AS current_order_id, rt.name AS current_table_name
                    FROM kitchen_item_allocations a JOIN kitchen_ticket_items w ON w.id=a.work_id
                    JOIN order_items i ON i.id=a.current_order_item_id JOIN customer_orders o ON o.id=i.order_id
                    LEFT JOIN restaurant_tables rt ON rt.id=o.restaurant_table_id
                    WHERE w.ticket_id = ANY(?) AND NOT a.superseded ORDER BY w.line_no NULLS LAST, a.created_at, a.id
                    """);
            ps.setArray(1, uuidArray(con, byId.keySet()));
            return ps;
        }, rs -> {
            List<String> mods = rs.getString("modifiers") == null ? List.of()
                    : List.of(objectMapper.readValue(rs.getString("modifiers"), String[].class));
            byId.get(rs.getObject("ticket_id", UUID.class)).items().add(new KitchenDtos.TicketItem(rs.getObject("line_uid", UUID.class),
                    rs.getString("product_name"), rs.getString("variant_label"), mods, rs.getInt("quantity"), rs.getInt("voided_quantity"),
                    rs.getString("void_reason"), rs.getString("note"), rs.getString("course"), (Integer) rs.getObject("line_no"),
                    rs.getObject("allocation_id", UUID.class), rs.getObject("work_id", UUID.class), rs.getString("allocation_status"),
                    rs.getObject("current_order_id", UUID.class), rs.getString("current_table_name")));
        });
        return heads;
    }

    // ── dashboard: stations and routing ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<KitchenDtos.Station> listStations(UUID storeId) {
        readable(storeId);
        return stations(storeId);
    }

    /** Stations of a store with their routes (also sent to the tills with the restaurant setup). */
    public List<KitchenDtos.Station> stations(UUID storeId) {
        Map<UUID, List<UUID>> cats = new HashMap<>(), prods = new HashMap<>();
        jdbc.query("SELECT kc.station_id, kc.category_id FROM kitchen_station_categories kc JOIN kitchen_stations s ON s.id = kc.station_id WHERE s.store_id = ?",
                rs -> { cats.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class)); }, storeId);
        jdbc.query("SELECT kp.station_id, kp.product_id FROM kitchen_station_products kp JOIN kitchen_stations s ON s.id = kp.station_id WHERE s.store_id = ?",
                rs -> { prods.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class)); }, storeId);
        return jdbc.query("SELECT id, name, sort_order, active FROM kitchen_stations WHERE store_id = ? ORDER BY sort_order, name",
                (rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new KitchenDtos.Station(id, rs.getString("name"), rs.getInt("sort_order"), rs.getBoolean("active"),
                            cats.getOrDefault(id, List.of()), prods.getOrDefault(id, List.of()));
                }, storeId);
    }

    @Transactional
    public KitchenDtos.Station createStation(UUID storeId, KitchenDtos.StationRequest r) {
        writable(storeId);
        uniqueName(storeId, r.name(), null);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM kitchen_stations WHERE store_id = ?", Integer.class, storeId);
        UUID id = jdbc.queryForObject("INSERT INTO kitchen_stations (store_id, name, sort_order, active) VALUES (?, ?, ?, ?) RETURNING id", UUID.class,
                storeId, r.name().trim(), r.sortOrder() == null ? count : r.sortOrder(), r.active() == null || r.active());
        return station(storeId, id);
    }

    @Transactional
    public KitchenDtos.Station updateStation(UUID id, KitchenDtos.StationRequest r) {
        UUID storeId = stationStore(id);
        writable(storeId);
        boolean active = r.active() == null ? Boolean.TRUE.equals(jdbc.queryForObject("SELECT active FROM kitchen_stations WHERE id = ?", Boolean.class, id)) : r.active();
        if (active) uniqueName(storeId, r.name(), id);
        jdbc.update("UPDATE kitchen_stations SET name = ?, sort_order = COALESCE(?, sort_order), active = ?, updated_at = now() WHERE id = ?",
                r.name().trim(), r.sortOrder(), active, id);
        return station(storeId, id);
    }

    /** Replaces the station's routes; every category and product must be this store's. */
    @Transactional
    public KitchenDtos.Station updateRoutes(UUID id, KitchenDtos.RoutesRequest r) {
        UUID storeId = stationStore(id);
        writable(storeId);
        Set<UUID> categories = new LinkedHashSet<>(r.categoryIds()), products = new LinkedHashSet<>(r.productIds());
        for (UUID c : categories) {
            if (count("SELECT count(*) FROM categories WHERE id = ? AND store_id = ?", c, storeId) == 0) {
                throw new IllegalArgumentException("A category is not one of this store's");
            }
        }
        for (UUID p : products) {
            if (count("SELECT count(*) FROM products WHERE id = ? AND store_id = ?", p, storeId) == 0) {
                throw new IllegalArgumentException("A product is not one of this store's");
            }
        }
        jdbc.update("DELETE FROM kitchen_station_categories WHERE station_id = ?", id);
        jdbc.update("DELETE FROM kitchen_station_products WHERE station_id = ?", id);
        for (UUID c : categories) jdbc.update("INSERT INTO kitchen_station_categories (station_id, category_id) VALUES (?, ?)", id, c);
        for (UUID p : products) jdbc.update("INSERT INTO kitchen_station_products (station_id, product_id) VALUES (?, ?)", id, p);
        jdbc.update("UPDATE kitchen_stations SET updated_at = now() WHERE id = ?", id);
        return station(storeId, id);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private KitchenDtos.Station station(UUID storeId, UUID id) {
        return stations(storeId).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private UUID stationStore(UUID id) {
        List<UUID> s = jdbc.queryForList("SELECT store_id FROM kitchen_stations WHERE id = ?", UUID.class, id);
        if (s.isEmpty()) throw new EntityNotFoundException("Station not found");
        return s.get(0);
    }

    private void uniqueName(UUID storeId, String name, UUID except) {
        long taken = except == null
                ? count2("SELECT count(*) FROM kitchen_stations WHERE store_id = ? AND active AND lower(name) = lower(?)", storeId, name.trim())
                : count3("SELECT count(*) FROM kitchen_stations WHERE store_id = ? AND active AND lower(name) = lower(?) AND id <> ?", storeId, name.trim(), except);
        if (taken > 0) throw new ConflictException("Another active station is already called \"" + name.trim() + "\"");
    }

    private Store readable(UUID storeId) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.ORDERS, PermissionLevel.VIEW);
        return store;
    }

    private Store writable(UUID storeId) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.POS, PermissionLevel.EDIT);
        return store;
    }

    private Map<UUID, Set<UUID>> routes(String sql, UUID storeId, List<UUID> productIds) {
        Map<UUID, Set<UUID>> result = new HashMap<>();
        if (productIds.isEmpty()) return result;
        jdbc.query(con -> {
            var ps = con.prepareStatement(sql);
            ps.setObject(1, storeId);
            ps.setArray(2, uuidArray(con, productIds));
            return ps;
        }, rs -> {
            result.computeIfAbsent(rs.getObject(1, UUID.class), k -> new LinkedHashSet<>()).add(rs.getObject(2, UUID.class));
        });
        return result;
    }

    private void touch(UUID ticket) {
        jdbc.update("UPDATE kitchen_tickets SET version = version + 1, updated_at = now() WHERE id = ?", ticket);
    }

    private void event(UUID storeId, UUID ticket, String type, String from, String to, Actor a, String detail) {
        jdbc.update("""
                INSERT INTO kitchen_ticket_events (store_id, ticket_id, operation_id, device_id, event_type, from_status, to_status, staff_id, staff_name, detail, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, storeId, ticket, a.operationId(), a.deviceId(), type, from, to, a.staffId(), truncate(a.staffName(), 160), detail, Timestamp.from(a.at()));
    }

    private String hash(KitchenDtos.KitchenOp r) {
        tools.jackson.databind.node.ObjectNode node = objectMapper.valueToTree(r);
        node.remove("operationId");
        return PosPriceBookService.sha256(objectMapper.writeValueAsString(node));
    }

    private PosDevice uploader(PosDevicePrincipal principal) {
        PosDevice device = deviceRepository.findById(principal.deviceId()).orElseThrow();
        if (!device.getStore().getId().equals(principal.storeId())) throw new AccessDeniedException("Access denied");
        return device;
    }

    private UUID existingUser(UUID id) {
        return id != null && count("SELECT count(*) FROM app_users WHERE id = ?", id, null) > 0 ? id : null;
    }

    private long count(String sql, UUID a, UUID b) {
        Long n = b == null ? jdbc.queryForObject(sql, Long.class, a) : jdbc.queryForObject(sql, Long.class, a, b);
        return n == null ? 0 : n;
    }

    private long count2(String sql, Object a, Object b) {
        Long n = jdbc.queryForObject(sql, Long.class, a, b);
        return n == null ? 0 : n;
    }

    private long count3(String sql, Object a, Object b, Object c) {
        Long n = jdbc.queryForObject(sql, Long.class, a, b, c);
        return n == null ? 0 : n;
    }

    private static Array uuidArray(Connection con, java.util.Collection<UUID> ids) throws SQLException {
        return con.createArrayOf("uuid", ids.toArray());
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
