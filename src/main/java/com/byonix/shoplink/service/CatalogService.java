package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.*;
import com.byonix.shoplink.common.ConflictException;
import com.byonix.shoplink.domain.entity.Category;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.CategoryType;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.InventoryAdjustmentReason;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.domain.enums.ProductType;
import com.byonix.shoplink.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogService {
    private static final int MAX_CATEGORY_DEPTH = 50;

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final StoreTemplateRepository templateRepository;
    private final StoreService storeService;
    private final CurrentUserService currentUser;
    private final MapperService mapper;
    private final ProductAssembler assembler;
    private final InventoryLedger ledger;
    private final ProductImageService imageService;

    public List<CategoryDtos.CategoryResponse> businessCategories() {
        return categoryRepository.findByCategoryTypeAndParentIsNullAndActiveTrueOrderBySortOrderAscNameEnAsc(CategoryType.BUSINESS)
                .stream().map(mapper::category).toList();
    }

    public List<CategoryDtos.CategoryResponse> businessSubcategories(String slug) {
        return categoryRepository.findByParent_SlugAndActiveTrueOrderBySortOrderAscNameEnAsc(slug)
                .stream().map(mapper::category).toList();
    }

    public List<ProductDtos.ProductResponse> publicProducts(String storeSlug) {
        storeService.publicStore(storeSlug);
        return assembler.storefront(productRepository.findByStore_SlugAndAvailableTrueOrderBySortOrderAscNameEnAsc(storeSlug));
    }

    public List<ProductDtos.ProductResponse> publicFeaturedProducts(String storeSlug) {
        storeService.publicStore(storeSlug);
        return assembler.storefront(productRepository.findByStore_SlugAndFeaturedTrueAndAvailableTrueOrderBySortOrderAscNameEnAsc(storeSlug));
    }

    public ProductDtos.ProductResponse publicProduct(String storeSlug, String productSlug) {
        storeService.publicStore(storeSlug);
        return assembler.storefront(productRepository.findByStore_SlugAndSlugAndAvailableTrue(storeSlug, productSlug)
                .orElseThrow(() -> new EntityNotFoundException("Product not found")));
    }

    public List<CategoryDtos.CategoryResponse> publicStoreCategories(String storeSlug) {
        storeService.publicStore(storeSlug);
        return categoryRepository.findByStore_SlugAndActiveTrueOrderBySortOrderAscNameEnAsc(storeSlug).stream().map(mapper::category).toList();
    }

    @Transactional
    public CategoryDtos.CategoryResponse createCategory(CategoryDtos.CategoryRequest r) {
        currentUser.requireMerchantOrAdmin();
        Category c = new Category();
        apply(c, r);
        return mapper.category(categoryRepository.save(c));
    }

    public List<CategoryDtos.CategoryResponse> dashboardCategories() {
        if (currentUser.isSuperAdmin()) {
            return categoryRepository.findAll().stream().map(mapper::category).toList();
        }
        currentUser.ensureListSectionAccess(DashboardSection.PRODUCTS, PermissionLevel.VIEW);
        return storeService.myStores().stream()
                .flatMap(s -> categoryRepository.findByStore_IdOrderBySortOrderAscNameEnAsc(s.id()).stream())
                .map(mapper::category).toList();
    }

    @Transactional
    public CategoryDtos.CategoryResponse updateCategory(UUID id, CategoryDtos.CategoryRequest r) {
        Category c = categoryRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Category not found"));
        ensureCategoryAccess(c);
        apply(c, r);
        return mapper.category(c);
    }

    @Transactional
    public void deleteCategory(UUID id) {
        Category c = categoryRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Category not found"));
        ensureCategoryAccess(c);
        categoryRepository.delete(c);
    }

    @Transactional
    public ProductDtos.ProductResponse createProduct(ProductDtos.ProductRequest r) {
        currentUser.requireMerchantOrAdmin();
        Product p = new Product();
        apply(p, r);
        Product saved = productRepository.save(p);
        imageService.seedFromPrimary(saved);
        // The opening count is the one stock value a product is created with; from then on the
        // count only moves through sales and inventory adjustments, each written to the ledger.
        if (saved.getStock() != null && saved.getStock() > 0) {
            ledger.record(saved.getStore(), saved, null, saved.getNameEn(), saved.getStock(), saved.getStock(),
                    InventoryAdjustmentReason.INITIAL, null, null);
        }
        return assembler.dashboard(saved);
    }

    // storeId is optional — when present, scopes the result to just that store instead of every
    // store this merchant owns. Filtering happens against myStores() (or, for a super admin, a
    // direct lookup) rather than trusting the caller's id blindly, so passing a storeId the
    // caller doesn't own yields an empty list, never another merchant's products.
    public List<ProductDtos.ProductResponse> dashboardProducts(UUID storeId) {
        if (currentUser.isSuperAdmin()) {
            List<Product> products = storeId != null
                    ? productRepository.findByStore_IdOrderBySortOrderAscNameEnAsc(storeId)
                    : productRepository.findAll();
            return assembler.dashboard(products);
        }
        currentUser.ensureListSectionAccess(DashboardSection.PRODUCTS, PermissionLevel.VIEW);
        List<Product> products = storeService.myStores().stream()
                .filter(s -> storeId == null || s.id().equals(storeId))
                .flatMap(s -> productRepository.findByStore_IdOrderBySortOrderAscNameEnAsc(s.id()).stream())
                .toList();
        return assembler.dashboard(products);
    }

    public ProductDtos.ProductResponse dashboardProduct(UUID id) {
        Product p = productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Product not found"));
        currentUser.ensureSectionAccess(p.getStore(), DashboardSection.PRODUCTS, PermissionLevel.VIEW);
        return assembler.dashboard(p);
    }

    @Transactional
    public ProductDtos.ProductResponse updateProduct(UUID id, ProductDtos.ProductRequest r) {
        Product p = productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Product not found"));
        currentUser.ensureSectionAccess(p.getStore(), DashboardSection.PRODUCTS, PermissionLevel.EDIT);
        apply(p, r);
        return assembler.dashboard(p);
    }

    @Transactional
    public void deleteProduct(UUID id) {
        Product p = productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Product not found"));
        currentUser.ensureSectionAccess(p.getStore(), DashboardSection.PRODUCTS, PermissionLevel.EDIT);
        UUID storeId = p.getStore().getId();
        productRepository.delete(p);
        productRepository.flush();
        storeService.revertToDraftIfNoProducts(storeId);
    }

    public List<TemplateResponse> templates(String categorySlug) {
        return templateRepository.findByCategorySlugAndActiveTrueOrderByDefaultTemplateDescNameAsc(categorySlug).stream().map(mapper::template).toList();
    }

    public List<TemplateResponse> dashboardTemplates() {
        return templateRepository.findByActiveTrueOrderByCategorySlugAscNameAsc().stream().map(mapper::template).toList();
    }

    private void apply(Category c, CategoryDtos.CategoryRequest r) {
        Store store = r.storeId() == null ? null : storeService.accessibleStore(r.storeId());
        if (store == null && !currentUser.isSuperAdmin()) {
            throw new AccessDeniedException("Access denied");
        }
        if (store != null) {
            currentUser.ensureSectionAccess(store, DashboardSection.PRODUCTS, PermissionLevel.EDIT);
            // Checked before any field is mutated so the query's auto-flush never writes a
            // half-applied update.
            boolean slugTaken = c.getId() == null
                    ? categoryRepository.existsByStore_IdAndSlug(store.getId(), r.slug())
                    : categoryRepository.existsByStore_IdAndSlugAndIdNot(store.getId(), r.slug(), c.getId());
            if (slugTaken) {
                throw new ConflictException("A category with the URL slug \"" + r.slug() + "\" already exists in your store");
            }
        }
        Category parent = r.parentId() == null ? null : categoryRepository.findById(r.parentId()).orElseThrow(() -> new EntityNotFoundException("Parent category not found"));
        if (parent != null) {
            if (store == null && parent.getStore() != null) {
                throw new AccessDeniedException("Invalid parent category");
            }
            if (store != null && parent.getStore() != null && !parent.getStore().getId().equals(store.getId())) {
                throw new AccessDeniedException("Invalid parent category");
            }
            // A category can't sit under itself or anything beneath it — that would detach the whole
            // branch from the tree (and loop any code walking parents).
            if (c.getId() != null) {
                Category ancestor = parent;
                for (int hops = 0; ancestor != null && hops < MAX_CATEGORY_DEPTH; hops++) {
                    if (ancestor.getId().equals(c.getId())) {
                        throw new IllegalArgumentException("A category can't be moved under itself or one of its own subcategories");
                    }
                    ancestor = ancestor.getParent();
                }
            }
        }
        c.setStore(store);
        c.setParent(parent);
        c.setNameEn(r.nameEn());
        c.setNameAr(r.nameAr());
        c.setSlug(r.slug());
        c.setDescription(r.description());
        c.setIcon(r.icon());
        c.setImageUrl(blank(r.imageUrl()));
        c.setSortOrder(r.sortOrder());
        c.setActive(r.active() == null || r.active());
        c.setCategoryType(r.categoryType());
    }

    private void apply(Product p, ProductDtos.ProductRequest r) {
        Store store = storeService.accessibleStore(r.storeId());
        currentUser.ensureSectionAccess(store, DashboardSection.PRODUCTS, PermissionLevel.EDIT);
        // Uniqueness is checked before any field is mutated, so the queries' auto-flush never
        // writes a half-applied update.
        boolean slugTaken = p.getId() == null
                ? productRepository.existsByStore_IdAndSlug(store.getId(), r.slug())
                : productRepository.existsByStore_IdAndSlugAndIdNot(store.getId(), r.slug(), p.getId());
        if (slugTaken) {
            throw new ConflictException("A product with the URL slug \"" + r.slug() + "\" already exists in your store");
        }
        String sku = blank(r.sku());
        if (sku != null) {
            // One SKU namespace per store, shared by products and their variants.
            boolean skuTaken = (p.getId() == null
                    ? productRepository.existsByStore_IdAndSkuIgnoreCase(store.getId(), sku)
                    : productRepository.existsByStore_IdAndSkuIgnoreCaseAndIdNot(store.getId(), sku, p.getId()))
                    || variantRepository.existsByStore_IdAndSkuIgnoreCase(store.getId(), sku);
            if (skuTaken) {
                throw new ConflictException("The SKU \"" + sku + "\" is already used by another product in your store");
            }
        }
        p.setStore(store);
        if (r.categoryId() != null) {
            Category category = categoryRepository.findById(r.categoryId()).orElseThrow(() -> new EntityNotFoundException("Category not found"));
            if (category.getStore() != null && !category.getStore().getId().equals(store.getId())) {
                throw new AccessDeniedException("Category does not belong to store");
            }
            p.setCategory(category);
        } else {
            p.setCategory(null);
        }
        p.setNameEn(r.nameEn());
        p.setNameAr(r.nameAr());
        p.setSlug(r.slug());
        p.setDescription(r.description());
        p.setPrice(r.price());
        p.setSalePrice(r.salePrice());
        // The store is the operating-currency authority. Keep an explicit legacy value when
        // editing/importing an existing product, but new products inherit the store currency.
        p.setCurrency(r.currency() == null ? store.getCurrency() : r.currency());
        // imageUrl is taken from the request only when the product is CREATED (it seeds the gallery).
        // Afterwards the gallery (ProductImageService) owns it: an edit form that does not carry the
        // image would otherwise wipe it on every save.
        if (p.getId() == null) {
            p.setImageUrl(blank(r.imageUrl()));
        }
        p.setGalleryJson(r.galleryJson());
        p.setSku(sku);
        ProductType type = r.productType() == null ? p.getProductType() : r.productType();
        p.setProductType(type);
        p.setAvailable(r.available() == null || r.available());
        p.setFeatured(r.featured() != null && r.featured());
        p.setSortOrder(r.sortOrder());
        // A SERVICE never tracks stock — force it to null regardless of what the client sent,
        // rather than trusting the client to have omitted it.
        //
        // Stock is only taken from the request when the product is CREATED (its opening count).
        // After that the count changes through sales and inventory adjustments only: an edit form
        // holds the number it loaded, so letting it write the field back would silently undo any
        // sale made since the page was opened. (A product sold through variants keeps its stock
        // on the variants and has none of its own.)
        if (type == ProductType.SERVICE) {
            p.setStock(null);
        } else if (p.getId() == null) {
            p.setStock(r.stock());
        }
        p.setLowStockThreshold(r.lowStockThreshold());
    }

    private void ensureCategoryAccess(Category c) {
        if (c.getStore() != null) {
            currentUser.ensureSectionAccess(c.getStore(), DashboardSection.PRODUCTS, PermissionLevel.EDIT);
        } else if (!currentUser.isSuperAdmin()) {
            throw new AccessDeniedException("Access denied");
        }
    }

    private String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
