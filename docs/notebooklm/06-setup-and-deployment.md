# khanGates — Setup & Deployment

## Prerequisites

- Docker Desktop (recommended)
- Or: Java 25 + Maven + PostgreSQL 16
- Flutter SDK (for frontend) at `C:\src\flutter` or PATH

---

## Quick start (Docker)

```powershell
cd c:\Users\user\Desktop\shoplink_backend
copy .env.example .env
# Edit .env — set MAIL_PASSWORD for Gmail if needed
docker compose up --build -d
```

| Service | URL |
|---------|-----|
| API | http://localhost:8081 |
| Health | http://localhost:8081/actuator/health |
| Swagger | http://localhost:8081/swagger-ui.html |
| Mailpit | http://localhost:8025 |
| Postgres | localhost:5432 |

---

## Flutter frontend

```powershell
cd c:\Users\user\Desktop\shoplink_flutter
.\run_web.ps1
```

Opens **http://localhost:8080**

| Screen | URL |
|--------|-----|
| Merchant login | http://localhost:8080/login |
| Admin login | http://localhost:8080/admin/login |

---

## Environment variables (`.env`)

### Database

| Variable | Default |
|----------|---------|
| DB_HOST | postgres (Docker) / localhost |
| DB_NAME | shoplink_db |
| DB_USERNAME | shoplink_user |
| DB_PASSWORD | shoplink_password |

### JWT

| Variable | Default |
|----------|---------|
| JWT_SECRET | Must be 32+ chars in production |
| JWT_ACCESS_EXPIRATION_MINUTES | 15 |
| JWT_REFRESH_EXPIRATION_DAYS | 7 |
| JWT_ADMIN_REFRESH_EXPIRATION_DAYS | 1 |
| JWT_CUSTOMER_REFRESH_EXPIRATION_DAYS | 30 |

### Email

| Variable | Example |
|----------|---------|
| MAIL_ENABLED | true |
| MAIL_HOST | smtp.gmail.com |
| MAIL_PORT | 587 |
| MAIL_USERNAME | info@khangates.com |
| MAIL_PASSWORD | Gmail app password |
| MAIL_FROM | info@khangates.com |
| MAIL_FROM_NAME | khanGates |
| FRONTEND_BASE_URL | http://localhost:8080 |

### Auth / OAuth

| Variable | Purpose |
|----------|---------|
| GOOGLE_OAUTH_CLIENT_IDS | Comma-separated Google client IDs |
| REFRESH_TOKEN_DELIVERY | BODY / COOKIE / COOKIE_OR_BODY |
| REQUIRE_SUPER_ADMIN_MFA | true |
| MFA_DEV_BYPASS | false (local only: true + code 000000) |

### CORS

| Variable | Purpose |
|----------|---------|
| CORS_ALLOWED_ORIGINS | Comma-separated origins |
| CORS_ALLOWED_ORIGIN_PATTERNS | e.g. http://localhost:* |

---

## Create admin user (SQL)

Run in pgAdmin on `shoplink_db`:

```sql
-- See scripts/create-admin-user.sql
```

Or PowerShell:

```powershell
.\scripts\create-admin-user.ps1 -Email "admin@shoplink.app" -Password "Admin@123456789"
```

---

## Useful commands

```powershell
# Restart backend after .env change
docker compose up -d --force-recreate backend

# Logs
docker compose logs -f backend

# Stop all
docker compose down

# Export OpenAPI for NotebookLM
curl.exe -s http://localhost:8081/v3/api-docs -o scripts/openapi.json
```

---

## Profiles

| Profile | File | Notes |
|---------|------|-------|
| local (default) | application-local.yml | Mailpit, CORS patterns, expose tokens in dev |
| prod | application-prod.yml | Strict validation |

Set `SPRING_PROFILES_ACTIVE=prod` in production.

---

## Ports summary

| Port | Service |
|------|---------|
| 8081 | Spring Boot API |
| 8080 | Flutter web |
| 5432 | PostgreSQL |
| 8025 | Mailpit UI |
| 1025 | Mailpit SMTP |
