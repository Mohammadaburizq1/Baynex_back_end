package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.UserRepository;
import com.byonix.shoplink.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POS-27 kitchen display over real HTTP on PostgreSQL, fed by POS-26 restaurant operations. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@WebAppConfiguration
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = com.byonix.shoplink.support.PostgresTestDatabase.class)
class KitchenIT {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired tools.jackson.databind.ObjectMapper json;

    private MockMvc mockMvc;
    private String remoteAddr;
    private User owner;
    private String ownerA, ownerB, storeA, storeB;
    private String mains, drinks, burger, steak, coke, fries;
    private String grill, fryer, bar, t1;

    @BeforeEach
    void setUp() throws Exception {
        Filter chain = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(chain).build();
        int n = COUNTER.incrementAndGet();
        remoteAddr = "10.94." + (n / 250) + "." + (n % 250 + 1);
        owner = saveUser("Olivia Owner");
        ownerA = jwtService.createAccessToken(owner);
        ownerB = jwtService.createAccessToken(saveUser("Other Owner"));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        storeA = id(send(POST, "/api/dashboard/stores", bearer(ownerA), "{\"name\":\"Kitchen Bistro\",\"slug\":\"kds-a-" + suffix
                + "\",\"categorySlug\":\"restaurants-cafes\",\"templateKey\":\"restaurant-default\",\"currency\":\"JOD\"}"));
        storeB = id(send(POST, "/api/dashboard/stores", bearer(ownerB), "{\"name\":\"Other\",\"slug\":\"kds-b-" + suffix
                + "\",\"categorySlug\":\"restaurants-cafes\",\"currency\":\"JOD\"}"));
        mains = id(send(POST, "/api/dashboard/categories", bearer(ownerA), "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Mains\",\"slug\":\"mains\",\"categoryType\":\"PRODUCT\",\"sortOrder\":0,\"active\":true}"));
        drinks = id(send(POST, "/api/dashboard/categories", bearer(ownerA), "{\"storeId\":\"" + storeA + "\",\"nameEn\":\"Drinks\",\"slug\":\"drinks\",\"categoryType\":\"PRODUCT\",\"sortOrder\":1,\"active\":true}"));
        burger = product("Burger", "burger", "5.000", mains);
        send(PUT, "/api/dashboard/products/" + burger + "/modifier-groups", bearer(ownerA), "{\"groups\":[{\"name\":\"Cheese\",\"minSelect\":0,"
                + "\"maxSelect\":1,\"options\":[{\"name\":\"Extra cheese\",\"priceDelta\":0.5,\"preselected\":false,\"available\":true}]}]}").andExpect(status().isOk());
        steak = product("Steak", "steak", "9.000", mains);
        fries = product("Fries", "fries", "2.000", mains);
        coke = product("Coke", "coke", "1.000", drinks);
        String area = id(send(POST, "/api/dashboard/restaurant/areas?storeId=" + storeA, bearer(ownerA), "{\"name\":\"Main Hall\"}"));
        t1 = id(send(POST, "/api/dashboard/restaurant/tables?storeId=" + storeA, bearer(ownerA), "{\"areaId\":\"" + area + "\",\"name\":\"T1\"}"));
        // Stations: mains → Grill (category), fries → Fryer (product override), drinks → Bar (category).
        grill = station("Grill");
        fryer = station("Fryer");
        bar = station("Bar");
        routes(grill, List.of(mains), List.of());
        routes(fryer, List.of(), List.of(fries));
        routes(bar, List.of(drinks), List.of());
    }

    @Test
    void stationsAreConfiguredStoreScopedAndSentToTheTills() throws Exception {
        send(POST, "/api/dashboard/kitchen/stations?storeId=" + storeA, bearer(ownerA), "{\"name\":\"grill\"}").andExpect(status().isConflict());
        // Store B can neither read nor change store A's stations, nor route to them.
        send(GET, "/api/dashboard/kitchen/stations?storeId=" + storeA, bearer(ownerB), null).andExpect(status().is4xxClientError());
        send(PUT, "/api/dashboard/kitchen/stations/" + grill, bearer(ownerB), "{\"name\":\"Mine\"}").andExpect(status().is4xxClientError());
        String otherCategory = id(send(POST, "/api/dashboard/categories", bearer(ownerB), "{\"storeId\":\"" + storeB
                + "\",\"nameEn\":\"B\",\"slug\":\"b\",\"categoryType\":\"PRODUCT\",\"sortOrder\":0,\"active\":true}"));
        send(PUT, "/api/dashboard/kitchen/stations/" + grill + "/routes", bearer(ownerA),
                "{\"categoryIds\":[\"" + otherCategory + "\"],\"productIds\":[]}").andExpect(status().is4xxClientError());
        Device a = activate(storeA, ownerA, "KDS");
        String setup = body(send(GET, "/api/pos/restaurant/setup", a.auth(), null));
        assertThat(JsonPath.<List<String>>read(setup, "$.data.kitchenStations[*].name")).containsExactly("Grill", "Fryer", "Bar");
        assertThat(JsonPath.<List<String>>read(setup, "$.data.kitchenStations[?(@.name=='Fryer')].productIds[0]")).containsExactly(fries);
    }

