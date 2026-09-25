package com.byonix.shoplink.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Add-ons a customer can pick when ordering a product ("Extras: cheese +1.50"). */
public final class ModifierDtos {
    private ModifierDtos() {}

    // ── responses ──────────────────────────────────────────────────────────────────────────────

    public record ModifierOptionResponse(UUID id, String name, BigDecimal priceDelta, boolean preselected,
                                         boolean available) {}

    /** minSelect 0 = optional, >= 1 = required; the customer picks between minSelect and maxSelect options. */
    public record ModifierGroupResponse(UUID id, String name, int minSelect, int maxSelect,
                                        List<ModifierOptionResponse> options) {}

    // ── requests ───────────────────────────────────────────────────────────────────────────────

    /** id null = new option; an existing id keeps it through a rename or repricing. */
    public record ModifierOptionRequest(
            UUID id,
            @NotBlank @Size(max = 80) String name,
            @NotNull @PositiveOrZero BigDecimal priceDelta,
            Boolean preselected,
            Boolean available) {}

    /** minSelect defaults to 0 and maxSelect to 1; maxSelect is capped at the number of options. */
    public record ModifierGroupRequest(
            UUID id,
            @NotBlank @Size(max = 80) String name,
            @Min(0) Integer minSelect,
            @Min(1) Integer maxSelect,
            @NotEmpty @Size(max = 30) List<@Valid ModifierOptionRequest> options) {}

    /** Replace-all: what's listed is what the product ends up with. Empty/absent = no add-ons. */
    public record SaveModifierGroupsRequest(@Size(max = 10) List<@Valid ModifierGroupRequest> groups) {}
}
