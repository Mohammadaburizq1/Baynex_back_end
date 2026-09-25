package com.byonix.shoplink.api;

import com.byonix.shoplink.api.dto.ImageDtos;
import com.byonix.shoplink.common.ApiResponse;
import com.byonix.shoplink.media.ImageType;
import com.byonix.shoplink.media.MediaStorage;
import com.byonix.shoplink.service.MediaService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.UUID;

/**
 * Picture files: an authenticated upload endpoint for merchants, and the public URLs those files are
 * then served from. Served files are public by design (they appear on public storefronts) — the
 * protection is that every name is a random UUID chosen by the server, so a URL can't be guessed.
 */
@RestController
@RequiredArgsConstructor
public class MediaController {
    private final MediaService mediaService;
    private final MediaStorage storage;

    @PostMapping("/api/dashboard/media/images")
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_STAFF')")
    public ApiResponse<ImageDtos.UploadResponse> upload(@RequestParam UUID storeId, @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(mediaService.uploadImage(storeId, file));
    }

    @GetMapping("/media/{storeId}/{fileName:.+}")
    public ResponseEntity<Resource> serve(@PathVariable String storeId, @PathVariable String fileName) {
        int dot = fileName.lastIndexOf('.');
        ImageType type = dot < 0 ? null : ImageType.fromExtension(fileName.substring(dot + 1)).orElse(null);
        UUID store;
        try {
            store = UUID.fromString(storeId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        if (type == null) {
            return ResponseEntity.notFound().build();
        }
        return storage.open(store, fileName)
                .map(resource -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(type.contentType()))
                        // The name is a random UUID and the content never changes under it.
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                        .body(resource))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
