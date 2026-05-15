# Atlas Migrate · deployment

This document covers Phase 2H — the Helm chart, hardened images, and
release-side CI scanning that take Atlas from `docker compose` to a
real Kubernetes cluster.

## What's shipped

| Layer | Where |
|---|---|
| **Hardened Dockerfiles** for all 13 services | `services/*/Dockerfile` — non-root user (uid 10001), OpenContainers labels, JVM container-aware heap cap, busybox-wget healthcheck on `/actuator/health` |
| **Helm chart** | `helm/atlas/` — Chart.yaml + values.yaml + reusable spring-service template + Ingress |
| **CI: helm lint + helm template** | `.github/workflows/ci.yml` → `helm-lint` job — runs on every PR |
| **CI: image scan + SBOM** | `.github/workflows/ci.yml` → `image-scan` job — runs on push to main, parallel matrix per service, Trivy SARIF → Security tab, Syft SPDX-JSON SBOM → artifact (90-day retention) |

## Container image hardening

Every service Dockerfile follows the same shape:

```Dockerfile
# syntax=docker/dockerfile:1.7
ARG ATLAS_VERSION=0.1.0

# build stage
FROM maven:3.9-eclipse-temurin-21 AS build
…

# runtime
FROM eclipse-temurin:21-jre-alpine

ARG ATLAS_VERSION
LABEL org.opencontainers.image.title="atlas-<svc>" \
      org.opencontainers.image.description="Atlas Migrate · <description>" \
      org.opencontainers.image.vendor="Envestnet" \
      org.opencontainers.image.source="https://github.com/envestnet/atlas-migrate" \
      org.opencontainers.image.licenses="Proprietary" \
      org.opencontainers.image.version="${ATLAS_VERSION}"

RUN addgroup -S -g 10001 atlas && adduser -S -u 10001 -G atlas atlas
WORKDIR /app
COPY --from=build --chown=atlas:atlas /app/target/*.jar app.jar
USER atlas:atlas

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE <port>

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:<port>/actuator/health | grep -q '"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Why these choices

| Decision | Rationale |
|---|---|
| **Non-root uid 10001** | Outside the OS-managed user range so it never collides with packages installed by the base image. Pod-level `runAsNonRoot: true` enforces it from the K8s side too. |
| **Alpine JRE base** | ~80 MB image vs. ~250 MB for Ubuntu-based JRE. Smaller attack surface. busybox `wget` is the only utility we need at runtime. |
| **Container-aware JVM** | Java 21 enables `UseContainerSupport` by default; the explicit `MaxRAMPercentage=75` caps heap to leave room for native memory, JIT codegen, and GC. Override per-deployment via `JAVA_TOOL_OPTIONS`. |
| **OpenContainers labels** | Image registries (Harbor, ECR, GHCR) display these in their UI; `org.opencontainers.image.source` is the link "View source" follows. |
| **`HEALTHCHECK` on `/actuator/health`** | Spring Boot Actuator exposes the same endpoint Kubernetes uses for readiness/liveness; defining it here keeps `docker run` and `docker compose` in sync with the K8s probes. |

## Helm chart

```
helm/atlas/
├── Chart.yaml             # chart metadata
├── values.yaml            # one entry per service + globals + ingress
└── templates/
    ├── _helpers.tpl       # name / labels / image-ref helpers
    ├── spring-service.yaml # iterates .Values.services → Deployment + Service
    └── ingress.yaml        # gateway + (optional) fake-idp
```

### Render coverage

`helm template` against the default values produces **26 manifests** —
13 Deployments + 13 Services, one each per Atlas service. Adding a
new service is values-only:

```yaml
# helm/atlas/values.yaml
services:
  new-service:
    image: atlas-new-service
    port: 8094
    replicas: 1
    env:
      OTEL_EXPORTER_OTLP_ENDPOINT: "{{ .Values.global.backingStores.otelEndpoint }}"
```

No template change required.

### Backing stores — NOT included

The chart deliberately doesn't bundle Postgres, Neo4j, MinIO, Redis,
Temporal, Prometheus, or Jaeger. Customers running on a cloud
typically use managed services (RDS, MemoryDB, S3, etc.); customers
running on bare K8s install Bitnami subcharts. Either way, point the
chart at the resulting endpoints via `global.backingStores.*`:

```yaml
global:
  backingStores:
    postgresHost: my-postgres.mgmt.svc.cluster.local
    postgresPort: 5432
    postgresDb:   atlas
    neo4jUri:     bolt://my-neo4j:7687
    minioEndpoint: https://s3.amazonaws.com
    redisHost:    my-redis.cache.svc.cluster.local
    otelEndpoint: http://otel-collector:4318/v1/traces
