package com.byonix.shoplink.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Product options ("Size", "Color") and the variants (M / Black) built from their values. */
public final class VariantDtos {
    private VariantDtos() {}

    // ── responses ──────────────────────────────────────────────────────────────────────────────

    public record OptionValueResponse(UUID id, String label) {}

    public record OptionResponse(UUID id, String name, List<OptionValueResponse> values) {}

    /**
     * selection has one label per option, in option order (["M","Black"]); label is those joined for
     * display. stock is the exact count for the merchant dashboard and null on the public storefront,
     * which only ever learns inStock.
     */
    public record VariantResponse(UUID id, String label, List<String> selection, String sku, BigDecimal price,
                                  BigDecimal salePrice, Integer stock, boolean inStock, boolean available,
                                  int sortOrder, Integer lowStockThreshold) {}

    public record VariantsResponse(List<OptionResponse> options, List<VariantResponse> variants) {}

    // ── requests ───────────────────────────────────────────────────────────────────────────────

    /** id null = a new value; an existing id keeps the value (and every variant using it) through a rename. */
    public record OptionValueRequest(UUID id, @NotBlank @Size(max = 80) String label) {}

    public record OptionRequest(
            UUID id,
            @NotBlank @Size(max = 60) String name,
            @NotEmpty @Size(max = 50) List<@Valid OptionValueRequest> values) {}

    /**
     * selection: one label per option, in the order the options are listed in the same request.
     * stock is applied to a NEW variant only — an existing variant's live count changes through
     * inventory adjustments, so saving a stale editor can never overwrite units sold in the meantime.
     * Null stock = not tracked.
     */
    public record VariantRequest(
            UUID id,
            @NotEmpty @Size(max = 3) List<@NotBlank @Size(max = 80) String> selection,
            @Size(max = 120) String sku,
            @NotNull @PositiveOrZero BigDecimal price,
            @PositiveOrZero BigDecimal salePrice,
            @PositiveOrZero Integer stock,
            Boolean available,
            @PositiveOrZero Integer lowStockThreshold) {}

    /** Replace-all: what's listed is what the product ends up with. Empty options = no variants. */
    public record SaveVariantsRequest(
            @Size(max = 3) List<@Valid OptionRequest> options,
            @Size(max = 200) List<@Valid VariantRequest> variants) {}
}
