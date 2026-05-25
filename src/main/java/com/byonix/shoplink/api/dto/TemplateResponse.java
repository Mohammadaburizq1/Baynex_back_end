package com.byonix.shoplink.api.dto;

import java.util.UUID;

public record TemplateResponse(UUID id, String categorySlug, String subCategorySlug, String templateKey, String name,
                               String description, String previewImageUrl, boolean defaultTemplate, boolean active) {
}
