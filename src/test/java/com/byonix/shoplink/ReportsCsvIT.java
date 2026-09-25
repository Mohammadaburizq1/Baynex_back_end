package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.support.ApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1-11: the Reports page's orders CSV (GET /api/dashboard/analytics/orders.csv) — real stored
 * order snapshots only, same store/permission scoping and date filter as the page's analytics.
 * Uses the same entity-generated H2 schema as CustomerOrderHistoryTest (V33 can't run on H2);
 * it does not validate the production Flyway migrations.
 */


class ReportsCsvIT extends ApiIT {
    private static final String HEADER = "order_code,created_at_utc,status,fulfillment_method,customer_name,"
            + "subtotal,discount,delivery_fee,total,currency";

    private String ownerA;
    private String ownerB;
    private String customer;
    private String slugA;
    private String storeA;
    private String storeB;
    private String mug;
    private String range;

    @BeforeEach
    void setUp() throws Exception {
        ownerA = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-a"));
        ownerB = tokenFor(saveUser(Role.MERCHANT_OWNER, "owner-b"));
        customer = tokenFor(saveUser(Role.CUSTOMER, "customer"));
        slugA = uniqueSlug("rep-a");
        storeA = createStore(ownerA, slugA);
        storeB = createStore(ownerB, uniqueSlug("rep-b"));
        mug = idOf(send(POST, "/api/dashboard/products", ownerA, productBody(storeA, "mug", null)).andExpect(status().isOk()));
        activateStore(ownerA, storeA, slugA);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        range = "from=" + today.minusDays(6) + "&to=" + today;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private String placeOrder(String customerName, int quantity) throws Exception {
        String name = customerName.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        String body = "{\"customerName\":\"" + name + "\",\"customerPhone\":\"+962790000000\",\"deliveryMethod\":\"PICKUP\","
                + "\"paymentMethod\":\"CASH\",\"items\":[{\"productId\":\"" + mug + "\",\"quantity\":" + quantity + "}]}";
        return idOf(send(POST, "/api/public/stores/" + slugA + "/orders", customer, body).andExpect(status().isOk()));
    }

    private String orderCode(String orderId) throws Exception {
        return read(send(GET, "/api/dashboard/orders/" + orderId, ownerA, null), "$.data.orderCode");
    }

    private MvcResult export(String token, String storeId, String query) throws Exception {
        return send(GET, "/api/dashboard/analytics/orders.csv?storeId=" + storeId + "&" + query, token, null).andReturn();
    }

    private static String csv(MvcResult result) throws Exception {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** Records of the file, BOM and trailing CRLF stripped (a quoted field may contain a bare \n). */
    private static List<String> records(String csv) {
        assertTrue(csv.startsWith("﻿"), "UTF-8 BOM expected");
        assertTrue(csv.endsWith("\r\n"), "CRLF-terminated records expected");
        return Arrays.asList(csv.substring(1, csv.length() - 2).split("\r\n", -1));
    }

    private static String rowFor(List<String> records, String orderCode) {
        return records.stream().filter(r -> r.startsWith("\"" + orderCode + "\",")).findFirst()
                .orElseThrow(() -> new AssertionError("No CSV row for " + orderCode + " in " + records));
    }

    private void nativeUpdate(String sql, Object... params) {
        var query = entityManager.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) query.setParameter(i + 1, params[i]);
        query.executeUpdate();
    }

    // ── export content ───────────────────────────────────────────────────────────────────────

    @Test
    void ownerExportsRealOrdersAsAnAttachmentWithStoredSnapshotValues() throws Exception {
        String orderId = placeOrder("Plain Buyer", 3);
        String code = orderCode(orderId);

        MvcResult result = export(ownerA, storeA, range);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("text/csv;charset=UTF-8", result.getResponse().getContentType().replace(" ", ""));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertEquals("attachment; filename=\"khangates-orders-" + today.minusDays(6) + "-to-" + today + ".csv\"",
                result.getResponse().getHeader("Content-Disposition"));
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));

        List<String> records = records(csv(result));
        assertEquals(HEADER, records.get(0));
        assertEquals(2, records.size());
        String row = rowFor(records, code);
        // Stored snapshot: 3 × 10.00, no discount/fee, the store's currency at order time (JOD
        // default), amounts as stored (plain decimals at the column's scale, never 3E+1).
        assertTrue(row.matches("\"" + code + "\",\"\\d{4}-\\d{2}-\\d{2}T[^\"]+Z\",\"NEW\",\"PICKUP\",\"Plain Buyer\","
                + "\"30\\.0+\",\"0(\\.0+)?\",\"0(\\.0+)?\",\"30\\.0+\",\"JOD\""), row);
    }

    @Test
    void commasQuotesNewlinesAndNonLatinTextAreEscaped() throws Exception {
        String code = orderCode(placeOrder("Smith, \"JJ\"\nSecond line — محمد", 1));

        String row = rowFor(records(csv(export(ownerA, storeA, range))), code);
        assertTrue(row.contains(",\"Smith, \"\"JJ\"\"\nSecond line — محمد\","), row);
    }

    @Test
    void formulaLikeCustomerTextIsNeutralized() throws Exception {
        String eq = orderCode(placeOrder("=HYPERLINK(\"http://evil.test\",\"x\")", 1));
        String plus = orderCode(placeOrder("+1+1", 1));
        String minus = orderCode(placeOrder("-2+3", 1));
        String at = orderCode(placeOrder("@SUM(A1)", 1));
        String hidden = orderCode(placeOrder("  =1+1", 1));
        String safe = orderCode(placeOrder("Ahmad-Ali @home", 1));

        List<String> records = records(csv(export(ownerA, storeA, range)));
        assertTrue(rowFor(records, eq).contains(",\"'=HYPERLINK(\"\"http://evil.test\"\",\"\"x\"\")\","));
        assertTrue(rowFor(records, plus).contains(",\"'+1+1\","));
        assertTrue(rowFor(records, minus).contains(",\"'-2+3\","));
        assertTrue(rowFor(records, at).contains(",\"'@SUM(A1)\","));
        assertTrue(rowFor(records, hidden).contains(",\"'  =1+1\","));
        // Only a leading marker is dangerous; ordinary text is left untouched.
        assertTrue(rowFor(records, safe).contains(",\"Ahmad-Ali @home\","));
    }

    // ── historical/currency safety ───────────────────────────────────────────────────────────

    @Test
    void exportUsesOrderSnapshotsNotCurrentPricesOrStoreCurrency() throws Exception {
        String newCode = orderCode(placeOrder("Snapshot Buyer", 2));
        String legacyId = placeOrder("Legacy Buyer", 1);
        String legacyCode = orderCode(legacyId);
        // A pre-M1-02 order: currency was never recorded.
        nativeUpdate("UPDATE customer_orders SET currency = NULL WHERE id = ?", UUID.fromString(legacyId));

        // Later changes to the catalog and store settings must not rewrite history.
        var product = productRepository.findById(UUID.fromString(mug)).orElseThrow();
        product.setPrice(new BigDecimal("99.00"));
        productRepository.save(product);
        Store store = storeRepository.findById(UUID.fromString(storeA)).orElseThrow();
        store.setCurrency("USD");
        storeRepository.save(store);

        List<String> records = records(csv(export(ownerA, storeA, range)));
        String newRow = rowFor(records, newCode);
        assertTrue(newRow.matches(".*,\"20\\.0+\",\"JOD\""), newRow);
        String legacyRow = rowFor(records, legacyCode);
        assertTrue(legacyRow.matches(".*,\"10\\.0+\",\"\""), "Legacy currency must stay blank, not USD: " + legacyRow);
        assertFalse(String.join("\n", records).contains("USD"));
        assertFalse(String.join("\n", records).contains("99.0"));
        assertFalse(String.join("\n", records).contains("198.0"));
    }

    // ── filter consistency with the page ─────────────────────────────────────────────────────

    @Test
    void exportFollowsTheSameDateRangeAndExclusionsAsTheReportTotals() throws Exception {
        String inRange = placeOrder("In Range", 1);
        String cancelled = placeOrder("Cancelled", 4);
        send(PUT, "/api/dashboard/orders/" + cancelled + "/status", ownerA, "{\"status\":\"CANCELLED\"}")
                .andExpect(status().isOk());

        List<String> records = records(csv(export(ownerA, storeA, range)));
        assertEquals(2, records.size(), "Only the non-cancelled order: " + records);
        assertTrue(rowFor(records, orderCode(inRange)).matches(".*,\"10\\.0+\",\"JOD\""));

        // The page's revenue/order totals come from daily_store_sales: the CSV rows reconcile with them.
        Object[] totals = (Object[]) entityManager.createNativeQuery(
                        "SELECT COALESCE(SUM(total_revenue), 0), COALESCE(SUM(order_count), 0) FROM daily_store_sales "
                                + "WHERE store_id = ? AND sale_date BETWEEN ? AND ?")
                .setParameter(1, UUID.fromString(storeA))
                .setParameter(2, LocalDate.now(ZoneOffset.UTC).minusDays(6))
                .setParameter(3, LocalDate.now(ZoneOffset.UTC))
                .getSingleResult();
        assertEquals(0, new BigDecimal(totals[0].toString()).compareTo(new BigDecimal("10.00")));
        assertEquals(1L, ((Number) totals[1]).longValue());

        // An order outside the window is left out, and appears once its UTC day is selected.
        String old = placeOrder("Old Order", 2);
        LocalDate longAgo = LocalDate.now(ZoneOffset.UTC).minusDays(40);
        nativeUpdate("UPDATE customer_orders SET created_at = ? WHERE id = ?",
                longAgo.atTime(23, 59).toInstant(ZoneOffset.UTC), UUID.fromString(old));
        List<String> current = records(csv(export(ownerA, storeA, range)));
        assertEquals(2, current.size(), "Old order must not be in the last-7-days export: " + current);
        List<String> oldWindow = records(csv(export(ownerA, storeA, "from=" + longAgo + "&to=" + longAgo)));
        assertEquals(2, oldWindow.size());
        rowFor(oldWindow, orderCode(old));
        // ...and not the next UTC day (upper bound is exclusive at midnight UTC).
        assertEquals(1, records(csv(export(ownerA, storeA,
                "from=" + longAgo.plusDays(1) + "&to=" + longAgo.plusDays(1)))).size());
    }

    // daily-store-sales/top-products are native queries whose UUID columns H2 returns as byte[]
    // (see MultiTenantIsolationIT), so with rows present they can only be exercised on Postgres.
    // Here: the owner is authorized, and an empty store gets a valid empty (not failed) result.
    @Test
    void storeWithNoOrdersReturnsEmptyReportsAndAHeaderOnlyCsv() throws Exception {
        send(GET, "/api/dashboard/analytics/daily-store-sales?storeId=" + storeB + "&" + range, ownerB, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        send(GET, "/api/dashboard/analytics/top-products?storeId=" + storeB + "&" + range, ownerB, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        MvcResult result = export(ownerB, storeB, range);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(List.of(HEADER), records(csv(result)));
    }

    @Test
    void invalidRangesAndLimitsAreRejected() throws Exception {
        send(GET, "/api/dashboard/analytics/orders.csv?storeId=" + storeA + "&from=2026-02-01&to=2026-01-01", ownerA, null)
                .andExpect(status().isBadRequest());
        send(GET, "/api/dashboard/analytics/orders.csv?storeId=" + storeA + "&from=2024-01-01&to=2026-01-01", ownerA, null)
                .andExpect(status().isBadRequest());
        send(GET, "/api/dashboard/analytics/orders.csv?storeId=" + storeA + "&from=nope&to=2026-01-01", ownerA, null)
                .andExpect(status().isBadRequest());
        send(GET, "/api/dashboard/analytics/orders.csv?from=2026-01-01&to=2026-01-02", ownerA, null)
                .andExpect(status().isBadRequest());
        send(GET, "/api/dashboard/analytics/top-products?storeId=" + storeA + "&" + range + "&limit=0", ownerA, null)
                .andExpect(status().isBadRequest());
        send(GET, "/api/dashboard/analytics/top-products?storeId=" + storeA + "&" + range + "&limit=101", ownerA, null)
                .andExpect(status().isBadRequest());
    }

    // ── authorization ────────────────────────────────────────────────────────────────────────

    @Test
    void anotherMerchantCannotExportThisStoresOrders() throws Exception {
        String code = orderCode(placeOrder("Private Buyer", 1));

        MvcResult result = export(ownerB, storeA, range);
        assertEquals(403, result.getResponse().getStatus());
        assertFalse(csv(result).contains(code));
        assertFalse(csv(result).contains("Private Buyer"));
        // Same for the report data behind the page.
        send(GET, "/api/dashboard/analytics/daily-store-sales?storeId=" + storeA + "&" + range, ownerB, null)
                .andExpect(status().isForbidden());
        send(GET, "/api/dashboard/analytics/top-products?storeId=" + storeA + "&" + range, ownerB, null)
                .andExpect(status().isForbidden());
        // B's own (empty) store still works for B.
        assertEquals(200, export(ownerB, storeB, range).getResponse().getStatus());
    }

    @Test
    void unknownStoreAnonymousAndCustomerCallersAreRejected() throws Exception {
        placeOrder("Someone", 1);
        assertEquals(404, export(ownerA, UUID.randomUUID().toString(), range).getResponse().getStatus());
        assertEquals(401, export(null, storeA, range).getResponse().getStatus());
        assertEquals(403, export(customer, storeA, range).getResponse().getStatus());
    }

    @Test
    void staffNeedReportsViewAndAreConfinedToTheirStore() throws Exception {
        String code = orderCode(placeOrder("Staff Visible", 1));
        Store storeAEntity = storeRepository.findById(UUID.fromString(storeA)).orElseThrow();
        User staff = saveUser(Role.MERCHANT_STAFF, "staff-a");
        staff.setStore(storeAEntity);
        staff = userRepository.save(staff);
        String staffToken = tokenFor(staff);

        send(PUT, "/api/dashboard/staff/" + staff.getId() + "/permissions", ownerA,
                "{\"grants\":[{\"section\":\"REPORTS\",\"level\":\"VIEW\"}]}").andExpect(status().isOk());
        MvcResult allowed = export(staffToken, storeA, range);
        assertEquals(200, allowed.getResponse().getStatus());
        rowFor(records(csv(allowed)), code);
        assertEquals(403, export(staffToken, storeB, range).getResponse().getStatus());

        send(PUT, "/api/dashboard/staff/" + staff.getId() + "/permissions", ownerA,
                "{\"grants\":[{\"section\":\"REPORTS\",\"level\":\"NONE\"}]}").andExpect(status().isOk());
        MvcResult denied = export(staffToken, storeA, range);
        assertEquals(403, denied.getResponse().getStatus());
        assertFalse(csv(denied).contains(code));
        send(GET, "/api/dashboard/analytics/top-products?storeId=" + storeA + "&" + range, staffToken, null)
                .andExpect(status().isForbidden());
    }

    @Test
    void errorsComeBackAsTheJsonEnvelopeNotAFile() throws Exception {
        send(GET, "/api/dashboard/analytics/orders.csv?storeId=" + storeA + "&from=2026-02-01&to=2026-01-01", ownerA, null)
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(jsonPath("$.message").value("'to' must be on or after 'from'"));
    }
}
