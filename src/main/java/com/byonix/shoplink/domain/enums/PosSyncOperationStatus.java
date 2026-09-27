package com.byonix.shoplink.domain.enums;

/** APPLYING only exists inside the uploading transaction; a committed row is always SYNCED*. */
public enum PosSyncOperationStatus {
    APPLYING, SYNCED, SYNCED_WITH_CONFLICTS
}
