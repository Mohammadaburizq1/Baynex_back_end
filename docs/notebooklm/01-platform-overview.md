# khanGates — Platform Overview

## What is khanGates?

**khanGates** (codebase: ShopLink) is a **multi-tenant SaaS platform** for small businesses to run branded online stores. Merchants manage products, orders, and settings; customers browse storefronts and place orders **without needing a merchant account**.

| Layer | Technology |
|-------|------------|
| Backend API | Java 25, Spring Boot 4, PostgreSQL 16, Flyway |
| Merchant & admin UI | Flutter (web, Android, iOS) |
| Auth | JWT access tokens + hashed refresh tokens |
| Email | SMTP (Gmail or Mailpit locally) |

---

## User types

| User | Role(s) | Primary UI | Purpose |
|------|---------|------------|---------|
| **Merchant owner** | `MERCHANT_OWNER` | `/login` → `/dashboard` | Create stores, products, manage orders |
| **Merchant staff** | `MERCHANT_STAFF` | Same as owner | Limited dashboard access (same API role group) |
| **End customer** | `CUSTOMER` | Storefront + `/api/public/auth/*` | Register/login, order history (future), Google sign-in |
| **Guest shopper** | None | Public storefront | Browse and checkout without account |
| **Platform admin** | `SUPER_ADMIN`, `SUPPORT_ADMIN`, etc. | `/admin/login` → `/admin/dashboard` | Platform operations, security monitoring |

---

## High-level architecture

```
┌─────────────────┐     HTTPS      ┌──────────────────────────┐
│  Flutter Web    │ ──────────────►│  Spring Boot API :8081   │
│  (khanGates UI) │   Bearer JWT   │  /api/auth               │
│  :8080          │                │  /api/public             │
└─────────────────┘                │  /api/dashboard          │
                                   │  /api/admin              │
                                   └───────────┬──────────────┘
                                               │
                                   ┌───────────▼──────────────┐
                                   │  PostgreSQL (shoplink_db) │
                                   └──────────────────────────┘
```

---

## API surface areas

| Prefix | Audience | Auth |
|--------|----------|------|
| `/api/auth` | Merchants | Public login/register; Bearer for `/me`, sessions |
| `/api/public` | Everyone | Storefront, guest orders, categories |
| `/api/public/auth` | Customers | Register, login, Google OAuth |
| `/api/dashboard` | Merchants | Bearer + `MERCHANT_OWNER` or `MERCHANT_STAFF` |
| `/api/admin/auth` | Admins | Separate login + MFA for `SUPER_ADMIN` |
| `/api/admin/security` | Admins | Security monitoring, unlock, IP block |

---

## Core domain concepts

| Concept | Description |
|---------|-------------|
| **Store** | One shop (slug URL, branding, category, template) owned by a merchant |
| **Product** | Item sold by a store (price, images, availability) |
| **Category** | Business taxonomy (global) or per-store product categories |
| **Order** | Customer purchase (`customer_orders` + `order_items`) |
| **Template** | Visual theme for a store category (e.g. restaurant layout) |

---

## Multi-tenancy model

- Each **store** belongs to one **merchant** (`stores.owner_id`).
- Dashboard APIs enforce **store ownership** — merchants only see their stores.
- Public APIs resolve stores by **slug** (e.g. `/api/public/stores/foodie-demo`).
- Platform **admins** can access cross-tenant data via `/api/admin/*` (when implemented in UI).

---

## Split authentication (critical)

khanGates uses **three separate auth portals** — never mix them:

1. **Merchant** — `POST /api/auth/login`
2. **Admin** — `POST /api/admin/auth/login` (+ MFA for super admin)
3. **Customer** — `POST /api/public/auth/login` or `/google`

Each portal issues JWTs with the correct role. Refresh tokens are scoped: `MERCHANT`, `ADMIN`, or `CUSTOMER` (separate cookies when using cookie mode).

---

## Default local URLs

| Service | URL |
|---------|-----|
| API | http://localhost:8081 |
| Swagger | http://localhost:8081/swagger-ui.html |
| Flutter web | http://localhost:8080 |
| Mailpit (test email) | http://localhost:8025 |
| PostgreSQL | localhost:5432 / `shoplink_db` |

---

## Related documents in this pack

- **Auth details:** `02-auth-api.md`
- **Every endpoint:** `03-rest-api-reference.md`
- **Database:** `04-database-schema.md`
- **Flutter:** `07-flutter-client.md`
