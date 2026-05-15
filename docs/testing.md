# Atlas Migrate · testing

Phase 2B introduces Flyway-managed migrations and a JUnit 5 + Testcontainers
harness across the Spring services. This page describes how to run the suite
locally and in CI.

## Layers

| Layer | Where | What it covers |
|---|---|---|
| **Unit** | `*Test` classes that don't extend `BaseServiceTest` | Pure-logic classes across services: `SourceWalker`, `Sanitizer`, `CanonicalXml`, `Merger`, `BindingsGenerator`, `ClosureDocAgent`, `ProjectController` (mocked repos), and the uplift trio (`RecipeCatalog`, `RecipeTransforms`, `UnifiedDiff`). No Spring context, no containers, milliseconds to run. |
| **Service integration** | `*Test` classes that extend `BaseServiceTest` | Each `@Service` against a real postgres + MinIO via Testcontainers. Spring context boots once per JVM (containers shared via `withReuse(true)`). |
| **Controller integration** | TODO — Phase 2B.2.c | `@WebMvcTest` against the same Testcontainers postgres. |
| **Frontend** | `frontend/src/**/*.test.{ts,tsx}` (Phase 2B.3) | Vitest + React Testing Library + jsdom. Pure UI components, the api client, and a setup file. |
| **E2E** | `frontend/e2e/*.spec.ts` (Phase 2B.4) | Playwright happy paths. Default mode is API-mocked (no backend); a documented `E2E_BASE_URL` override runs them against the real docker-compose stack. |
| **CI** | `.github/workflows/ci.yml` (Phase 2B.5) | GitHub Actions runs Java unit + integration (matrix per service), frontend Vitest, and Playwright E2E in parallel on every PR. |

## Running tests locally

### Prereqs

- **Maven 3.9+**
- **JDK 21**
- **Docker** (Desktop on macOS/Windows, native engine on Linux)

