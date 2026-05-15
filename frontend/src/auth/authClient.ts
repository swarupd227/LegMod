/**
 * Atlas frontend · auth client.
 *
 * Implements OAuth2 authorization-code flow with PKCE against the configured
 * issuer (see /api/v1/auth/config). Tokens land in localStorage; identity
 * is decoded from the JWT payload (no separate /userinfo round-trip — the
 * gateway exposes the same shape via /api/v1/me which uses the validated JWT).
 *
 * State machine:
 *   loadConfig()        — fetch /api/v1/auth/config (cached).
 *   login()             — PKCE init + redirect to authorize.
 *   handleCallback()    — exchange ?code=... for a token, store it.
 *   getToken()          — current token or null (also expiry-checked).
 *   logout()            — wipe token, redirect to login.
 *
 * The whole module is dependency-free except for fetch.
 */

const API_BASE =
  (import.meta.env.VITE_API_BASE as string | undefined) ||
  `${window.location.protocol}//${window.location.hostname}:8080`;

const TOKEN_KEY     = 'atlas_token';
const VERIFIER_KEY  = 'atlas_pkce_verifier';
const RETURN_KEY    = 'atlas_post_login_path';
const CLIENT_ID     = 'atlas-spa';
const CALLBACK_PATH = '/auth/callback';

/**
 * Generated from `openapi/gateway.json`. Re-exported as `AuthConfig`
 * so existing call-sites keep working. To regenerate:
 *   cd frontend && npm run codegen
 *
 * The schema's narrative descriptions (when `endSessionUrl` is empty,
 * what `traceUiUrl` drives, etc.) live in the JSON spec and are
 * preserved as JSDoc on the generated type.
 */
import type { components as GatewayComponents } from '../api/generated/gateway';
export type AuthConfig = GatewayComponents['schemas']['AuthConfig'];

let cachedConfig: AuthConfig | null = null;

/**
 * Test-only hook: drops the in-process AuthConfig cache so the next
 * loadConfig() call hits the network. Production code does not call
 * this — config is treated as immutable for the lifetime of a tab.
 */
export function _resetAuthConfigCache(): void { cachedConfig = null; }

/* ------------------------------------------------------------------ */
/* Identity decoded from the JWT payload                              */
/* ------------------------------------------------------------------ */

export interface Identity {
  email: string;
  name: string;
  roles: string[];
  demo: boolean;
  exp: number;       // seconds since epoch
}

export function decodeIdentity(token: string): Identity | null {
  try {
    const [, payload] = token.split('.');
    const json = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
    return {
      email: String(json.email ?? json.sub ?? ''),
      name:  String(json.name  ?? json.email ?? json.sub ?? ''),
      roles: Array.isArray(json.roles) ? json.roles.map(String) : [],
      demo:  Boolean(json.atlas_demo),
      exp:   Number(json.exp ?? 0)
    };
  } catch {
    return null;
  }
}

/* ------------------------------------------------------------------ */
/* Config                                                              */
/* ------------------------------------------------------------------ */

export async function loadConfig(): Promise<AuthConfig> {
  if (cachedConfig) return cachedConfig;
  const res = await fetch(`${API_BASE}/api/v1/auth/config`);
  if (!res.ok) throw new Error(`auth/config: ${res.status} ${res.statusText}`);
  cachedConfig = (await res.json()) as AuthConfig;
  return cachedConfig;
}

/* ------------------------------------------------------------------ */
/* Token store                                                         */
/* ------------------------------------------------------------------ */

