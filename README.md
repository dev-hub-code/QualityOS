# QualityOS

Multi-tenant manufacturing quality management and product traceability platform.
A modular monolith: Spring Boot API, Next.js web app, PostgreSQL.

**Status:** Phases 1 and 2 (foundation, identity and tenancy) complete. See [the implementation plan](docs/IMPLEMENTATION_PLAN.md).

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

## Documentation

- [Architecture and technology decisions](docs/ARCHITECTURE.md)
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md)
- [Database schema](docs/DATABASE_SCHEMA.md)
- [Tenant isolation strategy](docs/TENANT_ISOLATION.md)
- [API contracts](docs/API_CONTRACTS.md)