```

### Secrets

`global.secrets.*` names point to Kubernetes Secrets the customer
provisions out-of-band (Sealed Secrets, External Secrets, AWS / GCP /
Azure native). Each secret has a known key set:

| Secret | Keys |
|---|---|
| `postgresSecretName` | `username`, `password` |
| `redisSecretName`    | `password` |
| `minioSecretName`    | `rootUser`, `rootPassword` |
| `oidcSecretName`     | `issuerUri`, `audience`, `endSessionUrl` (only consumed by api-gateway) |
| `llmSecretName`      | `anthropicApiKey` (only consumed by llm-gateway) |

Empty values disable the corresponding `valueFrom.secretKeyRef`
binding — the env var stays unset and the service falls back to its
default (e.g. fake-idp for auth, stub LLM responses).

### Ingress

Off by default (`ingress.enabled: false`) since ingress controllers
vary widely. When enabled, exposes:

- the api-gateway at `ingress.hosts.gateway`
- (when `services.fake-idp.enabled: true`) fake-idp at
  `ingress.hosts.fakeIdp`

TLS is on by default — point `ingress.tls.secretName` at a
cert-manager-managed Secret.

## Per-environment values files

The recommended pattern:

```
helm/atlas/
├── values.yaml              # demo / dev defaults (auth-sim + fake-idp on)
├── values-staging.yaml      # auth-real + small replica counts
└── values-prod.yaml         # auth-real + HPA + Ingress + customer registry
```

`values-staging.yaml` and `values-prod.yaml` aren't checked in (they
contain customer-specific endpoints). The ones we DO check in are the
demo (`values.yaml`) and a `values-prod-template.yaml` showing the
shape — left as a follow-up.

## Deploy walkthrough

### 1. Build + push images

```bash
# In a build pipeline, per service:
docker build \
  --build-arg ATLAS_VERSION=0.1.0 \
  -t ghcr.io/envestnet/atlas-prj-service:0.1.0 \
  services/prj-service/

docker push ghcr.io/envestnet/atlas-prj-service:0.1.0
```

The CI workflow's `image-scan` job builds (but doesn't push) on every
push to main. Wire push + sign in your own deploy pipeline.

### 2. Provision backing stores

Out of scope for this chart — depends on your platform.

### 3. Provision secrets

```bash
kubectl create secret generic atlas-postgres-credentials \
  --from-literal=username=atlas \
  --from-literal=password=<from-secret-manager>

kubectl create secret generic atlas-llm-credentials \
  --from-literal=anthropicApiKey=<from-secret-manager>
# …repeat for redis, minio, oidc as needed
```

### 4. Install

```bash
# Demo install (auth-sim + fake-idp + Bitnami postgres alongside).
helm install atlas ./helm/atlas \
  --namespace atlas \
  --create-namespace

# Production install (auth-real + customer-supplied OIDC).
helm install atlas ./helm/atlas \
  --namespace atlas \
  --create-namespace \
  --set global.springProfile=auth-real \
  --set global.secrets.oidcSecretName=atlas-oidc \
  --set global.secrets.llmSecretName=atlas-llm \
  --set "services.fake-idp.enabled=false" \
  --set ingress.enabled=true \
  --set ingress.hosts.gateway=atlas.example.com
```

### 5. Verify

```bash
# All deployments healthy
kubectl -n atlas get deploy

# Gateway is the only thing that should be externally reachable.
kubectl -n atlas port-forward svc/api-gateway 8080:8080
curl -s localhost:8080/actuator/health
```

## CI — release-side scanning

The `image-scan` job runs on push to main (not on PRs) and:

1. Builds the per-service image (Buildx with GHA-cached layers)
2. **Trivy** scans for HIGH + CRITICAL CVEs, uploads SARIF to the
   GitHub Security tab. **Currently set to `exit-code: 0`** — findings
   are reported but don't block the build. Promote to `exit-code: 1`
   once the team has a baseline of accepted CVEs to suppress.
3. **Syft** generates an SPDX-JSON SBOM, uploads as a 90-day artifact

The `helm-lint` job runs on every PR and:

1. `helm lint helm/atlas` — fails on syntax / template errors
2. `helm template atlas helm/atlas` with default values — validates
   the demo path renders
3. Same with `--set global.springProfile=auth-real --set …` —
   validates the production-shaped path renders

Both `actionlint` and `helm lint` pass on the committed workflow + chart.

## Deferred / open follow-ups

- **HPA stub** — wire HorizontalPodAutoscaler templates that turn on
  with `services.<name>.hpa.enabled: true`. Skipped because the
  metrics needed (CPU + custom Prometheus) require Phase 2C metrics
  to be wired through a metrics adapter on the cluster.
- **PodDisruptionBudgets** — should land alongside replicas: 2+
  services. Same pattern as HPA — values-driven template.
- **NetworkPolicies** — default-deny + explicit allows. Customer-
  cluster-specific (depends on CNI), so deferred.
- **Cosign image signing** — `image-scan` job builds but doesn't sign.
  Add cosign sign + cosign verify once the team picks a key store
  (KMS, Vault, sigstore Fulcio).
- **Push step** — the `image-scan` job loads images into the runner's
  local docker for scanning. Push + sign as a separate `image-publish`
  job that runs only after image-scan passes, gated on tagged releases.
- **Bitnami subcharts** — listing the recommended ones (postgresql,
  redis, neo4j, minio) in `Chart.yaml` `dependencies`. Today the chart
  is BYO-database; add them as opt-in dependencies in a follow-up
  (`helm dep update` + `--set postgresql.enabled=true`).

## Related docs

- [observability.md](observability.md) — what `/actuator/prometheus`
  serves, Prometheus + Jaeger setup
- [auth-production.md](auth-production.md) — OIDC issuer setup per IdP
- [reliability.md](reliability.md) — circuit breakers + idempotency
  middleware that hardened images run

## Phased roadmap

- **2H.1 (shipped)** — Dockerfile hardening: non-root user, OCI
  labels, JVM container-awareness, healthcheck
- **2H.2 (shipped)** — Helm chart skeleton: 13 Deployments + 13
  Services rendered from values, optional ingress, per-secret
  binding hooks
- **2H.3 (shipped)** — CI helm-lint + image-scan (Trivy SARIF + Syft
  SBOM) jobs
- **2H.4 (shipped)** — this document

### Next phase candidates

- HPA + PDB templates
- Cosign signing in the release path
- Push step to the configured registry
- Bitnami subcharts as opt-in dependencies