    @Test
    void sentItemsRouteToStationsIncrementallyAndTheKitchenMovesThemThroughToHistory() throws Exception {
        Device till = activate(storeA, ownerA, "Counter");
        Device kds = activate(storeA, ownerA, "Kitchen screen");
        String version = catalogVersion(till);
        String cheese = option(till, burger, "Extra cheese");
        String order = UUID.randomUUID().toString();
        op(till, "OPEN", order, Map.of("open", Map.of("orderType", "DINE_IN", "tableId", t1, "guestCount", 3, "receiptNumber", "POSR-K" + COUNTER.incrementAndGet(),
                "ticketNumber", "#101", "currency", "JOD")));
        // 12:00 — burger (cheese, "No onion"), fries, coke.
        String burgerLine = UUID.randomUUID().toString();
        op(till, "ADD_ITEMS", order, Map.of("catalogVersion", version, "items", List.of(
                line(burgerLine, burger, List.of(cheese), 2, "5.500", "No onion", "MAIN"),
                line(UUID.randomUUID().toString(), fries, List.of(), 1, "2.000", null, "MAIN"),
                line(UUID.randomUUID().toString(), coke, List.of(), 2, "1.000", null, "DRINK")))).andExpect(status().isOk());
        String board = body(send(GET, "/api/pos/kitchen/tickets", kds.auth(), null));
        assertThat(JsonPath.<List<String>>read(board, "$.data.tickets[*].stationName")).containsExactlyInAnyOrder("Grill", "Fryer", "Bar");
        String grillTicket = JsonPath.<List<String>>read(board, "$.data.tickets[?(@.stationName=='Grill')].id").get(0);
        assertThat(JsonPath.<List<String>>read(board, "$.data.tickets[?(@.stationName=='Grill')].items[0].name")).containsExactly("Burger");
        assertThat(JsonPath.<List<String>>read(board, "$.data.tickets[?(@.stationName=='Grill')].items[0].note")).containsExactly("No onion");
        assertThat(JsonPath.<List<String>>read(board, "$.data.tickets[?(@.stationName=='Grill')].items[0].modifiers[0]")).containsExactly("Extra cheese");
        assertThat(JsonPath.<List<Object>>read(board, "$.data.tickets[?(@.stationName=='Grill')].tableName")).containsExactly("T1");
        assertThat(JsonPath.<List<Object>>read(board, "$.data.tickets[?(@.stationName=='Grill')].ticketNumber")).containsExactly("#101");
        assertThat(JsonPath.<List<Object>>read(board, "$.data.tickets[?(@.stationName=='Grill')].guestCount")).containsExactly(3);
        // 12:10 — a steak: one new Grill ticket with the steak only; the burger is not fired again.
        op(till, "ADD_ITEMS", order, Map.of("catalogVersion", version, "items", List.of(line(UUID.randomUUID().toString(), steak, List.of(), 1, "9.000", null, "MAIN"))));
        board = body(send(GET, "/api/pos/kitchen/tickets", kds.auth(), null));
        List<List<String>> grillItems = JsonPath.read(board, "$.data.tickets[?(@.stationName=='Grill')].items[*].name");
        assertThat(JsonPath.<List<Object>>read(board, "$.data.tickets[?(@.stationName=='Grill')]")).hasSize(2);
        assertThat(JsonPath.<List<String>>read(board, "$.data.tickets[?(@.stationName=='Grill')].items[*].name")).containsExactlyInAnyOrder("Burger", "Steak");
        assertThat(grillItems).isNotNull();

        // Accept → prepare → ready → served (bumped); each step once, audited.
        for (String to : List.of("ACCEPTED", "PREPARING", "READY", "SERVED")) {
            kitchen(kds, grillTicket, "STATUS", to).andExpect(jsonPath("$.data.applied").value(true)).andExpect(jsonPath("$.data.ticket.status").value(to));
        }
        // Bumped tickets leave the board but stay in history (a later pull still lists them as changed).
        board = body(send(GET, "/api/pos/kitchen/tickets?since=" + Instant.now().plusSeconds(600), kds.auth(), null));
        assertThat(JsonPath.<List<String>>read(board, "$.data.tickets[*].id")).doesNotContain(grillTicket);
        assertThat(jdbc.queryForObject("SELECT status FROM kitchen_tickets WHERE id = ?::uuid", String.class, grillTicket)).isEqualTo("SERVED");
        // Recall a mistaken bump: READY again, audited with who and the old/new status.
        kitchen(kds, grillTicket, "RECALL", null).andExpect(jsonPath("$.data.ticket.status").value("READY"));
        Map<String, Object> recall = jdbc.queryForMap("SELECT from_status, to_status, staff_name, device_id::text AS device FROM kitchen_ticket_events "
                + "WHERE ticket_id = ?::uuid AND event_type = 'RECALL'", grillTicket);
        assertThat(recall).containsEntry("from_status", "SERVED").containsEntry("to_status", "READY").containsEntry("staff_name", "Olivia Owner")
                .containsEntry("device", kds.id());
        assertThat(jdbc.queryForList("SELECT to_status FROM kitchen_ticket_events WHERE ticket_id = ?::uuid AND event_type = 'STATUS' ORDER BY created_at",
                String.class, grillTicket)).containsExactly("ACCEPTED", "PREPARING", "READY", "SERVED");

        // The till voids one burger: the kitchen sees VOID and the reason, the history stays.
        op(till, "VOID_LINE", order, Map.of("voidLine", Map.of("lineUid", burgerLine, "quantity", 1, "reason", "CUSTOMER_CHANGED", "restock", false, "baseLineVersion", 1)));
        String after = body(send(GET, "/api/pos/kitchen/tickets", kds.auth(), null));
        List<Integer> voidQuantities = JsonPath.read(after, "$.data.tickets[?(@.id=='" + grillTicket + "')].items[*].voidedQuantity");
        List<Integer> sentQuantities = JsonPath.read(after, "$.data.tickets[?(@.id=='" + grillTicket + "')].items[*].quantity");
        assertThat(voidQuantities.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(sentQuantities.stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(after, "$.data.tickets[?(@.id=='" + grillTicket + "')].items[*].voidReason")).contains("CUSTOMER_CHANGED");
    }

    @Test
    void retriesApplyOnceAndTwoKitchenScreensConvergeOnTheFurthestState() throws Exception {
        Device till = activate(storeA, ownerA, "Counter");
        Device kds1 = activate(storeA, ownerA, "Grill screen");
        Device kds2 = activate(storeA, ownerA, "Expo screen");
        String order = UUID.randomUUID().toString();
        op(till, "OPEN", order, Map.of("open", Map.of("orderType", "TAKEAWAY", "receiptNumber", "POSR-K" + COUNTER.incrementAndGet(), "currency", "JOD")));
        op(till, "ADD_ITEMS", order, Map.of("catalogVersion", catalogVersion(till), "items", List.of(line(UUID.randomUUID().toString(), burger, List.of(), 1, "5.000", null, null))));
        String ticket = JsonPath.<List<String>>read(body(send(GET, "/api/pos/kitchen/tickets", kds1.auth(), null)), "$.data.tickets[*].id").get(0);

        Map<String, Object> accept = kitchenBody(kds1, ticket, "STATUS", "ACCEPTED");
        post(kds1, accept).andExpect(jsonPath("$.data.applied").value(true));
        post(kds1, accept).andExpect(jsonPath("$.data.replayed").value(true)).andExpect(jsonPath("$.data.ticket.status").value("ACCEPTED"));
        // Same id, different content: refused.
        Map<String, Object> reused = new LinkedHashMap<>(accept);
        reused.put("toStatus", "READY");
        post(kds1, reused).andExpect(status().isConflict());
        // Screen 1 marks it READY; screen 2 (offline, behind) later sends PREPARING: ignored, recorded as stale.
        kitchen(kds1, ticket, "STATUS", "READY");
        kitchen(kds2, ticket, "STATUS", "PREPARING").andExpect(jsonPath("$.data.applied").value(false))
                .andExpect(jsonPath("$.data.ticket.status").value("READY"));
        kitchen(kds2, ticket, "STATUS", "READY").andExpect(jsonPath("$.data.applied").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kitchen_ticket_events WHERE ticket_id = ?::uuid AND event_type = 'STALE'", Long.class, ticket)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kitchen_ticket_events WHERE ticket_id = ?::uuid AND event_type = 'STATUS'", Long.class, ticket)).isEqualTo(2);
        // Recalling a ticket that is not bumped changes nothing.
        kitchen(kds2, ticket, "RECALL", null).andExpect(jsonPath("$.data.applied").value(false));
    }

