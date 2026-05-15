# Atlas Migrate · authentication

Phase 2D.1 introduces real OAuth2/OIDC authentication at the api-gateway,
with a local **fake-IdP** for demos and CI. The same auth code paths run
in production (`auth-real` profile) and in demos (`auth-sim` profile) —
only the issuer changes.

## Two profiles, one architecture

| Profile     | Issuer                                                     | When                              |
|-------------|------------------------------------------------------------|-----------------------------------|
| `auth-sim`  | Local `fake-idp` service in-stack on port `28093 → 8093`   | Demos, dev, CI                    |
| `auth-real` | Customer's OIDC provider (Okta / Entra / Auth0 / ...)      | Customer engagements / production |

The gateway always validates incoming JWTs against the configured issuer's
JWKS. The same role-claim mapping, the same `/api/v1/me` controller, the
same identity propagation — only the JWKS source flips.

The two profiles are **mutually exclusive** in `application.yml`. There is
no way to ship the demo path to production without explicitly setting
`SPRING_PROFILES_ACTIVE=auth-sim`.

## Demo flow (`auth-sim`)

```
   ┌──────────┐                     ┌──────────────┐
   │ Browser  │                     │  fake-idp    │
   │  (SPA)   │                     │  :8093       │
   └────┬─────┘                     └──────┬───────┘
        │  GET /authorize?code_challenge…   │
        ├──────────────────────────────────►│  → 302 /login (no session)
        │                                    │
        │  GET /login                        │
        ├──────────────────────────────────►│  → renders persona picker
        │                                    │
        │  POST /login (personaId=alice)     │
        ├──────────────────────────────────►│  → 302 /authorize w/ session
        │                                    │
        │  GET /authorize (with cookie)      │
        ├──────────────────────────────────►│  → 302 /auth/callback?code=…
        │                                    │
        │  POST /token (code, verifier)      │
        ├──────────────────────────────────►│  → JWT (RS256, atlas_demo:true)
        │                                    │
        │  Authorization: Bearer <jwt>       │
        ├─────────► api-gateway :8080 ───────┴─► validates JWT, routes…
```

The `atlas_demo: true` claim is set by fake-idp and is the signal the
frontend uses to render the "DEMO IDENTITY" banner. Real OIDC providers
never set this claim, so the banner never shows in production.

### Personas

`services/fake-idp/src/main/resources/application.yml` defines three:

| Persona | Email                       | Roles                            |
|---------|-----------------------------|----------------------------------|
| Alice   | alice@envestnet.local       | `[ENGINEER]`                     |
| Bob     | bob@envestnet.local         | `[ENGINEER, TECH_LEAD]`          |
| Carol   | carol@envestnet.local       | `[ENGINEER, TECH_LEAD, ADMIN]`   |

Roles in the `roles` claim are mapped to Spring `ROLE_*` authorities at
the gateway, so downstream `@PreAuthorize("hasRole('TECH_LEAD')")` checks
land cleanly. (Phase 2D.2 will add those checks.)

## Production setup (`auth-real`)

Per-IdP walkthroughs (Keycloak, Okta, Microsoft Entra ID) live in
**[auth-production.md](auth-production.md)**, including the universal
env-var contract, audience-mapper config, role-claim mapping, the
RP-initiated logout endpoint, and a troubleshooting table.

The frontend's PKCE flow works against any compliant OIDC provider — no
SPA changes needed.

## Files

```
services/fake-idp/
  pom.xml
  Dockerfile
  src/main/java/com/envestnet/atlas/fakeidp/
    FakeIdpApplication.java        ← settings + bootstrap
    JwtIssuer.java                 ← RS256 signing, in-memory keypair
    OidcController.java            ← /authorize, /login, /token, JWKS
    WebConfig.java                 ← CORS for SPA's /token POST
  src/main/resources/
    application.yml                ← personas, issuer, TTL
    templates/login.html           ← Thymeleaf persona picker
  src/test/java/.../OidcSmokeTest.java   ← end-to-end PKCE walk

services/api-gateway/
  pom.xml                          ← + spring-boot-starter-oauth2-resource-server
  src/main/java/com/envestnet/atlas/gateway/
    GatewayApplication.java        ← /api/v1/me reads Jwt, /api/v1/auth/config probe
    SecurityConfig.java            ← reactive filter chain, role mapper
  src/main/resources/application.yml
                                   ← auth-sim + auth-real profile config
  src/test/java/.../SecurityConfigTest.java   ← 6 cases pinning the contract

frontend/
  src/auth/
    authClient.ts                  ← PKCE init, token store, callback handler
    AuthGate.tsx                   ← redirects to login when no token
  src/auth/authClient.test.ts      ← 11 unit cases
  src/api/client.ts                ← Authorization header on every fetch, 401 → clear

docs/auth.md                       ← (this document)
docker-compose.yml                 ← fake-idp service + gateway profile wiring
```

