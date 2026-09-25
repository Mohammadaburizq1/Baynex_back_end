package com.byonix.shoplink.api.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

// JsonNode here is Jackson 3's (tools.jackson.databind) — what Spring Boot 4's MVC message
// converters bind request/response bodies with. The Jackson 2 type (com.fasterxml...) can't be
// deserialized by that mapper at all ("abstract types either need to be mapped to concrete
// types"), so it must never appear on a DTO. See StoreThemeContentService for the bridge to the
// Jackson 2 JsonNode the entities persist through Hibernate.
public final class ThemeContentDtos {
    private ThemeContentDtos() {}

    public record SaveDraftRequest(@NotNull UUID storeId, @NotNull JsonNode content) {}

    public record PublishRequest(@NotNull UUID storeId) {}

    public record RestoreVersionRequest(@NotNull UUID storeId, @NotNull Integer version) {}

    public record DashboardContentResponse(
            UUID storeId,
            JsonNode draftContent,
            JsonNode publishedContent,
            int publishedVersion,
            Instant draftUpdatedAt,
            Instant publishedAt) {}

    public record VersionSummary(int version, Instant publishedAt) {}
}
