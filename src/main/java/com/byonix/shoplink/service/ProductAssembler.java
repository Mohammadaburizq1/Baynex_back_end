package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.ImageDtos;
import com.byonix.shoplink.api.dto.ModifierDtos;
import com.byonix.shoplink.api.dto.ProductDtos;
import com.byonix.shoplink.api.dto.VariantDtos;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.ProductImage;
import com.byonix.shoplink.domain.entity.ProductModifierGroup;
import com.byonix.shoplink.domain.entity.ProductOption;
import com.byonix.shoplink.domain.entity.ProductVariant;
import com.byonix.shoplink.repository.ProductImageRepository;
import com.byonix.shoplink.repository.ProductModifierGroupRepository;
import com.byonix.shoplink.repository.ProductOptionRepository;
import com.byonix.shoplink.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds product responses together with everything that hangs off a product (options, variants
 * and add-on groups). A page of products is assembled with one query per relation for the whole page — never
 * one per product — which is why this isn't just a per-entity mapper method.
 *
 * Two views: {@code dashboard} carries exact stock; {@code storefront} never does (customers only
 * learn {@code inStock}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductAssembler {
    private final ProductOptionRepository optionRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductModifierGroupRepository modifierGroupRepository;
    private final ProductImageRepository imageRepository;

    public ProductDtos.ProductResponse dashboard(Product product) {
        return assemble(List.of(product), false).get(0);
    }

    public List<ProductDtos.ProductResponse> dashboard(Collection<Product> products) {
        return assemble(products, false);
    }

    public ProductDtos.ProductResponse storefront(Product product) {
        return assemble(List.of(product), true).get(0);
    }

    public List<ProductDtos.ProductResponse> storefront(Collection<Product> products) {
        return assemble(products, true);
    }

    /** The variant editor's view of one product: its options and every variant, with exact stock. */
    public VariantDtos.VariantsResponse variantsOf(Product product) {
        Data data = load(List.of(product));
        return new VariantDtos.VariantsResponse(
                optionResponses(data.options.getOrDefault(product.getId(), List.of())),
                variantResponses(data.variants.getOrDefault(product.getId(), List.of()), false));
    }

    /** The add-on editor's view of one product: its modifier groups and their options. */
    public List<ModifierDtos.ModifierGroupResponse> modifierGroupsOf(Product product) {
        return modifierGroupResponses(load(List.of(product)).groups.getOrDefault(product.getId(), List.of()));
    }

    // ── assembly ───────────────────────────────────────────────────────────────────────────────

    private record Data(Map<UUID, List<ProductOption>> options, Map<UUID, List<ProductVariant>> variants,
                        Map<UUID, List<ProductModifierGroup>> groups, Map<UUID, List<ProductImage>> images) {}

    /** One query per relation for the whole page. Options/variants are only looked up for products that have them. */
    private Data load(Collection<Product> products) {
        Map<UUID, List<ProductOption>> options = new HashMap<>();
        Map<UUID, List<ProductVariant>> variants = new HashMap<>();
        Map<UUID, List<ProductModifierGroup>> groups = new HashMap<>();
        Map<UUID, List<ProductImage>> images = new HashMap<>();
        if (products.isEmpty()) {
            return new Data(options, variants, groups, images);
        }
        List<UUID> variantIds = products.stream().filter(Product::isHasVariants).map(Product::getId).toList();
        if (!variantIds.isEmpty()) {
            for (ProductOption o : optionRepository.findWithValuesByProductIdIn(variantIds)) {
                options.computeIfAbsent(o.getProduct().getId(), k -> new ArrayList<>()).add(o);
            }
            for (ProductVariant v : variantRepository.findWithValuesByProductIdIn(variantIds)) {
                variants.computeIfAbsent(v.getProduct().getId(), k -> new ArrayList<>()).add(v);
            }
        }
        List<UUID> allIds = products.stream().map(Product::getId).toList();
        for (ProductModifierGroup g : modifierGroupRepository.findWithOptionsByProductIdIn(allIds)) {
            groups.computeIfAbsent(g.getProduct().getId(), k -> new ArrayList<>()).add(g);
        }
        for (ProductImage i : imageRepository.findByProduct_IdInOrderBySortOrderAsc(allIds)) {
            images.computeIfAbsent(i.getProduct().getId(), k -> new ArrayList<>()).add(i);
        }
        return new Data(options, variants, groups, images);
    }

    private List<ProductDtos.ProductResponse> assemble(Collection<Product> products, boolean storefront) {
        Data data = load(products);
        return products.stream()
                .map(p -> toResponse(p, data.options.getOrDefault(p.getId(), List.of()),
                        data.variants.getOrDefault(p.getId(), List.of()),
                        data.groups.getOrDefault(p.getId(), List.of()), data.images.getOrDefault(p.getId(), List.of()), storefront))
                .toList();
    }

    private ProductDtos.ProductResponse toResponse(Product p, List<ProductOption> options, List<ProductVariant> variants,
                                                   List<ProductModifierGroup> groups, List<ProductImage> images,
                                                   boolean storefront) {
        BigDecimal price = p.getPrice();
        BigDecimal salePrice = p.getSalePrice();
        Integer stock = p.getStock();
        boolean inStock;

        if (p.isHasVariants() && !variants.isEmpty()) {
            // "From" pricing: the cheapest variant a customer could actually buy, else the cheapest overall.
            Comparator<ProductVariant> cheapest = Comparator.comparing(ProductVariant::effectivePrice);
            ProductVariant from = variants.stream().filter(ProductVariant::isAvailable).min(cheapest)
                    .orElseGet(() -> variants.stream().min(cheapest).orElseThrow());
            price = from.getPrice();
            salePrice = from.getSalePrice();
            List<ProductVariant> tracked = variants.stream().filter(v -> v.getStock() != null).toList();
            stock = tracked.isEmpty() ? null : tracked.stream().mapToInt(ProductVariant::getStock).sum();
            inStock = p.isAvailable() && variants.stream().anyMatch(ProductVariant::inStock);
        } else {
            inStock = p.isAvailable() && (p.getStock() == null || p.getStock() > 0);
        }

        return new ProductDtos.ProductResponse(p.getId(), p.getStore().getId(),
                p.getCategory() == null ? null : p.getCategory().getId(),
                p.getNameEn(), p.getNameAr(), p.getSlug(), p.getDescription(), price, salePrice, p.getCurrency(),
                p.getImageUrl(), p.getGalleryJson(), p.getSku(), p.getProductType(), p.isAvailable(), p.isFeatured(),
                p.getSortOrder(), storefront ? null : stock, inStock, p.isHasVariants(),
                optionResponses(options), variantResponses(variants, storefront), modifierGroupResponses(groups),
                storefront ? null : p.getLowStockThreshold(),
                images.stream().map(i -> new ImageDtos.ImageResponse(i.getId(), i.getUrl(), i.getAltText())).toList());
    }

    private static List<ModifierDtos.ModifierGroupResponse> modifierGroupResponses(List<ProductModifierGroup> groups) {
        return groups.stream()
                .sorted(Comparator.comparingInt(ProductModifierGroup::getSortOrder).thenComparing(ProductModifierGroup::getName))
                .map(g -> new ModifierDtos.ModifierGroupResponse(g.getId(), g.getName(), g.getMinSelect(), g.getMaxSelect(),
                        g.getOptions().stream()
                                .sorted(Comparator.comparingInt((com.byonix.shoplink.domain.entity.ProductModifierOption o) -> o.getSortOrder())
                                        .thenComparing(com.byonix.shoplink.domain.entity.ProductModifierOption::getName))
                                .map(o -> new ModifierDtos.ModifierOptionResponse(o.getId(), o.getName(), o.getPriceDelta(),
                                        o.isPreselected(), o.isAvailable()))
                                .toList()))
                .toList();
    }

    private static List<VariantDtos.OptionResponse> optionResponses(List<ProductOption> options) {
        return options.stream()
                .sorted(Comparator.comparingInt(ProductOption::getSortOrder).thenComparing(ProductOption::getName))
                .map(o -> new VariantDtos.OptionResponse(o.getId(), o.getName(),
                        o.getValues().stream()
                                .sorted(Comparator.comparingInt((com.byonix.shoplink.domain.entity.ProductOptionValue v) -> v.getSortOrder())
                                        .thenComparing(com.byonix.shoplink.domain.entity.ProductOptionValue::getLabel))
                                .map(v -> new VariantDtos.OptionValueResponse(v.getId(), v.getLabel()))
                                .toList()))
                .toList();
    }

    private static List<VariantDtos.VariantResponse> variantResponses(List<ProductVariant> variants, boolean storefront) {
        return variants.stream()
                .sorted(Comparator.comparingInt(ProductVariant::getSortOrder).thenComparing(ProductVariant::label))
                .map(v -> new VariantDtos.VariantResponse(v.getId(), v.label(),
                        v.orderedValues().stream().map(ov -> ov.getLabel()).toList(),
                        v.getSku(), v.getPrice(), v.getSalePrice(), storefront ? null : v.getStock(),
                        v.inStock(), v.isAvailable(), v.getSortOrder(), storefront ? null : v.getLowStockThreshold()))
                .toList();
    }
}
