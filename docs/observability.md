# Atlas Migrate · observability

Three layers, landing in three milestones:

| Phase | Status | What it ships |
|---|---|---|
| **2C.1** | shipped | Structured JSON logging across every service, with MDC enrichment for trace, user, and project context |
| **2C.2** | shipped | Prometheus metrics on `/actuator/prometheus` everywhere, custom business metrics (gate transitions, LLM latency), Prometheus container in compose |
| **2C.3** | shipped | OpenTelemetry traces wired through the call graph via Micrometer Tracing, Jaeger in compose, trace IDs stamped into provenance entries, "View trace" link in the audit browser |

This document covers 2C.1 (the parts that have shipped). 2C.2 and 2C.3
will land their own sections.

---

## Structured logging (2C.1)

### One layout, profile-driven

Every Spring service ships a `src/main/resources/logback-spring.xml`
that emits two layouts depending on the active Spring profile:

- **`docker` or `prod` profile** → JSON-per-line to stdout, suitable for
  Loki / Promtail / CloudWatch / Splunk
- **anything else** (dev, tests, `default`) → human-readable text with
  level, thread, logger, message, and the MDC keys appended

This means `docker compose up` produces machine-parseable logs while
`mvn test` and IDE runs produce something a human can scan with `tail`.

The encoder is `net.logstash.logback.encoder.LogstashEncoder`, version
8.0, declared as a runtime dependency in every service's `pom.xml`.

### MDC contract

Every log line emitted while servicing an HTTP request carries up to
four enrichment keys:

| MDC key       | Source                                                             | Lifecycle                                  |
|---------------|--------------------------------------------------------------------|--------------------------------------------|
| `requestId`   | `X-Atlas-Request-Id` header (gateway-stamped) or fresh UUID         | per-request; cleared in `finally`          |
| `userEmail`   | `X-Atlas-User-Email` header (gateway-asserted)                      | per-request; absent on unauthenticated calls |
| `userRoles`   | `X-Atlas-User-Roles` header (comma-separated)                       | per-request; absent on unauthenticated calls |
| `projectId`   | parsed from `/projects/<uuid>/` segment in the request path         | per-request; absent on workspace-level calls |

The keys land at the top level of each JSON event, so log queries can
filter by `userEmail = "alice@envestnet.local"` or
`requestId = "<uuid>"` directly.

### Servlet services

The 12 servlet services (`arch`, `cap`, `diff`, `fake-idp`, `gen`,
`graph`, `llm-gateway`, `prj`, `prov`, `recon`, `reports`, `uplift`)
each have a `logging/RequestContextFilter` (`OncePerRequestFilter`):

```
src/main/java/com/envestnet/atlas/<svc>/logging/RequestContextFilter.java
```

The class is registered as `@Component("atlasRequestContextFilter")` —
the explicit bean name avoids collision with Spring Boot's own
auto-configured `requestContextFilter` (different class, same default
name).

The filter:

1. Reads or generates the request id, populates the MDC, and echoes the
   id in the response headers.
2. Pulls user identity headers into the MDC when present.
3. Extracts the project UUID from the path when applicable.
4. Always clears the MDC in a `finally` block so the keys don't leak
   across requests on a reused worker thread.

Outbound traffic (e.g. `prj-service` calling `uplift-service`)
propagates the request id automatically: the
`IdentityForwardingInterceptor` in each calling service reads the
current MDC value and re-sends it as `X-Atlas-Request-Id` on the
outbound `RestTemplate` request. This means the same id flows through
the entire call graph for one user click.

### API gateway (reactive)

The gateway has a `RequestIdFilter` implementing `GlobalFilter` that:

1. Stamps every routed request with `X-Atlas-Request-Id` (preserving the
   client-supplied value if any), via a `ServerHttpRequestDecorator`
   so the headers stay writable across the filter chain.
2. Sets the same id on the response headers so the SPA can read it
   (`exposedHeaders` in CORS config includes `X-Atlas-Request-Id`).

The gateway itself doesn't do MDC enrichment — its log volume is
mostly startup + routing decisions, and webflux's reactive context
propagation has its own subtleties best handled with OpenTelemetry in
2C.3 rather than ad-hoc MDC bridges.

### How a request gets logged end-to-end

Trace one user clicking "Finalize Stage B":

