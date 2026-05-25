# khanGates — Troubleshooting & FAQ

---

## Backend won't start

| Symptom | Fix |
|---------|-----|
| Docker not running | Start Docker Desktop, wait until ready |
| Flyway migration failed | Check `docker compose logs backend`; fix DB state or reset volume |
| Port 8081 in use | Stop other process or change `SERVER_PORT` |
| JWT_SECRET too short (prod) | Use 32+ character secret |

---

## Flutter ERR_CONNECTION_REFUSED :8081

Backend is down. Run:

```powershell
cd shoplink_backend
docker compose up -d
curl http://localhost:8081/actuator/health
```

---

## Login returns 401

| Cause | Fix |
|-------|-----|
| Wrong password | Reset via SQL or forgot-password |
| Wrong login portal | Merchants → `/login` + `/api/auth/login`. Admins → `/admin/login` |
| Email typo | Match DB exactly (e.g. `mohammadsarq@gmail.com`) |
| Account is admin trying merchant login | Use admin login |
| Account locked | Admin unlock or wait for lock expiry |

---

## Lands on admin dashboard instead of shop dashboard

| Cause | Fix |
|-------|-----|
| Opened `/admin/login` or old admin session | Use http://localhost:8080/login |
| Browser cached admin tokens | Clear site data for localhost:8080 |
| Hot restart Flutter after auth fixes | Full restart `run_web.ps1` |

---

## CORS error in browser

- Ensure backend running with `application-local` profile (origin patterns)
- Or set `CORS_ALLOWED_ORIGINS` to your Flutter URL
- API base must be `http://localhost:8081/api` not `:8081` alone

---

## Email / forgot-password not received

| Check | Action |
|-------|--------|
| MAIL_ENABLED | true in `.env` |
| MAIL_PASSWORD | Gmail **app password**, not login password |
| Docker env | `docker compose up -d --force-recreate backend` |
| Mailpit (local) | Open http://localhost:8025 |
| FRONTEND_BASE_URL | Must match Flutter port (8080) |

---

## Reset link opens wrong page / 404

- Flutter must run on port **8080** (or update `FRONTEND_BASE_URL`)
- Path `/reset-password` must exist in Flutter router
- Admin reset uses `/admin/reset-password`

---

## Google sign-in fails

| Check | Action |
|-------|--------|
| GOOGLE_OAUTH_CLIENT_IDS | Set in `.env`, restart backend |
| Token type | Send **ID token**, not access token |
| Email exists as merchant | Cannot auto-login as customer (generic error) |

---

## Swagger /v3/api-docs 500

- Use springdoc 3.x with Spring Boot 4 (already in pom.xml)
- Rebuild: `docker compose build backend`

---

## PostgreSQL connection

```
Host: localhost
Port: 5432
Database: shoplink_db
User: shoplink_user
Password: shoplink_password
```

---

## pgAdmin: reset merchant password

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

UPDATE app_users
SET password_hash = crypt('YourNewPassword1!', gen_salt('bf', 12)),
    failed_login_count = 0,
    locked_until = NULL
WHERE lower(email) = lower('your@email.com');

SELECT crypt('YourNewPassword1!', password_hash) = password_hash AS ok
FROM app_users WHERE lower(email) = lower('your@email.com');
```

Password rules: 10+ chars, upper, lower, number, special.

---

## Port 8080 already in use (Flutter)

Another Flutter instance running. Kill it or use:

```powershell
flutter run -d chrome --web-port=8082 ...
```

---

## Quick health checklist

- [ ] `docker compose ps` — postgres + backend Up
- [ ] http://localhost:8081/actuator/health → UP
- [ ] http://localhost:8080 loads Flutter
- [ ] Merchant login at `/login` not `/admin/login`
- [ ] `.env` loaded by backend container
