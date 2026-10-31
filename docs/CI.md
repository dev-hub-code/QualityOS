# Continuous integration

`.github/workflows/ci.yml` runs on pull requests into `dev` and `main`, on pushes to them, and on demand.

| Job | Checks |
|---|---|
| `backend` | Compile, unit tests, integration tests on PostgreSQL through Testcontainers, all Flyway migrations, tenant isolation tests, packaging. Uploads test reports and the jar |
| `migrations` | `MigrationIT`: every migration on an empty PostgreSQL database, plus the upgrade of existing data |
| `frontend` | `npm ci`, lint, TypeScript, unit tests, production build |
| `e2e` | Playwright against the Docker Compose stack |
| `security` | `npm audit` on production dependencies, then Trivy on dependencies and both container images. Fails on a high or critical issue with a published fix |

The Trivy version is pinned in `.github/scripts/trivy.sh`, and the frontend image carries no npm at runtime.

Dependabot opens weekly update pull requests against `dev`.

## Credentials

CI uses only the local defaults in `docker-compose.yml` and throwaway Testcontainers databases, so it needs no secrets.
Production credentials must never be added to this workflow. The production pipeline gets its own, in Phase 11.

## Blocking merges

Passing checks only block a merge when GitHub is told to require them. Require `backend`, `migrations`, `frontend`, `e2e`
and `security` on `dev` and `main`.

Branch protection and rulesets are refused on a free private repository. Until the repository moves to GitHub Pro or
Team, or becomes public, a failed check does not stop a merge.

The backend job uploads the sample recall, audit and quality reports and an invoice as `report-samples`. Committed copies are in `docs/samples`. The browser job raises `AUTH_RATE_LIMIT_PER_MINUTE`, because the suite signs in far more often
than a person, from one address. To run it locally, start the stack with the same setting.
