# Atlas Migrate · hardening

This document covers Phase 2J — the layer of input-side defenses that
sit between the public internet and the Atlas application logic. Three
concerns, three pieces:

1. **Rate limiting** at the gateway, per-user where possible, per-IP
   as a fallback. Stops abusive callers from monopolising compute or
   driving up LLM cost. Wired via Spring Cloud Gateway's
   `RequestRateLimiter` filter on a Redis-backed token bucket.
2. **Bean Validation** on every controller `@RequestBody`, with a
   centralised `@RestControllerAdvice` that turns failures into a
   stable 400 envelope. Replaces Spring's default behaviour of
   leaking Jackson stack traces to the caller.
3. **Request-size caps** on prj-service. A 1 MB body / 32 KB header
   ceiling makes "send a 5 GB JSON to OOM the heap" a non-attack.

The phase is opinionated about its blast radius: every default is
production-safe out of the box, and every limit is overridable via
environment variables so a customer with legitimate large-payload
workflows can tune without code changes.

## What's shipped (Phase 2J)

| Layer | Where |
|---|---|
| **Rate-limit beans** | `services/api-gateway/src/main/java/com/envestnet/atlas/gateway/RateLimitConfig.java` |
| **Rate-limit filter wiring** | `services/api-gateway/src/main/java/com/envestnet/atlas/gateway/GatewayApplication.java` (`.filters(f -> f.requestRateLimiter(...))`) |
| **Rate-limit config** | `services/api-gateway/src/main/resources/application.yml` (`atlas.ratelimit.*`) |
| **Bean Validation deps** | `services/prj-service/pom.xml` (`spring-boot-starter-validation`) |
| **Validation annotations** | `services/prj-service/src/main/java/com/envestnet/atlas/prj/web/ProjectController.java` (`CreateProjectRequest`, `AttachSourceRequest`) |
| **Error handler** | `services/prj-service/src/main/java/com/envestnet/atlas/prj/web/ValidationExceptionHandler.java` |
| **Request-size caps** | `services/prj-service/src/main/resources/application.yml` (`server.tomcat.*`, `spring.servlet.multipart.*`) |
| **Tests** | `RateLimitConfigTest` (6 cases) + `ValidationExceptionHandlerTest` (4 cases) |

## Rate limiting

### Why

The api-gateway is the only ingress for SPA + customer-SDK callers.
Three classes of bad behaviour the gateway is the right place to stop:

- **Runaway scripts.** A misbehaving cron job hitting
  `/api/v1/projects` 1000×/s would saturate prj-service's thread pool
  and starve interactive users.
- **Credential-stuffing on `/api/v1/auth/*`.** Even though fake-idp /
  the customer IdP does the actual auth, the gateway shouldn't help
  an attacker iterate by amplifying their request rate.
- **LLM-cost abuse on `/api/v1/llm/**`.** Each call there ultimately
  hits a paid OpenAI / Anthropic endpoint. Rate-limit-per-user means
  one compromised token can't drain the budget in seconds.

### Architecture

Spring Cloud Gateway ships a `RequestRateLimiter` filter that needs
two collaborators: a `KeyResolver` (what bucket does this request go
in?) and a `RateLimiter` (the actual algorithm — Redis token-bucket,
in our case).

```
   client          api-gateway                    Redis
    │  HTTP req      │                              │
    │ ─────────────► │ KeyResolver: "user:alice"    │
    │                │ ──── INCRBY bucket key ────► │
    │                │ ◄──── tokens remaining ───── │
    │                │                              │
    │                │ if tokens >= 0 → proxy on    │
    │                │ if tokens <  0 → 429         │
```

### Key resolution

`RateLimitConfig.userKeyResolver()` reads the
`ReactiveSecurityContextHolder`, which is populated upstream by the
JWT validation filter. Three branches:

| Request shape | Bucket key |
|---|---|
| Authenticated (JWT in context) | `user:<jwt.subject>` |
| No auth, `X-Forwarded-For` set | `ip:<first XFF entry>` |
| No auth, no XFF | `ip:<remoteAddress.host>` |
| No auth, no XFF, no remote | `ip:unknown` |

