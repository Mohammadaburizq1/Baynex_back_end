package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.domain.entity.StoreBusinessHour;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

public final class BusinessHoursDtos {
    private BusinessHoursDtos() {}

    public record DayRequest(
            @NotNull DayOfWeek dayOfWeek,
            boolean closed,
            boolean open24Hours,
            LocalTime openTime,
            LocalTime closeTime) {}

    public record UpdateRequest(@NotNull @Valid List<@Valid DayRequest> days) {}

    public record DayResponse(DayOfWeek dayOfWeek, boolean closed, boolean open24Hours,
                              LocalTime openTime, LocalTime closeTime) {
        public static DayResponse from(StoreBusinessHour hour) {
            return new DayResponse(hour.getDayOfWeek(), hour.isClosed(), hour.isOpen24Hours(),
                    hour.getOpenTime(), hour.getCloseTime());
        }
    }

    public record Response(String status, boolean configured, String timezone, DayOfWeek currentDay,
                           LocalTime closesAt, LocalTime opensAt, List<DayResponse> days,
                           boolean acceptingOrders, boolean canAcceptOrders) {}
}
