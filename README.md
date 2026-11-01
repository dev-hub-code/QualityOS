# QualityOS

Multi-tenant manufacturing quality management and product traceability platform.
A modular monolith: Spring Boot API, Next.js web app, PostgreSQL.

**Status:** Phases 1 to 10 are built and tested locally. This is a development-stage product: it has not been deployed
to production, and GitHub Actions has not run yet (see [CI](docs/CI.md)). Production work (Phase 11) is deferred, so the
Docker Compose stack here is for local use only. See [the implementation plan](docs/IMPLEMENTATION_PLAN.md) for phases
and known gaps.

## What it does

| Area | Included |
|---|---|
| Identity and tenancy | Organisations, roles, permissions, facility limits, invitations, email verification, password reset |
| Master data and purchasing | Facilities, suppliers, materials, products, units, purchase orders, material receipts, attachments |
| Inspection | Versioned templates, sampling, incoming, in-process and final inspection, approval, disposition |
| NCR and CAPA | Containment, root cause, corrective actions, verification, closure |
| Traceability | Production batches, serial numbers, dispatch, returns, material corrections, forward and backward traces, recalls |
| Calibration and audits | Instruments, calibration records, audit checklists, findings that raise NCRs |
| Analytics | Dashboard, supplier scorecards, Pareto, PDF and Excel reports |
| Subscription | Plans, limits, trials, invoices, and the Control Center at `/platform`, with operator roles, audit log, support console, settings and announcements. Payments are taken online through Cashfree once its keys are set, or recorded by hand, and invoices are not yet GST tax invoices |

## Prerequisites

Docker with Compose. For running outside containers: Java 25, Node 26.

## Run everything in Docker

```bash
docker compose up --build -d --wait
```

| Service | URL |
|---|---|
| Web app | http://localhost:3000 (create an organisation at `/register`) |
| System status | http://localhost:3000/status |
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |

Ports bind to 127.0.0.1 and can be changed in `.env` (see `.env.example`). Credentials default to
local-only values. JWT keys are generated at each backend start, so a restart signs everyone out. Role passwords are set when the Postgres volume is first created, so after changing
them run `docker compose down -v`, which **deletes the local database**.

## Run the apps on the host

```bash
docker compose up -d --wait postgres
cd backend && ./mvnw spring-boot:run      # local profile; DB defaults in application-local.yml
cd frontend && npm install && npm run dev # BACKEND_URL defaults to http://localhost:8080
```

## Tests

```bash
cd backend && ./mvnw verify      # unit tests, plus integration tests on Testcontainers (needs Docker)

cd frontend
npm run typecheck && npm run lint && npm test
npx playwright install chromium  # first time only
npm run test:e2e                 # needs the running stack
```

These commands are the current evidence. CI has not run them on GitHub yet.

## Documentation

- [Architecture and technology decisions](docs/ARCHITECTURE.md)
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md)
- [Database schema](docs/DATABASE_SCHEMA.md)
- [Tenant isolation strategy](docs/TENANT_ISOLATION.md)
- [API contracts](docs/API_CONTRACTS.md)
- [Analytics definitions](docs/ANALYTICS.md)
- [Continuous integration](docs/CI.md)
- [Security review](docs/SECURITY_REVIEW.md)
- [Platform administration](docs/PLATFORM_ADMIN.md)
