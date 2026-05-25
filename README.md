# ShopLink Backend

Secure Spring Boot REST API for the Byonix / ShopLink multi-store SaaS app.

## Stack

- Java 21, Spring Boot 3, Maven
- Spring Web, Security, Validation, Data JPA
- PostgreSQL 16, Flyway
- JWT access tokens and hashed refresh tokens
- Lombok, MapStruct dependency, Springdoc OpenAPI

## Local Run

1. Start PostgreSQL with database `shoplink_db` and user `shoplink_user`.
2. Set environment variables or use the local defaults in `src/main/resources/application.yml`.
3. Run:

```bash
mvn spring-boot:run
```

The API runs on `http://localhost:8081`.

## Docker Run

```bash
docker compose up --build
```

Services:

- Backend: `http://localhost:8081`
- PostgreSQL: `localhost:5432`
- Health: `http://localhost:8081/actuator/health`
- Swagger: `http://localhost:8081/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8081/v3/api-docs`

## Environment Variables

| Variable | Default |
| --- | --- |
| `DB_HOST` | `localhost` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `shoplink_db` |
| `DB_USERNAME` | `shoplink_user` |
| `DB_PASSWORD` | `shoplink_password` |
| `JWT_SECRET` | local placeholder, replace in real environments |
| `JWT_ACCESS_EXPIRATION_MINUTES` | `15` |
| `JWT_REFRESH_EXPIRATION_DAYS` | `7` |
| `CORS_ALLOWED_ORIGINS` | localhost frontend origins |
| `SPRING_PROFILES_ACTIVE` | `local` |
| `SWAGGER_ENABLED` | `true` |

Do not use `*` for CORS in production. Set a long random `JWT_SECRET`.

## Flyway

Migrations run automatically on startup:

- `V1__init_schema.sql`: schema, constraints, indexes
- `V2__seed_business_categories.sql`: business and restaurant categories
- `V3__seed_restaurant_templates.sql`: restaurant templates
- `V4__seed_demo_restaurant_store.sql`: demo restaurant and food items

## Auth Examples

Register:

```bash
curl -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Merchant User","email":"merchant@example.com","password":"StrongPass123!","phone":"+962790000000"}'
```

Login:

```bash
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"merchant@example.com","password":"StrongPass123!"}'
```

Use the returned access token:

```bash
curl http://localhost:8081/api/dashboard/stores/my \
  -H "Authorization: Bearer ACCESS_TOKEN"
```

## Demo Endpoints

- `GET /api/public/categories/business`
- `GET /api/public/categories/business/restaurants-cafes/subcategories`
- `GET /api/public/stores/foodie-demo`
- `GET /api/public/stores/foodie-demo/homepage`
- `GET /api/public/stores/foodie-demo/products`
- `GET /api/public/templates/category/restaurants-cafes`

## Security Notes

- Passwords are BCrypt hashed and never returned by DTOs.
- Refresh tokens are random, hashed before database storage, rotated on refresh, and revoked on logout.
- JWT access tokens include `tokenVersion`; incrementing the user token version invalidates existing tokens.
- Dashboard services enforce store ownership. Super admins can access all data.
- Sensitive endpoints have IP-based fixed-window rate limiting and return HTTP 429 when exceeded.
- Validation DTOs constrain emails, slugs, colors, phones, URLs, amounts, and note lengths.
- Error responses are consistent and avoid stack traces, SQL internals, and JWT internals.
