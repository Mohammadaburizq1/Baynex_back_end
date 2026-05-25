# khanGates — Flutter Client Guide

**Project:** `shoplink_flutter/`  
**Brand name in UI:** khanGates

---

## Run locally

```powershell
cd c:\Users\user\Desktop\shoplink_flutter
.\run_web.ps1
```

Equivalent:

```powershell
flutter run -d chrome --web-port=8080 `
  --dart-define=API_BASE_URL=http://localhost:8081/api `
  --dart-define=USE_BACKEND_API=true
```

---

## API configuration

| Dart define | Default | Purpose |
|-------------|---------|---------|
| API_BASE_URL | http://localhost:8081/api | Must include `/api` |
| USE_BACKEND_API | true | false = mock data only |

Defined in `lib/core/api/api_config.dart`.

---

## Three login surfaces (do not mix)

| User | Flutter route | API endpoint |
|------|---------------|--------------|
| Merchant | `/login` | POST `/api/auth/login` |
| Admin | `/admin/login` | POST `/api/admin/auth/login` |
| Customer | (storefront — future) | POST `/api/public/auth/login` |

**Important:** Merchant login always navigates to `/dashboard`. Admin goes to `/admin/dashboard`. Stale admin sessions are cleared when opening merchant login.

---

## Token storage (SharedPreferences)

| Audience | Access key | Refresh key |
|----------|------------|-------------|
| Merchant | shoplink_api_access_token | shoplink_api_refresh_token |
| Admin | shoplink_admin_access_token | shoplink_admin_refresh_token |

Customer tokens will use separate keys when storefront auth UI is wired.

---

## Key routes

| Path | Screen |
|------|--------|
| `/` | Landing |
| `/login` | Merchant login |
| `/register` → `/onboarding` | Registration flow |
| `/dashboard` | Merchant home |
| `/dashboard/products` | Products |
| `/dashboard/orders` | Orders |
| `/store/{slug}` | Public storefront |
| `/admin/login` | Admin login |
| `/admin/dashboard` | Admin panel |
| `/reset-password?token=` | Password reset |

---

## Merchant flow after login

1. `POST /api/auth/login` → save tokens
2. `GET /api/dashboard/stores/my` — if empty → `/onboarding`
3. Else → `/dashboard`

Store create: `POST /api/dashboard/stores` with `StoreRequest` payload (category slugs from Flyway V2 seed).

---

## Admin flow

1. `POST /api/admin/auth/login`
2. If `mfaRequired` → MFA screen → `POST /api/admin/auth/mfa/verify`
3. Navigate to `/admin/dashboard`

---

## Password reset (Flutter web)

Email links point to:

- Merchant: `http://localhost:8080/reset-password?token=...`
- Admin: `http://localhost:8080/admin/reset-password?token=...` (path from backend `MAIL_ADMIN_PASSWORD_RESET_PATH`)

Backend `FRONTEND_BASE_URL` must match Flutter port (8080).

---

## CORS

Backend allows `http://localhost:*` in local profile so Flutter web works on port 8080.

---

## Mock mode

```powershell
flutter run -d chrome --dart-define=USE_BACKEND_API=false
```

Uses in-memory mock data — no database writes.

---

## Project structure (high level)

```
lib/
  app.dart                 # MaterialApp, theme
  core/api/                # HTTP client, auth services
  core/routing/            # GoRouter routes
  features/auth/           # Merchant login
  features/dashboard/      # Merchant dashboard
  features/landing/        # Marketing landing
  features/storefront/     # Public store pages
  admin/                   # Admin panel (separate tree)
  l10n/                    # English + Arabic strings
```
