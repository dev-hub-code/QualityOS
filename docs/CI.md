# Continuous integration

`.github/workflows/ci.yml` runs on every pull request and on pushes to `dev` and `main`.

| Job | Checks |
|---|---|
| `backend` | Compile, unit tests, integration tests on PostgreSQL through Testcontainers, all Flyway migrations, tenant isolation tests, packaging. Uploads test reports and the jar |
| `frontend` | `npm ci`, lint, TypeScript, unit tests, production build |
| `e2e` | Playwright against the Docker Compose stack |
| `security` | `npm audit` on production dependencies, then Trivy on dependencies and both container images. Fails on a high or critical issue with a published fix |

The Trivy version is pinned in the workflow, and the frontend image carries no npm at runtime.

Dependabot opens weekly update pull requests against `dev`.

## Credentials

CI uses only the local defaults in `docker-compose.yml` and throwaway Testcontainers databases, so it needs no secrets.
Production credentials must never be added to this workflow. The production pipeline gets its own, in Phase 11.

## Blocking merges

Passing checks only block a merge when GitHub is told to require them. Require `backend`, `frontend`, `e2e` and
`security` on `dev` and `main`.

Branch protection and rulesets are refused on a free private repository. Until the repository moves to GitHub Pro or
Team, or becomes public, a failed check does not stop a merge.