The integration tests use [Testcontainers](https://testcontainers.com/) which
spins up disposable postgres and MinIO containers on demand. They share a JVM
across test classes via `withReuse(true)` so the suite stays fast.

### From the host

```bash
cd services/uplift-service
mvn verify                          # full suite + JaCoCo coverage check
mvn -Dtest=RecipeServiceTest test   # one class
mvn -Dtest='*Transforms*' test      # glob
```

JaCoCo enforces a project-level **70% line coverage** floor. Failing the floor
fails `mvn verify`; coverage report lands at `target/site/jacoco/index.html`.

### Docker Desktop on Windows — known constraint

Running `mvn` inside a `maven:*` container with the host docker socket
**bind-mounted** (`-v /var/run/docker.sock:/var/run/docker.sock`) does **not**
work on Docker Desktop for Windows. The Linux-side socket is a forwarder for
the underlying named-pipe daemon and returns an incomplete `info` payload that
Testcontainers' validator rejects (`UnixSocketClientProviderStrategy: failed
with BadRequestException` — labels mention `npipe://\\\\.\\pipe\\docker_cli`).

The supported paths on Windows are:

1. **WSL2 shell with Maven installed.** `wsl -d Ubuntu` → `cd
   /mnt/c/Envestnet/services/uplift-service && mvn verify`. The WSL2 docker
   integration exposes a real Linux daemon Testcontainers accepts.
2. **Direct host install of Maven + JDK 21**, then `mvn verify` in PowerShell.
   Docker Desktop's resource-saver settings need to be loose enough for
   container creation under ~5 s; Testcontainers tolerates slow startup but
   surefire's first call has a 60 s default timeout.
3. **CI (GitHub Actions, Linux runners)** runs cleanly out of the box; Phase
   2B.5 wires the workflow.

The pure unit tests (`RecipeCatalogTest`, `RecipeTransformsTest`,
`UnifiedDiffTest`) run anywhere without Docker — they're the baseline guard
for compile-time regressions even on machines where Testcontainers is
unavailable.

## Writing new tests

1. **Pure logic** → put it next to the service class as a JUnit 5 test, no
   Spring context. AssertJ assertions, descriptive method names.
2. **Service integration** → extend `com.envestnet.atlas.uplift.testsupport.BaseServiceTest`
   and `@Autowired` the bean under test. Call `fix.wipe()` from `@BeforeEach`.
   The `Fixtures` helper covers the upstream chain (project →
   scan_run → module → finding); use it instead of inserting raw SQL.
3. **No live network** in tests. The base profile sets `PROV_ENABLED=false`
   and points `PROV_SERVICE_URL`, `LLM_GATEWAY_URL` at `localhost:1` so any
   accidental outbound call fails fast.
4. **Determinism**: services that use `Random` should accept a seed via
   `@Value` or expose a constructor overload — the existing seeded
   randomness in `CharService` is verified by `CharServiceTest`.

## Coverage targets

| Service | Floor | Status |
|---|---:|---|
| uplift-service | 70% | Enforced via JaCoCo plugin |
| prj-service     | TBD | JaCoCo wired; floor enforcement deferred to Phase 2B.5 (CI) |
| arch-service    | TBD | JaCoCo wired; floor enforcement deferred to Phase 2B.5 (CI) |
| cap-service     | TBD | JaCoCo wired; floor enforcement deferred to Phase 2B.5 (CI) |
| recon-service   | TBD | JaCoCo wired; floor enforcement deferred to Phase 2B.5 (CI) |
| gen-service     | TBD | JaCoCo wired; floor enforcement deferred to Phase 2B.5 (CI) |
| diff-service    | TBD | JaCoCo wired; floor enforcement deferred to Phase 2B.5 (CI) |
| reports-service | TBD | JaCoCo wired; floor enforcement deferred to Phase 2B.5 (CI) |
| prov-service    | TBD | JaCoCo wired; floor enforcement deferred to Phase 2B.5 (CI) |

### Per-service test inventory (Phase 2B.2.b)

| Service | Pure-unit test class | Integration harness | Coverage focus |
|---|---|---|---|
| arch-service    | `SourceWalkerTest` (8)           | `BaseServiceTest` ready | SOAP service interface detection, adapter inference, vendor namespace, AST resilience |
| cap-service     | `SanitizerTest` (8)              | `BaseServiceTest` ready (postgres + MinIO) | redact/tokenize/hash/leave strategies, multi-rule chain, special-char quoting |
| diff-service    | `CanonicalXmlTest` (7)           | `BaseServiceTest` ready (postgres + MinIO) | byte-equivalent canonicalisation, namespace prefix rewriting, XXE hardening |
| gen-service     | `BindingsGeneratorTest` (10)     | `BaseServiceTest` ready (postgres + MinIO) | JAXB .xjb emission for date adapters, error fallback, package propagation |
| prj-service     | `ProjectControllerTest` (13)     | `BaseServiceTest` ready | gate state machine across stages A→F, run/finalize endpoints, 404 paths |
| prov-service    | `ProvServiceTest` (integration)  | `BaseServiceTest` ready | emit, query, aggregate, CSV export with quoting |
| recon-service   | `MergerTest` (9)                 | `BaseServiceTest` ready (postgres + MinIO) | divergence detection, vendor-only path filtering, deterministic sort |
| reports-service | `ClosureDocAgentTest` (10)       | `BaseServiceTest` ready (postgres + MinIO) | deterministic narrative, all 7 H2 sections, LLM stub fallback |
| uplift-service  | 8 service tests + transform unit | `BaseServiceTest` ready | full strangler/recipe/migration/cutover/character lifecycle |

## Frontend tests (Phase 2B.3)

`frontend/` ships a Vitest + React Testing Library + jsdom harness. The setup
file (`src/test/setup.ts`) wires `@testing-library/jest-dom/vitest` matchers
and registers an `afterEach(cleanup)` so tests don't leak DOM between cases.

```bash
cd frontend
npm install                # one-time
npm test                   # vitest run — exits with code 0/1 for CI
npm run test:watch         # interactive watcher for local development
npm run test:coverage      # v8 coverage report → coverage/index.html
```

Vitest configuration lives inline at the bottom of `vite.config.ts` (no
separate `vitest.config.ts`) so the test runner shares the dev server's plugin
chain. `import.meta.env.VITE_API_BASE` resolves the same way it does in the
browser bundle, falling back to `http://<host>:8080` when unset.

### Coverage at the start of Phase 2B.3

| Surface | File | Cases |
|---|---|---:|
| Stage marker | `components/ui/StageChip.test.tsx`         | 9 |
| Inline status pill | `components/ui/StatusBadge.test.tsx`        | 10 |
| Stat tile | `components/ui/MetricTile.test.tsx`         | 9 |
| Definition list row | `components/ui/KeyValuePair.test.tsx`       | 7 |
| Stage-chrome counts | `components/ui/CountChipRow.test.tsx`       | 8 |
| Accessible tabs primitive | `components/ui/Tabs.test.tsx`               | 12 |
| Filter pill row | `components/ui/SegmentedControl.test.tsx`   | 7 |
| API client (URL building, headers, error paths, query string, text endpoints, download URL builders) | `api/client.test.ts`                        | 27 |

Total: **89 cases across 8 files**, ~2 s wall-clock once jsdom is hot.

### Conventions

1. **Co-locate**: place `Foo.test.tsx` next to `Foo.tsx`. Vitest discovers via
   the `src/**/*.test.{ts,tsx}` glob.
2. **Use roles, not classnames**, for everything user-observable. Class-based
   assertions are reserved for tone/variant smoke checks where the role is
   identical across variants.
3. **No live network**. The api-client test stubs `globalThis.fetch` and
   inspects URL/method/body. There is no MSW dependency — the surface is
   small enough that direct `fetch` mocking is clearer.
4. **No snapshot tests**. They lock in details that the component owns
   intentionally (Tailwind classnames, child element ordering) and break on
   every refactor without flagging real regressions.

## E2E tests (Phase 2B.4)

`frontend/e2e/` ships a Playwright + Chromium suite. Default mode is
**API-mocked**: each spec calls `page.route('**/api/v1/**', …)` to inject
deterministic JSON. Playwright boots `vite preview` on port 4173, serves the
production bundle, and runs everything against that — no backend services
required.

```bash
cd frontend
npm install                # one-time (also installs Playwright)
npx playwright install     # one-time (downloads Chromium)
npm run e2e                # headless, full suite
npm run e2e:headed         # see the browser
npm run e2e:ui             # interactive debugger UI
npm run e2e:report         # open the HTML report from the last run
```

To run against the **real docker-compose stack** instead of the mocked
preview:

```bash
.\up.ps1                                       # bring the stack up
E2E_BASE_URL=http://localhost:3000 npm run e2e
```

When `E2E_BASE_URL` is set, Playwright skips the `vite preview` boot and
hits the URL directly. Routes the spec doesn't override fall through to
the api-gateway.

### Layout

```
frontend/
  playwright.config.ts          ← timeouts, parallelism, web-server recipe
  e2e/
    fixtures/
      sample-data.ts            ← deterministic JSON for every endpoint
      api-mock.ts               ← test fixture that wires base routes
    dashboard.spec.ts           ← workspace dashboard (5 cases)
    create-project.spec.ts      ← new-project modal + form (4 cases)
    project-detail.spec.ts      ← stage chrome navigation (4 cases)
    audit-browser.spec.ts       ← provenance browser smoke (4 cases)
```

Total: **17 cases across 4 specs**, ~4 min wall-clock in CI (the bulk of
which is the production `vite build` Playwright runs as part of webServer
boot — the tests themselves are <2 min).

### Conventions

1. **Mock at the boundary**. Specs use `page.route` against `**/api/v1/**`
   patterns (relative or absolute URLs both match), never call into the
   network. The fixture's `mockApi.json(path, body, status?)` helper covers
   90% of needs; for capture-the-request assertions, drop down to
   `page.route(...)` directly.
2. **Roles + names, not classnames.** Same rule as the unit tests. The one
   exception is `<button role="radio">`, which neither `getByRole('radio')`
   nor `getByRole('button')` resolves reliably; for those, use
   `getByRole('radiogroup', { name: '…' }).locator('[role="radio"]')`.
3. **No screenshots-as-assertions.** Screenshots are captured on failure
   for debugging only; they are NOT diffed against baselines. Visual
   regression is out of scope for Phase 2B.4.
4. **Parallelism**. `workers: 4` (2 in CI) — the single-process vite
   preview can't service more than that without head-of-line blocking
   that masquerades as test slowness.

## Continuous integration (Phase 2B.5)

CI lives at `.github/workflows/ci.yml`. It runs on every push to `main` and
every pull request. The three jobs run in parallel — Java service tests
fan out into a matrix so a single slow service can't gate the rest.

```
┌──────────────────────────────────────┐
│  java · arch-service                 │
│  java · cap-service                  │
│  java · diff-service                 │
│  java · gen-service                  │
│  java · prj-service                  │   parallel matrix
│  java · prov-service                 │   ubuntu-latest, JDK 21,
│  java · recon-service                │   real Docker daemon
│  java · reports-service              │
│  java · uplift-service               │
└──────────────────────────────────────┘
┌──────────────────────────────────────┐
│  frontend · vitest                   │   Node 20, jsdom
└──────────────────────────────────────┘
┌──────────────────────────────────────┐
│  frontend · playwright               │   Node 20 + Chromium,
│    (gated by frontend · vitest)      │   API-mocked mode
└──────────────────────────────────────┘
```

### Coverage floors (JaCoCo)

The `mvn verify` step runs JaCoCo's `check` execution where it's wired in
`pom.xml`. Today only `uplift-service` enforces a floor (70% line coverage,
project-wide); the eight services that landed test infrastructure in Phase
2B.2.b emit coverage reports but do **not** fail the build below a
threshold. Floors will be ratcheted in per service as service-integration
tests fill out — a hard floor on a service whose only test is a
single-component unit test would be misleading.

When you add an integration test class that materially raises a service's
coverage, lift its floor in the same PR — see the `jacoco-maven-plugin`
`check` execution block at the bottom of
`services/uplift-service/pom.xml` for the canonical pattern.

### Artifacts

Every CI run uploads:

- `jacoco-<service>` — HTML coverage report per service (14-day retention).
- `surefire-<service>` — Surefire output, **on failure only**.
- `vitest-coverage` — v8 coverage HTML.
- `playwright-report` — Playwright's HTML report.
- `playwright-traces` — failure traces (`.zip` files openable with
  `npx playwright show-trace`), **on failure only**.

### Local reproduction

The CI workflow runs the exact commands you'd run locally. To reproduce:

```bash
# Java
cd services/<svc> && mvn -B -ntp verify

# Frontend unit tests
cd frontend && npm test

# E2E
cd frontend && npx playwright install --with-deps chromium && npm run e2e
```

The Windows Docker Desktop named-pipe quirk (see above) does not affect
CI — Linux runners have a working daemon.

## Flyway migration workflow

Schemas are owned per-service. To add a column or table:

1. Identify which service owns the schema (see
   `services/<name>/src/main/resources/db/migration/`).
2. Add `V<next>__<name>.sql` — Flyway picks up new files alphabetically.
3. Restart that service. Flyway logs the migration in
   `<schema>.flyway_schema_history`.

The legacy `infra/postgres/migrations/*.sql` files have been removed in favour
of per-service Flyway. `init.sql` only creates the application database, the
schemas, and the shared extensions; everything else is a Flyway migration.

For tests, each service's `src/test/resources/init/extensions.sql` recreates
the postgres extensions in the disposable container; Flyway runs the same
migrations against the empty schema.
