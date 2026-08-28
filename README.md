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

## Testing

```bash
mvn test
```

No Docker or running Postgres required. Unit tests use Mockito. Any test annotated
`@SpringBootTest` with `@ActiveProfiles("test")` boots the real app against an in-memory H2
database (`src/test/resources/application-test.yml`) and runs the **real** Flyway migrations
from `src/main/resources/db/migration` against it — the same files that run against Postgres in
every other environment. `StorePublishLifecycleIT` is the reference example: it hits real HTTP
endpoints (`MockMvc`) through the real controllers, security filter chain, validation, and JPA.

**Why this works on H2 at all**: H2's `MODE=PostgreSQL` (set in the test datasource URL) covers
most of Postgres's dialect (casts, `AT TIME ZONE`, `now()`, `gen_random_uuid()`, views, standard
index syntax). Four spots the migrations use that H2 still can't parse — `CREATE EXTENSION`, and
three partial/functional unique indexes — are Flyway placeholders (`${pgcryptoExtensionStatement}`
etc.) with the real Postgres statement as the default, defined once in `application.yml`.
`application-test.yml` overrides those four placeholders with H2-compatible equivalents; every
other environment is unaffected. See the comments next to the placeholder values in both files
for what each fallback gives up (mainly: case-insensitive email uniqueness and global-category
slug uniqueness aren't enforced by the H2 fallback indexes — full detail in
`application-test.yml`). Two other patterns needed direct rewrites in the migration files
themselves rather than placeholders, because they're behaviorally identical on Postgres either
way (not test-only shims): `DEFAULT ... PRIMARY KEY` column-constraint ordering (H2 requires
`DEFAULT` before `PRIMARY KEY`; Postgres accepts either order) and `TIMESTAMP WITH TIME ZONE`
spelled out instead of the `TIMESTAMPTZ` abbreviation (H2 doesn't recognize the abbreviation).

**If you have an existing local Postgres volume from before this change**: several migration
files' bytes changed (even though the SQL Postgres actually executes is identical), so their
Flyway checksums changed too. A fresh `docker compose up` (new volume) is unaffected. An
*existing* volume with these migrations already applied will fail Flyway's checksum validation
on next boot — run `docker compose down -v` to recreate it, or `flyway repair` if you need to
keep existing data.

**Known gap**: a few native queries (`UserRepository.findByPhoneDigits` /
`existsByPhoneDigits`) use Postgres-only `regexp_replace(..., 'g')` syntax that H2 doesn't
support the same way. Phone-based registration/login flows will fail against H2 as a result — a
test that needs those should still expect to run against real Postgres, or the query needs a
portable rewrite first (out of scope here; flagging so it doesn't surprise the next person).

**MockMvc note**: `@AutoConfigureMockMvc` and `spring-security-test`'s `springSecurity()`
configurer aren't resolvable in this project's dependency set in offline mode (Spring Boot 4
moved MockMvc's test-autoconfiguration to a module not currently cached locally). Wire it
manually instead — see `StorePublishLifecycleIT`'s `@BeforeEach`:

```java
Filter springSecurityFilterChain = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .addFilters(springSecurityFilterChain)
        .build();
```

Authenticate by persisting a `User` via `UserRepository` and minting a real token with
`JwtService.createAccessToken(user)` — no need to go through the registration/login endpoints.

**Frontend/backend field-mapping regression check**: `shoplink_nextjs/lib/api/*.ts` files each
declare a `*Raw` interface describing the exact JSON a backend DTO produces, and a `map*()`
function that adapts it to what the UI consumes — added after a bug where a frontend type was
just assumed to match the backend (it didn't: `nameEn`/`salePrice`/no `stock` field vs. the
frontend's `name`/`discountPrice`/`stock`) and went unnoticed because 500s weren't logged. Run
`npm run verify-backend-shapes` (or `node verify-backend-shapes.mjs`) from `shoplink_nextjs`
against a running backend (H2 test profile above, or real Postgres) to check the live JSON
still matches what those `*Raw` interfaces expect — it seeds a store/product/order through the
real HTTP API and asserts specific field names/types on each response. A backend DTO rename
without a matching frontend update fails this loudly instead of silently breaking the UI; a
frontend-side typo in a `map*()` function (e.g. `raw.name` instead of `raw.nameEn`) is instead
caught by `tsc` at compile time, since the `*Raw` interfaces only declare the fields that
actually exist on the wire.

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
