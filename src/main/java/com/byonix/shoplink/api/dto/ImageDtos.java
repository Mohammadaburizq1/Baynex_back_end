package com.byonix.shoplink.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** A product's picture gallery, and uploading the picture files themselves. */
public final class ImageDtos {
    private ImageDtos() {}

    public record ImageResponse(UUID id, String url, String altText) {}

    /** id null = a new entry; an existing id keeps its row. The first entry becomes the primary image. */
    public record ImageRequest(
            UUID id,
            @NotBlank @Pattern(regexp = "^https?://.{3,480}$", message = "Invalid image URL") String url,
            @Size(max = 200) String altText) {}

    /** Replace-all: the gallery ends up exactly as listed, in this order. Empty/absent = no images. */
    public record SaveImagesRequest(@Size(max = 12) List<@Valid ImageRequest> images) {}

    /** Where an uploaded file can now be fetched — pass it back as an ImageRequest.url. */
    public record UploadResponse(String url) {}
}
