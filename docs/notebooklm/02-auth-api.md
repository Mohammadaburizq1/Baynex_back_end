# khanGates — Authentication & Security API

> **Purpose:** Reference document for **NotebookLM**, Flutter/mobile clients, and API consumers.  
> **Base URL (local):** `http://localhost:8081`  
> **API prefix:** `/api`  
> **Swagger UI:** `http://localhost:8081/swagger-ui.html`

---

## 1. Overview

khanGates (ShopLink backend) uses a **split authentication model**:

| Audience | Login endpoint | Who can sign in |
|----------|----------------|-----------------|
| **Merchants** (shop owners & staff) | `POST /api/auth/login` | `MERCHANT_OWNER`, `MERCHANT_STAFF` |
| **Platform admins** | `POST /api/admin/auth/login` | `SUPER_ADMIN`, `SUPPORT_ADMIN`, `FINANCE_ADMIN`, `READ_ONLY_ADMIN` |

**Critical rule:** Admin accounts **cannot** use merchant login, and merchants **cannot** use admin login. Wrong portal always returns **401** with a generic **"Invalid credentials"** message (no account enumeration).

**Tokens:**

- **Access token** — short-lived JWT (default **15 minutes**). Send as `Authorization: Bearer <accessToken>`.
- **Refresh token** — stored hashed in DB; returned in JSON body and/or **HttpOnly cookies** depending on config.

---

## 2. Response envelope

All successful API responses use:

```json
{
  "success": true,
  "message": "OK",
  "data": { },
  "errors": null
}
```

Errors:

```json
{
  "success": false,
  "message": "Validation failed",
  "data": null,
  "errors": { "field": "reason" }
}
```

Auth failures (login, refresh) typically return **HTTP 401** with a generic message, not detailed reasons.

---

## 3. Roles

| Role | Type | Merchant login | Admin login | MFA on admin login |
|------|------|----------------|-------------|-------------------|
| `MERCHANT_OWNER` | Merchant | Yes | No | — |
| `MERCHANT_STAFF` | Merchant | Yes | No | — |
| `SUPER_ADMIN` | Admin | No | Yes | **Required** (step 2) |
| `SUPPORT_ADMIN` | Admin | No | Yes | No |
| `FINANCE_ADMIN` | Admin | No | Yes | No |
| `READ_ONLY_ADMIN` | Admin | No | Yes | No |
| `CUSTOMER` | Public | No | No | — |

**Admin security permissions:**

- View monitoring (`login-attempts`, `security-events`, etc.): `SUPER_ADMIN`, `SUPPORT_ADMIN`, `READ_ONLY_ADMIN`
- Write security (`unlock`, `force-password-reset`, `block-ip`): **`SUPER_ADMIN` only**

---

## 4. Merchant authentication (`/api/auth`)

### 4.1 Register

`POST /api/auth/register`

**Body:**

```json
{
  "fullName": "Ahmad Khan",
  "email": "owner@example.com",
  "password": "StrongPass123!",
  "phone": "+962790000000"
}
```

**Password rules:** minimum 10 characters; at least one uppercase, lowercase, digit, and special character.

**Response `data`:** `AuthResponse` (access + refresh tokens + user).

---

### 4.2 Login

`POST /api/auth/login`

**Body:**

```json
{
  "email": "owner@example.com",
  "password": "StrongPass123!"
}
```