## Testing

| Layer | Where | What it pins |
|---|---|---|
| Java unit (fake-idp) | `OidcSmokeTest` | Discovery doc, JWKS, full PKCE flow including JWT signature verification, PKCE-mismatch rejection |
| Java unit (gateway) | `SecurityConfigTest` | Public endpoints (`/actuator/**`, `/api/v1/auth/config`), 401 on missing/invalid bearer, 200 + identity body on valid bearer, multi-role propagation |
| Frontend unit | `authClient.test.ts`, `client.test.ts` | JWT decode, token expiry, Authorization header, 401 → token clear |
| E2E | Existing Playwright specs (token injected via `addInitScript`) | Auth-gated routes still render with a synthetic token in localStorage |

All tests run on `mvn -B verify` (Java) and `npm test` / `npm run e2e`
(frontend), and are wired into `.github/workflows/ci.yml`.

## Logout

Demo logout (`auth-sim`) is local-only: clearing the token in the SPA is
enough. A page reload then triggers the `AuthGate` redirect to the
persona picker. In production (`auth-real`), customer IdPs typically
expose an RP-initiated logout endpoint — wire that into
`authClient.logout()` per-customer if single sign-out is required; the
hook is documented inline.

## Role-based authorization (Phase 2D.2)

Authorization is enforced **at the gateway** via path patterns, so
downstream services can stay auth-unaware until 2D.3. Two decisions:

1. **Day-to-day work needs `ENGINEER`** — running stages, deciding on
   recipes, editing notes, marking strangler steps ready, triaging
   characterization cases, etc. Every authenticated `/api/v1/**` request
   that isn't a finalize/build requires this role.
2. **Gate finalization needs `TECH_LEAD`** — moving the project's
   `currentStage` cursor forward is a checkpoint, so it requires a
   second role. `ADMIN` carries `TECH_LEAD` by convention so admins can
   finalize too.

### Path → role matrix

| Method · Path                                                                | Required role |
|------------------------------------------------------------------------------|---------------|
| `POST /api/v1/projects/*/stages/recipes/finalize`                            | `TECH_LEAD`   |
| `POST /api/v1/projects/*/stages/strangler/finalize`                          | `TECH_LEAD`   |
| `POST /api/v1/projects/*/stages/migration/finalize`                          | `TECH_LEAD`   |
| `POST /api/v1/projects/*/stages/characterize/finalize`                       | `TECH_LEAD`   |
| `POST /api/v1/projects/*/stages/cutover/finalize`                            | `TECH_LEAD`   |
| `POST /api/v1/projects/*/stages/capture/finalize`                            | `TECH_LEAD`   |
| `POST /api/v1/projects/*/stages/reports/build`                               | `TECH_LEAD`   |
| `*    /api/v1/auth/**`                                                       | (public)      |
| `*    /actuator/**`                                                          | (public)      |
| Everything else under `/api/v1/**`                                           | `ENGINEER`    |

The rules live in
[`SecurityConfig.springSecurityFilterChain`](../services/api-gateway/src/main/java/com/envestnet/atlas/gateway/SecurityConfig.java).
Per-finalize coverage is pinned by `FinalizeAuthorizationTest` (20
parameterised cases — engineer denied × 7, tech-lead allowed × 7,
admin allowed × 3, plus three smoke tests for anonymous and roleless
JWTs).

### Frontend behaviour on 403

The api client throws a typed `ForbiddenError` (distinct from the
generic `Error` it throws on 401/5xx) so callers can render a helpful
message rather than a generic "request failed" toast. Critically, 403
does **not** clear the stored token — the user is correctly signed in,
they're just lacking a role.

Every finalize/build button across the stage screens — Recipe Authoring
(B), Runtime Capture (B), Strangler Designer (C), Module Migration (D),
Characterization (E), Cutover & Decommission (F UPLIFT), Reports Hub
(F SOAP) — checks `hasRole('TECH_LEAD')` from the JWT and:

- **Disables the button** when the user lacks the role.
- **Sets the `title` tooltip** to "Finalizing a stage requires the
  Tech Lead role" so the gating reason is discoverable on hover.

