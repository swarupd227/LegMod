# Atlas Migrate · reliability

This document covers Phase 2G — the patterns Atlas uses to keep the
user-facing read path responsive when downstream services misbehave,
and to make POST/PUT/DELETE retries from a flaky network safe to
re-issue.

## What's shipped (Phase 2G)

| Layer | Where |
|---|---|
| **Circuit breaker + retry** | `services/prj-service/pom.xml` (Resilience4j 2.2.0), `services/prj-service/src/main/resources/application.yml` (policies), `…/reliability/DownstreamGateway.java` (decorated wrapper) |
| **Server-side idempotency** | `services/prj-service/src/main/java/com/envestnet/atlas/prj/reliability/IdempotencyFilter.java` |
| **Frontend Idempotency-Key generation** | `frontend/src/api/client.ts` (auto-injects on POST/PUT/PATCH/DELETE) |
| **Tests** | `IdempotencyFilterTest` (10 cases) + `client.test.ts` idempotency block (5 cases) |

## Circuit breaker + retry

### Why

prj-service is the fan-out hub: a single user click on "Run
archaeology" triggers calls to arch / cap / recon / gen / diff /
reports / prov / uplift in turn. Without protection, one slow
downstream blocks prj-service threads, which blocks the user-facing
read endpoints like `GET /api/v1/projects` because they share the same
RestTemplate / connection pool.

### How