**Response `data`:**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "opaque-refresh-token-string",
  "user": {
    "id": "uuid",
    "fullName": "Ahmad Khan",
    "email": "owner@example.com",
    "phone": "+962790000000",
    "role": "MERCHANT_OWNER",
    "active": true,
    "tokenVersion": 1,
    "emailVerified": false
  },
  "riskScore": 0,
  "requiresExtraVerification": false
}
```

**Production note:** `emailVerificationToken` and password-reset tokens are **never** returned in API responses. Emails contain links only.

---

### 4.3 Refresh session

`POST /api/auth/refresh`

**Body (when `refresh-token-delivery=BODY`):**

```json
{
  "refreshToken": "your-refresh-token"
}
```

**Cookie mode:** send HttpOnly cookie `shoplink_refresh` instead (local profile often uses `COOKIE_OR_BODY`).

**Response:** new `AuthResponse` with rotated refresh token.

---

### 4.4 Logout

`POST /api/auth/logout`

Revokes the refresh token (body or cookie). Returns a **generic** logout message (same whether or not the token existed).

---

### 4.5 Current user

`GET /api/auth/me`  
**Header:** `Authorization: Bearer <accessToken>`

**Response `data`:** `UserResponse` (no tokens).

---

### 4.6 Email verification

| Endpoint | Description |
|----------|-------------|
| `POST /api/auth/verify-email` | Body: `{ "token": "..." }` from email link |
| `POST /api/auth/verify-email/resend` | Body: `{ "email": "..." }` — generic success message |

Email link format: `{FRONTEND_BASE_URL}/verify-email?token=...`  
Default frontend: `http://localhost:8080`

---

### 4.7 Forgot / reset password

| Endpoint | Body |
|----------|------|
| `POST /api/auth/forgot-password` | `{ "email": "..." }` |
| `POST /api/auth/reset-password` | `{ "token": "...", "newPassword": "NewStrong1!" }` |

Reset link: `{FRONTEND_BASE_URL}/reset-password?token=...`

Always returns generic messages (does not reveal if email exists).

---

## 5. Merchant account security (`/api/auth` — authenticated)

Requires Bearer access token.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/auth/sessions` | List active refresh sessions |
| `DELETE` | `/api/auth/sessions/{sessionId}` | Revoke one session |
| `POST` | `/api/auth/logout-all` | Revoke all refresh tokens for user |
| `GET` | `/api/auth/security/events` | Paginated security events for current user |
| `POST` | `/api/auth/change-password` | Body: `currentPassword`, `newPassword` |

**Change password body:**

```json
{
  "currentPassword": "OldStrong1!",
  "newPassword": "NewStrong2!"
}
```

---

## 6. Platform admin authentication (`/api/admin/auth`)

### 6.1 Admin login (step 1)

`POST /api/admin/auth/login`

**Body:** same shape as merchant login (`email`, `password`).

**Response A — MFA required (`SUPER_ADMIN`):**

```json
{
  "mfaRequired": true,
  "mfaChallengeToken": "eyJhbGciOiJIUzI1NiIs...",
  "auth": null
}
```

**Response B — no MFA (other admin roles):**

```json
{
  "mfaRequired": false,
  "mfaChallengeToken": null,
  "auth": { "accessToken": "...", "refreshToken": "...", "user": { ... } }
}
```

---

### 6.2 MFA verify (step 2)

`POST /api/admin/auth/mfa/verify`

**Body:**

```json
{
  "mfaChallengeToken": "token-from-step-1",
  "mfaCode": "123456"
}
```

**Response `data`:** full `AuthResponse` (access + refresh + user).

**Local development:** when `app.security.login.mfa-dev-bypass=true`, code `000000` may be accepted (never enable in production).

---

### 6.3 Admin refresh / logout / me

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/api/admin/auth/refresh` | Uses cookie `shoplink_admin_refresh` or JSON body |
| `POST` | `/api/admin/auth/logout` | Revokes admin refresh session |
| `GET` | `/api/admin/auth/me` | Requires admin role in JWT |

Admin refresh TTL is **shorter** than merchant (default **1 day** vs **7 days**).

---

## 7. Admin security monitoring (`/api/admin/security`)

All routes require **admin** Bearer token.

| Method | Path | Permission | Purpose |
|--------|------|------------|---------|
| `GET` | `/login-attempts` | View monitoring | Paginated login attempts |
| `GET` | `/security-events` | View monitoring | Platform security events |
| `GET` | `/suspicious-activity` | View monitoring | High-risk attempts |
| `GET` | `/locked-users` | View monitoring | Locked accounts |
| `POST` | `/users/{userId}/unlock` | **SUPER_ADMIN** | Unlock account |
| `POST` | `/users/{userId}/force-password-reset` | **SUPER_ADMIN** | Force password reset flag |
| `POST` | `/block-ip` | **SUPER_ADMIN** | Block IP address |
| `POST` | `/unblock-ip?ip=...` | **SUPER_ADMIN** | Remove IP block |

