package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.RestaurantDtos;
import com.byonix.shoplink.common.ConflictException;
import com.byonix.shoplink.domain.entity.PosDevice;
import com.byonix.shoplink.domain.entity.RestaurantArea;
import com.byonix.shoplink.domain.entity.RestaurantTable;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.repository.DeliveryZoneRepository;
import com.byonix.shoplink.repository.PosDeviceRepository;
import com.byonix.shoplink.repository.RestaurantAreaRepository;
import com.byonix.shoplink.repository.RestaurantTableRepository;
import com.byonix.shoplink.repository.StoreRepository;
import com.byonix.shoplink.security.pos.PosDevicePrincipal;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * POS-26: restaurant setup — whether the POS runs restaurant mode, the floor areas and the tables.
 *
 * Restaurant mode follows the store's business type (restaurant / café templates and categories) unless
 * the merchant sets it explicitly. Retail and service stores keep the existing POS flow. Tables and
 * areas are store scoped; they are deactivated, never deleted once an order has used them.
 * Reading needs the ORDERS grid; changing the setup needs POS manager rights (POS EDIT).
 */
@Service
@RequiredArgsConstructor
public class RestaurantSetupService {
    /** Business-type markers the storefront also reads as "restaurant" (lib/api/storefront-api.ts). */
    private static final List<String> RESTAURANT_KEYS = List.of("restaurant", "coffee", "street-food", "burger", "dessert", "ramen",
            "mediterranean", "smoothie", "korean", "french", "food", "cafe", "pizza", "bakery");

    private final StoreService storeService;
    private final StoreRepository storeRepository;
    private final CurrentUserService currentUser;
    private final RestaurantAreaRepository areaRepository;
    private final RestaurantTableRepository tableRepository;
    private final DeliveryZoneRepository zoneRepository;
    private final PosDeviceRepository deviceRepository;
    private final JdbcTemplate jdbc;
    private final KitchenService kitchenService;

    static boolean restaurantByBusinessType(Store store) {
        for (String value : new String[]{store.getTemplateKey(), store.getCategorySlug()}) {
            if (value == null) continue;
            String v = value.toLowerCase(Locale.ROOT);
            if (RESTAURANT_KEYS.stream().anyMatch(v::contains)) return true;
        }
        return false;
    }

    static boolean restaurantMode(Store store) {
        return store.getPosRestaurantMode() != null ? store.getPosRestaurantMode() : restaurantByBusinessType(store);
    }