[Resilience4j](https://resilience4j.readme.io/) on prj-service. A
single shared instance named **`downstream`** carries the policy:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      downstream:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50               # %
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        record-exceptions:
          - org.springframework.web.client.RestClientException
          - java.io.IOException
  retry:
    instances:
      downstream:
        max-attempts: 3
        wait-duration: 200ms
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - org.springframework.web.client.HttpServerErrorException
          - java.net.SocketTimeoutException
          - java.net.ConnectException
```

Notes on the policy:
- **No TimeLimiter** — the existing `RestTemplate` read timeout (3 min,
  set in `PrjApplication.restTemplate`) is the source of truth.
  TimeLimiter shorter than that would break archaeology / generation
  runs which can legitimately take 60+ seconds. If you tighten one,
  tighten the other.
- **4xx is not retried** — those are deterministic client errors
  (validation, missing project, etc.). Retrying would just hammer the
  downstream with the same broken request.
- **5xx + connect errors are retried** — likely transient.
- **Circuit breaker trips at 50% failure over 20 calls** — conservative
  enough not to flap on a single bad request, aggressive enough to
  shed load when a downstream is genuinely down.

### Consumer pattern

Use `DownstreamGateway` instead of calling `RestTemplate` directly:

```java
@Autowired DownstreamGateway downstream;
…
ProjectStatus status = downstream.getForObject(uri, ProjectStatus.class);
```

The wrapper's public methods are annotated with
`@CircuitBreaker(name = "downstream")` + `@Retry(name = "downstream")`,
so the policies above apply automatically. **Calling `RestTemplate`
directly bypasses the policy** — Spring's AOP only wraps proxied
methods.

ProjectController hasn't been mass-migrated to DownstreamGateway yet;
existing call sites still go through the raw RestTemplate.
DownstreamGateway is wired and tested; new code should prefer it, and
hot-path call sites can be converted in follow-ups as load testing
surfaces them.

### What gets observed

- `resilience4j.circuitbreaker.calls` — Micrometer auto-published.
  Tagged with `kind={successful,failed,not_permitted}` and
  `name=downstream`. Surface on Grafana to see the trip rate.
- `resilience4j.retry.calls` — counts retry vs. successful first-try.

Both land at `/actuator/prometheus` via the metrics layer wired in
[Phase 2C.2](observability.md).

## Server-side idempotency

### Why

A user clicks "Finalize Stage B" once. The browser request times out
on a slow network; the SPA retries; both reach the server. Without
protection, the gate transitions twice (no harm — gates are
idempotent at the DB level today) but `/decisions/{id}/resolve`
double-writes a provenance entry, double-charges LLM tokens, etc.

The right shape: a stable, client-generated **Idempotency-Key**
header per logical mutation. The server replays the original response
on retry — no double-execution.

### How — server side

`IdempotencyFilter` is a `OncePerRequestFilter` registered on
prj-service. On every POST / PUT / PATCH / DELETE:

1. If the request carries an `Idempotency-Key` header, look it up in
   an in-memory TTL cache, scoped by `(method, path, key)`.
2. **Cache hit** → replay the cached status + body without invoking
   the controller. Set `Idempotent-Replay: true` on the response so
   callers (and operators reading logs) can tell.
3. **Cache miss** → execute the controller, capture the response,
   cache it for 10 min if it's a 2xx.
4. **Non-2xx is never cached** — clients should be free to fix the
   request and re-submit with the same key without confusion.
5. Requests **without** an `Idempotency-Key` flow through normally.
   Idempotency is opt-in at the client.

Storage is `IdempotencyFilter.IdempotencyCache`, with the default
`InMemoryIdempotencyCache` adequate for the local-Docker demo and
single-pod deployments. Multi-pod customers should drop in a Redis
implementation of the same interface — Redis is already in compose
for the LLM gateway.

### How — client side

The frontend api client auto-generates a fresh `Idempotency-Key` on
every mutating request:

```ts
// frontend/src/api/client.ts
const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
function authHeaders(method: string, extra?: HeadersInit): HeadersInit {
  …
  if (MUTATING_METHODS.has(method.toUpperCase())) {
    out['Idempotency-Key'] = newIdempotencyKey();   // crypto.randomUUID()
  }
  …
}
```

Notes:
- `crypto.randomUUID` is preferred; a hand-built RFC 4122 v4 fallback
  covers older environments.
- **One key per logical action.** Today every fetch generates a fresh
  key, which means a SPA-level retry of the SAME logical button click
  would generate a new key and bypass the cache. That's fine for
  network-flake retries (browser auto-retries on transient errors
  use the same headers) but doesn't protect against a user double-
  clicking the button in 50 ms. The button's `disabled` state during
  pending mutations covers that case today; if we want server-side
  protection too, push the key generation up to the React Query
  mutation level (one key per `mutationFn` invocation, not per
  `fetch`).
- **GET requests get no key** — they're idempotent by HTTP semantics.

### Replay semantics, in detail

| Scenario                                           | Server behavior                                    |
|----------------------------------------------------|----------------------------------------------------|
| First call, key=K, path=`/X`                       | Executes, caches `(POST,/X,K) → response` for 10m  |
| Second call, key=K, path=`/X` (within 10m)         | Replays cached response, header `Idempotent-Replay: true` |
| Different key on same path                         | Re-executes (different cache slot)                 |
| Same key on different path                         | Re-executes (path-scoped)                          |
| Same key on different method                       | Re-executes (method-scoped)                        |
| Original returned 4xx                              | Not cached. Second call re-executes.               |
| Original returned 5xx                              | Not cached. Second call re-executes.               |
| Cache TTL elapsed                                  | Re-executes; new entry caches.                     |

These are pinned by `IdempotencyFilterTest` — 10 cases, all green.

### Limitations

- **Single-pod cache only.** The default in-memory cache doesn't share
  across replicas. A user whose first request lands on pod A and a
  retry on pod B will see the retry execute. Documented in the cache
  abstraction; Redis-backed implementation is the path forward.
- **No request-body hashing.** The cache keys on `(method, path,
  Idempotency-Key)`, not on the request body. A misbehaving client
  that reuses a key with a different body still gets the original
  cached response — silently wrong. The Stripe-style
  request-body-hash check is the right next step; deferred because the
  SPA generates fresh keys per call so the misuse pattern doesn't
  exist for our consumer.

## Tests

| Layer | Where | What it pins |
|---|---|---|
| Filter unit | `prj-service/.../IdempotencyFilterTest` | 10 cases — GET passthrough, missing/blank key, first-call-caches, replay-on-duplicate, scoping by method+URI, no-cache on 4xx/5xx |
| Frontend | `frontend/src/api/client.test.ts § api · idempotency key` | 5 cases — UUID-v4 shape on POST/PUT/DELETE, omitted on GET, fresh key per call |
| Integration | (none) — the filter ships independent of any controller; smoke-testing it inside prj-service's full context would test the same paths the unit tests already cover |

All within the existing CI workflow (`mvn -B verify` for Java, `npm
test` for the SPA).

## Phased roadmap

- **2G.1 (shipped)** — Resilience4j circuit breaker + retry on
  prj-service; `DownstreamGateway` wrapper; policies in
  application.yml
- **2G.2 (shipped)** — `IdempotencyFilter` server-side, opt-in via
  the `Idempotency-Key` header, in-memory TTL cache
- **2G.3 (shipped)** — frontend auto-generates Idempotency-Key per
  mutation; UUID v4 via `crypto.randomUUID` with a fallback
- **2G.4 (shipped)** — this document

### Open follow-ups

- **Redis-backed `IdempotencyCache`** for multi-pod deployments —
  swappable bean, no consumer changes
- **Request-body hash check** — Stripe-style; reject the second call
  if the body differs from the first
- **DownstreamGateway adoption sweep** — migrate the ~30 `http.
  getForObject(...)` call sites in ProjectController to use the
  decorated wrapper. Today it's wired but unused; circuit breaker
  metrics will show all-zero until the conversion lands
- **Bulkhead** — Resilience4j's `@Bulkhead` would cap concurrent
  outbound calls per downstream. Useful when one downstream goes
  slow but not failing — the circuit breaker doesn't trip. Deferred
  until load testing shows real evidence
- **Outbound idempotency key forwarding** — when prj-service fans out
  to uplift, etc., it should forward the user-supplied
  Idempotency-Key (or derive a stable per-fanout key) so the chain
  stays exactly-once even on partial failure
