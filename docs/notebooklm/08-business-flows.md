# khanGates — Business Flows

End-to-end journeys across backend + Flutter.

---

## Flow 1: Merchant onboarding

```
Landing → Register (/onboarding)
    → POST /api/auth/register (MERCHANT_OWNER)
    → Email verification (optional link)
    → Create first store POST /api/dashboard/stores
    → Dashboard /dashboard
```

**Store fields:** name, slug, category (`retail` → `general-store`, restaurant → `restaurants-cafes`), colors, phone, template.

---

## Flow 2: Merchant daily operations

```
Login POST /api/auth/login
    → Dashboard
    → Manage products (CRUD /api/dashboard/products)
    → View orders GET /api/dashboard/orders
    → Update status PUT /api/dashboard/orders/{id}/status
    → Analytics GET /api/dashboard/analytics/daily-store-sales
```

---

## Flow 3: Guest checkout (no account)

```
Customer visits /store/{slug} (Flutter)
    → GET /api/public/stores/{slug}/homepage
    → GET /api/public/stores/{slug}/products
    → Add to cart (client-side)
    → POST /api/public/stores/{slug}/orders
    → Receives order with orderCode
```

**Track order:**

```
POST /api/public/stores/{slug}/orders/lookup
{ "orderCode": "ABC123", "email": "..." }  OR  { "orderCode": "...", "phone": "..." }
```

---

## Flow 4: Customer account (new)

```
POST /api/public/auth/register  → CUSTOMER role
    OR
POST /api/public/auth/google    → Google ID token
    → AuthResponse (access + refresh)
    → Future: order history, saved addresses
```

Customer login uses **separate** JWT scope from merchant/admin.

---

## Flow 5: Platform admin

```
/admin/login
    → POST /api/admin/auth/login
    → [SUPER_ADMIN] MFA verify
    → /admin/dashboard
    → Security: login attempts, locked users, block IP
    → Forgot password: POST /api/admin/auth/forgot-password
```

---

## Flow 6: Password reset (any portal)

```
Forgot password POST (role-filtered on server)
    → Email with link (generic response always)
    → User opens Flutter reset page with ?token=
    → POST reset-password with token + newPassword
    → All refresh sessions revoked
    → Login again on correct portal
```

---

## Flow 7: Session management (merchant)

```
GET /api/auth/sessions        → list devices
DELETE /api/auth/sessions/{id} → revoke one
POST /api/auth/logout-all     → revoke all
POST /api/auth/change-password → bumps token_version
```

---

## Order lifecycle (merchant view)

| Status | Meaning |
|--------|---------|
| NEW | Just placed |
| CONFIRMED | Merchant accepted |
| PREPARING | In kitchen/packing |
| READY | Ready for pickup/delivery |
| DELIVERED | Completed |
| CANCELLED | Cancelled |

---

## Who can do what (summary)

| Action | Guest | Customer | Merchant | Admin |
|--------|-------|----------|----------|-------|
| Browse store | Yes | Yes | Yes | Yes |
| Place order | Yes | Yes | N/A | N/A |
| Manage products | No | No | Yes | Via admin UI |
| Platform security | No | No | No | Yes |
| Merchant login API | No | No | Yes | No |
