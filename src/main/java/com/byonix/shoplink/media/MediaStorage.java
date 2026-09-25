package com.byonix.shoplink.media;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Where uploaded picture files live. The rest of the app only knows this interface, so moving from
 * local disk to object storage (S3, R2, …) is a new implementation, not a change to callers.
 */
public interface MediaStorage {
    /** Stores the bytes under the store's own namespace and returns the generated file name. */
    String save(UUID storeId, ImageType type, byte[] content) throws IOException;

    /** The stored file, if {@code fileName} is a name this storage could have generated and it exists. */
    Optional<Resource> open(UUID storeId, String fileName);
}
