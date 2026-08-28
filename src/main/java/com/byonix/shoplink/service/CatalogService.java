package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.*;
import com.byonix.shoplink.domain.entity.Category;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.CategoryType;
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
        return productRepository.findByStore_SlugAndAvailableTrueOrderBySortOrderAscNameEnAsc(storeSlug).stream().map(mapper::product).toList();
    }

    public List<ProductDtos.ProductResponse> publicFeaturedProducts(String storeSlug) {
        storeService.publicStore(storeSlug);
        return productRepository.findByStore_SlugAndFeaturedTrueAndAvailableTrueOrderBySortOrderAscNameEnAsc(storeSlug)
                .stream().map(mapper::product).toList();
    }

    public ProductDtos.ProductResponse publicProduct(String storeSlug, String productSlug) {
        storeService.publicStore(storeSlug);
        return mapper.product(productRepository.findByStore_SlugAndSlugAndAvailableTrue(storeSlug, productSlug)
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

    public List<ProductDtos.ProductResponse> dashboardProducts() {
        if (currentUser.isSuperAdmin()) {
            return productRepository.findAll().stream().map(mapper::product).toList();
        }
        return storeService.myStores().stream()
                .flatMap(s -> productRepository.findByStore_IdOrderBySortOrderAscNameEnAsc(s.id()).stream())
                .map(mapper::product).toList();
    }

    public ProductDtos.ProductResponse dashboardProduct(UUID id) {
        Product p = productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Product not found"));
        ensureStoreAccess(p.getStore());
        return mapper.product(p);
    }

    @Transactional
    public ProductDtos.ProductResponse updateProduct(UUID id, ProductDtos.ProductRequest r) {
        Product p = productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Product not found"));
        ensureStoreAccess(p.getStore());
        apply(p, r);
        return mapper.product(p);
    }

    @Transactional
    public void deleteProduct(UUID id) {
        Product p = productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Product not found"));
        ensureStoreAccess(p.getStore());
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
        Store store = r.storeId() == null ? null : storeService.ownedStore(r.storeId());
        if (store == null && !currentUser.isSuperAdmin()) {
            throw new AccessDeniedException("Access denied");
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
        Store store = storeService.ownedStore(r.storeId());
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
        p.setProductType(r.productType() == null ? p.getProductType() : r.productType());
        p.setAvailable(r.available() == null || r.available());
        p.setFeatured(r.featured() != null && r.featured());
        p.setSortOrder(r.sortOrder());
    }

    private void ensureCategoryAccess(Category c) {
        if (c.getStore() != null) {
            ensureStoreAccess(c.getStore());
        } else if (!currentUser.isSuperAdmin()) {
            throw new AccessDeniedException("Access denied");
        }
    }

    private void ensureStoreAccess(Store s) {
        if (!currentUser.isSuperAdmin() && !s.getOwner().getId().equals(currentUser.user().getId())) {
            throw new AccessDeniedException("Access denied");
        }
    }

    private String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