Two non-obvious choices, both worth keeping:

- **JWT subject, not email.** The subject is opaque, stable across
  the lifetime of the IdP-side user object, and not user-typeable.
  Email can change (mailbox migration, marriage, domain reorg) and
  the bucket would then split.
- **First entry of `X-Forwarded-For` on multi-hop.** The de-facto
  convention (CloudFront, AWS ALB, GCLB, nginx, Spring Cloud Gateway
  itself) is `client, proxy1, proxy2, ...`. Taking the last entry
  would key every request from the same LB into the same bucket —
  exactly the abuse pattern we're trying to stop.

The fallback to `ip:unknown` (vs. crashing) is deliberate: in test
harnesses and some misconfigured proxies the remote address is null.
Better to group those into a shared bucket than to 500 on every
request.

### Token-bucket policy

```yaml
atlas:
  ratelimit:
    # 10 req/s sustained, 20 req burst. A real SPA user peaks at
    # 2-3 req/s, so this is comfortable headroom while still
    # stopping abuse.
    replenish:       ${RATE_LIMIT_REPLENISH:10}
    burst:           ${RATE_LIMIT_BURST:20}
    requested-tokens: 1
```

These translate directly to the three `RedisRateLimiter` constructor
arguments. All three are configurable per environment via env vars —
no code change needed when load testing tells us a customer needs
more headroom.

### What's protected, what isn't

The filter is attached only to the two routes that proxy downstream:

```java
.route("prj", r -> r.path("/api/v1/workspaces/**", "/api/v1/projects/**")
        .filters(f -> f.requestRateLimiter(c -> c
                .setRateLimiter(defaultRedisRateLimiter)
                .setKeyResolver(userKeyResolver)))
        .uri(prjUrl))
.route("llm", r -> r.path("/api/v1/llm/**")
        .filters(f -> f.requestRateLimiter(c -> c
                .setRateLimiter(defaultRedisRateLimiter)
                .setKeyResolver(userKeyResolver)))
        .uri(llmUrl))
```

`/api/v1/auth/**` (the unauthenticated bootstrap probe) and
`/actuator/**` (health + metrics) are handled inline by the gateway
itself and never enter the route table, so they automatically bypass
the limiter — which is what we want. The cost is correctly observed:
an attacker still can't DoS auth bootstrap because (a) the gateway
serves it from memory and (b) ingress-level rate limiting at the
infrastructure tier is the right defense for static-ish endpoints.

### Test coverage

`RateLimitConfigTest` is a pure-unit suite — no Spring context, no
Redis, just the `KeyResolver` bean reading from a `MockServerWebExchange`.
Six cases pin the policy:

| Case | What it pins |
|---|---|
| `authenticatedRequestKeysOnTheJwtSubject` | JWT path produces `user:<sub>` |
| `unauthenticatedRequestFallsBackToRemoteAddress` | No auth, no XFF → `ip:<remoteAddr>` |
| `xForwardedForHeaderOverridesRemoteAddress` | Single-hop XFF beats remote addr |
| `multiHopXForwardedForTakesTheFirstEntry` | `1.1.1.1, 2.2.2.2, 3.3.3.3` → `ip:1.1.1.1` |
| `resolverHandlesMissingRemoteAddressGracefully` | Null remote address → `ip:unknown` |
| `nonJwtAuthenticationFallsBackToIp` | Non-JWT auth in context → IP fallback |

## Bean Validation

### Why

Atlas's typed records (`CreateProjectRequest`, `AttachSourceRequest`,
etc.) already encode the shape of valid input. Bean Validation lets
us check the constraints declaratively in one place rather than
peppering controller methods with `if (req.name() == null || ...)`
preambles. Two wins:

- **Single source of truth** — the constraint sits next to the
  field. Future contributors see "`@Size(min = 3, max = 200) String
  name`" and don't have to grep the controller body.
- **Predictable error shape for the SPA / SDK** — every validation
  failure returns the same envelope (see below). Client code can
  render field-level errors generically instead of per-endpoint.

### Constraint surface

`ProjectController.CreateProjectRequest`:

| Field | Constraints |
|---|---|
| `workspaceId` | (UUID, type-enforced by Jackson) |
| `name` | `@NotBlank`, `@Size(min = 3, max = 200)` |
| `description` | `@Size(max = 4000)` |
| `mode` | `@NotBlank`, `@Pattern("SOAP|UPLIFT")` |
| `sourceFramework` / `targetFramework` / `vendorPartner` / `owner` | `@Size(max = 200)` |
| `targetJavaVersion` | `@Size(max = 50)` |
| `riskTier` | `@Pattern("LOW|MEDIUM|HIGH|CRITICAL")` |
| `sourcePath` | `@Size(max = 1024)` |

`ProjectController.AttachSourceRequest`:

| Field | Constraints |
|---|---|
| `path` | `@NotBlank`, `@Size(max = 1024)` |

The `@RequestBody` parameters carry `@jakarta.validation.Valid` to
trigger the constraint engine. Without it, the annotations are
inert metadata.

### Response envelope

`ValidationExceptionHandler` is a `@RestControllerAdvice` with three
handlers. All three return the same shape so the SPA renders errors
generically:

```json
{
  "status": 400,
  "error": "validation_failed",
  "message": "request body failed validation",
  "fieldErrors": [
    { "field": "name",  "rejected": "ab",  "message": "size must be between 3 and 200" },
    { "field": "mode",  "rejected": "xyz", "message": "mode must be one of SOAP, UPLIFT" }
  ],
  "timestamp": "2026-05-11T12:34:56.789Z"
}
```

The three exceptions handled:

| Exception | `error` discriminator | When |
|---|---|---|
| `MethodArgumentNotValidException` | `validation_failed` | `@Valid` constraint violation |
| `HttpMessageNotReadableException` | `malformed_body` | Body can't be parsed as JSON at all |
| `MethodArgumentTypeMismatchException` | `type_mismatch` | Path / query param can't bind (e.g. non-UUID where UUID expected) |

### The no-leak invariant on `malformed_body`

The wrapped Jackson exception carries detail like
`"Unexpected character ('n' (code 110)) in JSON at line 1, column 2"`.
That text leaks parser internals to the caller — line/column of the
bad byte, the exact bad character — which customer security scanners
flag as information disclosure. The handler deliberately discards
the cause's message and emits a fixed `"request body could not be
parsed as JSON"`. The unit test asserts the response body does NOT
contain `"line 1"`, `"Unexpected character"`, or `"code 110"`.

### Test coverage

`ValidationExceptionHandlerTest` is a pure-unit suite — constructs
synthetic `BeanPropertyBindingResult` / `JsonParseException` /
`MethodArgumentTypeMismatchException` instances and feeds them to
the handler. Four cases:

| Case | What it pins |
|---|---|
| `validationFailureReturns400WithFieldLevelErrorMap` | Multi-field violation produces a 2-element `fieldErrors` array |
| `validationFailureWithSingleErrorEmitsSingletonArray` | 1-error case still emits an array, not a flattened object |
| `malformedJsonReturns400WithoutLeakingParserDetails` | No `"line 1"`, no `"Unexpected character"`, no `"code 110"` |
| `typeMismatchOnUuidPathParamReturns400` | Bad UUID → 400 with field + rejected + message mentioning `UUID` |

## Request-size caps

### Why

Tomcat's defaults allow up to 2 MB request bodies and 8 KB headers.
We tighten both:

- **1 MB body.** Atlas's largest legitimate POST is a custom-recipe
  body (~10 KB); 1 MB is comfortable headroom while still capping
  "POST me a 5 GB JSON" abuse.
- **32 KB headers.** A user with a chunky JWT (Keycloak with lots of
  client roles can push 6-8 KB) plus an Idempotency-Key plus a
  trace propagation header plus the request-id header fits
  comfortably; 32 KB caps "stuff 64 KB of cookies in" abuse.

### Configuration

