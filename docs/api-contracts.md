# Atlas Migrate · API contracts

This document covers Phase 2E — the OpenAPI / TypeScript-codegen
workflow that keeps the frontend and backend types in lockstep.

## Layers

```
                ┌──────────────────────────┐
                │  Java controllers        │
                │  (annotations + DTOs)    │
                └────────────┬─────────────┘
                             │ springdoc-openapi
                             ▼
              ┌──────────────────────────┐
              │  /v3/api-docs            │  ← per-service, reachable
              │  /swagger-ui.html        │    inside the cluster
              └────────────┬─────────────┘
                           │ curl > frontend/openapi/<svc>.json
                           ▼
            ┌──────────────────────────┐
            │  frontend/openapi/*.json │  ← checked-in source of truth
            └────────────┬─────────────┘
                         │ openapi-typescript
                         ▼
        ┌──────────────────────────────────┐
        │  frontend/src/api/generated/*.ts │  ← consumed by client.ts +
        │  (generated, not hand-edited)    │    authClient.ts
        └──────────────────────────────────┘
                         │ tsc -b
                         ▼
                 dist/assets/index.js
```

Three contract boundaries get enforced:

1. **Service ↔ spec** — springdoc generates the spec from controller
   code at runtime; `OpenApiEndpointTest` (llm-gateway) pins the shape.
2. **Spec ↔ types** — `npm run codegen` regenerates TS; `npm run
   codegen:check` fails CI if the result differs from the committed
   files (catches "I edited the JSON but forgot to regenerate" and the
   reverse).
3. **Types ↔ frontend code** — `tsc -b` and `vitest` keep the SPA's
   call sites honest. A type-shape change cascades from spec → types →
   compile errors at the call sites.

## What's shipped (Phase 2E)

| Layer | Where |
|---|---|
| `springdoc-openapi-starter-webmvc-ui` 2.6.0 on every servlet service (12 of them) | each service's `pom.xml` |
| `springdoc-openapi-starter-webflux-ui` 2.6.0 on the reactive gateway | `services/api-gateway/pom.xml` |
| Document-level metadata (title, version, license, global bearer-jwt) | `services/<svc>/src/main/java/com/envestnet/atlas/<svc>/openapi/OpenApiConfig.java` (one per service) |
| Gateway permits `/v3/api-docs/**` + `/swagger-ui/**` without a JWT | `SecurityConfig.springSecurityFilterChain` |
| Hand-curated specs | `frontend/openapi/gateway.json`, `frontend/openapi/llm-gateway.json` |
| Generated types | `frontend/src/api/generated/gateway.ts`, `frontend/src/api/generated/llm-gateway.ts` |
| `Identity` and `AuthConfig` migrated to use generated types | `frontend/src/api/client.ts`, `frontend/src/auth/authClient.ts` |
| CI drift check | `.github/workflows/ci.yml` → `frontend-tests` job → `codegen:check` step |
| Endpoint contract tests | `services/llm-gateway/src/test/java/.../OpenApiEndpointTest.java` (4 cases — spec validity, route enumeration, security scheme, Swagger UI) |

## How to find the spec for a service

Local:
```
http://localhost:8081/v3/api-docs            # prj-service
http://localhost:8082/v3/api-docs            # llm-gateway
http://localhost:8093/v3/api-docs            # fake-idp
…
```

The browsable Swagger UI is at `http://localhost:<port>/swagger-ui.html`
on each service. It honours the global bearer-jwt scheme: clicking
"Authorize" + pasting a JWT lets you exercise endpoints right from the UI.

## Refreshing a checked-in spec

Today, the JSON specs in `frontend/openapi/` are hand-curated. Two
ways to refresh:

**Option A — copy from a running service** (preferred):

```bash
.\up.ps1                                              # bring the stack up
curl -s http://localhost:8082/v3/api-docs | jq . \
   > frontend/openapi/llm-gateway.json
cd frontend && npm run codegen
```

The `jq .` step keeps the JSON pretty-printed so the diff is reviewable.

**Option B — `springdoc-openapi-maven-plugin`** (upgrade path, not yet
shipped): bake the capture into `mvn verify` so the spec file is
regenerated as part of the service's build. Pattern:

