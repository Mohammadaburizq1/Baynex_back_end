package com.byonix.shoplink.service;

import com.byonix.shoplink.api.dto.ImageDtos;
import com.byonix.shoplink.domain.entity.Store;
import com.byonix.shoplink.domain.enums.DashboardSection;
import com.byonix.shoplink.domain.enums.PermissionLevel;
import com.byonix.shoplink.media.ImageType;
import com.byonix.shoplink.media.MediaStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * Accepts picture uploads for a store's catalogue. Only someone who may edit that store's products
 * can upload into it, and the file is judged by its own bytes, not by what the client claims.
 */
@Service
@RequiredArgsConstructor
public class MediaService {
    static final long MAX_BYTES = 5L * 1024 * 1024;

    private final StoreService storeService;
    private final CurrentUserService currentUser;
    private final MediaStorage storage;

    @Value("${app.media.public-base-url}")
    private String publicBaseUrl;

    public ImageDtos.UploadResponse uploadImage(UUID storeId, MultipartFile file) {
        Store store = storeService.accessibleStore(storeId);
        currentUser.ensureSectionAccess(store, DashboardSection.PRODUCTS, PermissionLevel.EDIT);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose an image to upload");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Images can be up to 5 MB");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Images can be up to 5 MB");
            ImageType type = ImageType.detect(bytes)
                    .orElseThrow(() -> new IllegalArgumentException("Upload a JPEG, PNG, WebP or GIF image"));
            String name = storage.save(store.getId(), type, bytes);
            String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
            return new ImageDtos.UploadResponse(base + "/media/" + store.getId() + "/" + name);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the uploaded image", e);
        }
    }
}
