package com.byonix.shoplink.api.dto;

import com.byonix.shoplink.domain.enums.ProductType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class ProductDtos {
    private ProductDtos() {}

    public record ProductRequest(
            @NotNull UUID storeId,
            UUID categoryId,
            @NotBlank @Size(max = 180) String nameEn,
            @Size(max = 180) String nameAr,
            @NotBlank @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String slug,
            @Size(max = 2000) String description,
            @NotNull @PositiveOrZero BigDecimal price,
            @PositiveOrZero BigDecimal salePrice,
            @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @Pattern(regexp = "^$|^https?://.{3,480}$", message = "Invalid image URL") String imageUrl,
            String galleryJson,
            @Size(max = 120) String sku,
            ProductType productType,
            Boolean available,
            Boolean featured,
            int sortOrder,
            // Null = don't track stock for this product. Present but rejected client-side for
            // SERVICE products — see toProductRequest in products.ts.
            @PositiveOrZero Integer stock,
            // Null = the store-wide default. Applies to the product itself (or to nothing once it has variants).
            @PositiveOrZero Integer lowStockThreshold) {}

    /**
     * For a product with variants, price/salePrice are the cheapest variant's ("from" pricing) and
     * stock the sum of its tracked variants — derived on read, see ProductAssembler. inStock is the
     * one availability signal the public storefront gets (it never sees exact stock).
     */
    public record ProductResponse(UUID id, UUID storeId, UUID categoryId, String nameEn, String nameAr, String slug,
                                  String description, BigDecimal price, BigDecimal salePrice, String currency,
                                  String imageUrl, String galleryJson, String sku, ProductType productType,
                                  boolean available, boolean featured, int sortOrder, Integer stock,
                                  boolean inStock, boolean hasVariants,
                                  List<VariantDtos.OptionResponse> options,
                                  List<VariantDtos.VariantResponse> variants,
                                  List<ModifierDtos.ModifierGroupResponse> modifierGroups,
                                  Integer lowStockThreshold,
                                  List<ImageDtos.ImageResponse> images) {}
}
