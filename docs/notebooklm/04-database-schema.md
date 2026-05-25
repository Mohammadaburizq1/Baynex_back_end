# khanGates — Database Schema (PostgreSQL)

**Database:** `shoplink_db`  
**User:** `shoplink_user`  
**Migrations:** Flyway `V1` … `V11` in `src/main/resources/db/migration/`

---

## Entity relationship (simplified)

```
app_users ──┬──< stores ──┬──< products
            │             ├──< categories (store-scoped)
            │             └──< customer_orders ──< order_items
            ├──< refresh_tokens
            ├──< email_verification_tokens
            └──< password_reset_tokens
```

---

## Core tables

### `app_users`

| Column | Notes |
|--------|-------|
| id | UUID PK |
| full_name, email, password_hash, phone | |
| role | See roles below |
| is_active, token_version | JWT invalidation |
| email_verified_at | Optional verification |
| google_sub | Google OAuth subject (V11, unique) |
| failed_login_count, locked_until | Account lock |
| force_password_reset, admin_unlock_required | Security flags |
| mfa_enabled, mfa_secret | Admin MFA |

**Roles (V9+):** `SUPER_ADMIN`, `SUPPORT_ADMIN`, `FINANCE_ADMIN`, `READ_ONLY_ADMIN`, `MERCHANT_OWNER`, `MERCHANT_STAFF`, `CUSTOMER`

---

### `refresh_tokens`

| Column | Notes |
|--------|-------|
| token_hash | BCrypt/hashed opaque token |
| session_scope | `MERCHANT`, `ADMIN`, `CUSTOMER` (V10/V11) |
| expires_at, revoked_at | Rotation on refresh |
| ip_address, user_agent | Session metadata |

---

### `stores`

Merchant-owned shop: `owner_id`, `name`, `slug` (unique), branding colors, `category_slug`, `template_key`, `status` (`DRAFT`, `ACTIVE`, `SUSPENDED`).

---

### `categories`

- **Global** (`store_id` NULL): business taxonomy (V2 seed)
- **Per-store** (`store_id` set): product categories
- `category_type`: `BUSINESS` or `PRODUCT`

---

### `products`

Belongs to `store_id`. Fields: `name_en`, `name_ar`, `slug`, `price`, `sale_price`, `currency` (default JOD), `product_type` (`PRODUCT`, `SERVICE`, `FOOD_ITEM`), `is_available`, `is_featured`.

---

### `customer_orders` + `order_items`

Guest or future customer orders. Includes `order_code` (V9) for public lookup. Status workflow: `NEW` … `DELIVERED` / `CANCELLED`.

---

### `store_templates`

Predefined UI templates per business category (V3 seed).

---

## Security tables (V8+)

| Table | Purpose |
|-------|---------|
| `login_attempts` | Success/failure audit |
| `security_events` | User/platform events |
| `ip_blocklist` | Blocked IPs |
| `admin_audit_logs` | Admin actions (unlock, block IP, etc.) |
| `email_verification_tokens` | Hashed verify tokens |
| `password_reset_tokens` | Hashed reset tokens |

---

## Analytics (V6/V7)

| Object | Purpose |
|--------|---------|
| `daily_store_sales` (view/table) | Aggregated sales for dashboard charts |

---

## Flyway migration history

| Version | Description |
|---------|-------------|
| V1 | Initial schema (users, stores, products, orders) |
| V2 | Seed business categories |
| V3 | Seed restaurant templates |
| V4 | Demo restaurant store `foodie-demo` |
| V5 | Email verify + password reset tokens |
| V6 | Daily store sales view |
| V7 | Daily store sales table |
| V8 | Login security (attempts, events, IP block) |
| V9 | Production auth (roles, order_code, MFA fields) |
| V10 | Refresh token `session_scope` MERCHANT/ADMIN |
| V11 | CUSTOMER scope, `google_sub` on users |

---

## Useful SQL (local)

```sql
-- List users and roles
SELECT email, role, is_active FROM app_users ORDER BY email;

-- Stores per merchant
SELECT u.email, s.name, s.slug, s.status
FROM stores s JOIN app_users u ON s.owner_id = u.id;

-- Recent orders
SELECT order_code, customer_name, status, total, created_at
FROM customer_orders ORDER BY created_at DESC LIMIT 20;
```
