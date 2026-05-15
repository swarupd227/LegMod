# Atlas Migrate · production OIDC setup

This document walks through wiring Atlas Migrate's `auth-real` profile
against three of the most common enterprise IdPs:

- [Keycloak](#keycloak) — self-hosted, often used in regulated installs
- [Okta](#okta) — SaaS, common at mid-market customers
- [Microsoft Entra ID](#microsoft-entra-id-azure-ad) — common at enterprise customers

The architecture is identical across all three. Only the issuer URL,
the role-claim path, and the audience value differ. The tables in each
section give the per-IdP knobs; the env-var contract at the bottom is
universal.

For the demo / development flow against the bundled `fake-idp` service
see [auth.md](auth.md).

---

## What the gateway expects

Production builds run with `SPRING_PROFILES_ACTIVE=auth-real`. In that
profile the gateway:

1. **Validates the JWT signature** against the issuer's JWKS, fetched
   automatically from `<issuer>/.well-known/openid-configuration` (Spring
   Boot's `OAuth2ResourceServerProperties.Jwt.issuer-uri` discovery).
2. **Validates `iss`** matches the configured issuer URI.
3. **Validates `aud`** matches the configured audience (`OIDC_AUDIENCE`,
   default `atlas-spa`). Set this to whatever string your IdP includes
   in the `aud` array — see the per-IdP sections below.
4. **Validates `exp` / `nbf`** through Spring's default timestamp validator.
5. **Reads roles** from a configurable JSON-pointer-style path
   (`OIDC_ROLES_CLAIM`). Both arrays of strings and comma-separated
   strings are accepted.
6. **Re-stamps `X-Atlas-User-*` headers** on every authenticated
   forwarded request (see [auth.md § Identity propagation](auth.md)).

Roles must include at least `ENGINEER` for any user to do anything.
`TECH_LEAD` is required to finalize a stage. See
[auth.md § Role-based authorization](auth.md) for the full path-to-role
matrix.

---

## Universal env-var contract

| Variable                | Required? | What it does                                               |
|-------------------------|-----------|-------------------------------------------------------------|
| `SPRING_PROFILES_ACTIVE`| Required  | Set to `auth-real`.                                        |
| `OIDC_ISSUER_URI`       | Required  | The IdP's issuer URL. Atlas hits `<issuer>/.well-known/openid-configuration` for JWKS. |
| `OIDC_AUDIENCE`         | Optional  | Audience the JWT must carry. Defaults to `atlas-spa`. Override per-IdP convention. |
| `OIDC_ROLES_CLAIM`      | Optional  | Dot-notation path to the roles array. Defaults to `roles`. See per-IdP rows. |
| `OIDC_LOGIN_URL`        | Optional  | Browser-facing authorize endpoint. Defaults to `<issuer>/protocol/openid-connect/auth` (Keycloak shape) — override for Okta/Entra. |
| `OIDC_END_SESSION_URL`  | Optional  | OIDC end-session endpoint. When set, Sign-out performs RP-initiated logout. |

The defaults shipped in [`application.yml`](../services/api-gateway/src/main/resources/application.yml)
work out of the box for Keycloak; Okta and Entra need explicit
overrides.

---

## Keycloak

### 1. Realm + client setup

In your Keycloak admin console:

1. **Create or pick a realm.** The realm path becomes the issuer URL:
   `https://keycloak.example.com/realms/<realm>`.
2. **Create a client** named `atlas-spa`:
   - Client type: `OpenID Connect`
   - Client authentication: **OFF** (public client — PKCE is its
     defense)
   - Standard flow: **ON**
   - Valid redirect URIs: `https://atlas.example.com/auth/callback`
   - Valid post-logout redirect URIs: `https://atlas.example.com/`
   - Web origins: `https://atlas.example.com` (so the SPA's `POST /token`
     CORS request is allowed)
3. **Add an Audience mapper** under *Client scopes →
   atlas-spa-dedicated → Mappers → Add mapper → By configuration →
   Audience*:
   - Included Client Audience: `atlas-spa`
   - Add to access token: **ON**
   - Add to ID token: **ON**

### 2. Roles

Create realm-level roles (or client roles, see below):

- `ENGINEER`, `TECH_LEAD`, `ADMIN`

Assign them to users via *Users → user → Role mapping*. Keycloak
emits them in the JWT under `realm_access.roles`:

```json
{
  "realm_access": { "roles": ["ENGINEER", "TECH_LEAD"] }
}
```

### 3. Atlas env-vars

```bash
SPRING_PROFILES_ACTIVE=auth-real
OIDC_ISSUER_URI=https://keycloak.example.com/realms/atlas
OIDC_AUDIENCE=atlas-spa
OIDC_ROLES_CLAIM=realm_access.roles
OIDC_LOGIN_URL=https://keycloak.example.com/realms/atlas/protocol/openid-connect/auth
OIDC_END_SESSION_URL=https://keycloak.example.com/realms/atlas/protocol/openid-connect/logout
```

### Optional: client-scoped roles

If you'd rather use Keycloak's client-roles (scoped per `atlas-spa`
client), the JWT shape becomes:

```json
{
  "resource_access": {
    "atlas-spa": { "roles": ["ENGINEER"] }
  }
}
```

Set `OIDC_ROLES_CLAIM=resource_access.atlas-spa.roles`.

---

## Okta

### 1. Application + authorization server

In the Okta admin console:

1. **Create an OIDC Single-Page App**:
   - Application type: SPA
   - Sign-in redirect URI: `https://atlas.example.com/auth/callback`
   - Sign-out redirect URI: `https://atlas.example.com/`
   - Trusted Origins: `https://atlas.example.com` (CORS for the
     `POST /token` call)
2. **Use Okta's default authorization server** at
   `https://<your-tenant>.okta.com/oauth2/default`, OR create a custom
   authorization server with audience set to `atlas-spa`. Custom is
   strongly recommended for production — it gives you a stable
   audience independent of Okta's tenant URL.

### 2. Groups → roles

Okta typically uses groups for role mapping. Create groups
`Atlas-Engineer`, `Atlas-TechLead`, `Atlas-Admin` and assign users.

To strip the `Atlas-` prefix and emit clean role names, add a Group
Claim under *Authorization Servers → default → Claims → Add Claim*:

- Name: `roles`
- Include in token type: Access Token (Always)
- Value type: Groups
- Filter: `Starts with` → `Atlas-`
- Group filter pattern: drop the prefix in the OIDC token using a
  Custom Expression: `Groups.startsWith("OKTA","Atlas-",100).map(g, g.replace("Atlas-",""))`

The result lands in the JWT as:

```json
{
  "roles": ["ENGINEER", "TECH_LEAD"]
}
```

### 3. Atlas env-vars

```bash
SPRING_PROFILES_ACTIVE=auth-real
OIDC_ISSUER_URI=https://your-tenant.okta.com/oauth2/<authorization-server-id>
OIDC_AUDIENCE=atlas-spa
OIDC_ROLES_CLAIM=roles
OIDC_LOGIN_URL=https://your-tenant.okta.com/oauth2/<authorization-server-id>/v1/authorize
OIDC_END_SESSION_URL=https://your-tenant.okta.com/oauth2/<authorization-server-id>/v1/logout
```

### Optional: keep groups under `groups`

If you can't customize the claim, leave the default `groups` claim and
adjust the path:

```bash
OIDC_ROLES_CLAIM=groups
```

…then arrange the group names so the strings match `ENGINEER`,
`TECH_LEAD`, `ADMIN`.

---

## Microsoft Entra ID (Azure AD)

### 1. App registration + App Roles

In Microsoft Entra admin center:

1. **Register a new application**:
   - Supported account types: *Accounts in this organizational directory only*
   - Redirect URI: SPA → `https://atlas.example.com/auth/callback`
2. **Authentication blade** → ensure SPA platform is registered;
   confirm Implicit flow is **OFF** (PKCE only).
3. **Expose an API**:
   - Set the **Application ID URI** to `api://atlas-spa` (this becomes
     the audience in Atlas's JWTs).
   - Add a scope, e.g. `Atlas.Access`, set to *Admins and users*.
4. **App roles**:
   - Add app roles `ENGINEER`, `TECH_LEAD`, `ADMIN`. Allowed member
     types: *Users/Groups*.
   - Assign users/groups to roles under *Enterprise applications →
     atlas-spa → Users and groups*.
5. **Token configuration**: nothing to configure — Entra emits assigned
   App Roles in the JWT's top-level `roles` claim by default.

### 2. JWT shape

Entra's access token (with App Roles) carries:

```json
{
  "aud": "api://atlas-spa",
  "iss": "https://login.microsoftonline.com/<tenant-id>/v2.0",
  "roles": ["ENGINEER", "TECH_LEAD"]
}
```

### 3. Atlas env-vars

```bash
SPRING_PROFILES_ACTIVE=auth-real
OIDC_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
OIDC_AUDIENCE=api://atlas-spa
OIDC_ROLES_CLAIM=roles
OIDC_LOGIN_URL=https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/authorize
OIDC_END_SESSION_URL=https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/logout
```

### Note on multi-tenant

If you register a multi-tenant app, replace `<tenant-id>` with `common`
or `organizations`. The audience handling is unchanged — Atlas
validates against the literal `OIDC_AUDIENCE` value.

---

## Validation checklist

Before declaring the wiring done, confirm in this order:

1. `curl -s $OIDC_ISSUER_URI/.well-known/openid-configuration | jq .`
   from inside the gateway container — must return a discovery doc.
2. Sign in via Atlas — the SPA bounces to `OIDC_LOGIN_URL`; after
   authenticating, it should land on `/auth/callback?code=…` and then
   the workspace dashboard.
3. Open `/api/v1/me` (the SPA's chrome reads this on every page) — it
   should show the user's email and the roles array. If `roles` is
   empty, the IdP isn't emitting them — check the audience mapper /
   App Roles assignment.
4. Click any **Finalize Stage X** button as a non-`TECH_LEAD` user —
   the button should be disabled with a "requires the Tech Lead role"
   tooltip (the gateway also returns 403 if the SPA is bypassed).
5. Click **Sign out** — verify you're redirected to the IdP's
   end-session endpoint and back to `/` cleanly. If the IdP doesn't
   honour `post_logout_redirect_uri`, register the URL explicitly in
   the IdP application config.
6. Inspect any audit entry in the Audit trail screen — the `actor` field
   should match the email of the user who took the action, NOT
   `"system"`.

---

## Troubleshooting

| Symptom                                              | Likely cause                                                                                  | Fix                                                                                  |
|------------------------------------------------------|-----------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| Every request returns 401 immediately after login    | `aud` claim doesn't match `OIDC_AUDIENCE`                                                     | Add an audience mapper at the IdP, OR set `OIDC_AUDIENCE` to whatever the IdP emits  |
| `/api/v1/me` returns 200 but `roles` is empty        | `OIDC_ROLES_CLAIM` path doesn't resolve in the JWT                                            | Decode the JWT (jwt.io); set `OIDC_ROLES_CLAIM` to the path you actually see          |
| Sign-out redirects but the user is auto-signed-in again | RP-initiated logout isn't terminating the IdP session                                       | Check `OIDC_END_SESSION_URL` is set; verify the post-logout redirect URI is registered |
| 403 Forbidden on every request                       | User has none of `ENGINEER` / `TECH_LEAD` / `ADMIN`                                          | Provision the role at the IdP (Keycloak Realm Roles, Okta Group Claim, Entra App Role) |
| 401 with "JWT audience missing required value"       | Same as row 1, but the validator's message text differs                                       | (See row 1)                                                                          |
| 401 with "Issuer mismatch"                           | `OIDC_ISSUER_URI` differs from the JWT's `iss` claim                                          | Ensure the IdP's tenant/realm path matches the env-var exactly                        |
| CORS error on `POST <issuer>/token`                  | The IdP doesn't allow CORS from the SPA origin                                                | Register `https://atlas.example.com` as a Web Origin / Trusted Origin at the IdP      |
| JWKS fetch fails on gateway boot                     | Gateway can't reach the IdP from inside its container                                          | Verify network policy / proxy settings; the gateway needs HTTPS egress to the issuer  |

---

## Tested against

- Keycloak 23.0
- Okta (default authorization server, October 2025 features)
- Microsoft Entra ID (v2.0 endpoints, App Roles)

The gateway's resource-server validation only depends on standard OIDC
behaviour, so any compliant IdP should work — the per-IdP sections
above are starting points, not exhaustive.

---

## See also

- [auth.md](auth.md) — demo flow, role matrix, identity propagation, and
  shared architecture
- [`SecurityConfig.java`](../services/api-gateway/src/main/java/com/envestnet/atlas/gateway/SecurityConfig.java)
  — the gateway-side enforcement
- [`application.yml`](../services/api-gateway/src/main/resources/application.yml)
  — `auth-real` profile definitions