```xml
<plugin>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-maven-plugin</artifactId>
  <executions>
    <execution><id>pre-int</id><goals><goal>start</goal></goals></execution>
    <execution><id>post-int</id><goals><goal>stop</goal></goals></execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-maven-plugin</artifactId>
  <version>1.4</version>
  <executions>
    <execution>
      <phase>integration-test</phase>
      <goals><goal>generate</goal></goals>
    </execution>
  </executions>
  <configuration>
    <apiDocsUrl>http://localhost:8082/v3/api-docs</apiDocsUrl>
    <outputFileName>llm-gateway.json</outputFileName>
    <outputDir>../../frontend/openapi</outputDir>
  </configuration>
</plugin>
```

This is left as a follow-up because the demo's per-service boot times
are slow enough that adding 13 service-boots to `mvn verify` would
double the CI duration. Hand-curated specs are honest for now.

## Coverage

| Service | Spec checked in? | Generated types? | Migrated in `client.ts`? |
|---|---|---|---|
| api-gateway | yes — `gateway.json` (auth + identity) | `generated/gateway.ts` | `Identity`, `AuthConfig` |
| llm-gateway | yes — `llm-gateway.json` | `generated/llm-gateway.ts` | not yet — backend-to-backend only |
| prj-service | **no** — biggest surface; hand-curate is too noisy. The runtime spec at `:8081/v3/api-docs` is the source of truth; client.ts types are still hand-maintained. | — | — |
| arch / cap / diff / fake-idp / gen / graph / prov / recon / reports / uplift | runtime spec only | — | — |

The `prj-service` spec migration is the obvious next deliverable
(Phase 2E follow-up). It needs Option B above (Maven-plugin capture)
to stay sane — too many endpoints to hand-curate.

## CI

`.github/workflows/ci.yml` runs `npm run codegen:check` in the
`frontend · vitest` job before `vitest`. The check:

1. Re-runs `openapi-typescript` against `frontend/openapi/*.json`.
2. `git diff --exit-code src/api/generated/` — non-zero if the
   regenerated output differs from what's committed.

If the check fails locally, run `npm run codegen` and commit the
updated files.

## Why hand-curated for now?

Two reasons:

1. **Boot time.** The fastest-booting service (llm-gateway) takes ~75s
   to start in a Docker container; adding 13 service boots to CI
   doubles wall-clock time. The springdoc-openapi-maven-plugin path
   solves this but adds another 13 plugin configurations to maintain.
2. **Explicit narratives.** Hand-curated specs let us tighten
   descriptions (the SDK consumer reads the OpenAPI doc) and add
   constraints (`enum`, `minimum`, `format`) that springdoc only
   surfaces from explicit `@Schema` annotations. The hand-curated
   `llm-gateway.json` carries narrative descriptions richer than what
   springdoc emits today.

Both are tractable; the Maven-plugin capture is the upgrade path for
the next phase, with an `@Schema` annotation pass to enrich the
auto-generated narrative.

## Testing the spec endpoint

`OpenApiEndpointTest` in llm-gateway pins the contract surface:

- `/v3/api-docs` returns OpenAPI 3.x JSON
- The `info.title` carries the wired metadata
- The expected paths are enumerated
- The global `bearer-jwt` security scheme is present
- `/swagger-ui/index.html` serves the actual Swagger UI HTML

These run in the standard `mvn test` invocation; no extra wiring.

## Phased roadmap

- **2E.1 (shipped)** — `springdoc-openapi` on every service,
  `OpenApiConfig` with metadata, contract tests
- **2E.2 (deferred)** — gateway-side aggregation of downstream specs
  at `/v3/api-docs/{service}`. Useful for SDK consumers that talk to
  the gateway only; not on the frontend codegen critical path.
- **2E.3 (shipped)** — `openapi-typescript` codegen pipeline,
  hand-curated `gateway.json` + `llm-gateway.json`, `Identity` +
  `AuthConfig` migrated
- **2E.4 (shipped)** — CI drift check via `npm run codegen:check`
- **2E.5 (shipped)** — this document

**Phase 2E follow-ups** (separate PR): springdoc-openapi-maven-plugin
to auto-capture per-service specs at `mvn verify` time; `prj-service`
spec captured + types migrated; per-service `@Schema` annotations on
DTOs to richen the generated narrative.
