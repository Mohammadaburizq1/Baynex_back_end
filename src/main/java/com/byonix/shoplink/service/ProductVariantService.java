package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.VariantDtos;
import com.byonix.shoplink.common.ConflictException;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.ProductOption;
import com.byonix.shoplink.domain.entity.ProductOptionValue;
import com.byonix.shoplink.domain.entity.ProductVariant;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.InventoryAdjustmentReason;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.repository.ProductOptionRepository;
import com.byonix.shoplink.repository.ProductRepository;
import com.byonix.shoplink.repository.ProductVariantRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The merchant's options-and-variants editor: read the whole aggregate, or replace it wholesale.
 *
 * Replace-all with reconciliation, not create/update/delete endpoints per row, because the editor
 * naturally works on the whole matrix at once (add a "Color" option → a new column of variants).
 * Rows are matched to what already exists — by id, or failing that by name / label / combination of
 * option values — so a variant keeps its id (and therefore its order history and stock) through
 * edits; only variants that are no longer listed are deleted. Orders keep a copy of what they sold,
 * so deleting a variant never rewrites history.
 *
 * Order of work matters for the database: new options/values are written first (their ids are what
 * each variant's options_key is built from), then variants are re-pointed / created / deleted, and
 * only then are the options and values that are no longer listed removed, so nothing is ever deleted
 * while a surviving variant still references it.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductVariantService {
    static final int MAX_OPTIONS = 3;
    static final int MAX_VARIANTS = 200;

    private final ProductRepository productRepository;
    private final ProductOptionRepository optionRepository;
    private final ProductVariantRepository variantRepository;
    private final CurrentUserService currentUser;
    private final ProductAssembler assembler;
    private final InventoryLedger ledger;

    public VariantDtos.VariantsResponse get(UUID productId) {
        Product product = loadProduct(productId);
        currentUser.ensureSectionAccess(product.getStore(), DashboardSection.PRODUCTS, PermissionLevel.VIEW);
        return assembler.variantsOf(product);
    }

    @Transactional
    public VariantDtos.VariantsResponse save(UUID productId, VariantDtos.SaveVariantsRequest request) {
        Product product = loadProduct(productId);
        currentUser.ensureSectionAccess(product.getStore(), DashboardSection.PRODUCTS, PermissionLevel.EDIT);

        List<VariantDtos.OptionRequest> optionReqs = request.options() == null ? List.of() : request.options();
        List<VariantDtos.VariantRequest> variantReqs = request.variants() == null ? List.of() : request.variants();
        validateShape(optionReqs, variantReqs);
        List<String> skus = validateVariants(optionReqs, variantReqs);

        List<ProductOption> existingOptions = optionRepository.findWithValuesByProductIdIn(List.of(productId));
        List<ProductVariant> existingVariants = variantRepository.findWithValuesByProductIdIn(List.of(productId));
        checkSkusAvailable(product, skus);

        // ── 1) options and values: create / rename / reorder in place ─────────────────────────
        Map<UUID, ProductOption> optionsById = new HashMap<>();
        Map<String, ProductOption> optionsByName = new HashMap<>();
        existingOptions.forEach(o -> {
            optionsById.put(o.getId(), o);
            optionsByName.put(normalize(o.getName()), o);
        });
        Set<UUID> keptOptionIds = new HashSet<>();
        List<ProductOption> finalOptions = new ArrayList<>();
        List<Map<String, ProductOptionValue>> finalValuesByLabel = new ArrayList<>();
        List<ProductOptionValue> removedValues = new ArrayList<>();

        for (int i = 0; i < optionReqs.size(); i++) {
            VariantDtos.OptionRequest or = optionReqs.get(i);
            ProductOption option = or.id() != null ? optionsById.get(or.id()) : optionsByName.get(normalize(or.name()));
            if (or.id() != null && option == null) {
                throw new EntityNotFoundException("Option not found");
            }
            if (option != null && !keptOptionIds.add(option.getId())) {
                throw new IllegalArgumentException("The option \"" + or.name().trim() + "\" is listed twice");
            }
            if (option == null) {
                option = new ProductOption();
                option.setStore(product.getStore());
                option.setProduct(product);
            }
            option.setName(or.name().trim());
            option.setSortOrder(i);

            Map<String, ProductOptionValue> byLabel = new HashMap<>();
            removedValues.addAll(syncValues(option, or.values(), byLabel));
            finalOptions.add(option);
            finalValuesByLabel.add(byLabel);
        }
        List<ProductOption> removedOptions = existingOptions.stream()
                .filter(o -> !keptOptionIds.contains(o.getId()))
                .toList();

        // Only genuinely new options are saved: an already-managed option must NOT go through
        // save() (that's a merge, which would swap its new values for copies and leave the ones
        // referenced below without ids). New values on existing options are persisted by the
        // cascade at flush, in place. The flush assigns every new id.
        for (ProductOption option : finalOptions) {
            if (option.getId() == null) {
                optionRepository.save(option);
            }
        }
        optionRepository.flush();

        // ── 2) variants: match, update or create, delete the rest ─────────────────────────────
        Map<UUID, ProductVariant> variantsById = new HashMap<>();
        Map<String, ProductVariant> variantsByKey = new HashMap<>();
        existingVariants.forEach(v -> {
            variantsById.put(v.getId(), v);
            variantsByKey.put(v.getOptionsKey(), v);
        });
        Set<UUID> usedVariantIds = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        List<ProductVariant> newVariants = new ArrayList<>();

        for (int i = 0; i < variantReqs.size(); i++) {
            VariantDtos.VariantRequest vr = variantReqs.get(i);
            List<ProductOptionValue> chosen = new ArrayList<>();
            for (int o = 0; o < finalOptions.size(); o++) {
                ProductOptionValue value = finalValuesByLabel.get(o).get(normalize(vr.selection().get(o)));
                if (value == null) {
                    throw new IllegalArgumentException("Variant " + (i + 1) + ": \"" + vr.selection().get(o)
                            + "\" isn't a value of the option \"" + finalOptions.get(o).getName() + "\"");
                }
                chosen.add(value);
            }
            String key = chosen.stream().map(v -> v.getId().toString()).collect(Collectors.joining(","));
            if (!seenKeys.add(key)) {
                throw new IllegalArgumentException("Two variants have the same combination: "
                        + chosen.stream().map(ProductOptionValue::getLabel).collect(Collectors.joining(" / ")));
            }

            ProductVariant variant = null;
            if (vr.id() != null) {
                variant = variantsById.get(vr.id());
                if (variant == null) {
                    throw new EntityNotFoundException("Variant not found");
                }
            } else {
                // No id sent (e.g. the editor regenerated its matrix): keep the existing variant for
                // this same combination rather than deleting it and creating a stranger.
                ProductVariant sameCombination = variantsByKey.get(key);
                if (sameCombination != null && !usedVariantIds.contains(sameCombination.getId())) {
                    variant = sameCombination;
                }
            }
            if (variant == null) {
                variant = new ProductVariant();
                variant.setStore(product.getStore());
                variant.setProduct(product);
                // Initial stock only — see VariantRequest: an existing variant's live count is never
                // overwritten by saving the editor.
                variant.setStock(vr.stock());
                newVariants.add(variant);
            } else if (!usedVariantIds.add(variant.getId())) {
                throw new IllegalArgumentException("The same variant is listed twice");
            }
            variant.setOptionsKey(key);
            variant.setSku(blankToNull(vr.sku()));
            variant.setPrice(vr.price());
            variant.setSalePrice(vr.salePrice());
            variant.setAvailable(vr.available() == null || vr.available());
            variant.setLowStockThreshold(vr.lowStockThreshold());
            variant.setSortOrder(i);
            variant.getOptionValues().clear();
            variant.getOptionValues().addAll(chosen);
        }
        variantRepository.deleteAll(existingVariants.stream().filter(v -> !usedVariantIds.contains(v.getId())).toList());
        variantRepository.saveAll(newVariants);
        variantRepository.flush();
        // A new variant's opening count starts its stock history.
        for (ProductVariant created : newVariants) {
            if (created.getStock() != null && created.getStock() > 0) {
                ledger.record(product.getStore(), product, created,
                        InventoryLedger.itemName(product.getNameEn(), created.label()),
                        created.getStock(), created.getStock(), InventoryAdjustmentReason.INITIAL, null, null);
            }
        }

        // ── 3) what's no longer listed: nothing references it any more ────────────────────────
        removedValues.forEach(v -> v.getOption().getValues().remove(v));
        optionRepository.deleteAll(removedOptions);

        product.setHasVariants(!variantReqs.isEmpty());
        optionRepository.flush();
        return assembler.variantsOf(product);
    }

    // ── option values ──────────────────────────────────────────────────────────────────────────

    /**
     * Brings one option's values in line with the request (renaming/reordering in place, adding new
     * ones), fills {@code finalByLabel} with the values that remain, and returns the ones that no
     * longer are — left attached for now, removed by the caller once no variant uses them.
     */
    private List<ProductOptionValue> syncValues(ProductOption option, List<VariantDtos.OptionValueRequest> requested,
                                                Map<String, ProductOptionValue> finalByLabel) {
        Map<UUID, ProductOptionValue> existingById = new HashMap<>();
        Map<String, ProductOptionValue> existingByLabel = new HashMap<>();
        option.getValues().forEach(v -> {
            existingById.put(v.getId(), v);
            existingByLabel.put(normalize(v.getLabel()), v);
        });
        List<ProductOptionValue> kept = new ArrayList<>();
        for (int j = 0; j < requested.size(); j++) {
            VariantDtos.OptionValueRequest vr = requested.get(j);
            ProductOptionValue value = vr.id() != null ? existingById.get(vr.id()) : existingByLabel.get(normalize(vr.label()));
            if (vr.id() != null && value == null) {
                throw new EntityNotFoundException("Option value not found");
            }
            if (value != null && kept.contains(value)) {
                throw new IllegalArgumentException("\"" + option.getName() + "\" lists a value twice");
            }
            if (value == null) {
                value = new ProductOptionValue();
                value.setOption(option);
                option.getValues().add(value);
            }
            value.setLabel(vr.label().trim());
            value.setSortOrder(j);
            kept.add(value);
            finalByLabel.put(normalize(vr.label()), value);
        }
        return option.getValues().stream().filter(v -> !kept.contains(v)).toList();
    }

    // ── validation ─────────────────────────────────────────────────────────────────────────────

    private void validateShape(List<VariantDtos.OptionRequest> options, List<VariantDtos.VariantRequest> variants) {
        if (options.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("A product can have at most " + MAX_OPTIONS + " options");
        }
        if (options.isEmpty() && !variants.isEmpty()) {
            throw new IllegalArgumentException("Add at least one option (for example Size) before adding variants");
        }
        if (!options.isEmpty() && variants.isEmpty()) {
            throw new IllegalArgumentException("Add at least one variant, or remove the options");
        }
        if (variants.size() > MAX_VARIANTS) {
            throw new IllegalArgumentException("A product can have at most " + MAX_VARIANTS + " variants");
        }
        Set<String> names = new HashSet<>();
        for (VariantDtos.OptionRequest option : options) {
            if (!names.add(normalize(option.name()))) {
                throw new IllegalArgumentException("Two options are both called \"" + option.name().trim() + "\"");
            }
            Set<String> labels = new HashSet<>();
            for (VariantDtos.OptionValueRequest value : option.values()) {
                if (!labels.add(normalize(value.label()))) {
                    throw new IllegalArgumentException("\"" + option.name().trim() + "\" lists \""
                            + value.label().trim() + "\" more than once");
                }
            }
        }
    }

    /** Per-variant field rules and in-request SKU duplicates; returns the distinct lower-cased SKUs used. */
    private List<String> validateVariants(List<VariantDtos.OptionRequest> options, List<VariantDtos.VariantRequest> variants) {
        Set<String> skus = new LinkedHashSet<>();
        for (int i = 0; i < variants.size(); i++) {
            VariantDtos.VariantRequest v = variants.get(i);
            if (v.selection().size() != options.size()) {
                throw new IllegalArgumentException("Variant " + (i + 1) + " must pick one value for each of the "
                        + options.size() + " option(s)");
            }
            BigDecimal sale = v.salePrice();
            if (sale != null && sale.compareTo(v.price()) >= 0) {
                throw new IllegalArgumentException("Variant " + (i + 1) + ": the sale price must be lower than the price");
            }
            String sku = blankToNull(v.sku());
            if (sku != null && !skus.add(sku.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("The SKU \"" + sku + "\" is used by more than one variant");
            }
        }
        return new ArrayList<>(skus);
    }

    /** SKUs share one namespace per store across products and variants — reject any held by another product. */
    private void checkSkusAvailable(Product product, List<String> lowerCaseSkus) {
        if (lowerCaseSkus.isEmpty()) {
            return;
        }
        UUID storeId = product.getStore().getId();
        List<String> taken = new ArrayList<>(productRepository.findSkusUsedByOtherProducts(storeId, product.getId(), lowerCaseSkus));
        taken.addAll(variantRepository.findSkusUsedByOtherProducts(storeId, product.getId(), lowerCaseSkus));
        if (!taken.isEmpty()) {
            throw new ConflictException("The SKU \"" + taken.get(0) + "\" is already used by another product in your store");
        }
    }

    private Product loadProduct(UUID id) {
        return productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Product not found"));
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
