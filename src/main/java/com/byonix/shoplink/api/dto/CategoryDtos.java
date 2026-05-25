package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.domain.enums.CategoryType;
import jakarta.validation.constraints.*;

import java.util.UUID;

public final class CategoryDtos {
    private CategoryDtos() {}

    public record CategoryRequest(
            UUID storeId,
            UUID parentId,
            @NotBlank @Size(max = 160) String nameEn,
            @Size(max = 160) String nameAr,
            @NotBlank @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String slug,
            @Size(max = 800) String description,
            @Size(max = 120) String icon,
            @Pattern(regexp = "^$|^https?://.{3,480}$", message = "Invalid image URL") String imageUrl,
            int sortOrder,
            Boolean active,
            @NotNull CategoryType categoryType) {}

    public record CategoryResponse(UUID id, UUID storeId, UUID parentId, String nameEn, String nameAr, String slug,
                                   String description, String icon, String imageUrl, int sortOrder, boolean active,
                                   CategoryType categoryType) {}
}