Admin actions are **audit-logged**.

---

## 8. JWT access token claims

Access JWT includes (among others):

| Claim | Meaning |
|-------|---------|
| `sub` | User email |
| `uid` | User UUID |
| `role` | e.g. `MERCHANT_OWNER`, `SUPER_ADMIN` |
| `tokenVersion` | Invalidates tokens after password change / logout-all |
| `purpose` | `access` (or `mfa_challenge` for MFA step only) |

---

## 9. Refresh token delivery modes

Configured via `app.auth.refresh-token-delivery`:

| Mode | Merchant cookie | Admin cookie | Mobile / Flutter dev |
|------|-----------------|--------------|----------------------|
| `BODY` | — | — | Token in JSON only |
| `COOKIE` | `shoplink_refresh` | `shoplink_admin_refresh` | Cookie only |
| `COOKIE_OR_BODY` | Either | Either | Both accepted |

Cookies are **HttpOnly**, **Secure** (configurable), **SameSite=Strict** by default.

Refresh tokens are stored **hashed** in PostgreSQL with scope `MERCHANT` or `ADMIN`.

---

## 10. Login security (server-side)

On every login attempt the server may:

1. Rate-limit by IP, email, and email+IP
2. Check IP blocklist
3. Apply progressive delay after failed attempts
4. Lock account after thresholds (stricter for admin than merchant)
5. Calculate **risk score** (0–100)
6. Log attempt and security events

**Typical lock thresholds:**

| User type | Failures → lock |
|-----------|-----------------|
| Merchant | 5 / 10 / 20 → 15 min / 60 min / admin unlock |
| Admin | 3 / 5 / 10 → 15 min / 60 min / admin unlock |

Failed login always returns the same generic error to the client.

---

## 11. Flutter client mapping

| UI | URL | API |
|----|-----|-----|
| Merchant login | `http://localhost:8080/login` | `POST /api/auth/login` |
| Admin login | `http://localhost:8080/admin/login` | `POST /api/admin/auth/login` (+ MFA) |

**Dart config:**

```text
API_BASE_URL=http://localhost:8081/api
USE_BACKEND_API=true
```

Token storage:

- Merchant: `shoplink_api_access_token` / `shoplink_api_refresh_token`
- Admin: `shoplink_admin_access_token` / `shoplink_admin_refresh_token`

After merchant login, the app routes to `/dashboard` (shop). Admin goes to `/admin/dashboard`.

---

## 12. Environment variables (auth-related)

| Variable | Default | Description |
|----------|---------|-------------|
| `JWT_SECRET` | (required, 32+ chars) | Signs JWTs |
| `JWT_ACCESS_EXPIRATION_MINUTES` | `15` | Access token lifetime |
| `JWT_REFRESH_EXPIRATION_DAYS` | `7` | Merchant refresh |
| `JWT_ADMIN_REFRESH_EXPIRATION_DAYS` | `1` | Admin refresh |
| `REFRESH_TOKEN_DELIVERY` | `BODY` | `BODY`, `COOKIE`, `COOKIE_OR_BODY` |
| `REQUIRE_EMAIL_VERIFICATION_FOR_LOGIN` | `false` | Block login if email unverified |
| `REQUIRE_SUPER_ADMIN_MFA` | `true` | MFA for `SUPER_ADMIN` |
| `MFA_DEV_BYPASS` | `false` | Allow `000000` locally only |
| `EXPOSE_AUTH_TOKENS_IN_RESPONSE` | `false` | Dev: log tokens at DEBUG |
| `FRONTEND_BASE_URL` | `http://localhost:8080` | Email link base |
| `MAIL_FROM_NAME` | `khanGates` | Email sender display name |
| `CORS_ALLOWED_ORIGINS` | localhost ports | Allowed browser origins |

**Local profile (`application-local.yml`):** often sets `expose-tokens-in-response: true` and `refresh-token-delivery: COOKIE_OR_BODY`.

---

## 13. Quick curl examples

**Merchant login:**

```bash
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"owner@example.com\",\"password\":\"StrongPass123!\"}"
```

**Admin login (step 1):**