export function getToken(): string | null {
  const t = localStorage.getItem(TOKEN_KEY);
  if (!t) return null;
  const id = decodeIdentity(t);
  if (!id || id.exp * 1000 <= Date.now()) {
    // Expired — surface it to the caller as "no token" so the next
    // request triggers a re-login.
    return null;
  }
  return t;
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

export function currentIdentity(): Identity | null {
  const t = localStorage.getItem(TOKEN_KEY);
  return t ? decodeIdentity(t) : null;
}

/** Roles claimed by the JWT — empty array if no token / no claim. */
export function currentRoles(): string[] {
  const id = currentIdentity();
  return id?.roles ?? [];
}

/**
 * True if the current user has the named role (matched case-sensitively
 * against the JWT's `roles` claim). For the demo, valid roles are
 * `ENGINEER`, `TECH_LEAD`, `ADMIN`. Returns false when not signed in.
 */
export function hasRole(role: string): boolean {
  return currentRoles().includes(role);
}

/* ------------------------------------------------------------------ */
/* PKCE login                                                          */
/* ------------------------------------------------------------------ */

function randomBase64Url(bytes: number): string {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  return base64UrlEncode(buf);
}

function base64UrlEncode(bytes: Uint8Array): string {
  let s = '';
  bytes.forEach(b => (s += String.fromCharCode(b)));
  return btoa(s).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

async function s256(input: string): Promise<string> {
  const data = new TextEncoder().encode(input);
  const buf = await crypto.subtle.digest('SHA-256', data);
  return base64UrlEncode(new Uint8Array(buf));
}

/**
 * Kick off the PKCE flow. Saves the verifier in sessionStorage and the
 * "where to land after login" in localStorage, then redirects to the
 * issuer's /authorize.
 */
export async function login(returnPath?: string): Promise<void> {
  const cfg = await loadConfig();
  const verifier = randomBase64Url(64);
  const challenge = await s256(verifier);

  sessionStorage.setItem(VERIFIER_KEY, verifier);
  if (returnPath) localStorage.setItem(RETURN_KEY, returnPath);
  else            localStorage.removeItem(RETURN_KEY);

  const redirectUri = `${window.location.origin}${CALLBACK_PATH}`;
  const params = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: 'openid profile email roles',
    code_challenge: challenge,
    code_challenge_method: 'S256',
    state: randomBase64Url(16)
  });
  window.location.assign(`${cfg.loginUrl}?${params.toString()}`);
}

/**
 * Called from the /auth/callback route. Reads `?code=…` from the URL,
 * exchanges it for a JWT at the issuer's token endpoint, stores the
 * token, and returns the path to navigate to next.
 */
export async function handleCallback(): Promise<string> {
  const cfg = await loadConfig();
  const params = new URLSearchParams(window.location.search);
  const code = params.get('code');
  if (!code) throw new Error('callback missing ?code parameter');
  const verifier = sessionStorage.getItem(VERIFIER_KEY);
  if (!verifier) throw new Error('PKCE verifier missing — start login again');

  const tokenUrl = `${cfg.issuer}/token`;
  const redirectUri = `${window.location.origin}${CALLBACK_PATH}`;

  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: redirectUri,
    client_id: CLIENT_ID,
    code_verifier: verifier
  });
  const res = await fetch(tokenUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString()
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`token exchange failed: ${res.status} ${text}`);
  }
  const json = (await res.json()) as { access_token: string };
  setToken(json.access_token);
  sessionStorage.removeItem(VERIFIER_KEY);

  const returnPath = localStorage.getItem(RETURN_KEY) ?? '/';
  localStorage.removeItem(RETURN_KEY);
  return returnPath;
}

export async function logout(): Promise<void> {
  // Capture the token BEFORE we clear it — OIDC end-session endpoints
  // accept it as id_token_hint so the IdP can identify the session
  // being terminated.
  const token = localStorage.getItem(TOKEN_KEY);

  // Pull config so we know whether to do RP-initiated logout. If the
  // user landed straight onto a page with a valid stored token, this
  // is the first time we've fetched it. Failures here fall through to
  // the local-only path — the user always at least gets logged out
  // locally.
  let cfg: AuthConfig | null = null;
  try { cfg = await loadConfig(); } catch { /* fall through */ }

  clearToken();

  // RP-initiated logout: when the gateway exposes an end-session URL
  // (typical for auth-real / customer OIDC), redirect through it so the
  // user is logged out at the IdP too. Without this, the SPA forgets
  // the token but the IdP session lingers — the next login round-trip
  // could silently re-authenticate.
  if (cfg?.endSessionUrl) {
    const params = new URLSearchParams({
      post_logout_redirect_uri: window.location.origin + '/',
      client_id: CLIENT_ID
    });
    if (token) params.set('id_token_hint', token);
    window.location.assign(`${cfg.endSessionUrl}?${params.toString()}`);
    return;
  }

  // Demo / no end-session endpoint: just bounce to / and let AuthGate
  // redirect to /login.
  window.location.assign('/');
}
