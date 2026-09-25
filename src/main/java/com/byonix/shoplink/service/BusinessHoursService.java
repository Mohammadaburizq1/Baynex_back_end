package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.BusinessHoursDtos;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.StoreBusinessHour;
import com.byonix.shoplink.repository.StoreBusinessHourRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BusinessHoursService {
    private final StoreBusinessHourRepository repository;
    private final StoreService storeService;

    public BusinessHoursDtos.Response merchantHours(UUID storeId) {
        return response(storeService.ownedStore(storeId), Instant.now());
    }

    @Transactional
    public BusinessHoursDtos.Response update(UUID storeId, BusinessHoursDtos.UpdateRequest request) {
        Store store = storeService.ownedStore(storeId);
        Map<DayOfWeek, BusinessHoursDtos.DayRequest> requested = validate(request);
        // Update each day's row in place. Deleting and re-inserting in one transaction fails on the
        // second save: Hibernate flushes the new rows before the deletes, violating
        // UNIQUE (store_id, day_of_week) — merchants could set hours once but never change them.
        Map<DayOfWeek, StoreBusinessHour> existing = new EnumMap<>(DayOfWeek.class);
        for (StoreBusinessHour hour : repository.findByStore_IdOrderByDayOfWeekAsc(storeId)) {
            existing.put(hour.getDayOfWeek(), hour);
        }
        List<StoreBusinessHour> saved = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            BusinessHoursDtos.DayRequest input = requested.get(day);
            StoreBusinessHour hour = existing.getOrDefault(day, new StoreBusinessHour());
            hour.setStore(store);
            hour.setDayOfWeek(day);
            hour.setClosed(input.closed());
            hour.setOpen24Hours(input.open24Hours());
            hour.setOpenTime(input.openTime());
            hour.setCloseTime(input.closeTime());
            saved.add(hour);
        }
        repository.saveAll(saved);
        return response(store, Instant.now());
    }

    public BusinessHoursDtos.Response publicHours(String slug) {
        return response(storeService.publicStore(slug), Instant.now());
    }

    public void assertCanAcceptOrder(Store store) {
        assertCanAcceptOrder(store, Instant.now());
    }

    void assertCanAcceptOrder(Store store, Instant now) {
        if (!store.isAcceptingOrders()) {
            throw new OrderUnavailableException("ORDERS_PAUSED", "Online ordering is temporarily paused.");
        }
        BusinessHoursDtos.Response current = response(store, now);
        if ("CLOSED".equals(current.status())) {
            throw new OrderUnavailableException("STORE_CLOSED", "This store is currently closed.");
        }
    }

    BusinessHoursDtos.Response response(Store store, Instant now) {
        List<StoreBusinessHour> stored = repository.findByStore_IdOrderByDayOfWeekAsc(store.getId());
        Map<DayOfWeek, StoreBusinessHour> byDay = new EnumMap<>(DayOfWeek.class);
        stored.forEach(hour -> byDay.put(hour.getDayOfWeek(), hour));
        List<BusinessHoursDtos.DayResponse> days = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            StoreBusinessHour hour = byDay.get(day);
            days.add(hour == null
                    ? new BusinessHoursDtos.DayResponse(day, true, false, null, null)
                    : BusinessHoursDtos.DayResponse.from(hour));
        }
        ZonedDateTime local = now.atZone(ZoneId.of(store.getTimezone()));
        if (stored.isEmpty()) {
            return new BusinessHoursDtos.Response("NOT_CONFIGURED", false, store.getTimezone(), local.getDayOfWeek(), null, null, days,
                    store.isAcceptingOrders(), store.isAcceptingOrders());
        }
        Status status = calculate(byDay, local);
        return new BusinessHoursDtos.Response(status.open ? "OPEN" : "CLOSED", true, store.getTimezone(),
                local.getDayOfWeek(), status.closesAt, status.opensAt, days, store.isAcceptingOrders(),
                store.isAcceptingOrders() && status.open);
    }

    private Map<DayOfWeek, BusinessHoursDtos.DayRequest> validate(BusinessHoursDtos.UpdateRequest request) {
        if (request.days().size() != 7) {
            throw new IllegalArgumentException("Business hours must include exactly one entry for each day");
        }
        Map<DayOfWeek, BusinessHoursDtos.DayRequest> byDay = new EnumMap<>(DayOfWeek.class);
        for (BusinessHoursDtos.DayRequest day : request.days()) {
            if (byDay.put(day.dayOfWeek(), day) != null) {
                throw new IllegalArgumentException("Business hours contain a duplicate day");
            }
            if (day.closed()) {
                if (day.open24Hours() || day.openTime() != null || day.closeTime() != null) {
                    throw new IllegalArgumentException("A closed day cannot contain opening times");
                }
            } else if (day.open24Hours()) {
                if (day.openTime() != null || day.closeTime() != null) {
                    throw new IllegalArgumentException("24-hour days cannot contain opening times");
                }
            } else {
                if (day.openTime() == null || day.closeTime() == null || day.openTime().equals(day.closeTime())) {
                    throw new IllegalArgumentException("An open day requires different opening and closing times");
                }
            }
        }
        if (byDay.size() != 7) {
            throw new IllegalArgumentException("Business hours must include all seven days");
        }
        return byDay;
    }

    private Status calculate(Map<DayOfWeek, StoreBusinessHour> byDay, ZonedDateTime local) {
        DayOfWeek today = local.getDayOfWeek();
        LocalTime time = local.toLocalTime();
        StoreBusinessHour current = byDay.get(today);
        if (current != null && !current.isClosed()) {
            if (current.isOpen24Hours()) return new Status(true, null, null);
            if (isInside(current, time)) return new Status(true, current.getCloseTime(), null);
        }
        DayOfWeek previousDay = today.minus(1);
        StoreBusinessHour previous = byDay.get(previousDay);
        if (previous != null && !previous.isClosed() && !previous.isOpen24Hours()
                && previous.getCloseTime().isBefore(previous.getOpenTime())
                && time.isBefore(previous.getCloseTime())) {
            return new Status(true, previous.getCloseTime(), null);
        }
        for (int offset = 0; offset < 7; offset++) {
            DayOfWeek candidateDay = today.plus(offset);
            StoreBusinessHour candidate = byDay.get(candidateDay);
            if (candidate != null && !candidate.isClosed()) {
                if (candidate.isOpen24Hours()) return new Status(false, null, null);
                LocalTime opening = candidate.getOpenTime();
                if (offset > 0 || opening.isAfter(time)) return new Status(false, null, opening);
            }
        }
        return new Status(false, null, null);
    }

    private boolean isInside(StoreBusinessHour hour, LocalTime time) {
        if (hour.getCloseTime().isAfter(hour.getOpenTime())) {
            return !time.isBefore(hour.getOpenTime()) && time.isBefore(hour.getCloseTime());
        }
        return !time.isBefore(hour.getOpenTime());
    }

    private record Status(boolean open, LocalTime closesAt, LocalTime opensAt) {}
}
