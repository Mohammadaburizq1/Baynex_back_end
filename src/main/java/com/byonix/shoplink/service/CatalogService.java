package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.*;
import com.byonix.shoplink.domain.entity.Category;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.CategoryType;
import com.byonix.shoplink.domain.enums.DashboardSection;
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
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final StoreTemplateRepository templateRepository;
    private final StoreService storeService;
    private final CurrentUserService currentUser;
    private final MapperService mapper;

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
        return productRepository.findByStore_SlugAndAvailableTrueOrderBySortOrderAscNameEnAsc(storeSlug).stream().map(mapper::publicProduct).toList();
    }

    public List<ProductDtos.ProductResponse> publicFeaturedProducts(String storeSlug) {
        storeService.publicStore(storeSlug);
        return productRepository.findByStore_SlugAndFeaturedTrueAndAvailableTrueOrderBySortOrderAscNameEnAsc(storeSlug)
                .stream().map(mapper::publicProduct).toList();
    }

    public ProductDtos.ProductResponse publicProduct(String storeSlug, String productSlug) {
        storeService.publicStore(storeSlug);
        return mapper.publicProduct(productRepository.findByStore_SlugAndSlugAndAvailableTrue(storeSlug, productSlug)
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
        return mapper.product(productRepository.save(p));
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
            return products.stream().map(mapper::product).toList();
        }
        currentUser.ensureListSectionAccess(DashboardSection.PRODUCTS, PermissionLevel.VIEW);
        return storeService.myStores().stream()
                .filter(s -> storeId == null || s.id().equals(storeId))
                .flatMap(s -> productRepository.findByStore_IdOrderBySortOrderAscNameEnAsc(s.id()).stream())
                .map(mapper::product).toList();
    }

    public ProductDtos.ProductResponse dashboardProduct(UUID id) {
        Product p = productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Product not found"));
        currentUser.ensureSectionAccess(p.getStore(), DashboardSection.PRODUCTS, PermissionLevel.VIEW);
        return mapper.product(p);
    }

    @Transactional
    public ProductDtos.ProductResponse updateProduct(UUID id, ProductDtos.ProductRequest r) {
        Product p = productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Product not found"));
        currentUser.ensureSectionAccess(p.getStore(), DashboardSection.PRODUCTS, PermissionLevel.EDIT);
        apply(p, r);
        return mapper.product(p);
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
        }
        c.setStore(store);
        Category parent = r.parentId() == null ? null : categoryRepository.findById(r.parentId()).orElseThrow(() -> new EntityNotFoundException("Parent category not found"));
        if (parent != null) {
            if (store == null && parent.getStore() != null) {
                throw new AccessDeniedException("Invalid parent category");
            }
            if (store != null && parent.getStore() != null && !parent.getStore().getId().equals(store.getId())) {
                throw new AccessDeniedException("Invalid parent category");
            }
        }
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
        p.setCurrency(r.currency() == null ? "JOD" : r.currency());
        p.setImageUrl(blank(r.imageUrl()));
        p.setGalleryJson(r.galleryJson());
        p.setSku(r.sku());
        ProductType type = r.productType() == null ? p.getProductType() : r.productType();
        p.setProductType(type);
        p.setAvailable(r.available() == null || r.available());
        p.setFeatured(r.featured() != null && r.featured());
        p.setSortOrder(r.sortOrder());
        // A SERVICE never tracks stock — force it to null regardless of what the client sent,
        // rather than trusting the client to have omitted it.
        p.setStock(type == ProductType.SERVICE ? null : r.stock());
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
