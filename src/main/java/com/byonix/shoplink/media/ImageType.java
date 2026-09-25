package com.byonix.shoplink.media;

import java.util.Optional;

/**
 * The picture formats a merchant may upload. The format is decided from the file's own first bytes
 * — never from the client-supplied file name or Content-Type, either of which is trivially spoofed.
 * SVG is deliberately absent: it can carry script.
 */
public enum ImageType {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    GIF("gif", "image/gif"),
    WEBP("webp", "image/webp");

    private final String extension;
    private final String contentType;

    ImageType(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public static Optional<ImageType> detect(byte[] b) {
        if (b == null || b.length < 12) {
            return Optional.empty();
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return Optional.of(PNG);
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8' && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return Optional.of(GIF);
        }
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    public static Optional<ImageType> fromExtension(String extension) {
        for (ImageType t : values()) {
            if (t.extension.equals(extension)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