    // ── POS device ────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public RestaurantDtos.PosSetup posSetup(PosDevicePrincipal principal) {
        PosDevice device = deviceRepository.findById(principal.deviceId()).orElseThrow();
        if (!device.getStore().getId().equals(principal.storeId())) throw new AccessDeniedException("Access denied");
        Store store = device.getStore();
        List<RestaurantDtos.Zone> zones = zoneRepository.findByStore_IdOrderBySortOrderAsc(store.getId()).stream()
                .filter(z -> z.isActive())
                .map(z -> new RestaurantDtos.Zone(z.getId(), z.getName(), z.getDeliveryFee(), z.getMinOrder())).toList();
        return new RestaurantDtos.PosSetup(restaurantMode(store), areas(store.getId()), tables(store.getId()), zones, Instant.now(),
                kitchenService.stations(store.getId()));
    }

    // ── dashboard ─────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public RestaurantDtos.Settings settings(UUID storeId) {
        Store store = readable(storeId);
        return new RestaurantDtos.Settings(store.getPosRestaurantMode(), restaurantMode(store), restaurantByBusinessType(store));
    }

    @Transactional
    public RestaurantDtos.Settings updateSettings(UUID storeId, RestaurantDtos.UpdateSettings request) {
        Store store = writable(storeId);
        store.setPosRestaurantMode(request.restaurantMode());
        storeRepository.save(store);
        return new RestaurantDtos.Settings(store.getPosRestaurantMode(), restaurantMode(store), restaurantByBusinessType(store));
    }

    @Transactional(readOnly = true)
    public List<RestaurantDtos.Area> listAreas(UUID storeId) {
        readable(storeId);
        return areas(storeId);
    }

    @Transactional(readOnly = true)
    public List<RestaurantDtos.Table> listTables(UUID storeId) {
        readable(storeId);
        return tables(storeId);
    }

    @Transactional
    public RestaurantDtos.Area createArea(UUID storeId, RestaurantDtos.AreaRequest r) {
        writable(storeId);
        RestaurantArea a = new RestaurantArea();
        a.setStoreId(storeId);
        a.setName(r.name().trim());
        a.setSortOrder(r.sortOrder() == null ? areaRepository.findByStoreIdOrderBySortOrderAscNameAsc(storeId).size() : r.sortOrder());
        a.setActive(r.active() == null || r.active());
        return area(areaRepository.save(a));
    }

    @Transactional
    public RestaurantDtos.Area updateArea(UUID id, RestaurantDtos.AreaRequest r) {
        RestaurantArea a = areaRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Area not found"));
        writable(a.getStoreId());
        a.setName(r.name().trim());
        if (r.sortOrder() != null) a.setSortOrder(r.sortOrder());
        if (r.active() != null) a.setActive(r.active());
        return area(areaRepository.save(a));
    }

    @Transactional
    public RestaurantDtos.Table createTable(UUID storeId, RestaurantDtos.TableRequest r) {
        writable(storeId);
        ownArea(storeId, r.areaId());
        uniqueName(storeId, r.name(), null);
        RestaurantTable t = new RestaurantTable();
        t.setStoreId(storeId);
        t.setAreaId(r.areaId());
        t.setName(r.name().trim());
        t.setCapacity(r.capacity());
        t.setSortOrder(r.sortOrder() == null ? tableRepository.findByStoreIdOrderBySortOrderAscNameAsc(storeId).size() : r.sortOrder());
        t.setActive(r.active() == null || r.active());
        return table(tableRepository.save(t));
    }

    /** Edits a table. Deactivating one that has an open order is refused (it must be settled or moved first). */
    @Transactional
    public RestaurantDtos.Table updateTable(UUID id, RestaurantDtos.TableRequest r) {
        RestaurantTable t = tableRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Table not found"));
        writable(t.getStoreId());
        ownArea(t.getStoreId(), r.areaId());
        boolean active = r.active() == null ? t.isActive() : r.active();
        if (active) uniqueName(t.getStoreId(), r.name(), t.getId());
        if (t.isActive() && !active && openOrdersOn(t.getStoreId(), t.getId()) > 0) {
            throw new ConflictException("This table has an open order. Settle or move it before deactivating the table.");
        }
        t.setAreaId(r.areaId());
        t.setName(r.name().trim());
        t.setCapacity(r.capacity());
        if (r.sortOrder() != null) t.setSortOrder(r.sortOrder());
        t.setActive(active);
        return table(tableRepository.save(t));
    }

    /** Past restaurant orders of one table (orders on it now, or moved/merged from or to it). Store scoped. */
    @Transactional(readOnly = true)
    public List<RestaurantDtos.TableOrder> tableHistory(UUID tableId) {
        RestaurantTable t = tableRepository.findById(tableId).orElseThrow(() -> new EntityNotFoundException("Table not found"));
        readable(t.getStoreId());
        return jdbc.query("""
                SELECT o.id, o.order_code, o.pos_ticket_number, o.pos_order_type, o.status, o.pos_merged_into_order_id, o.guest_count,
                       o.waiter_name, o.total, o.pos_opened_at, o.pos_closed_at, o.restaurant_table_id,
                       (SELECT COALESCE(SUM(p.amount), 0) FROM pos_order_payments p WHERE p.order_id = o.id) AS paid
                FROM customer_orders o
                WHERE o.store_id = ? AND o.pos_order_type IS NOT NULL
                  AND (o.restaurant_table_id = ? OR EXISTS (SELECT 1 FROM pos_restaurant_events e WHERE e.order_id = o.id
                       AND (e.from_table_id = ? OR e.to_table_id = ?)))
                ORDER BY o.pos_opened_at DESC NULLS LAST LIMIT 200
                """, (rs, i) -> new RestaurantDtos.TableOrder(rs.getObject("id", UUID.class), rs.getString("order_code"),
                rs.getString("pos_ticket_number"), rs.getString("pos_order_type"),
                PosRestaurantService.stateName(rs.getString("status"), rs.getObject("pos_merged_into_order_id", UUID.class) != null),
                (Integer) rs.getObject("guest_count"), rs.getString("waiter_name"), rs.getBigDecimal("total"), rs.getBigDecimal("paid"),
                ts(rs.getTimestamp("pos_opened_at")), ts(rs.getTimestamp("pos_closed_at")), rs.getObject("restaurant_table_id", UUID.class)),
                t.getStoreId(), tableId, tableId, tableId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

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

    private void ownArea(UUID storeId, UUID areaId) {
        areaRepository.findById(areaId).filter(a -> a.getStoreId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("Choose an area of this store"));
    }

    private void uniqueName(UUID storeId, String name, UUID except) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        boolean taken = tableRepository.findByStoreIdOrderBySortOrderAscNameAsc(storeId).stream()
                .anyMatch(t -> t.isActive() && !t.getId().equals(except) && t.getName().toLowerCase(Locale.ROOT).equals(n));
        if (taken) throw new ConflictException("Another active table is already called \"" + name.trim() + "\"");
    }

    private long openOrdersOn(UUID storeId, UUID tableId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM customer_orders WHERE store_id = ? AND restaurant_table_id = ? "
                + "AND pos_order_type IS NOT NULL AND status = 'CONFIRMED'", Long.class, storeId, tableId);
        return n == null ? 0 : n;
    }

    private List<RestaurantDtos.Area> areas(UUID storeId) {
        return areaRepository.findByStoreIdOrderBySortOrderAscNameAsc(storeId).stream().map(RestaurantSetupService::area).toList();
    }

    private List<RestaurantDtos.Table> tables(UUID storeId) {
        return tableRepository.findByStoreIdOrderBySortOrderAscNameAsc(storeId).stream().map(RestaurantSetupService::table).toList();
    }

    private static RestaurantDtos.Area area(RestaurantArea a) {
        return new RestaurantDtos.Area(a.getId(), a.getName(), a.getSortOrder(), a.isActive());
    }

    private static RestaurantDtos.Table table(RestaurantTable t) {
        return new RestaurantDtos.Table(t.getId(), t.getAreaId(), t.getName(), t.getCapacity(), t.getSortOrder(), t.isActive());
    }

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