    @Test
    void anotherStoresKitchenCannotSeeOrMoveThisStoresTickets() throws Exception {
        Device till = activate(storeA, ownerA, "Counter");
        Device other = activate(storeB, ownerB, "Other KDS");
        String order = UUID.randomUUID().toString();
        op(till, "OPEN", order, Map.of("open", Map.of("orderType", "TAKEAWAY", "receiptNumber", "POSR-K" + COUNTER.incrementAndGet(), "currency", "JOD")));
        op(till, "ADD_ITEMS", order, Map.of("catalogVersion", catalogVersion(till), "items", List.of(line(UUID.randomUUID().toString(), coke, List.of(), 1, "1.000", null, null))));
        String ticket = jdbc.queryForObject("SELECT id::text FROM kitchen_tickets WHERE store_id = ?::uuid", String.class, storeA);
        assertThat(JsonPath.<List<Object>>read(body(send(GET, "/api/pos/kitchen/tickets", other.auth(), null)), "$.data.tickets")).isEmpty();
        kitchen(other, ticket, "STATUS", "READY").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("POS_SYNC_KITCHEN_TICKET_UNKNOWN"));
        assertThat(jdbc.queryForObject("SELECT status FROM kitchen_tickets WHERE id = ?::uuid", String.class, ticket)).isEqualTo("NEW");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    @Test
    void voidAfterPartialItemTransferReachesOriginalKitchenWorkWithoutRefiring() throws Exception {
        Device till = activate(storeA, ownerA, "Counter");
        String source = UUID.randomUUID().toString();
        String target = UUID.randomUUID().toString();
        String originalLine = UUID.randomUUID().toString();
        String movedLine = UUID.randomUUID().toString();
        op(till, "OPEN", source, Map.of("open", Map.of("orderType", "TAKEAWAY", "receiptNumber",
                "POSR-K" + COUNTER.incrementAndGet(), "currency", "JOD")));
        op(till, "ADD_ITEMS", source, Map.of("catalogVersion", catalogVersion(till), "items",
                List.of(line(originalLine, burger, List.of(), 2, "5.000", "No onion", "MAIN"))));
        String ticket = jdbc.queryForObject("SELECT id::text FROM kitchen_tickets WHERE store_id = ?::uuid", String.class, storeA);
        kitchen(till, ticket, "STATUS", "PREPARING").andExpect(status().isOk());
        op(till, "TRANSFER_ITEMS", source, Map.of("transfer", Map.of("targetOrderId", target,
                "openTarget", Map.of("orderType", "TAKEAWAY", "receiptNumber", "POSR-K" + COUNTER.incrementAndGet(), "currency", "JOD"),
                "lines", List.of(Map.of("lineUid", originalLine, "newLineUid", movedLine, "quantity", 1)))));
        op(till, "VOID_LINE", target, Map.of("voidLine", Map.of("lineUid", movedLine, "quantity", 1,
                "reason", "CUSTOMER_CHANGED", "restock", false, "baseLineVersion", 1)));

        assertLineage(2, 1);
        // Moving a billed unit must retain its kitchen lineage; it is not another send.
        String board = body(send(GET, "/api/pos/kitchen/tickets", till.auth(), null));
        List<Integer> voided = JsonPath.read(board, "$.data.tickets[*].items[*].voidedQuantity");
        assertThat(voided.stream().mapToInt(Integer::intValue).sum())
                .as("The moved unit's void must remain visible to the kitchen").isEqualTo(1);
        List<String> reasons = JsonPath.read(board, "$.data.tickets[*].items[*].voidReason");
        assertThat(reasons).contains("CUSTOMER_CHANGED");
        List<String> statuses = JsonPath.read(board, "$.data.tickets[*].status");
        assertThat(statuses).doesNotContain("NEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kitchen_ticket_events WHERE store_id = ?::uuid AND event_type = 'CREATED'",
                Integer.class, storeA)).isEqualTo(1);
    }

    @Test
    void repeatedPartialMovesAndFullMoveConserveFiveAndNeverMoveStockAgain() throws Exception {
        Device till = activate(storeA, ownerA, "Counter");
        jdbc.update("UPDATE products SET stock=20 WHERE id=?::uuid", burger);
        String source = opened(till);
        String line = sent(till, source, 5);
        long stockRows = ledgerCount();
        assertThat(stockRows).isEqualTo(1);
        String target = opened(till);
        String first = UUID.randomUUID().toString();
        move(till, source, target, line, first, 2);
        assertLineage(5, 0);
        move(till, source, target, line, UUID.randomUUID().toString(), 1);
        assertLineage(5, 0);
        String third = opened(till);
        move(till, target, third, first, UUID.randomUUID().toString(), 1);
        assertLineage(5, 0);
        // Full move of the original line's remaining two: it must not create new work.
        move(till, source, third, line, UUID.randomUUID().toString(), 2);
        assertLineage(5, 0);
        assertThat(workCount()).isEqualTo(1);
        assertThat(ledgerCount()).isEqualTo(stockRows);
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?::uuid", Integer.class, burger)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kitchen_item_allocations a JOIN kitchen_ticket_items w ON w.id=a.work_id "
                + "JOIN kitchen_tickets t ON t.id=w.ticket_id WHERE t.store_id=?::uuid AND a.superseded AND "
                + "a.quantity<>(SELECT sum(c.quantity) FROM kitchen_item_allocations c WHERE c.parent_id=a.id)", Long.class, storeA)).isZero();
    }

    @Test
    void lostMoveResponseAndOfflineVoidReplayUseTheSameAllocations() throws Exception {
        Device till = activate(storeA, ownerA, "Counter");
        String source = opened(till), target = opened(till), line = sent(till, source, 2);
        String moved = UUID.randomUUID().toString();
        Map<String,Object> request = restaurantBody(till, "TRANSFER_ITEMS", source, Map.of("transfer",
                Map.of("targetOrderId",target,"lines",List.of(Map.of("lineUid",line,"newLineUid",moved,"quantity",1)))));
        String serialized = json.writeValueAsString(request);
        send(POST,"/api/pos/restaurant/operations",till.auth(),serialized).andExpect(status().isOk());
        long revisions = allocationCount();
        send(POST,"/api/pos/restaurant/operations",till.auth(),serialized).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(allocationCount()).isEqualTo(revisions);
        assertLineage(2,0);
        Map<String,Object> voidRequest = restaurantBody(till,"VOID_LINE",target,Map.of("voidLine",
                Map.of("lineUid",moved,"quantity",1,"reason","CUSTOMER_CHANGED","restock",false,"baseLineVersion",1)));
        String voidJson = json.writeValueAsString(voidRequest);
        send(POST,"/api/pos/restaurant/operations",till.auth(),voidJson).andExpect(status().isOk());
        revisions = allocationCount();
        send(POST,"/api/pos/restaurant/operations",till.auth(),voidJson).andExpect(status().isOk());
        assertThat(allocationCount()).isEqualTo(revisions);
        assertLineage(2,1);
    }

    @Test
    void twoOfflineDevicesCannotAllocateThreeOfTwo() throws Exception {
        Device a = activate(storeA,ownerA,"A"), b = activate(storeA,ownerA,"B");
        String source=opened(a), target=opened(a), third=opened(b), line=sent(a,source,2);
        move(a,source,target,line,UUID.randomUUID().toString(),1);
        move(b,source,third,line,UUID.randomUUID().toString(),2)
                .andExpect(jsonPath("$.data.conflicts[0].type").value("RESTAURANT_LINE_CONFLICT"));
        assertLineage(2,0);
        assertThat(workCount()).isEqualTo(1);
    }

    @Test
    void mergeRetainsDistinctPreparationStatesAndModifierOrigins() throws Exception {
        Device till=activate(storeA,ownerA,"Counter");
        String first=opened(till), second=opened(till);
        sent(till,first,1);
        String ticket=jdbc.queryForObject("SELECT id::text FROM kitchen_tickets WHERE store_id=?::uuid",String.class,storeA);
        kitchen(till,ticket,"STATUS","PREPARING").andExpect(status().isOk());
        String cheese=option(till,burger,"Extra cheese");
        op(till,"ADD_ITEMS",second,Map.of("catalogVersion",catalogVersion(till),"items",
                List.of(line(UUID.randomUUID().toString(),burger,List.of(cheese),1,"5.500","Extra cheese","MAIN"))));
        op(till,"MERGE",first,Map.of("merge",Map.of("sourceOrderId",second)));
        assertLineage(2,0);
        assertThat(workCount()).isEqualTo(2);
        String board=body(send(GET,"/api/pos/kitchen/tickets",till.auth(),null));
        assertThat(JsonPath.<List<String>>read(board,"$.data.tickets[*].items[*].allocationStatus")).containsExactlyInAnyOrder("PREPARING","NEW");
        assertThat(JsonPath.<List<String>>read(board,"$.data.tickets[*].items[*].currentOrderId")).containsOnly(first);
        assertThat(JsonPath.<List<String>>read(board,"$.data.tickets[*].items[*].modifiers[*]")).containsExactly("Extra cheese");
    }

    @Test
    void correctionsOnlyReachMovedQuantityAndOriginalInstructionsRemainInHistory() throws Exception {
        Device till=activate(storeA,ownerA,"Counter");
        String source=opened(till), target=opened(till), line=sent(till,source,2), moved=UUID.randomUUID().toString();
        move(till,source,target,line,moved,1);
        op(till,"UPDATE_LINE",target,Map.of("lineUpdate",Map.of("lineUid",moved,"note","Allergy: onion","course","STARTER","baseLineVersion",1)));
        String board=body(send(GET,"/api/pos/kitchen/tickets",till.auth(),null));
        assertThat(JsonPath.<List<String>>read(board,"$.data.tickets[*].items[?(@.lineUid=='"+moved+"')].note")).containsExactly("Allergy: onion");
        assertThat(JsonPath.<List<String>>read(board,"$.data.tickets[*].items[?(@.lineUid=='"+moved+"')].course")).containsExactly("STARTER");
        assertThat(JsonPath.<List<String>>read(board,"$.data.tickets[*].items[?(@.lineUid=='"+line+"')].note")).containsExactly("No onion");
        assertThat(jdbc.queryForList("SELECT w.note FROM kitchen_ticket_items w JOIN kitchen_tickets t ON t.id=w.ticket_id WHERE t.store_id=?::uuid",String.class,storeA)).containsExactly("No onion");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kitchen_item_allocations a JOIN kitchen_ticket_items w ON w.id=a.work_id "
                +"JOIN kitchen_tickets t ON t.id=w.ticket_id WHERE t.store_id=?::uuid AND a.superseded AND a.note='No onion'",Long.class,storeA)).isGreaterThan(0);
        assertLineage(2,0);
    }

    @Test
    void databaseRejectsQuantityCreationAndHistoricalRewrites() throws Exception {
        Device till=activate(storeA,ownerA,"Counter");
        sent(till,opened(till),2);
        String work=jdbc.queryForObject("SELECT w.id::text FROM kitchen_ticket_items w JOIN kitchen_tickets t ON t.id=w.ticket_id WHERE t.store_id=?::uuid",String.class,storeA);
        assertThatThrownBy(() -> jdbc.update("UPDATE kitchen_item_allocations SET quantity=3 WHERE work_id=?::uuid",work))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE kitchen_ticket_items SET note='Changed history' WHERE id=?::uuid",work))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO kitchen_item_allocations(work_id,current_order_item_id,quantity,status,action,occurred_at)
                SELECT work_id,current_order_item_id,1,status,'SEND',now() FROM kitchen_item_allocations WHERE work_id=?::uuid
                """,work)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertLineage(2,0);
    }

    @Test
    void stationFanOutConservesEachStationsWorkWhenMovedAndVoided() throws Exception {
        routes(grill,List.of(mains),List.of(burger));
        routes(fryer,List.of(),List.of(fries,burger));
        Device till=activate(storeA,ownerA,"Counter");
        String source=opened(till),target=opened(till),line=sent(till,source,2),moved=UUID.randomUUID().toString();
        move(till,source,target,line,moved,1);
        op(till,"VOID_LINE",target,Map.of("voidLine",Map.of("lineUid",moved,"quantity",1,"reason","KITCHEN_ERROR","restock",false)));
        List<Map<String,Object>> sums=jdbc.queryForList("SELECT a.work_id,sum(a.quantity) AS total,sum(CASE WHEN a.voided THEN a.quantity ELSE 0 END) AS voided "
                +"FROM kitchen_item_allocations a JOIN kitchen_ticket_items w ON w.id=a.work_id JOIN kitchen_tickets t ON t.id=w.ticket_id "
                +"WHERE t.store_id=?::uuid AND NOT a.superseded GROUP BY a.work_id",storeA);
        assertThat(sums).hasSize(2);
        for (var row:sums) {
            assertThat(((Number)row.get("total")).intValue()).isEqualTo(2);
            assertThat(((Number)row.get("voided")).intValue()).isEqualTo(1);
        }
    }

    @Test
    void foreignStaffCannotMutateAnAuthorizedDevicesKitchenTicket() throws Exception {
        Device till=activate(storeA,ownerA,"Counter");
        sent(till,opened(till),1);
        String ticket=jdbc.queryForObject("SELECT id::text FROM kitchen_tickets WHERE store_id=?::uuid",String.class,storeA);
        Map<String,Object> change=kitchenBody(till,ticket,"STATUS","READY");
        User foreign=saveUser("Foreign worker");
        change.put("staff",Map.of("userId",foreign.getId().toString(),"name",foreign.getFullName()));
        post(till,change).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM kitchen_tickets WHERE id=?::uuid",String.class,ticket)).isEqualTo("NEW");
    }

    private String opened(Device device) throws Exception {
        String order=UUID.randomUUID().toString();
        op(device,"OPEN",order,Map.of("open",Map.of("orderType","TAKEAWAY","receiptNumber","POSR-K"+COUNTER.incrementAndGet(),"currency","JOD")));
        return order;
    }

    private String sent(Device device,String order,int quantity) throws Exception {
        String uid=UUID.randomUUID().toString();
        op(device,"ADD_ITEMS",order,Map.of("catalogVersion",catalogVersion(device),"items",List.of(line(uid,burger,List.of(),quantity,"5.000","No onion","MAIN"))));
        return uid;
    }

    private ResultActions move(Device d,String source,String target,String line,String moved,int quantity) throws Exception {
        return op(d,"TRANSFER_ITEMS",source,Map.of("transfer",Map.of("targetOrderId",target,"lines",
                List.of(Map.of("lineUid",line,"newLineUid",moved,"quantity",quantity)))));
    }

    private void assertLineage(int total,int voided) {
        Map<String,Object> sums=jdbc.queryForMap("SELECT sum(a.quantity) AS total, sum(CASE WHEN a.voided THEN a.quantity ELSE 0 END) AS voided "
                +"FROM kitchen_item_allocations a JOIN kitchen_ticket_items w ON w.id=a.work_id JOIN kitchen_tickets t ON t.id=w.ticket_id "
                +"WHERE t.store_id=?::uuid AND NOT a.superseded",storeA);
        assertThat(((Number)sums.get("total")).intValue()).isEqualTo(total);
        assertThat(((Number)sums.get("voided")).intValue()).isEqualTo(voided);
    }

    private long workCount() {
        return jdbc.queryForObject("SELECT count(*) FROM kitchen_ticket_items w JOIN kitchen_tickets t ON t.id=w.ticket_id WHERE t.store_id=?::uuid",Long.class,storeA);
    }

    private long allocationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM kitchen_item_allocations a JOIN kitchen_ticket_items w ON w.id=a.work_id JOIN kitchen_tickets t ON t.id=w.ticket_id WHERE t.store_id=?::uuid",Long.class,storeA);
    }

    private long ledgerCount() {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_adjustments WHERE store_id=?::uuid",Long.class,storeA);
    }

    private Map<String,Object> restaurantBody(Device device,String type,String order,Map<String,Object> extra) {
        Map<String,Object> b=new LinkedHashMap<>();
        b.put("operationId",UUID.randomUUID().toString()); b.put("originDeviceId",device.id());
        b.put("type",type); b.put("orderId",order); b.put("occurredAt",Instant.now().minusSeconds(30).toString());
        b.put("staff",Map.of("userId",owner.getId().toString(),"name",owner.getFullName()));
        b.put("overrides",List.of()); b.putAll(extra); return b;
    }

    record Device(String id, String credential) {
        String auth() {
            return "PosDevice " + credential;
        }
    }

    private ResultActions op(Device device, String type, String orderId, Map<String, Object> extra) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("operationId", UUID.randomUUID().toString());
        b.put("originDeviceId", device.id());
        b.put("type", type);
        b.put("orderId", orderId);
        b.put("occurredAt", Instant.now().minusSeconds(30).toString());
        b.put("staff", Map.of("userId", owner.getId().toString(), "name", owner.getFullName()));
        b.put("overrides", List.of());
        b.putAll(extra);
        return send(POST, "/api/pos/restaurant/operations", device.auth(), json.writeValueAsString(b)).andExpect(status().isOk());
    }

    private Map<String, Object> kitchenBody(Device device, String ticket, String type, String to) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("operationId", UUID.randomUUID().toString());
        b.put("originDeviceId", device.id());
        b.put("ticketId", ticket);
        b.put("type", type);
        b.put("toStatus", to);
        b.put("occurredAt", Instant.now().minusSeconds(5).toString());
        b.put("staff", Map.of("userId", owner.getId().toString(), "name", owner.getFullName()));
        return b;
    }

    private ResultActions post(Device device, Map<String, Object> body) throws Exception {
        return send(POST, "/api/pos/kitchen/operations", device.auth(), json.writeValueAsString(body));
    }

    private ResultActions kitchen(Device device, String ticket, String type, String to) throws Exception {
        return post(device, kitchenBody(device, ticket, type, to));
    }

    private static Map<String, Object> line(String uid, String productId, List<String> modifiers, int qty, String unit, String note, String course) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineUid", uid);
        m.put("productId", productId);
        m.put("modifierOptionIds", modifiers);
        m.put("quantity", qty);
        m.put("unitPrice", unit);
        m.put("lineTotal", new java.math.BigDecimal(unit).multiply(java.math.BigDecimal.valueOf(qty)).toPlainString());
        m.put("note", note);
        m.put("course", course);
        return m;
    }

    private String product(String name, String slug, String price, String category) throws Exception {
        return id(send(POST, "/api/dashboard/products", bearer(ownerA), "{\"storeId\":\"" + storeA + "\",\"categoryId\":\"" + category
                + "\",\"nameEn\":\"" + name + "\",\"slug\":\"" + slug + "\",\"price\":" + price + ",\"sortOrder\":" + COUNTER.incrementAndGet() + "}"));
    }

    private String station(String name) throws Exception {
        return id(send(POST, "/api/dashboard/kitchen/stations?storeId=" + storeA, bearer(ownerA), "{\"name\":\"" + name + "\"}"));
    }

    private void routes(String station, List<String> categories, List<String> products) throws Exception {
        send(PUT, "/api/dashboard/kitchen/stations/" + station + "/routes", bearer(ownerA),
                json.writeValueAsString(Map.of("categoryIds", categories, "productIds", products))).andExpect(status().isOk());
    }

    private String option(Device device, String productId, String name) throws Exception {
        List<String> ids = JsonPath.read(body(send(GET, "/api/pos/catalog", device.auth(), null)),
                "$.data.products[?(@.id=='" + productId + "')].modifierGroups[*].options[?(@.name=='" + name + "')].id");
        return ids.get(0);
    }

    private Device activate(String storeId, String ownerToken, String name) throws Exception {
        String created = body(send(POST, "/api/dashboard/pos-devices", bearer(ownerToken), "{\"storeId\":\"" + storeId + "\",\"name\":\"" + name + "\"}"));
        String activated = body(send(POST, "/api/pos/activate", null, "{\"activationCode\":\"" + JsonPath.read(created, "$.data.activationCode")
                + "\",\"installationId\":\"" + UUID.randomUUID() + "\",\"platform\":\"windows\",\"appVersion\":\"0.1.0\"}"));
        return new Device(JsonPath.read(activated, "$.data.deviceId"), JsonPath.read(activated, "$.data.deviceCredential"));
    }

    private String catalogVersion(Device device) throws Exception {
        return JsonPath.read(body(send(GET, "/api/pos/catalog", device.auth(), null)), "$.data.catalogVersion");
    }

    private ResultActions send(HttpMethod method, String path, String authorization, String body) throws Exception {
        MockHttpServletRequestBuilder req = request(method, path).with(r -> {
            r.setRemoteAddr(remoteAddr);
            return r;
        });
        if (authorization != null) req = req.header("Authorization", authorization);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(req);
    }

    private static String body(ResultActions r) throws Exception {
        return r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(body(result), "$.data.id");
    }

    private User saveUser(String name) {
        User user = new User();
        user.setFullName(name);
        user.setEmail(name.toLowerCase().replace(' ', '.') + "-" + UUID.randomUUID() + "@test.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setRole(Role.MERCHANT_OWNER);
        user.setActive(true);
        return userRepository.save(user);
    }
}