```
SPA              POST /api/v1/projects/<pid>/stages/recipes/finalize
                 Authorization: Bearer <jwt>
                 (no X-Atlas-Request-Id)

Gateway          RequestIdFilter generates id = "abc-123"
                 → forward request with X-Atlas-Request-Id: abc-123
                 → set response header X-Atlas-Request-Id: abc-123
                 → IdentityForwardingFilter adds X-Atlas-User-Email etc.

prj-service      RequestContextFilter populates MDC:
                   requestId=abc-123, userEmail=bob@..., projectId=<pid>
                 Controller logs: "finalizing recipes" → JSON event
                   carries requestId=abc-123 alongside the message.
                 prj-service POSTs to uplift-service for status check.
                   IdentityForwardingInterceptor re-sends
                   X-Atlas-Request-Id: abc-123 on the outbound call.

uplift-service   RequestContextFilter populates MDC with the SAME
                   requestId=abc-123. All log lines from uplift's
                   handler tagged identically.

prov-service     (Same chain, same requestId on every audit-write log
                   line.)
```

Aggregated into Loki/Splunk, a single query
`requestId=abc-123` reveals the full sequence across all 13 services
without having to correlate timestamps.

### Sample JSON log line

```json
{
  "@timestamp": "2026-05-07T15:42:18.301Z",
  "level": "INFO",
  "logger_name": "com.envestnet.atlas.prj.web.ProjectController",
  "thread_name": "http-nio-8081-exec-3",
  "message": "Finalizing stage B for project 11111111-1111-1111-1111-111111111111",
  "app": "prj-service",
  "requestId": "abc-def-123",
  "userEmail": "bob@envestnet.local",
  "userRoles": "ENGINEER,TECH_LEAD",
  "projectId": "11111111-1111-1111-1111-111111111111"
}
```

### Files at a glance

```
services/<svc>/
  pom.xml                                            ← logstash-logback-encoder dep
  src/main/resources/logback-spring.xml              ← profile-aware encoder config
  src/main/java/com/envestnet/atlas/<svc>/
    logging/RequestContextFilter.java                ← servlet MDC filter
    auth/IdentityForwardingInterceptor.java          ← already forwards user; now also forwards request id

services/api-gateway/
  pom.xml                                            ← logstash dep
  src/main/resources/logback-spring.xml              ← gateway logging
  src/main/resources/application.yml                 ← CORS exposes X-Atlas-Request-Id
  src/main/java/com/envestnet/atlas/gateway/
    RequestIdFilter.java                             ← reactive request-id stamper
```

### Tests

| Layer | Where | What it pins |
|---|---|---|
| Filter unit | `prj-service/.../RequestContextFilterTest` | MDC populated from headers, project UUID parsed from path, MDC cleared in finally, response carries the request id, blank/missing headers handled |
| Gateway integration | `api-gateway/.../FinalizeAuthorizationTest`, `IdentityForwardingFilterTest` | The new RequestIdFilter doesn't break the existing security / identity-propagation behavior — 45/45 cases green |

Pure-unit logging coverage is fine because MDC is a straightforward
SLF4J primitive — there's no value in a Spring-context test that just
re-verifies SLF4J's own contract.

---

---

## Metrics (2C.2)

### `/actuator/prometheus` everywhere

Every Atlas service includes `io.micrometer:micrometer-registry-prometheus`
and exposes `prometheus` on the actuator endpoint allow-list. Hitting
`http://<svc>:<port>/actuator/prometheus` returns the standard text
exposition format with:

- **JVM metrics** — heap/non-heap memory, GC pauses, thread states, class
  loading
- **HTTP server metrics** — `http_server_requests_seconds` histograms
  by method/uri/status (auto-instrumented for both servlet services and
  the reactive gateway)
- **HTTP client metrics** — `http_client_requests_seconds` for
  `RestTemplate` calls between services
- **Process metrics** — open file descriptors, CPU usage, uptime
- **Atlas custom metrics** — see below

### Atlas-specific custom metrics

These are the business-level metrics Atlas registers explicitly. They
land at the top level of the scrape output alongside the auto metrics.

