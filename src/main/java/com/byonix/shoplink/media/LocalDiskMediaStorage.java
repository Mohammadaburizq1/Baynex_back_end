package com.byonix.shoplink.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores files on the local filesystem as {@code <root>/<storeId>/<random-uuid>.<ext>}.
 *
 * File names are always generated here (a random UUID plus an extension chosen from the detected
 * image type), never taken from the client, and lookups only accept exactly that shape — so a
 * request can't name any other path, and a name can't be guessed.
 */
@Component
public class LocalDiskMediaStorage implements MediaStorage {
    private static final Pattern GENERATED_NAME =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|gif|webp)$");

    private final Path root;

    public LocalDiskMediaStorage(@Value("${app.media.storage-dir}") String storageDir) {
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @Override
    public String save(UUID storeId, ImageType type, byte[] content) throws IOException {
        Path dir = root.resolve(storeId.toString());
        Files.createDirectories(dir);
        String name = UUID.randomUUID() + "." + type.extension();
        // CREATE_NEW: never overwrite an existing file, even in the (practically impossible) event of a name clash.
        Files.write(dir.resolve(name), content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return name;
    }

    @Override
    public Optional<Resource> open(UUID storeId, String fileName) {
        if (fileName == null || !GENERATED_NAME.matcher(fileName).matches()) {
            return Optional.empty();
        }
        Path file = root.resolve(storeId.toString()).resolve(fileName).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(new FileSystemResource(file));
    }
}
