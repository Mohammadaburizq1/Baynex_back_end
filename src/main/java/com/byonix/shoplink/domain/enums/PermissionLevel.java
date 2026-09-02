package com.byonix.shoplink.domain.enums;

// Declaration order matters: CurrentUserService.ensureSectionAccess compares levels via
// Enum.ordinal(), so NONE < VIEW < EDIT must hold.
public enum PermissionLevel {
    NONE, VIEW, EDIT
}