| Metric                              | Type      | Tags                          | Where                                                                 | Why                                              |
|-------------------------------------|-----------|-------------------------------|-----------------------------------------------------------------------|--------------------------------------------------|
| `atlas_stage_finalize_total`        | counter   | `stage`, `state`              | prj-service · every call to `advanceGate(...)`                        | "How often does each gate transition?"           |
| `atlas_llm_invocation_seconds`      | timer (with percentiles 0.5/0.95/0.99) | `model`, `outcome` (`success` / `stub_no_key` / `stub_error`) | llm-gateway · wraps every `/api/v1/llm/invoke` call                   | "How long is an LLM round-trip, and is it real or stubbed?" |

**Naming convention** — all custom metrics:
- start with `atlas_` to namespace away from JVM / Spring metrics
- use lowercase snake_case
- counters end with `_total` (Prometheus convention)
- timers use seconds + percentiles (no separate `_seconds_count` /
  `_seconds_sum` — Micrometer emits those automatically)

Adding new ones lives in `metrics/AtlasMetrics.java` (per service) so
metric names + label vocabulary stay centralised; controllers don't
construct `Counter` / `Timer` instances inline.

### Prometheus container

`docker-compose.yml` ships a Prometheus instance scraping every Atlas
service every 15s:

```yaml
prometheus:
  image: prom/prometheus:v2.55.1
  command:
    - "--config.file=/etc/prometheus/prometheus.yml"
    - "--storage.tsdb.path=/prometheus"
    - "--storage.tsdb.retention.time=14d"
  ports:
    - "${PORT_PROMETHEUS:-29090}:9090"
  volumes:
    - ./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    - prometheus-data:/prometheus
```

Browse to `http://localhost:29090` after `docker compose up` for the
Prometheus query UI. Configuration in
[`infra/prometheus/prometheus.yml`](../infra/prometheus/prometheus.yml).

In production, replace the `static_configs` with the appropriate
service-discovery mechanism (Kubernetes, Consul, DNS) — the per-service
endpoint contract doesn't change.

### Test coverage

- **prj-service** [`AtlasMetricsTest`](../services/prj-service/src/test/java/com/envestnet/atlas/prj/metrics/AtlasMetricsTest.java)
  — pure-unit, in-memory `SimpleMeterRegistry`, asserts the counter is
  registered with the right tags, increments correctly, and partitions
  per (stage, state).
- **llm-gateway** [`PrometheusEndpointTest`](../services/llm-gateway/src/test/java/com/envestnet/atlas/llm/PrometheusEndpointTest.java)
  — full `@SpringBootTest`, hits `/actuator/prometheus`, asserts the
  scrape body carries JVM metrics + the timer with the expected
  `model` / `outcome` labels and percentiles. Uses
  `@AutoConfigureObservability` to opt back into observability
  (Spring Boot disables it in tests by default).

### Quick scrape sanity check

After `docker compose up`:

```bash
# A specific Atlas custom metric:
curl -s http://localhost:29090/api/v1/query?query=atlas_stage_finalize_total | jq

# All currently-tracked counters from prj-service:
curl -s http://localhost:8081/actuator/prometheus | grep '^atlas_'
```

---

---

## Distributed tracing (2C.3)

### Stack

| Layer            | Library / image                                    | Notes                                                                                                                         |
|------------------|----------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| Span instrumentation (Java) | `io.micrometer:micrometer-tracing-bridge-otel`     | Bridges Spring Boot's auto-instrumentation (HTTP server / client, JDBC, etc.) to the OpenTelemetry SDK.                       |
| Span export (Java)          | `io.opentelemetry:opentelemetry-exporter-otlp`     | Ships finished spans to Jaeger / Tempo / any OTel collector via OTLP-HTTP.                                                    |
| Demo backend                | `jaegertracing/all-in-one:1.62`                    | OTLP receiver + storage + query UI in one container; UI on port `26686`, OTLP-HTTP on `24318`.                                |
| Production backend          | _customer choice_                                  | The contract is the same OTLP endpoint; only `OTEL_EXPORTER_OTLP_ENDPOINT` changes (Tempo, AWS X-Ray, Datadog, Honeycomb…).    |

### Configuration

Every service's `application.yml` ships a `management.tracing` block:

```yaml
management:
  tracing:
    sampling:
      probability: ${TRACE_SAMPLING_PROBABILITY:1.0}
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://jaeger:4318/v1/traces}
      transport: http
```