```yaml
server:
  port: 8081
  tomcat:
    max-http-form-post-size: ${MAX_REQUEST_SIZE:1MB}
    max-swallow-size:        ${MAX_REQUEST_SIZE:1MB}
  max-http-request-header-size: ${MAX_HEADER_SIZE:32KB}

spring:
  servlet:
    multipart:
      max-file-size:    ${MAX_REQUEST_SIZE:1MB}
      max-request-size: ${MAX_REQUEST_SIZE:1MB}
```

Both ceilings are env-overridable. A customer with a legitimate
50 MB-payload workflow can set `MAX_REQUEST_SIZE=50MB` without
touching code.

### Why not at the gateway?

Spring Cloud Gateway is reactive — limiting request size there
requires a `ModifyRequestBody` filter or similar. The downstream
Tomcat limit is the simpler safety net and works for every
service. Pushing the cap into the gateway is a deferred
follow-up (see below); for now an attacker who somehow gets past
ingress still hits the wall at the service tier.

## What this layer does NOT do

Inherent limitations, called out so future work has a clear
shopping list:

- **Per-route rate limits.** The current policy is global (10
  req/s for everything). A heavy endpoint like
  `POST /api/v1/projects/{id}/stages/archaeology/run` arguably
  deserves a tighter budget than `GET /api/v1/me`. Per-route
  overrides happen at the filter declaration site; we'll add
  them as load testing reveals hot endpoints.
- **Reactive-gateway body-size cap.** As noted above, the size
  ceiling lives at the Tomcat layer. An attacker who can hit
  prj-service directly (e.g. via a hostile network with east-
  west access) still gets capped. But traffic through the
  gateway flows over Netty, and Netty's default cap is generous.
  Adding a `ModifyRequestBody` filter (or a Spring Cloud Gateway
  `RemoveContentLength`-style filter that 413s big bodies) is
  the right next step.
- **Request-body schema validation at the gateway.** The
  gateway today is a dumb proxy; all schema validation lives
  downstream. Pushing OpenAPI-driven validation to the gateway
  would let us reject malformed payloads before they hit
  prj-service threads, but introduces a tight coupling between
  the gateway and every downstream's contract.
- **CSRF on state-changing requests.** The SPA uses JWT bearer
  auth, not cookies, so CSRF isn't applicable today. If we ever
  add cookie-based auth (rare in Atlas-shaped products),
  re-enable Spring Security's CSRF filter on the gateway.

## Phased roadmap

- **2J.1 (shipped)** — Rate limiter beans (`KeyResolver`,
  `RedisRateLimiter`), wired on prj + llm routes, with 6 unit
  tests pinning key-resolution policy.
- **2J.2 (shipped)** — Bean Validation enabled, constraints on
  `CreateProjectRequest` + `AttachSourceRequest`,
  `ValidationExceptionHandler` translating to a stable 400
  envelope, with 4 unit tests pinning the response shape and
  the no-leak invariant.
- **2J.3 (shipped)** — Tomcat-tier request size caps (1 MB body,
  32 KB headers), both env-overridable.
- **2J.4 (shipped)** — this document.

### Open follow-ups

- **Per-route rate-limit overrides.** A `.filters(... rate-limiter
  with replenish=2, burst=4)` block on the archaeology-run route
  would protect against scripted stage abuse without throttling
  read-heavy SPA browsing.
- **413 at the gateway.** A reactive size-cap filter so abusive
  payloads die at ingress, not at the service tier.
- **Validation on the other controllers.** Today only
  `ProjectController` carries `@Valid`. As the other services
  grow public POST endpoints, the same pattern should be applied
  — the `ValidationExceptionHandler` ships as a `@ControllerAdvice`
  so it's drop-in.
- **Custom validators for SOAP-WSDL paths and Java-version
  strings.** Today `targetJavaVersion` is `@Size(max = 50)`;
  ideally it would be `@Pattern("(8|11|17|21)")` once we pin
  which versions Atlas supports end-to-end.
- **Rate-limit observability.** Spring Cloud Gateway emits
  `spring.cloud.gateway.requests` with status tags;
  `status=429` count tells us when buckets are tripping.
  Surface this on Grafana so we see throttling before customers
  complain.
