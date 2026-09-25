package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.ModifierDtos;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.ProductModifierGroup;
import com.byonix.shoplink.domain.entity.ProductModifierOption;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.repository.ProductModifierGroupRepository;
import com.byonix.shoplink.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The merchant's add-on editor: read a product's modifier groups, or replace them wholesale.
 *
 * Replace-all with reconciliation, like {@link ProductVariantService}: rows are matched to what
 * exists by id (or by name when none is sent) and updated in place; whatever is no longer listed is
 * deleted. Nothing else references an add-on — orders hold a plain-text copy of what they sold — so
 * deleting one can't affect history.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductModifierService {
    static final int MAX_GROUPS = 10;
    static final int MAX_OPTIONS_PER_GROUP = 30;

    private final ProductRepository productRepository;
    private final ProductModifierGroupRepository groupRepository;
    private final CurrentUserService currentUser;
    private final ProductAssembler assembler;

    public List<ModifierDtos.ModifierGroupResponse> get(UUID productId) {
        Product product = loadProduct(productId);
        currentUser.ensureSectionAccess(product.getStore(), DashboardSection.PRODUCTS, PermissionLevel.VIEW);
        return assembler.modifierGroupsOf(product);
    }

    @Transactional
    public List<ModifierDtos.ModifierGroupResponse> save(UUID productId, ModifierDtos.SaveModifierGroupsRequest request) {
        Product product = loadProduct(productId);
        currentUser.ensureSectionAccess(product.getStore(), DashboardSection.PRODUCTS, PermissionLevel.EDIT);

        List<ModifierDtos.ModifierGroupRequest> requested = request.groups() == null ? List.of() : request.groups();
        validate(requested);

        List<ProductModifierGroup> existing = groupRepository.findWithOptionsByProductIdIn(List.of(productId));
        Map<UUID, ProductModifierGroup> byId = new HashMap<>();
        Map<String, ProductModifierGroup> byName = new HashMap<>();
        existing.forEach(g -> {
            byId.put(g.getId(), g);
            byName.put(normalize(g.getName()), g);
        });

        Set<UUID> keptIds = new HashSet<>();
        List<ProductModifierGroup> created = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            ModifierDtos.ModifierGroupRequest gr = requested.get(i);
            ProductModifierGroup group = gr.id() != null ? byId.get(gr.id()) : byName.get(normalize(gr.name()));
            if (gr.id() != null && group == null) {
                throw new EntityNotFoundException("Add-on group not found");
            }
            if (group != null && !keptIds.add(group.getId())) {
                throw new IllegalArgumentException("The add-on group \"" + gr.name().trim() + "\" is listed twice");
            }
            if (group == null) {
                group = new ProductModifierGroup();
                group.setStore(product.getStore());
                group.setProduct(product);
                created.add(group);
            }
            int optionCount = gr.options().size();
            int min = gr.minSelect() == null ? 0 : gr.minSelect();
            int max = Math.min(gr.maxSelect() == null ? 1 : gr.maxSelect(), optionCount);
            if (min > max) {
                throw new IllegalArgumentException("\"" + gr.name().trim() + "\": the minimum number of choices ("
                        + min + ") can't be more than the maximum (" + max + ") or the number of options");
            }
            group.setName(gr.name().trim());
            group.setMinSelect(min);
            group.setMaxSelect(max);
            group.setSortOrder(i);
            syncOptions(group, gr.options(), product);
        }
        groupRepository.deleteAll(existing.stream().filter(g -> !keptIds.contains(g.getId())).toList());
        // Only new groups go through save() — an already-managed group must not (that's a merge,
        // which would replace its new options with copies). New options on existing groups are
        // persisted in place by the cascade at flush.
        created.forEach(groupRepository::save);
        groupRepository.flush();
        return assembler.modifierGroupsOf(product);
    }

    private void syncOptions(ProductModifierGroup group, List<ModifierDtos.ModifierOptionRequest> requested, Product product) {
        Map<UUID, ProductModifierOption> byId = new HashMap<>();
        Map<String, ProductModifierOption> byName = new HashMap<>();
        group.getOptions().forEach(o -> {
            byId.put(o.getId(), o);
            byName.put(normalize(o.getName()), o);
        });
        List<ProductModifierOption> kept = new ArrayList<>();
        for (int j = 0; j < requested.size(); j++) {
            ModifierDtos.ModifierOptionRequest or = requested.get(j);
            ProductModifierOption option = or.id() != null ? byId.get(or.id()) : byName.get(normalize(or.name()));
            if (or.id() != null && option == null) {
                throw new EntityNotFoundException("Add-on option not found");
            }
            if (option != null && kept.contains(option)) {
                throw new IllegalArgumentException("\"" + group.getName() + "\" lists an option twice");
            }
            if (option == null) {
                option = new ProductModifierOption();
                option.setStore(product.getStore());
                option.setGroup(group);
                group.getOptions().add(option);
            }
            option.setName(or.name().trim());
            option.setPriceDelta(or.priceDelta());
            option.setPreselected(or.preselected() != null && or.preselected());
            option.setAvailable(or.available() == null || or.available());
            option.setSortOrder(j);
            kept.add(option);
        }
        group.getOptions().removeIf(o -> !kept.contains(o));
    }

    private void validate(List<ModifierDtos.ModifierGroupRequest> groups) {
        if (groups.size() > MAX_GROUPS) {
            throw new IllegalArgumentException("A product can have at most " + MAX_GROUPS + " add-on groups");
        }
        Set<String> groupNames = new HashSet<>();
        for (ModifierDtos.ModifierGroupRequest g : groups) {
            if (!groupNames.add(normalize(g.name()))) {
                throw new IllegalArgumentException("Two add-on groups are both called \"" + g.name().trim() + "\"");
            }
            if (g.options().size() > MAX_OPTIONS_PER_GROUP) {
                throw new IllegalArgumentException("\"" + g.name().trim() + "\" can have at most " + MAX_OPTIONS_PER_GROUP + " options");
            }
            Set<String> optionNames = new HashSet<>();
            for (ModifierDtos.ModifierOptionRequest o : g.options()) {
                if (!optionNames.add(normalize(o.name()))) {
                    throw new IllegalArgumentException("\"" + g.name().trim() + "\" lists \"" + o.name().trim() + "\" more than once");
                }
            }
        }
    }

    private Product loadProduct(UUID id) {
        return productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Product not found"));
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