Sampling at `1.0` is fine for the local demo. In production, downsample to
`0.1` or lower (combined with `tail-based sampling` at the collector) to
keep span volume manageable.

### Propagation

Spring Boot 3.x + Micrometer Tracing auto-configures W3C `traceparent`
propagation on every:

- Inbound HTTP request — incoming `traceparent` is honoured; otherwise a
  fresh root span is created.
- Outbound `RestTemplate` / `WebClient` / `RestClient` call — the
  current span is propagated via `traceparent`.
- JDBC operation — instrumented when the JDBC starter is on the
  classpath; spans nest under the HTTP span that triggered them.

The result is that one user click → finalize-stage call traces cleanly
through `gateway → prj-service → uplift-service → prov-service` as a
single connected trace in Jaeger.

### MDC + response headers

Micrometer Tracing populates the SLF4J MDC with `traceId` and `spanId`
for the duration of any active span. The `RequestContextFilter` in each
servlet service:

- Includes `traceId` and `spanId` in the JSON log envelope (alongside
  `requestId`, `userEmail`, `projectId`).
- Echoes the `traceId` back on the response as `X-Atlas-Trace-Id` so
  the SPA / curl / log queries can correlate.

The reactive gateway's `RequestIdFilter` does the same via the
`Tracer` bean — `tracer.currentSpan().context().traceId()` is set on
the response headers at filter time.

### Trace IDs in provenance

`prov.entry` gained a nullable `trace_id text` column in
[V2__add_trace_id_to_prov_entry.sql](../services/prov-service/src/main/resources/db/migration/V2__add_trace_id_to_prov_entry.sql).
`ProvService.emit(...)` reads `traceId` from MDC at write time (or accepts
it explicitly on the `EmitRequest` for replay scenarios). Older entries
written before 2C.3 keep `trace_id = NULL` and are unaffected.

### "View trace" in the audit browser

[`/api/v1/auth/config`](../services/api-gateway/src/main/java/com/envestnet/atlas/gateway/GatewayApplication.java)
returns a new `traceUiUrl` field — the gateway-side
`atlas.observability.trace-ui-url` property, which defaults to
`http://localhost:26686` in compose.

The frontend [`AuditBrowser`](../frontend/src/screens/AuditBrowser.tsx)
fetches the config once, then renders a "View trace" link on every
expanded entry that has a `traceId`:

```
trace: 4af9c2d1…   View trace ↗
```

Clicking opens `${traceUiUrl}/trace/${traceId}` in a new tab. When the
gateway is configured with `ATLAS_TRACE_UI_URL=` (empty), the link is
hidden — useful in environments where the tracing backend is private.

### Quick smoke test

After `docker compose up`:

```bash
# Make a tracing-active request through the gateway:
curl -H "Authorization: Bearer $JWT" \
     http://localhost:8080/api/v1/projects -i

# X-Atlas-Trace-Id header carries the id; copy it…
# …then pull it up in Jaeger:
open http://localhost:26686/trace/<paste id here>
```

### Why no end-to-end test for tracing?

The instrumentation is auto-applied by Micrometer Tracing; there's no
Atlas-specific span code to unit-test. The end-to-end contract — that
spans flow gateway → downstream and finally into Jaeger — is verified
manually by following the smoke test above. Existing per-service unit
suites (45 gateway, 26 prj-service, 5 fake-idp, 3 llm-gateway, 113
frontend) all stay green with the tracing deps + config changes.

---

## See also

- [auth.md](auth.md) — gateway-asserted identity headers (`X-Atlas-User-*`)
  that this layer reads
- [testing.md](testing.md) — how to run the full suite

## Phased roadmap

- **2C.1 (shipped)** — structured logging + MDC enrichment + request-id
  propagation across the whole call graph
- **2C.2 (shipped)** — Prometheus on every service, custom metrics
  (`atlas_stage_finalize_total`, `atlas_llm_invocation_seconds`),
  Prometheus container in compose
- **2C.3 (shipped)** — OpenTelemetry tracing via
  `micrometer-tracing-bridge-otel`, Jaeger all-in-one in compose,
  `trace_id` stamped on every provenance entry, "View trace" link in
  the audit browser, gateway exposes `traceUiUrl` in `/api/v1/auth/config`
