package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class PermissionDtos {
    private PermissionDtos() {}

    public record PermissionGrant(@NotNull DashboardSection section, @NotNull PermissionLevel level) {}

    public record PermissionGrantsRequest(@NotEmpty List<@Valid PermissionGrant> grants) {}
}
