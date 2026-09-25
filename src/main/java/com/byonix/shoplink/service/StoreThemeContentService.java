package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.ThemeContentDtos.DashboardContentResponse;
import com.byonix.shoplink.api.dto.ThemeContentDtos.VersionSummary;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.StoreThemeContent;
import com.byonix.shoplink.domain.entity.StoreThemeContentVersion;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.repository.StoreThemeContentRepository;
import com.byonix.shoplink.repository.StoreThemeContentVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityNotFoundException;
import tools.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Content is opaque JSON end to end — this service never interprets TemplateContent /
 * ClothingTemplateContent shape, it just persists whatever the dashboard sends and hands it
 * back. That's what lets both frontend content systems share this one table.
 *
 * Two unrelated JsonNode types meet here: the entities persist theirs through Hibernate's Jackson 2
 * format mapper (com.fasterxml.jackson.databind.JsonNode, imported below), while Spring Boot 4's MVC
 * layer binds request/response bodies with Jackson 3 (tools.jackson.databind.JsonNode, written out
 * in full). The public methods speak Jackson 3 to the controllers and translate at the edges, via
 * plain JSON text, so nothing else in the service has to care.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreThemeContentService {
    private static final ObjectMapper ENTITY_JSON = new ObjectMapper();
    private static final JsonMapper WEB_JSON = JsonMapper.builder().build();

    private final StoreThemeContentRepository repository;
    private final StoreThemeContentVersionRepository versionRepository;
    private final StoreService storeService;
    private final CurrentUserService currentUser;

    // Not read-only, despite being a "get": a store's first visit to the customize-storefront
    // page has no row yet, and getOrCreate() below needs to INSERT one. getOrCreate() is called
    // via a plain `this.` call (self-invocation bypasses the @Transactional proxy, per Spring's
    // documented AOP limitation), so it only ever runs inside whichever transaction its caller
    // already established — it must be this method that isn't read-only, not just getOrCreate().
    @Transactional
    public DashboardContentResponse getDashboard(UUID storeId) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.STOREFRONT, PermissionLevel.VIEW);
        return toResponse(getOrCreate(store));
    }

    @Transactional
    public DashboardContentResponse saveDraft(UUID storeId, tools.jackson.databind.JsonNode content) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.STOREFRONT, PermissionLevel.EDIT);
        StoreThemeContent row = getOrCreate(store);
        row.setDraftContent(toEntityJson(content));
        row.setDraftUpdatedAt(Instant.now());
        return toResponse(row);
    }

    @Transactional
    public DashboardContentResponse publish(UUID storeId) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.STOREFRONT, PermissionLevel.EDIT);
        StoreThemeContent row = getOrCreate(store);
        int newVersion = row.getPublishedVersion() + 1;
        Instant now = Instant.now();
        row.setPublishedContent(row.getDraftContent());
        row.setPublishedVersion(newVersion);
        row.setPublishedAt(now);

        StoreThemeContentVersion history = new StoreThemeContentVersion();
        history.setStore(store);
        history.setVersion(newVersion);
        history.setContent(row.getPublishedContent());
        history.setPublishedAt(now);
        versionRepository.save(history);

        return toResponse(row);
    }

    public List<VersionSummary> listVersions(UUID storeId) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.STOREFRONT, PermissionLevel.VIEW);
        return versionRepository.findByStore_IdOrderByVersionDesc(storeId).stream()
                .map(v -> new VersionSummary(v.getVersion(), v.getPublishedAt()))
                .toList();
    }

    // Restores a past published snapshot back into the draft only — the merchant still has to
    // click Publish to make it live, same as any other draft edit.
    @Transactional
    public DashboardContentResponse restoreVersion(UUID storeId, int version) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.STOREFRONT, PermissionLevel.EDIT);
        StoreThemeContentVersion history = versionRepository.findByStore_IdAndVersion(storeId, version)
                .orElseThrow(() -> new EntityNotFoundException("Version not found"));
        StoreThemeContent row = getOrCreate(store);
        row.setDraftContent(history.getContent());
        row.setDraftUpdatedAt(Instant.now());
        return toResponse(row);
    }

    /** Public storefront read — no auth, returns null until the merchant has published once. */
    public tools.jackson.databind.JsonNode getPublished(String slug) {
        Store store = storeService.publicStore(slug);
        return repository.findByStore_Id(store.getId())
                .map(StoreThemeContent::getPublishedContent)
                .map(StoreThemeContentService::toWebJson)
                .orElse(null);
    }

    @Transactional
    StoreThemeContent getOrCreate(Store store) {
        return repository.findByStore_Id(store.getId()).orElseGet(() -> {
            StoreThemeContent row = new StoreThemeContent();
            row.setStore(store);
            row.setDraftContent(JsonNodeFactory.instance.objectNode());
            return repository.save(row);
        });
    }

    private DashboardContentResponse toResponse(StoreThemeContent row) {
        return new DashboardContentResponse(
                row.getStore().getId(),
                toWebJson(row.getDraftContent()),
                toWebJson(row.getPublishedContent()),
                row.getPublishedVersion(),
                row.getDraftUpdatedAt(),
                row.getPublishedAt());
    }

    private static JsonNode toEntityJson(tools.jackson.databind.JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return ENTITY_JSON.readTree(WEB_JSON.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON content", e);
        }
    }

    private static tools.jackson.databind.JsonNode toWebJson(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return WEB_JSON.readTree(ENTITY_JSON.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored theme content is not valid JSON", e);
        }
    }
}