The check is purely a UX affordance; the gateway is still the
authoritative authorization boundary. An engineer who hand-crafts a
finalize POST gets a clean 403 from the gateway, regardless of what the
button says.

### Demo personas, restated for roles

| Persona | Roles                          | Can finalize? |
|---------|--------------------------------|---------------|
| Alice   | `[ENGINEER]`                   | No            |
| Bob     | `[ENGINEER, TECH_LEAD]`        | Yes           |
| Carol   | `[ENGINEER, TECH_LEAD, ADMIN]` | Yes           |

Demos that want to show both sides of the gate sign in as Alice first,
hit a finalize button (sees disabled state + tooltip), then sign out and
sign in as Bob to actually finalize.

## Identity propagation (Phase 2D.3)

The gateway is the only authoritative source of "who is doing this."
Every authenticated request to `/api/v1/**` carries three
gateway-stamped headers downstream:

| Header                  | Value                                                   |
|-------------------------|---------------------------------------------------------|
| `X-Atlas-User-Email`    | JWT `email` claim (or `sub` if missing)                 |
| `X-Atlas-User-Name`     | JWT `name` claim                                        |
| `X-Atlas-User-Roles`    | Comma-separated, `ROLE_` prefix stripped (e.g. `ENGINEER,TECH_LEAD`) |

### Defense in depth

The gateway's [`IdentityForwardingFilter`](../services/api-gateway/src/main/java/com/envestnet/atlas/gateway/IdentityForwardingFilter.java)
**strips** any `X-Atlas-User-*` headers the client sent **before** it
re-stamps them from the validated JWT. A client cannot spoof identity
even if it tries — the test
[`IdentityForwardingFilterTest.clientSuppliedIdentityHeadersAreAlwaysStripped`](../services/api-gateway/src/test/java/com/envestnet/atlas/gateway/IdentityForwardingFilterTest.java)
locks this in.

### Network assumption

Downstream services in production **must not be reachable from the
public internet** — only via the gateway. Docker Desktop demos expose
host ports for convenience; production compose / k8s deployments must
keep downstream services on a private network. This is the only
defense against an attacker on the same network sending forged
`X-Atlas-User-*` headers directly to a downstream service.

### Header propagation across the call graph

When prj-service fans out to uplift-service, arch-service, etc., it
must forward the identity headers it received. This is automatic via
the [`IdentityForwardingInterceptor`](../services/prj-service/src/main/java/com/envestnet/atlas/prj/auth/IdentityForwardingInterceptor.java)
registered on prj-service's `RestTemplate` bean — so the user identity
flows the full graph without per-call boilerplate.

```
   Browser                Gateway              prj-service          uplift-service
     │ Bearer <jwt>         │                       │                       │
     ├─────────────────────►│                       │                       │
     │                      │ X-Atlas-User-Email    │                       │
     │                      │ X-Atlas-User-Roles    │                       │
     │                      ├──────────────────────►│                       │
     │                      │                       │ X-Atlas-User-Email    │
     │                      │                       │ X-Atlas-User-Roles    │
     │                      │                       ├──────────────────────►│
```

### Reading the identity inside a controller

Each Spring service that needs the user-on-the-wire ships a small
helper class
[`GatewayIdentity`](../services/prj-service/src/main/java/com/envestnet/atlas/prj/auth/GatewayIdentity.java)
([uplift-service copy](../services/uplift-service/src/main/java/com/envestnet/atlas/uplift/auth/GatewayIdentity.java)).
The canonical pattern:

```java
String actor = GatewayIdentity.resolveActor(req.user());  // body field is fallback only
gates.save(new Gate(..., actor));
```

`resolveActor()` prefers the gateway header; if absent (background
tasks, tests) it falls back to a request-body value; if both are
missing it returns `"system"`. Audit columns therefore never go null.

### Wired today

| Service / field                                        | Source                        |
|--------------------------------------------------------|-------------------------------|
| `prj.gate.transitioned_by`                             | Gateway header (was `"system"`) |
| `uplift.recipe.decided_by`                             | Gateway header (was body `user`) |
| `uplift.strangler_step.decided_by`                     | Gateway header                |
| `uplift.cutover.approved_by`                           | Gateway header                |
| `uplift.char_case.triaged_by`                          | Gateway header                |
| `prov.entry.actor_id` (called by services above)       | Gateway header (header propagated by RestTemplate interceptor) |

### Tests

