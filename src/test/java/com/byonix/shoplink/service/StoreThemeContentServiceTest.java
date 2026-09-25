package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.ThemeContentDtos.DashboardContentResponse;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.entity.StoreThemeContent;
import com.byonix.shoplink.domain.entity.StoreThemeContentVersion;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.repository.StoreThemeContentRepository;
import com.byonix.shoplink.repository.StoreThemeContentVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * M1-10: draft must not change live published content; publish copies draft → published;
 * restore writes into draft only.
 */
@ExtendWith(MockitoExtension.class)
class StoreThemeContentServiceTest {
    private static final ObjectMapper ENTITY_JSON = new ObjectMapper();
    private static final JsonMapper WEB_JSON = JsonMapper.builder().build();

    @Mock StoreThemeContentRepository repository;
    @Mock StoreThemeContentVersionRepository versionRepository;
    @Mock StoreService storeService;
    @Mock CurrentUserService currentUser;
    @InjectMocks StoreThemeContentService service;

    private Store store;
    private StoreThemeContent row;

    @BeforeEach
    void setUp() {
        store = new Store();
        store.setId(UUID.randomUUID());
        row = new StoreThemeContent();
        row.setStore(store);
        row.setPublishedVersion(0);
    }

    private void stubAccessibleStore() {
        when(storeService.accessibleStore(store.getId())).thenReturn(store);
        when(repository.findByStore_Id(store.getId())).thenReturn(Optional.of(row));
    }

    @Test
    void saveDraftDoesNotChangePublishedContent() throws Exception {
        stubAccessibleStore();
        ObjectNode published = ENTITY_JSON.createObjectNode().put("heroTitle", "Live A");
        row.setPublishedContent(published);
        row.setPublishedVersion(1);

        tools.jackson.databind.JsonNode draftPayload = WEB_JSON.readTree("{\"heroTitle\":\"Draft B\"}");
        DashboardContentResponse res = service.saveDraft(store.getId(), draftPayload);

        assertEquals("Draft B", row.getDraftContent().get("heroTitle").asText());
        assertEquals("Live A", row.getPublishedContent().get("heroTitle").asText());
        assertEquals(1, row.getPublishedVersion());
        assertEquals("Draft B", res.draftContent().get("heroTitle").asString());
        assertEquals("Live A", res.publishedContent().get("heroTitle").asString());
        verify(currentUser).ensureSectionAccess(store, DashboardSection.STOREFRONT, PermissionLevel.EDIT);
    }

    @Test
    void publishCopiesDraftToPublishedAndCreatesVersion() throws Exception {
        stubAccessibleStore();
        row.setDraftContent(ENTITY_JSON.createObjectNode().put("heroTitle", "Draft B"));
        row.setPublishedContent(ENTITY_JSON.createObjectNode().put("heroTitle", "Live A"));
        row.setPublishedVersion(1);

        DashboardContentResponse res = service.publish(store.getId());

        assertEquals("Draft B", row.getPublishedContent().get("heroTitle").asText());
        assertEquals(2, row.getPublishedVersion());
        assertEquals(2, res.publishedVersion());
        assertEquals("Draft B", res.publishedContent().get("heroTitle").asString());

        ArgumentCaptor<StoreThemeContentVersion> captor = ArgumentCaptor.forClass(StoreThemeContentVersion.class);
        verify(versionRepository).save(captor.capture());
        assertEquals(2, captor.getValue().getVersion());
        assertEquals("Draft B", captor.getValue().getContent().get("heroTitle").asText());
    }

    @Test
    void restoreWritesPastVersionIntoDraftOnly() throws Exception {
        stubAccessibleStore();
        row.setDraftContent(ENTITY_JSON.createObjectNode().put("heroTitle", "Current draft"));
        row.setPublishedContent(ENTITY_JSON.createObjectNode().put("heroTitle", "Live B"));
        row.setPublishedVersion(2);

        StoreThemeContentVersion v1 = new StoreThemeContentVersion();
        v1.setStore(store);
        v1.setVersion(1);
        v1.setContent(ENTITY_JSON.createObjectNode().put("heroTitle", "Live A"));
        when(versionRepository.findByStore_IdAndVersion(store.getId(), 1)).thenReturn(Optional.of(v1));

        DashboardContentResponse res = service.restoreVersion(store.getId(), 1);

        assertEquals("Live A", row.getDraftContent().get("heroTitle").asText());
        assertEquals("Live B", row.getPublishedContent().get("heroTitle").asText());
        assertEquals(2, row.getPublishedVersion());
        assertEquals("Live A", res.draftContent().get("heroTitle").asString());
        assertEquals("Live B", res.publishedContent().get("heroTitle").asString());
    }

    @Test
    void getPublishedReturnsNullUntilFirstPublish() {
        row.setDraftContent(ENTITY_JSON.createObjectNode().put("heroTitle", "Unpublished"));
        when(storeService.publicStore(any())).thenReturn(store);
        when(repository.findByStore_Id(store.getId())).thenReturn(Optional.of(row));

        assertNull(service.getPublished("any-slug"));
    }
}