```bash
curl -X POST http://localhost:8081/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"admin@shoplink.app\",\"password\":\"YourPassword\"}"
```

**Authenticated request:**

```bash
curl http://localhost:8081/api/auth/me \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

---

## 14. FAQ / troubleshooting

| Problem | Likely cause | Fix |
|---------|--------------|-----|
| 401 on merchant login | Wrong password, typo in email, or user is **admin** | Use `/api/admin/auth/login` for admins |
| 401 on admin login | User is **merchant** | Use `/api/auth/login` |
| Lands on admin UI after merchant login | Stale admin session in browser | Clear site data; use `/login`; restart Flutter |
| Reset email link broken | `FRONTEND_BASE_URL` wrong or Flutter not on that port | Set `FRONTEND_BASE_URL=http://localhost:8080` and run Flutter web on 8080 |
| CORS error from Flutter web | Origin not allowed | Add origin or use `allowed-origin-patterns` for `http://localhost:*` |
| MFA stuck | Missing step 2 | Call `/api/admin/auth/mfa/verify` with challenge token + code |

---

## 15. Customer authentication (`/api/public/auth`)

End-customer accounts use role `CUSTOMER` and refresh scope `CUSTOMER` (cookie: `shoplink_customer_refresh`).

| Method | Path | Auth |
|--------|------|------|
| `POST` | `/api/public/auth/register` | Public |
| `POST` | `/api/public/auth/login` | Public |
| `POST` | `/api/public/auth/google` | Public — body: `{ "idToken": "..." }` |
| `POST` | `/api/public/auth/refresh` | Public |
| `POST` | `/api/public/auth/logout` | Public |
| `POST` | `/api/public/auth/forgot-password` | Public (generic response) |
| `POST` | `/api/public/auth/reset-password` | Public |
| `GET` | `/api/public/auth/me` | Bearer + `CUSTOMER` role |

**Google Sign-In:** set `GOOGLE_OAUTH_CLIENT_IDS` (comma-separated OAuth client IDs from Google Cloud Console).

---

## 16. Admin password reset

| Method | Path |
|--------|------|
| `POST` | `/api/admin/auth/forgot-password` |
| `POST` | `/api/admin/auth/reset-password` |

Only users with an **admin** role receive reset emails. Reset link path: `/admin/reset-password` on `FRONTEND_BASE_URL`.

---

## 17. Endpoint index (auth only)

### Merchant — public

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `POST /api/auth/verify-email`
- `POST /api/auth/verify-email/resend`
- `POST /api/auth/forgot-password`
- `POST /api/auth/reset-password`

### Merchant — Bearer required

- `GET /api/auth/me`
- `GET /api/auth/sessions`
- `DELETE /api/auth/sessions/{sessionId}`
- `POST /api/auth/logout-all`
- `GET /api/auth/security/events`
- `POST /api/auth/change-password`

### Customer — public

- `POST /api/public/auth/register`
- `POST /api/public/auth/login`
- `POST /api/public/auth/google`
- `POST /api/public/auth/refresh`
- `POST /api/public/auth/logout`
- `POST /api/public/auth/forgot-password`
- `POST /api/public/auth/reset-password`

### Customer — Bearer required

- `GET /api/public/auth/me`

### Admin — public

- `POST /api/admin/auth/login`
- `POST /api/admin/auth/mfa/verify`
- `POST /api/admin/auth/refresh`
- `POST /api/admin/auth/logout`
- `POST /api/admin/auth/forgot-password`
- `POST /api/admin/auth/reset-password`

### Admin — Bearer required

- `GET /api/admin/auth/me`
- `GET /api/admin/security/login-attempts`
- `GET /api/admin/security/security-events`
- `GET /api/admin/security/suspicious-activity`
- `GET /api/admin/security/locked-users`
- `POST /api/admin/security/users/{userId}/unlock`
- `POST /api/admin/security/users/{userId}/force-password-reset`
- `POST /api/admin/security/block-ip`
- `POST /api/admin/security/unblock-ip`

---

*Document version: aligned with khanGates / ShopLink backend production auth hardening (JWT, MFA, separate admin/merchant flows, refresh cookies, rate limits, audit logs).*
