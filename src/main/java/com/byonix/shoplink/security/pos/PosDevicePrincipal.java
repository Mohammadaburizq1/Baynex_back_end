package com.byonix.shoplink.security.pos;

import java.util.UUID;

/** The authenticated POS device. The store comes from the server-side device record, never from the request. */
public record PosDevicePrincipal(UUID deviceId, UUID storeId) {}
