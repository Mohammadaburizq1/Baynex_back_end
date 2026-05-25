# khanGates — NotebookLM Documentation Pack

Upload **all `.md` files in this folder** (and optionally `scripts/openapi.json`) into one NotebookLM notebook for a complete knowledge base.

## Recommended upload order

| # | File | What it covers |
|---|------|----------------|
| 1 | `01-platform-overview.md` | Product vision, user types, architecture |
| 2 | `02-auth-api.md` | Full authentication & security API |
| 3 | `03-rest-api-reference.md` | All REST endpoints (merchant, public, admin) |
| 4 | `04-database-schema.md` | PostgreSQL tables, Flyway migrations |
| 5 | `05-security-and-roles.md` | Roles, JWT, rate limits, production rules |
| 6 | `06-setup-and-deployment.md` | Docker, env vars, local run |
| 7 | `07-flutter-client.md` | Flutter app, routes, API config |
| 8 | `08-business-flows.md` | End-to-end user journeys |
| 9 | `09-troubleshooting-faq.md` | Common errors and fixes |

## Optional extra sources

| File | Location |
|------|----------|
| OpenAPI JSON | `scripts/openapi.json` (export from `/v3/api-docs`) |
| API JSON examples | `scripts/shoplink-api-json-examples.json` |
| Backend README | `README.md` (project root) |

## How to use in NotebookLM

1. Go to [notebooklm.google.com](https://notebooklm.google.com)
2. **New notebook** → **Add source** → upload each `.md` file (max 50 sources per notebook on free tier; this pack is 10 files)
3. Ask questions like:
   - "How does merchant login differ from admin login?"
   - "What body do I send for guest checkout?"
   - "Which env vars are required for Gmail?"
   - "Explain the database tables for orders"

## Project paths

| Component | Path |
|-----------|------|
| Backend (Spring Boot) | `shoplink_backend/` |
| Flutter app | `shoplink_flutter/` |
| API base (local) | `http://localhost:8081/api` |
| Flutter web (local) | `http://localhost:8080` |

*Last aligned with backend Flyway V11 (customer auth, Google OAuth, admin password reset).*
