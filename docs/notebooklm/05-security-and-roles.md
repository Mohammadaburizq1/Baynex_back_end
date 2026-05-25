# khanGates — Security & Roles

## Role matrix

| Role | Merchant API | Admin API | Customer API | Public checkout |
|------|--------------|-----------|--------------|-----------------|
| MERCHANT_OWNER | Yes | No | No | N/A |
| MERCHANT_STAFF | Yes | No | No | N/A |
| CUSTOMER | No | No | Yes | Yes (guest) |
| SUPER_ADMIN | No | Yes (+ MFA) | No | N/A |
| SUPPORT_ADMIN | No | Yes (limited) | No | N/A |
| FINANCE_ADMIN | No | Yes | No | N/A |
| READ_ONLY_ADMIN | No | Yes (read security) | No | N/A |

---

## JWT access token

- Signed with `JWT_SECRET` (min 32 chars in production)
- Default TTL: **15 minutes**
- Claims: `uid`, `role`, `tokenVersion`, `purpose=access`
- Send as: `Authorization: Bearer <token>`

Incrementing `token_version` on user invalidates all existing access tokens (password change, logout-all).

---

## Refresh tokens

| Scope | Cookie name (if cookie mode) | Default TTL |
|-------|------------------------------|-------------|
| MERCHANT | `shoplink_refresh` | 7 days |
| ADMIN | `shoplink_admin_refresh` | 1 day |
| CUSTOMER | `shoplink_customer_refresh` | 30 days |

- Stored **hashed** in `refresh_tokens` table
- **Rotated** on each refresh (old token revoked)
- **Reuse detection** revokes all sessions on theft attempt

Delivery modes (`REFRESH_TOKEN_DELIVERY`):

- `BODY` — JSON only (mobile/dev)
- `COOKIE` — HttpOnly cookies only
- `COOKIE_OR_BODY` — either (local profile default)

---

## Login security

### Progressive lockout

| Account type | Failed attempts → lock |
|--------------|------------------------|
| Merchant | 5 / 10 / 20 → 15 min / 60 min / admin unlock |
| Admin | 3 / 5 / 10 → stricter thresholds |

### Rate limiting (IP / email)

- Login, register, forgot-password, reset-password, refresh, order lookup
- Returns **HTTP 429** when exceeded

### Risk scoring

Login risk score 0–100 may trigger extra verification, alerts, or block.

### Generic errors

Wrong password, wrong portal, unknown email → same **401 "Invalid credentials"** (no enumeration).

---

## MFA (Super Admin)

1. `POST /api/admin/auth/login` → `{ mfaRequired: true, mfaChallengeToken }`
2. `POST /api/admin/auth/mfa/verify` → full `AuthResponse`

Local dev: `MFA_DEV_BYPASS=true` may accept code `000000` — **never in production**.

---

## Production hardening checklist

| Setting | Production value |
|---------|------------------|
| `EXPOSE_AUTH_TOKENS_IN_RESPONSE` | `false` |
| `JWT_SECRET` | Long random secret |
| `REFRESH_COOKIE_SECURE` | `true` |
| `HSTS_ENABLED` | `true` |
| `MFA_DEV_BYPASS` | `false` |
| `CORS_ALLOWED_ORIGINS` | Explicit origins (no `*`) |
| Admin refresh TTL | Shorter than merchant |

`ProductionSecurityValidator` fails startup on `prod` profile if misconfigured.

---

## CORS

Default origins: `http://localhost:8080`, `:3000`, `:5173`, `:4200`  
Local profile adds patterns: `http://localhost:*`, `http://127.0.0.1:*` for Flutter web.

---

## Security headers

- CSP, X-Frame-Options DENY, Referrer-Policy, optional HSTS
- No stack traces in API error responses

---

## Admin audit

SUPER_ADMIN actions (unlock user, block IP, force reset) written to `admin_audit_logs`.

---

## Password policy

Minimum **10 characters** with uppercase, lowercase, digit, and special character (register, reset, change-password).
