package com.byonix.shoplink.api.dto;

import java.util.List;
import java.util.Map;

public record HomepageResponse(
        StoreDtos.StoreResponse store,
        String templateKey,
        List<Map<String, String>> navbarLinks,
        Map<String, Object> hero,
        List<ProductDtos.ProductResponse> popularDishes,
        List<CategoryDtos.CategoryResponse> categories,
        List<ProductDtos.ProductResponse> featuredProducts,
        Map<String, Object> openingHours,
        Map<String, Object> contact) {
}
