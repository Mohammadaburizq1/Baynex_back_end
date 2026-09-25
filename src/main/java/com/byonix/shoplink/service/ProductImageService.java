package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.ImageDtos;
import com.byonix.shoplink.domain.entity.Product;
import com.byonix.shoplink.domain.entity.ProductImage;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.repository.ProductImageRepository;
import com.byonix.shoplink.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A product's picture gallery. Replace-all with reconciliation (entries matched by id keep their
 * row), and the first picture is mirrored to {@code products.image_url} — the field storefront
 * templates already read — so the primary image never has to be kept in sync by hand.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductImageService {
    static final int MAX_IMAGES = 12;

    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final CurrentUserService currentUser;

    public List<ImageDtos.ImageResponse> get(UUID productId) {
        Product product = loadProduct(productId);
        currentUser.ensureSectionAccess(product.getStore(), DashboardSection.PRODUCTS, PermissionLevel.VIEW);
        return imageRepository.findByProduct_IdInOrderBySortOrderAsc(List.of(productId)).stream()
                .map(i -> new ImageDtos.ImageResponse(i.getId(), i.getUrl(), i.getAltText()))
                .toList();
    }

    @Transactional
    public List<ImageDtos.ImageResponse> save(UUID productId, ImageDtos.SaveImagesRequest request) {
        Product product = loadProduct(productId);
        currentUser.ensureSectionAccess(product.getStore(), DashboardSection.PRODUCTS, PermissionLevel.EDIT);
        List<ImageDtos.ImageRequest> requested = request.images() == null ? List.of() : request.images();
        if (requested.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("A product can have at most " + MAX_IMAGES + " images");
        }

        List<ProductImage> existing = imageRepository.findByProduct_IdInOrderBySortOrderAsc(List.of(productId));
        Map<UUID, ProductImage> byId = new HashMap<>();
        existing.forEach(i -> byId.put(i.getId(), i));

        List<ProductImage> kept = new ArrayList<>();
        List<ProductImage> created = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            ImageDtos.ImageRequest r = requested.get(i);
            ProductImage image;
            if (r.id() != null) {
                image = byId.get(r.id());
                if (image == null || kept.contains(image)) {
                    throw new EntityNotFoundException("Image not found");
                }
            } else {
                image = new ProductImage();
                image.setStore(product.getStore());
                image.setProduct(product);
                created.add(image);
            }
            image.setUrl(r.url().trim());
            image.setAltText(r.altText() == null || r.altText().isBlank() ? null : r.altText().trim());
            image.setSortOrder(i);
            kept.add(image);
        }
        imageRepository.deleteAll(existing.stream().filter(i -> !kept.contains(i)).toList());
        imageRepository.saveAll(created);   // only new rows — an already-managed one must not be merged
        product.setImageUrl(kept.isEmpty() ? null : kept.get(0).getUrl());
        imageRepository.flush();
        return kept.stream().map(i -> new ImageDtos.ImageResponse(i.getId(), i.getUrl(), i.getAltText())).toList();
    }

    /** A product created with an image URL starts its gallery with it. */
    @Transactional
    public void seedFromPrimary(Product product) {
        if (product.getImageUrl() == null || product.getImageUrl().isBlank()) {
            return;
        }
        ProductImage image = new ProductImage();
        image.setStore(product.getStore());
        image.setProduct(product);
        image.setUrl(product.getImageUrl());
        image.setSortOrder(0);
        imageRepository.save(image);
    }

    private Product loadProduct(UUID id) {
        return productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Product not found"));
    }
}
