-- Extend the staff_permissions grid with STOREFRONT (customize-storefront theme content:
-- draft/publish/version history) — same alter-a-CHECK-constraint pattern already used in V25
-- when APPOINTMENTS was added. Before this, StoreThemeContentService only checked store
-- membership (accessibleStore), not the permission grid, so every staff member implicitly had
-- full edit+publish access to the live storefront regardless of what the owner configured for
-- them elsewhere in the grid. New rows default to EDIT via CurrentUserService.ensureSectionAccess
-- (no explicit row = EDIT), so this migration itself changes no existing staff member's access —
-- it only makes the section governable going forward.
ALTER TABLE staff_permissions DROP CONSTRAINT staff_permissions_section_check;
ALTER TABLE staff_permissions ADD CONSTRAINT staff_permissions_section_check
    CHECK (section IN ('PRODUCTS','ORDERS','DELIVERY','CUSTOMERS','REPORTS','OFFERS','APPOINTMENTS','STOREFRONT'));
