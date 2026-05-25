# khanGates — REST API Reference

**Base URL:** `http://localhost:8081`  
**API prefix:** `/api`  
**Auth header:** `Authorization: Bearer <accessToken>`

All responses use envelope: `{ "success", "message", "data", "errors" }`.

---

## Merchant auth — `/api/auth`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/register` | Public | Create merchant (`MERCHANT_OWNER`) |
| POST | `/login` | Public | Merchant login |
| POST | `/refresh` | Public | Rotate refresh token |
| POST | `/logout` | Public | Revoke refresh token |
| GET | `/me` | Bearer | Current user profile |
| POST | `/verify-email` | Public | Confirm email with token |
| POST | `/verify-email/resend` | Public | Resend verification (generic message) |
| POST | `/forgot-password` | Public | Merchant reset email (generic message) |
| POST | `/reset-password` | Public | Complete reset with token |
| GET | `/sessions` | Bearer | List refresh sessions |
| DELETE | `/sessions/{sessionId}` | Bearer | Revoke one session |
| POST | `/logout-all` | Bearer | Revoke all sessions |
| GET | `/security/events` | Bearer | User security events (paged) |
| POST | `/change-password` | Bearer | Change password while logged in |

---

## Customer auth — `/api/public/auth`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/register` | Public | Create `CUSTOMER` account |
| POST | `/login` | Public | Customer password login |
| POST | `/google` | Public | Google ID token → login/register |
| POST | `/refresh` | Public | Customer refresh token |
| POST | `/logout` | Public | Customer logout |
| POST | `/forgot-password` | Public | Customer reset (generic message) |
| POST | `/reset-password` | Public | Customer password reset |
| GET | `/me` | Bearer + CUSTOMER | Customer profile |

**Google body:** `{ "idToken": "<google-id-token>" }`

---

## Admin auth — `/api/admin/auth`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/login` | Public | Admin login (may return MFA challenge) |
| POST | `/mfa/verify` | Public | Complete SUPER_ADMIN MFA |
| POST | `/refresh` | Public | Admin refresh |
| POST | `/logout` | Public | Admin logout |
| GET | `/me` | Bearer + admin role | Admin profile |
| POST | `/forgot-password` | Public | Admin reset email (generic) |
| POST | `/reset-password` | Public | Admin password reset |

---

## Admin security — `/api/admin/security`

Requires admin Bearer token.

| Method | Path | Role | Description |
|--------|------|------|-------------|
| GET | `/login-attempts` | View monitoring | Paginated login attempts |
| GET | `/security-events` | View monitoring | Platform security events |
| GET | `/suspicious-activity` | View monitoring | High-risk attempts |
| GET | `/locked-users` | View monitoring | Locked accounts |
| POST | `/users/{userId}/unlock` | SUPER_ADMIN | Unlock user |
| POST | `/users/{userId}/force-password-reset` | SUPER_ADMIN | Force password reset flag |
| POST | `/block-ip` | SUPER_ADMIN | Block IP |
| POST | `/unblock-ip?ip=` | SUPER_ADMIN | Unblock IP |

---

## Public storefront — `/api/public`

No authentication required.

### Categories & templates

| Method | Path | Description |
|--------|------|-------------|
| GET | `/categories/business` | List business categories |
| GET | `/categories/business/{slug}/subcategories` | Subcategories for a business type |
| GET | `/templates/category/{categorySlug}` | Store templates for category |

### Store & catalog

| Method | Path | Description |
|--------|------|-------------|
| GET | `/stores/{slug}` | Store public profile |
| GET | `/stores/{slug}/homepage` | Homepage payload (hero, featured, categories) |
| GET | `/stores/{slug}/products` | All products |
| GET | `/stores/{slug}/categories` | Store product categories |
| GET | `/stores/{slug}/products/{productSlug}` | Single product |

### Orders (guest checkout)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/stores/{slug}/orders` | Create order (guest) |
| POST | `/stores/{slug}/orders/lookup` | Track order by code + email or phone |

**Create order body (summary):**

```json
{
  "customerName": "Ahmad",
  "customerEmail": "ahmad@example.com",
  "customerPhone": "+962790000000",
  "customerAddress": "Amman",
  "deliveryMethod": "DELIVERY",
  "paymentMethod": "CASH",
  "deliveryFee": 2.0,
  "discount": 0,
  "notes": "",
  "items": [{ "productId": "uuid", "quantity": 2 }]
}
```

**Order lookup:** requires `orderCode` plus **either** `email` **or** `phone` (not both).

---

## Merchant dashboard — `/api/dashboard`

Requires Bearer + `MERCHANT_OWNER` or `MERCHANT_STAFF`.

### Stores

| Method | Path | Description |
|--------|------|-------------|
| POST | `/stores` | Create store |
| GET | `/stores/my` | List my stores |
| PUT | `/stores/{id}` | Update store |
| DELETE | `/stores/{id}` | Delete store |

### Categories

| Method | Path | Description |
|--------|------|-------------|
| POST | `/categories` | Create category |
| GET | `/categories` | List categories |
| PUT | `/categories/{id}` | Update |
| DELETE | `/categories/{id}` | Delete |

### Products

| Method | Path | Description |
|--------|------|-------------|
| POST | `/products` | Create product |
| GET | `/products` | List products |
| GET | `/products/{id}` | Get one |
| PUT | `/products/{id}` | Update |
| DELETE | `/products/{id}` | Delete |

### Orders

| Method | Path | Description |
|--------|------|-------------|
| GET | `/orders` | List store orders |
| GET | `/orders/{id}` | Order detail |
| PUT | `/orders/{id}/status` | Update status (`NEW`, `CONFIRMED`, …) |

### Analytics & templates

| Method | Path | Description |
|--------|------|-------------|
| GET | `/templates` | Dashboard templates |
| GET | `/analytics/daily-store-sales?from=&to=&storeId=` | Sales analytics |

---

## System

| Method | Path | Description |
|--------|------|-------------|
| GET | `/actuator/health` | Health check (`UP`) |
| GET | `/v3/api-docs` | OpenAPI JSON |
| GET | `/swagger-ui.html` | Swagger UI |

---

## Order status values

`NEW` → `CONFIRMED` → `PREPARING` → `READY` → `DELIVERED` (or `CANCELLED`)

## Delivery / payment enums

- **Delivery:** `DELIVERY`, `PICKUP`
- **Payment:** `CASH`, `CARD`, `WHATSAPP_ONLY`

---

## Demo store (seed data)

After Flyway V4 seed:

- Slug: `foodie-demo`
- `GET /api/public/stores/foodie-demo`
- `GET /api/public/stores/foodie-demo/products`
