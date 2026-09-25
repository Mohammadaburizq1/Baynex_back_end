package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.BusinessHoursDtos;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.StoreBusinessHour;
import com.byonix.shoplink.repository.StoreBusinessHourRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessHoursServiceTest {
    @Mock StoreBusinessHourRepository repository;
    @Mock StoreService storeService;

    private final Store store = store("Asia/Amman");

    @Test
    void missingRowsAreNotConfigured() {
        when(repository.findByStore_IdOrderByDayOfWeekAsc(store.getId())).thenReturn(List.of());

        BusinessHoursDtos.Response response = service().response(store, Instant.parse("2026-09-21T08:00:00Z"));

        assertEquals("NOT_CONFIGURED", response.status());
        assertFalse(response.configured());
        assertEquals(7, response.days().size());
    }

    @Test
    void normalIntervalIsOpenOnlyInsideTheLocalInterval() {
        List<StoreBusinessHour> hours = everyDayClosed();
        hours.set(0, hour(DayOfWeek.MONDAY, false, false, "09:00", "17:00"));
        when(repository.findByStore_IdOrderByDayOfWeekAsc(store.getId())).thenReturn(hours);

        assertEquals("CLOSED", service().response(store, Instant.parse("2026-09-21T05:00:00Z")).status());
        assertEquals("OPEN", service().response(store, Instant.parse("2026-09-21T10:00:00Z")).status());
        assertEquals("CLOSED", service().response(store, Instant.parse("2026-09-21T15:00:00Z")).status());
    }

    @Test
    void timezoneIsUsedForCalculation() {
        List<StoreBusinessHour> hours = everyDayClosed();
        hours.set(0, hour(DayOfWeek.MONDAY, false, false, "09:00", "17:00"));
        when(repository.findByStore_IdOrderByDayOfWeekAsc(store.getId())).thenReturn(hours);

        // 08:00 UTC is 11:00 in Asia/Amman on this date.
        assertEquals("OPEN", service().response(store, Instant.parse("2026-09-21T08:00:00Z")).status());
    }

    @Test
    void overnightHoursRemainOpenAfterMidnight() {
        List<StoreBusinessHour> hours = everyDayClosed();
        hours.set(4, hour(DayOfWeek.FRIDAY, false, false, "18:00", "02:00"));
        when(repository.findByStore_IdOrderByDayOfWeekAsc(store.getId())).thenReturn(hours);

        assertEquals("OPEN", service().response(store, Instant.parse("2026-09-25T17:00:00Z")).status());
        assertEquals("OPEN", service().response(store, Instant.parse("2026-09-25T22:00:00Z")).status());
        assertEquals("CLOSED", service().response(store, Instant.parse("2026-09-25T23:00:00Z")).status());
    }

    @Test
    void openTwentyFourHoursIsDistinctFromClosed() {
        List<StoreBusinessHour> hours = everyDayClosed();
        hours.set(2, hour(DayOfWeek.WEDNESDAY, false, true, null, null));
        when(repository.findByStore_IdOrderByDayOfWeekAsc(store.getId())).thenReturn(hours);

        assertEquals("OPEN", service().response(store, Instant.parse("2026-09-23T20:00:00Z")).status());
        assertTrue(service().response(store, Instant.parse("2026-09-23T20:00:00Z")).days().get(2).open24Hours());
    }

    @Test
    void publicResponseExposesThePersistedWeeklySchedule() {
        List<StoreBusinessHour> hours = everyDayClosed();
        hours.set(0, hour(DayOfWeek.MONDAY, false, false, "09:00", "17:00"));
        when(storeService.publicStore("demo")).thenReturn(store);
        when(repository.findByStore_IdOrderByDayOfWeekAsc(store.getId())).thenReturn(hours);

        BusinessHoursDtos.Response response = service().publicHours("demo");

        assertTrue(response.configured());
        assertEquals(DayOfWeek.MONDAY, response.days().get(0).dayOfWeek());
        verify(storeService).publicStore("demo");
    }

    @Test
    void acceptingOrderDecisionKeepsNotConfiguredStoresCompatible() {
        when(repository.findByStore_IdOrderByDayOfWeekAsc(store.getId())).thenReturn(List.of());

        assertDoesNotThrow(() -> service().assertCanAcceptOrder(store,
                Instant.parse("2026-09-21T10:00:00Z")));
        store.setAcceptingOrders(false);
        OrderUnavailableException paused = assertThrows(OrderUnavailableException.class,
                () -> service().assertCanAcceptOrder(store, Instant.parse("2026-09-21T10:00:00Z")));
        assertEquals("ORDERS_PAUSED", paused.code());
    }

    @Test
    void acceptingOrderDecisionCoversOpenAndClosedStates() {
        List<StoreBusinessHour> hours = everyDayClosed();
        hours.set(0, hour(DayOfWeek.MONDAY, false, false, "09:00", "17:00"));
        when(repository.findByStore_IdOrderByDayOfWeekAsc(store.getId())).thenReturn(hours);

        assertDoesNotThrow(() -> service().assertCanAcceptOrder(store, Instant.parse("2026-09-21T10:00:00Z")));
        store.setAcceptingOrders(false);
        assertEquals("ORDERS_PAUSED", assertThrows(OrderUnavailableException.class,
                () -> service().assertCanAcceptOrder(store, Instant.parse("2026-09-21T10:00:00Z"))).code());

        store.setAcceptingOrders(true);
        assertEquals("STORE_CLOSED", assertThrows(OrderUnavailableException.class,
                () -> service().assertCanAcceptOrder(store, Instant.parse("2026-09-21T05:00:00Z"))).code());
    }

    @Test
    void updateRequiresSevenUniqueValidDaysAndUsesOwnerAuthorization() {
        when(storeService.ownedStore(store.getId())).thenReturn(store);
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        List<BusinessHoursDtos.DayRequest> days = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            days.add(new BusinessHoursDtos.DayRequest(day, false, false, LocalTime.of(9, 0), LocalTime.of(2, 0)));
        }

        service().update(store.getId(), new BusinessHoursDtos.UpdateRequest(days));

        verify(storeService).ownedStore(store.getId());
        verify(repository).saveAll(any());
        assertThrows(IllegalArgumentException.class, () -> service().update(store.getId(),
                new BusinessHoursDtos.UpdateRequest(days.subList(0, 6))));
        List<BusinessHoursDtos.DayRequest> duplicate = new ArrayList<>(days);
        duplicate.set(1, duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> service().update(store.getId(),
                new BusinessHoursDtos.UpdateRequest(duplicate)));
    }

    @Test
    void secondSaveUpdatesExistingRowsInPlaceInsteadOfReinserting() {
        // Regression: delete + re-insert violated UNIQUE (store_id, day_of_week) on PostgreSQL
        // (inserts flush before deletes), so hours could be saved once but never changed.
        when(storeService.ownedStore(store.getId())).thenReturn(store);
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        List<StoreBusinessHour> existing = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            StoreBusinessHour hour = new StoreBusinessHour();
            hour.setStore(store);
            hour.setDayOfWeek(day);
            hour.setClosed(true);
            existing.add(hour);
        }
        when(repository.findByStore_IdOrderByDayOfWeekAsc(store.getId())).thenReturn(existing);
        List<BusinessHoursDtos.DayRequest> days = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            days.add(new BusinessHoursDtos.DayRequest(day, false, true, null, null));
        }

        service().update(store.getId(), new BusinessHoursDtos.UpdateRequest(days));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<StoreBusinessHour>> saved = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertEquals(7, saved.getValue().size());
        for (int i = 0; i < 7; i++) {
            assertSame(existing.get(i), saved.getValue().get(i), "existing row reused for " + existing.get(i).getDayOfWeek());
            assertFalse(saved.getValue().get(i).isClosed());
            assertTrue(saved.getValue().get(i).isOpen24Hours());
        }
    }

    private BusinessHoursService service() {
        return new BusinessHoursService(repository, storeService);
    }

    private static Store store(String timezone) {
        Store store = new Store();
        store.setId(UUID.randomUUID());
        store.setTimezone(timezone);
        return store;
    }

    private static List<StoreBusinessHour> everyDayClosed() {
        List<StoreBusinessHour> hours = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) hours.add(hour(day, true, false, null, null));
        return hours;
    }

    private static StoreBusinessHour hour(DayOfWeek day, boolean closed, boolean open24, String open, String close) {
        StoreBusinessHour hour = new StoreBusinessHour();
        hour.setDayOfWeek(day);
        hour.setClosed(closed);
        hour.setOpen24Hours(open24);
        hour.setOpenTime(open == null ? null : LocalTime.parse(open));
        hour.setCloseTime(close == null ? null : LocalTime.parse(close));
        return hour;
    }
}