| Coverage | Test |
|---|---|
| Headers stamped on authenticated routed requests | [`IdentityForwardingFilterTest.authenticatedRequestStampsAllThreeIdentityHeaders`](../services/api-gateway/src/test/java/com/envestnet/atlas/gateway/IdentityForwardingFilterTest.java) |
| Multiple roles → comma-separated, no `ROLE_` prefix | `IdentityForwardingFilterTest.multipleRolesAreCommaSeparatedWithoutRolePrefix` |
| Spoofed `X-Atlas-User-*` always stripped + overridden | `IdentityForwardingFilterTest.clientSuppliedIdentityHeadersAreAlwaysStripped` |
| Anonymous requests carry no identity headers | `IdentityForwardingFilterTest.identityHeadersAreNotAddedToRoutedRequestWithoutAuthentication` |
| Email falls back to `sub` when `email` claim missing | `IdentityForwardingFilterTest.emailFallsBackToSubjectWhenEmailClaimIsAbsent` |
| `transitionedBy` records gateway-asserted user | [`ProjectControllerTest.gateTransitionsRecordTheGatewayAssertedUserAsTransitionedBy`](../services/prj-service/src/test/java/com/envestnet/atlas/prj/web/ProjectControllerTest.java) |
| Null-safe fallback to `"system"` when no auth | `ProjectControllerTest.gateTransitionsFallBackToSystemWhenNoGatewayHeaderIsPresent` |

## Production hardening (Phase 2D.4)

Three additions targeted at customer IdPs, all toggleable via
properties so the demo doesn't change shape:

1. **Audience validation.** Spring Boot 3.2+'s native
   `spring.security.oauth2.resourceserver.jwt.audiences` is now wired
   in both profiles. Demo expects `atlas-spa`; production picks up
   `OIDC_AUDIENCE`. JWTs with the wrong audience get a clean 401
   ([`SecurityConfigClaimsTest.wrongAudienceIsRejectedBeforeRoleCheck`](../services/api-gateway/src/test/java/com/envestnet/atlas/gateway/SecurityConfigClaimsTest.java)).
2. **Configurable roles claim.** `atlas.auth.roles-claim` is a
   dot-notation path through the JWT — `roles` (default, fake-idp +
   Entra App Roles), `realm_access.roles` (Keycloak realm roles),
   `groups` (Okta), or any nested path. Either array-of-strings or
   comma-separated values are accepted ([`SecurityConfigUnitTest`](../services/api-gateway/src/test/java/com/envestnet/atlas/gateway/SecurityConfigUnitTest.java)
   covers the path walker).
3. **RP-initiated logout.** When the gateway reports a non-empty
   `endSessionUrl` via `/api/v1/auth/config`, the SPA redirects through
   the IdP's `end_session_endpoint` on Sign-out. Without RP-initiated
   logout, the SPA forgets the token but the IdP session lingers — the
   next login could silently re-authenticate. Demos run with an empty
   `endSessionUrl` and stay on the local-only path
   ([`authClient.test.ts § logout`](../frontend/src/auth/authClient.test.ts)
   covers both branches).

### `/api/v1/auth/config` shape

The SPA's bootstrap probe now returns five fields the frontend uses:

```json
{
  "mode":          "real",
  "loginUrl":      "https://idp.example.com/protocol/openid-connect/auth",
  "issuer":        "https://idp.example.com",
  "clientId":      "atlas-spa",
  "audience":      "atlas-spa",
  "endSessionUrl": "https://idp.example.com/protocol/openid-connect/logout"
}
```

`audience` is informational (the gateway enforces it server-side, but
the SPA renders it on a config page when admins want to check what's
wired). `endSessionUrl` empty ⇒ no RP-initiated logout.

### Per-IdP setup

Walkthroughs for Keycloak, Okta, and Microsoft Entra ID are in
**[auth-production.md](auth-production.md)** — including audience
mapping, role assignment, redirect URI registration, sign-out URLs,
and a troubleshooting table.

## Phased roadmap

- **2D.1 (shipped)** — fake-IdP, gateway JWT validation, frontend login,
  DEMO banner, tests
- **2D.2 (shipped)** — gateway-level path → role authorization, role-aware
  UI affordances on every finalize button, `ForbiddenError` distinct
  from the 401 path, full coverage tests
- **2D.3 (shipped)** — gateway stamps signed identity headers on every
  authenticated request and strips client-supplied attempts; downstream
  services use them for `transitionedBy` / `decidedBy` / `triagedBy`
  audit fields; RestTemplate interceptor propagates the headers across
  the full call graph
- **2D.4 (shipped)** — audience validation, configurable roles-claim path
  for Keycloak/Okta/Entra, RP-initiated logout, per-IdP setup
  walkthroughs in [auth-production.md](auth-production.md)
