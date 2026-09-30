package com.byonix.shoplink;

import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.User;
import com.byonix.shoplink.domain.enums.Role;
import com.byonix.shoplink.repository.StoreRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-24 shifts and drawer cash over real HTTP (MockMvc + full security chain) on PostgreSQL.
 * Not @Transactional: idempotency depends on real commits and real unique keys.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@WebAppConfiguration
@ActiveProfiles("test")
@org.springframework.test.context.ContextConfiguration(initializers = com.byonix.shoplink.support.PostgresTestDatabase.class)
class PosShiftIT {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private String remoteAddr;
    private User owner;
    private String ownerA;
    private String ownerB;
    private String storeA;
    private String storeB;
    private String pizza;
    private String juice;
    private String wrap;

    @BeforeEach
    void setUp() throws Exception {
        Filter chain = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(chain).build();
        int n = COUNTER.incrementAndGet();
        remoteAddr = "10.92." + (n / 250) + "." + (n % 250 + 1);
        owner = saveUser(Role.MERCHANT_OWNER, "Olivia Owner", null);
        ownerA = jwtService.createAccessToken(owner);
        ownerB = jwtService.createAccessToken(saveUser(Role.MERCHANT_OWNER, "Other Owner", null));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        storeA = idOf(send(POST, "/api/dashboard/stores", bearer(ownerA),
                "{\"name\":\"Shift Cafe\",\"slug\":\"shift-a-" + suffix + "\",\"categorySlug\":\"general-store\",\"currency\":\"JOD\",\"timezone\":\"Asia/Amman\"}")
                .andExpect(status().isOk()));
        pizza = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), productBody(storeA, "Pizza", "pizza", "9.000", 0)).andExpect(status().isOk()));
        juice = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), productBody(storeA, "Juice", "juice", "1.500", 1)).andExpect(status().isOk()));
        wrap = idOf(send(POST, "/api/dashboard/products", bearer(ownerA), productBody(storeA, "Wrap", "wrap", "5.000", 2)).andExpect(status().isOk()));
        storeB = idOf(send(POST, "/api/dashboard/stores", bearer(ownerB),
                "{\"name\":\"Other\",\"slug\":\"shift-b-" + suffix + "\",\"categorySlug\":\"general-store\",\"currency\":\"JOD\"}")
                .andExpect(status().isOk()));
    }

    // â”€â”€ golden path: the spec's drawer â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void goldenPathExpectedCashIsExactlyOpeningPlusCashSalesPlusCashInMinusCashOutMinusCashRefunds() throws Exception {
        Device a = activate(storeA, ownerA, "Counter 1");
        String version = catalogVersion(a);
        Shift shift = new Shift(a, owner, "50.000");
        send(POST, "/api/pos/shifts/open", a.auth(), shift.openBody()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shiftStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.replayed").value(false));

        // Cash sale 10.500 (pizza 9.000 + juice 1.500) and a card-terminal sale 5.000.
        String cashSale = syncSale(a, new Sale(a, version, "CASH").line(pizza, 1, "9.000").line(juice, 1, "1.500").shift(shift));
        syncSale(a, new Sale(a, version, "EXTERNAL_CARD").line(wrap, 1, "5.000").shift(shift));
        move(a, shift, "CASH_IN", "CHANGE_FLOAT", "10.000", owner, null).andExpect(status().isOk());
        move(a, shift, "CASH_OUT", "PETTY_CASH", "2.000", owner, null).andExpect(status().isOk());
        // Cash refund of the juice: 1.500 leaves the drawer.
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, cashSale).item(2, juice, "1.500").refund("1.500", "0", "1.500", "CASH").shift(shift).body())
                .andExpect(status().isOk());

        String totals = "{\"openingCash\":\"50.000\",\"cashSales\":\"10.500\",\"terminalSales\":\"5.000\",\"cashRefunds\":\"1.500\","
                + "\"terminalRefunds\":\"0\",\"cashIn\":\"10.000\",\"cashOut\":\"2.000\",\"expectedCash\":\"67.000\",\"orderCount\":2,\"returnCount\":1}";
        String closed = send(POST, "/api/pos/shifts/" + shift.id + "/close", a.auth(), shift.closeBody("67.000", "0.000", totals, owner, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shiftStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.reconciliation").value("MATCHED"))
                .andExpect(jsonPath("$.data.status").value("SYNCED"))
                .andReturn().getResponse().getContentAsString();
        // Exact JOD, three decimals, as text on the wire.
        assertThat(closed).contains("\"expectedCash\":67.000").contains("\"terminalSales\":5.000").contains("\"cashRefunds\":1.500")
                .contains("\"variance\":0.000");

        String row = send(GET, "/api/dashboard/pos-shifts?storeId=" + storeA, bearer(ownerA), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(row, "$.data.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(row, "$.data[0].status")).isEqualTo("CLOSED");
        assertThat(JsonPath.<String>read(row, "$.data[0].deviceName")).isEqualTo("Counter 1");
        assertThat(JsonPath.<Integer>read(row, "$.data[0].totals.orderCount")).isEqualTo(2);
        assertThat(dec(row, "$.data[0].totals.expectedCash")).isEqualByComparingTo("67.000");
        assertThat(dec(row, "$.data[0].countedCash")).isEqualByComparingTo("67.000");
        assertThat(JsonPath.<Boolean>read(row, "$.data[0].lateRecords")).isFalse();

        String detail = send(GET, "/api/dashboard/pos-shifts/" + shift.id, bearer(ownerA), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(detail, "$.data.orders.length()")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(detail, "$.data.returns.length()")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(detail, "$.data.movements.length()")).isEqualTo(2);
        // Linked explicitly, not by time.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders WHERE pos_shift_id = ?::uuid", Long.class, shift.id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_returns WHERE shift_id = ?::uuid", Long.class, shift.id)).isEqualTo(1);
    }

    @Test
    void exchangesCountOnlyTheCashThatActuallyMovedAndTerminalRefundsNeverTouchTheDrawer() throws Exception {
        Device a = activate(storeA, ownerA, "Till");
        String version = catalogVersion(a);
        Shift shift = open(a, owner, "20.000");
        String juiceSale = syncSale(a, new Sale(a, version, "CASH").line(juice, 1, "1.500").shift(shift));   // +1.500
        String pizzaSale = syncSale(a, new Sale(a, version, "CASH").line(pizza, 1, "9.000").shift(shift));   // +9.000

        // Exchange 1: juice (1.500) for pizza (9.000): the customer pays 7.500 extra in cash.
        String rid1 = UUID.randomUUID().toString();
        Sale up = new Sale(a, version, "CASH").line(pizza, 1, "9.000").exchange(rid1, "1.500").shift(shift);
        syncSale(a, up);                                                                                    // +7.500
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, juiceSale).localId(rid1).exchange(up.localOrderId)
                .item(1, juice, "1.500").refund("1.500", "1.500", "0", null).shift(shift).approvedBy(owner).body()).andExpect(status().isOk());

        // Exchange 2: pizza (9.000) for juice (1.500): the customer gets 7.500 back in cash.
        String rid2 = UUID.randomUUID().toString();
        Sale down = new Sale(a, version, "CASH").line(juice, 1, "1.500").exchange(rid2, "1.500").shift(shift);
        syncSale(a, down);                                                                                  // +0
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, pizzaSale).localId(rid2).exchange(down.localOrderId)
                .item(1, pizza, "9.000").refund("9.000", "1.500", "7.500", "CASH").shift(shift).approvedBy(owner).body()).andExpect(status().isOk()); // âˆ’7.500

        // A card sale refunded on the card terminal: neither touches the drawer.
        String cardSale = syncSale(a, new Sale(a, version, "EXTERNAL_CARD").line(wrap, 1, "5.000").shift(shift));
        send(POST, "/api/pos/returns/sync", a.auth(), new Return(a, cardSale).item(1, wrap, "5.000").refund("5.000", "0", "5.000", "EXTERNAL_TERMINAL")
                .shift(shift).approvedBy(owner).body()).andExpect(status().isOk());

        String detail = send(GET, "/api/dashboard/pos-shifts/" + shift.id, bearer(ownerA), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 20 + (1.5 + 9 + 7.5 + 0) âˆ’ 7.5 = 30.500
        assertThat(dec(detail, "$.data.shift.totals.cashSales")).isEqualByComparingTo("18.000");
        assertThat(dec(detail, "$.data.shift.totals.cashRefunds")).isEqualByComparingTo("7.500");
        assertThat(dec(detail, "$.data.shift.totals.terminalSales")).isEqualByComparingTo("5.000");
        assertThat(dec(detail, "$.data.shift.totals.terminalRefunds")).isEqualByComparingTo("5.000");
        assertThat(dec(detail, "$.data.shift.totals.expectedCash")).isEqualByComparingTo("30.500");
        assertThat(JsonPath.<Integer>read(detail, "$.data.shift.totals.returnCount")).isEqualTo(3);
    }

    // â”€â”€ idempotency â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void retryingAnOpenNeverCreatesASecondShiftAndATillCannotHaveTwoOpenShifts() throws Exception {
        Device a = activate(storeA, ownerA, "Till");
        Shift shift = new Shift(a, owner, "0");
        send(POST, "/api/pos/shifts/open", a.auth(), shift.openBody()).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(false));
        // Lost response: same operation again â€¦
        send(POST, "/api/pos/shifts/open", a.auth(), shift.openBody()).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        // â€¦ or the same shift under a new operation id.
        shift.operationId = UUID.randomUUID().toString();
        send(POST, "/api/pos/shifts/open", a.auth(), shift.openBody()).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_shifts WHERE store_id = ?::uuid", Long.class, storeA)).isEqualTo(1);
        // The same operation id with different content is refused.
        shift.openingCash = "5.000";
        send(POST, "/api/pos/shifts/open", a.auth(), shift.openBody()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POS_SYNC_OPERATION_ID_REUSED"));
        // A second shift on the same till while the first is open is refused.
        send(POST, "/api/pos/shifts/open", a.auth(), new Shift(a, owner, "0").openBody()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POS_SYNC_SHIFT_ALREADY_OPEN"));
        // Negative or over-precise opening cash never gets in.
        send(POST, "/api/pos/shifts/open", a.auth(), new Shift(a, owner, "-1.000").openBody()).andExpect(status().isBadRequest());
        Device b = activate(storeA, ownerA, "Till 2");
        send(POST, "/api/pos/shifts/open", b.auth(), new Shift(b, owner, "10.0005").openBody()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("POS_SYNC_AMOUNT_INVALID"));
    }

    @Test
    void retryingACashMovementOrACloseNeverAppliesItTwice() throws Exception {
        Device a = activate(storeA, ownerA, "Till");
        Shift shift = open(a, owner, "100.000");
        Movement out = new Movement(shift, "CASH_OUT", "SAFE_DROP", "40.000", owner);
        send(POST, "/api/pos/shifts/" + shift.id + "/cash-movements", a.auth(), out.body()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(false));
        send(POST, "/api/pos/shifts/" + shift.id + "/cash-movements", a.auth(), out.body()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
        out.operationId = UUID.randomUUID().toString(); // same movement, new operation id
        send(POST, "/api/pos/shifts/" + shift.id + "/cash-movements", a.auth(), out.body()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.serverTotals.cashOut").value(40.0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_shift_cash_movements WHERE shift_id = ?::uuid", Long.class, shift.id)).isEqualTo(1);

        String totals = totalsJson("100.000", "0", "0", "0", "0", "0", "40.000", "60.000", 0, 0);
        String body = shift.closeBody("60.000", "0.000", totals, owner, null);
        send(POST, "/api/pos/shifts/" + shift.id + "/close", a.auth(), body).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(false));
        send(POST, "/api/pos/shifts/" + shift.id + "/close", a.auth(), body).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.shiftStatus").value("CLOSED"));
        // A different close of the same shift is refused: a shift closes once.
        shift.closeOperationId = UUID.randomUUID().toString();
        send(POST, "/api/pos/shifts/" + shift.id + "/close", a.auth(), shift.closeBody("59.000", "-1.000",
                totals, owner, null)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT counted_cash FROM pos_shifts WHERE id = ?::uuid", BigDecimal.class, shift.id)).isEqualByComparingTo("60.000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_sync_operations WHERE entity_id = ?::uuid AND operation_type = 'SHIFT_CLOSE'",
                Long.class, shift.id)).isEqualTo(1);
    }

    // â”€â”€ variance and approvals â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void shortageAndOverageKeepTheirSignAndALargeVarianceNeedsAManager() throws Exception {
        User cashier = saveUser(Role.MERCHANT_STAFF, "Casey Cashier", storeA);
        Device a = activate(storeA, ownerA, "Till A");
        Device b = activate(storeA, ownerA, "Till B");

        // Shortage 1.500 closed by the cashier with a manager's approval.
        Shift s1 = open(a, cashier, "100.000");
        send(POST, "/api/pos/shifts/" + s1.id + "/close", a.auth(), s1.closeBody("98.500", "-1.500",
                totalsJson("100.000", "0", "0", "0", "0", "0", "0", "100.000", 0, 0), cashier, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shiftStatus").value("CLOSED_WITH_VARIANCE"))
                .andExpect(jsonPath("$.data.variance").value(-1.5))
                .andExpect(jsonPath("$.data.conflicts.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT closing_manager_name FROM pos_shifts WHERE id = ?::uuid", String.class, s1.id)).isEqualTo("Olivia Owner");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_manager_overrides WHERE shift_id = ?::uuid AND action = 'SHIFT_CLOSE_APPROVAL' AND verified",
                Long.class, s1.id)).isEqualTo(1);

        // Overage 2.000 closed by the cashier alone: kept (the cash was counted) but flagged.
        Shift s2 = open(b, cashier, "100.000");
        String over = send(POST, "/api/pos/shifts/" + s2.id + "/close", b.auth(), s2.closeBody("102.000", "2.000",
                totalsJson("100.000", "0", "0", "0", "0", "0", "0", "100.000", 0, 0), cashier, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shiftStatus").value("CLOSED_WITH_VARIANCE"))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("SHIFT_APPROVAL_MISSING"))
                .andReturn().getResponse().getContentAsString();
        assertThat(over).contains("\"variance\":2.000");

        // A small variance (â‰¤ 1.000) needs no approval.
        Shift s3 = open(a, cashier, "10.000");
        send(POST, "/api/pos/shifts/" + s3.id + "/close", a.auth(), s3.closeBody("9.750", "-0.250",
                totalsJson("10.000", "0", "0", "0", "0", "0", "0", "10.000", 0, 0), cashier, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.conflicts.length()").value(0));

        String varianceList = send(GET, "/api/dashboard/pos-shifts?storeId=" + storeA + "&status=VARIANCE", bearer(ownerA), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(varianceList, "$.data.length()")).isEqualTo(3);
        send(GET, "/api/dashboard/pos-shifts?storeId=" + storeA + "&status=OPEN", bearer(ownerA), null)
                .andExpect(jsonPath("$.data.length()").value(0));
        send(GET, "/api/dashboard/pos-shifts?storeId=" + storeA + "&deviceId=" + b.id, bearer(ownerA), null)
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void aCashiersCashOutNeedsAManagerAndAnUnapprovedOneIsKeptButFlagged() throws Exception {
        User cashier = saveUser(Role.MERCHANT_STAFF, "Casey Cashier", storeA);
        Device a = activate(storeA, ownerA, "Till");
        Shift shift = open(a, cashier, "50.000");
        move(a, shift, "CASH_OUT", "PETTY_CASH", "5.000", cashier, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflicts[0].type").value("SHIFT_APPROVAL_MISSING"));
        move(a, shift, "CASH_OUT", "BANK_DEPOSIT", "20.000", cashier, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflicts.length()").value(0));
        // Small cash in: no approval; a large one: approval.
        move(a, shift, "CASH_IN", "CHANGE_FLOAT", "25.000", cashier, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflicts.length()").value(0));
        move(a, shift, "CASH_IN", "MANAGER_ADJUSTMENT", "150.000", cashier, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflicts[0].type").value("SHIFT_APPROVAL_MISSING"));
        // Structured reasons: OTHER needs a note; a cash-out reason cannot be a cash in.
        move(a, shift, "CASH_OUT", "OTHER", "1.000", owner, null).andExpect(status().isUnprocessableContent());
        move(a, shift, "CASH_IN", "SAFE_DROP", "1.000", owner, null).andExpect(status().isUnprocessableContent());

        String detail = send(GET, "/api/dashboard/pos-shifts/" + shift.id, bearer(ownerA), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Every movement is audited: 4 recorded (2 refused never got in), with who and which manager.
        assertThat(JsonPath.<Integer>read(detail, "$.data.movements.length()")).isEqualTo(4);
        assertThat(JsonPath.<String>read(detail, "$.data.movements[1].managerName")).isEqualTo("Olivia Owner");
        assertThat(JsonPath.<String>read(detail, "$.data.movements[0].staffName")).isEqualTo("Casey Cashier");
        assertThat(JsonPath.<Integer>read(detail, "$.data.conflicts.length()")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(detail, "$.data.approvals.length()")).isEqualTo(1);
        // 50 âˆ’ 5 âˆ’ 20 + 25 + 150 = 200
        assertThat(dec(detail, "$.data.shift.totals.expectedCash")).isEqualByComparingTo("200.000");
    }

    // â”€â”€ isolation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void twoTillsKeepSeparateDrawersAndAnotherStoreCanNeitherSeeNorTouchAShift() throws Exception {
        Device a = activate(storeA, ownerA, "Counter 1");
        Device b = activate(storeA, ownerA, "Counter 2");
        Device other = activate(storeB, ownerB, "Other till");
        String version = catalogVersion(a);
        String versionB = catalogVersion(b);
        Shift sa = open(a, owner, "10.000");
        Shift sb = open(b, owner, "20.000");
        syncSale(a, new Sale(a, version, "CASH").line(pizza, 1, "9.000").shift(sa));
        syncSale(b, new Sale(b, versionB, "CASH").line(juice, 1, "1.500").shift(sb));
        move(a, sa, "CASH_IN", "CHANGE_FLOAT", "5.000", owner, null).andExpect(status().isOk());

        String list = send(GET, "/api/dashboard/pos-shifts?storeId=" + storeA + "&status=OPEN", bearer(ownerA), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(list, "$.data.length()")).isEqualTo(2);
        assertThat(dec(send(GET, "/api/dashboard/pos-shifts/" + sa.id, bearer(ownerA), null).andReturn().getResponse().getContentAsString(),
                "$.data.shift.totals.expectedCash")).isEqualByComparingTo("24.000");
        assertThat(dec(send(GET, "/api/dashboard/pos-shifts/" + sb.id, bearer(ownerA), null).andReturn().getResponse().getContentAsString(),
                "$.data.shift.totals.expectedCash")).isEqualByComparingTo("21.500");

        // Till B cannot move or close till A's drawer (answered like a missing shift).
        send(POST, "/api/pos/shifts/" + sa.id + "/cash-movements", b.auth(), new Movement(sa, "CASH_OUT", "SAFE_DROP", "1.000", owner).from(b).body())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_SHIFT_UNKNOWN"));
        // A sale on till B naming till A's shift is refused, never counted in A's drawer.
        send(POST, "/api/pos/orders/sync", b.auth(), new Sale(b, versionB, "CASH").line(pizza, 1, "9.000").shift(sa).body())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_SHIFT_INVALID"));

        // Store B: no view, no change.
        send(GET, "/api/dashboard/pos-shifts?storeId=" + storeA, bearer(ownerB), null).andExpect(status().is4xxClientError());
        send(GET, "/api/dashboard/pos-shifts/" + sa.id, bearer(ownerB), null).andExpect(status().is4xxClientError());
        send(POST, "/api/dashboard/pos-shifts/" + sa.id + "/force-close", bearer(ownerB), "{\"note\":\"mine now\"}").andExpect(status().is4xxClientError());
        send(POST, "/api/pos/shifts/" + sa.id + "/close", other.auth(), new Shift(other, owner, "10.000").withId(sa.id).closeBody("10.000", "0.000",
                totalsJson("10.000", "0", "0", "0", "0", "0", "0", "10.000", 0, 0), owner, null))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_SHIFT_UNKNOWN"));
        // Another store reusing the id cannot take it over.
        send(POST, "/api/pos/shifts/open", other.auth(), new Shift(other, owner, "0").withId(sa.id).openBody())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("POS_SYNC_SHIFT_ID_REUSED"));
        assertThat(jdbc.queryForObject("SELECT status FROM pos_shifts WHERE id = ?::uuid", String.class, sa.id)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pos_shifts WHERE store_id = ?::uuid", Long.class, storeB)).isZero();
    }

    // â”€â”€ offline: records that arrive after the close â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void aCloseMadeOfflineWithASaleTheServerLacksIsFlaggedAndReconcilesWhenTheSaleArrivesLate() throws Exception {
        Device a = activate(storeA, ownerA, "Till");
        String version = catalogVersion(a);
        Shift shift = open(a, owner, "50.000");
        syncSale(a, new Sale(a, version, "CASH").line(pizza, 1, "9.000").shift(shift));
        // The till also sold a juice (1.500 cash) whose upload was refused and is retried later; its close includes it.
        Sale late = new Sale(a, version, "CASH").line(juice, 1, "1.500").shift(shift);
        String totals = totalsJson("50.000", "10.500", "0", "0", "0", "0", "0", "60.500", 2, 0);
        send(POST, "/api/pos/shifts/" + shift.id + "/close", a.auth(), shift.closeBody("60.500", "0.000", totals, owner, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reconciliation").value("MISMATCH"))
                .andExpect(jsonPath("$.data.conflicts[0].type").value("SHIFT_RECONCILIATION_MISMATCH"))
                .andExpect(jsonPath("$.data.conflicts[0].detail").value(org.hamcrest.Matchers.containsString("LOCAL_EXPECTED 60.500, SERVER_EXPECTED 59.000, difference 1.500")));
        // Server figure at close: 59.000 â†’ counted 60.500 is +1.500 over it.
        assertThat(jdbc.queryForObject("SELECT variance FROM pos_shifts WHERE id = ?::uuid", BigDecimal.class, shift.id)).isEqualByComparingTo("1.500");

        syncSale(a, late); // arrives after the close: still linked, still counted
        String row = send(GET, "/api/dashboard/pos-shifts/" + shift.id, bearer(ownerA), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(dec(row, "$.data.shift.totals.expectedCash")).isEqualByComparingTo("60.500");
        assertThat(JsonPath.<String>read(row, "$.data.shift.reconciliation")).isEqualTo("MATCHED");
        assertThat(JsonPath.<Boolean>read(row, "$.data.shift.lateRecords")).isTrue();
        assertThat(dec(row, "$.data.shift.variance")).isEqualByComparingTo("0");
        assertThat(dec(row, "$.data.shift.expectedCashAtClose")).isEqualByComparingTo("59.000");
        assertThat(JsonPath.<Integer>read(row, "$.data.conflicts.length()")).isEqualTo(1);
    }

    @Test
    void tamperedClosingFiguresAreRefusedAndAForceClosedShiftFreesTheTill() throws Exception {
        User cashier = saveUser(Role.MERCHANT_STAFF, "Casey Cashier", storeA);
        String cashierToken = jwtService.createAccessToken(cashier);
        Device a = activate(storeA, ownerA, "Till");
        Shift shift = open(a, owner, "50.000");
        // Totals that do not add up (expected â‰  opening + â€¦) or a variance that is not counted âˆ’ expected.
        send(POST, "/api/pos/shifts/" + shift.id + "/close", a.auth(), shift.closeBody("50.000", "0.000",
                totalsJson("50.000", "0", "0", "0", "0", "0", "0", "40.000", 0, 0), owner, null))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("POS_SYNC_SHIFT_TOTALS_INVALID"));
        send(POST, "/api/pos/shifts/" + shift.id + "/close", a.auth(), shift.closeBody("50.000", "1.000",
                totalsJson("50.000", "0", "0", "0", "0", "0", "0", "50.000", 0, 0), owner, null))
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("SELECT status FROM pos_shifts WHERE id = ?::uuid", String.class, shift.id)).isEqualTo("OPEN");

        // A cashier (POS view only) cannot force-close; the owner can. Then the till may open a new shift.
        send(POST, "/api/dashboard/pos-shifts/" + shift.id + "/force-close", bearer(cashierToken), "{\"note\":\"till lost\"}")
                .andExpect(status().is4xxClientError());
        send(POST, "/api/dashboard/pos-shifts/" + shift.id + "/force-close", bearer(ownerA), "{\"note\":\"Till reinstalled\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shift.status").value("FORCE_CLOSED"))
                .andExpect(jsonPath("$.data.shift.forceClosedByName").value("Olivia Owner"));
        send(POST, "/api/dashboard/pos-shifts/" + shift.id + "/force-close", bearer(ownerA), "{\"note\":\"again\"}")
                .andExpect(status().isConflict());
        open(a, owner, "0");
    }

    // â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    record Device(String id, String credential) {
        String auth() {
            return "PosDevice " + credential;
        }
    }

    final class Shift {
        String id = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        String closeOperationId = UUID.randomUUID().toString();
        final String number = "SHF-" + (400000 + COUNTER.incrementAndGet());
        final Instant openedAt = Instant.now().minusSeconds(3600);
        final Instant closedAt = Instant.now().minusSeconds(5);
        final String origin;
        final User cashier;
        String openingCash;

        Shift(Device device, User cashier, String openingCash) {
            this.origin = device.id;
            this.cashier = cashier;
            this.openingCash = openingCash;
        }

        Shift withId(String id) {
            this.id = id;
            return this;
        }

        String openBody() {
            return "{\"operationId\":\"" + operationId + "\",\"originDeviceId\":\"" + origin + "\",\"shiftId\":\"" + id + "\",\"shiftNumber\":\""
                    + number + "\",\"openedAt\":\"" + openedAt + "\",\"currency\":\"JOD\",\"openingCash\":\"" + openingCash + "\",\"cashier\":"
                    + staff(cashier) + "}";
        }

        String closeBody(String counted, String variance, String totals, User closer, User approver) {
            return "{\"operationId\":\"" + closeOperationId + "\",\"originDeviceId\":\"" + origin + "\",\"closedAt\":\"" + closedAt
                    + "\",\"currency\":\"JOD\",\"countedCash\":\"" + counted + "\",\"variance\":\"" + variance + "\",\"deviceTotals\":" + totals
                    + ",\"note\":null,\"closedBy\":" + staff(closer) + ",\"overrides\":" + approval("SHIFT_CLOSE_APPROVAL", approver) + "}";
        }
    }

    final class Movement {
        String operationId = UUID.randomUUID().toString();
        final String id = UUID.randomUUID().toString();
        final Instant movedAt = Instant.now().minusSeconds(30);
        String origin;
        final String type, reason, amount;
        final User staff;
        User approver;

        Movement(Shift shift, String type, String reason, String amount, User staff) {
            this.origin = shift.origin;
            this.type = type;
            this.reason = reason;
            this.amount = amount;
            this.staff = staff;
        }

        Movement from(Device device) {
            this.origin = device.id;
            return this;
        }

        String body() {
            return "{\"operationId\":\"" + operationId + "\",\"originDeviceId\":\"" + origin + "\",\"movementId\":\"" + id + "\",\"type\":\"" + type
                    + "\",\"reason\":\"" + reason + "\",\"note\":null,\"amount\":\"" + amount + "\",\"currency\":\"JOD\",\"movedAt\":\""
                    + movedAt + "\",\"staff\":" + staff(staff) + ",\"overrides\":" + approval("CASH_MOVEMENT_APPROVAL", approver) + "}";
        }
    }

    final class Sale {
        final String operationId = UUID.randomUUID().toString();
        final String localOrderId = UUID.randomUUID().toString();
        final String receipt;
        final String catalogVersion;
        final String origin;
        final String method;
        final List<String> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        String extra = "";

        Sale(Device device, String catalogVersion, String method) {
            this.origin = device.id;
            this.catalogVersion = catalogVersion;
            this.method = method;
            this.receipt = "POS" + device.id.substring(0, 4).toUpperCase() + "-20260929-" + (500000 + COUNTER.incrementAndGet());
        }

        Sale line(String productId, int quantity, String price) {
            BigDecimal lineTotal = new BigDecimal(price).multiply(BigDecimal.valueOf(quantity));
            lines.add("{\"productId\":\"" + productId + "\",\"variantId\":null,\"modifierOptionIds\":[],\"quantity\":" + quantity
                    + ",\"unitPrice\":\"" + price + "\",\"lineTotal\":\"" + lineTotal.toPlainString() + "\"}");
            total = total.add(lineTotal);
            return this;
        }

        Sale shift(Shift shift) {
            extra += ",\"shiftId\":\"" + shift.id + "\"";
            return this;
        }

        Sale exchange(String localReturnId, String credit) {
            extra += ",\"exchange\":{\"localReturnId\":\"" + localReturnId + "\",\"credit\":\"" + credit + "\"}";
            return this;
        }

        String body() {
            return "{\"operationId\":\"" + operationId + "\",\"originDeviceId\":\"" + origin + "\",\"localOrderId\":\"" + localOrderId
                    + "\",\"receiptNumber\":\"" + receipt + "\",\"catalogVersion\":\"" + catalogVersion + "\",\"soldAt\":\""
                    + Instant.now().minusSeconds(600) + "\",\"currency\":\"JOD\",\"paymentMethod\":\"" + method + "\",\"subtotal\":\"" + total.toPlainString()
                    + "\",\"discount\":\"0\",\"total\":\"" + total.toPlainString() + "\",\"note\":null"
                    + ",\"staff\":" + staff(owner) + extra + ",\"items\":[" + String.join(",", lines) + "]}";
        }
    }

    final class Return {
        final String operationId = UUID.randomUUID().toString();
        String localReturnId = UUID.randomUUID().toString();
        final String number = "RET-" + (600000 + COUNTER.incrementAndGet());
        final String origin;
        final String orderId;
        final List<String> items = new ArrayList<>();
        String kind = "RETURN", exchangeLocalOrderId = null, refundTotal, credit, paidOut, method, extra = "", overrides = "[]";

        Return(Device device, String orderId) {
            this.origin = device.id;
            this.orderId = orderId;
        }

        Return localId(String id) {
            this.localReturnId = id;
            return this;
        }

        Return exchange(String replacementLocalOrderId) {
            this.kind = "EXCHANGE";
            this.exchangeLocalOrderId = replacementLocalOrderId;
            return this;
        }

        Return item(int lineNo, String productId, String refund) {
            items.add("{\"lineNo\":" + lineNo + ",\"productId\":\"" + productId + "\",\"variantId\":null,\"quantity\":1,\"returnedBefore\":0,"
                    + "\"refundAmount\":\"" + refund + "\",\"disposition\":\"RESTOCK\"}");
            return this;
        }

        Return refund(String total, String credit, String paidOut, String method) {
            this.refundTotal = total;
            this.credit = credit;
            this.paidOut = paidOut;
            this.method = method;
            return this;
        }

        Return shift(Shift shift) {
            extra += ",\"shiftId\":\"" + shift.id + "\"";
            return this;
        }

        Return approvedBy(User manager) {
            overrides = approval("RETURN_APPROVAL", manager);
            return this;
        }

        String body() {
            return "{\"operationId\":\"" + operationId + "\",\"originDeviceId\":\"" + origin + "\",\"localReturnId\":\"" + localReturnId
                    + "\",\"returnNumber\":\"" + number + "\",\"kind\":\"" + kind + "\",\"originalOrderId\":\"" + orderId
                    + "\",\"returnedAt\":\"" + Instant.now().minusSeconds(60) + "\",\"currency\":\"JOD\",\"reason\":\"CUSTOMER_CHANGED_MIND\",\"reasonNote\":null"
                    + ",\"refundTotal\":\"" + refundTotal + "\",\"exchangeCredit\":\"" + credit + "\",\"refundPaidOut\":\"" + paidOut + "\",\"refundMethod\":"
                    + (method == null ? "null" : "\"" + method + "\"") + ",\"exchangeLocalOrderId\":"
                    + (exchangeLocalOrderId == null ? "null" : "\"" + exchangeLocalOrderId + "\"")
                    + ",\"staff\":" + staff(owner) + ",\"overrides\":" + overrides + extra + ",\"items\":[" + String.join(",", items) + "]}";
        }
    }

    private Shift open(Device device, User cashier, String openingCash) throws Exception {
        Shift shift = new Shift(device, cashier, openingCash);
        send(POST, "/api/pos/shifts/open", device.auth(), shift.openBody()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shiftStatus").value("OPEN"));
        return shift;
    }

    private ResultActions move(Device device, Shift shift, String type, String reason, String amount, User staff, User approver) throws Exception {
        Movement m = new Movement(shift, type, reason, amount, staff);
        m.approver = approver;
        return send(POST, "/api/pos/shifts/" + shift.id + "/cash-movements", device.auth(), m.body());
    }

    private static String totalsJson(String opening, String cashSales, String terminalSales, String cashRefunds, String terminalRefunds,
                                     String cashIn, String cashOut, String expected, int orders, int returns) {
        return "{\"openingCash\":\"" + opening + "\",\"cashSales\":\"" + cashSales + "\",\"terminalSales\":\"" + terminalSales
                + "\",\"cashRefunds\":\"" + cashRefunds + "\",\"terminalRefunds\":\"" + terminalRefunds + "\",\"cashIn\":\"" + cashIn
                + "\",\"cashOut\":\"" + cashOut + "\",\"expectedCash\":\"" + expected + "\",\"orderCount\":" + orders + ",\"returnCount\":" + returns + "}";
    }

    private static String staff(User user) {
        return "{\"userId\":\"" + user.getId() + "\",\"name\":\"" + user.getFullName() + "\"}";
    }

    private static String approval(String action, User manager) {
        if (manager == null) return "[]";
        return "[{\"action\":\"" + action + "\",\"managerId\":\"" + manager.getId() + "\",\"managerName\":\"" + manager.getFullName()
                + "\",\"approvedAt\":\"" + Instant.now().minusSeconds(20) + "\",\"detail\":null}]";
    }

    private String syncSale(Device device, Sale sale) throws Exception {
        String body = send(POST, "/api/pos/orders/sync", device.auth(), sale.body()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.orderId");
    }

    private Device activate(String storeId, String ownerToken, String name) throws Exception {
        String created = send(POST, "/api/dashboard/pos-devices", bearer(ownerToken),
                "{\"storeId\":\"" + storeId + "\",\"name\":\"" + name + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(created, "$.data.activationCode");
        String activated = send(POST, "/api/pos/activate", null, "{\"activationCode\":\"" + code + "\",\"installationId\":\""
                + UUID.randomUUID() + "\",\"platform\":\"windows\",\"appVersion\":\"0.1.0\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Device(JsonPath.read(activated, "$.data.deviceId"), JsonPath.read(activated, "$.data.deviceCredential"));
    }

    private String catalogVersion(Device device) throws Exception {
        String json = send(GET, "/api/pos/catalog", device.auth(), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.data.catalogVersion");
    }

    private static BigDecimal dec(String json, String path) {
        return new BigDecimal(JsonPath.read(json, path).toString());
    }

    private static String productBody(String storeId, String name, String slug, String price, int sort) {
        return "{\"storeId\":\"" + storeId + "\",\"nameEn\":\"" + name + "\",\"slug\":\"" + slug + "\",\"price\":" + price + ",\"sortOrder\":" + sort + "}";
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

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private User saveUser(Role role, String name, String storeId) {
        User user = new User();
        user.setFullName(name);
        user.setEmail(name.toLowerCase().replace(' ', '.') + "-" + UUID.randomUUID() + "@test.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setRole(role);
        user.setActive(true);
        if (storeId != null) {
            Store store = storeRepository.findById(UUID.fromString(storeId)).orElseThrow();
            user.setStore(store);
        }
        return userRepository.save(user);
    }
}
