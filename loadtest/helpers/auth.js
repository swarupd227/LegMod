// Auth helper for k6 scenarios.
//
// Walks the fake-IdP's authorization-code + PKCE flow once per VU and
// caches the resulting JWT for the rest of the test. Hand-rolled
// rather than using k6's OAuth2 helpers because:
//   - the flow has cookie state across /authorize → /login → /authorize
//   - we want explicit control of which persona we sign in as
//
// Usage in scenarios:
//   import { acquireToken } from '../helpers/auth.js';
//   const token = acquireToken('alice@envestnet.local');
//   http.get(`${BASE}/api/v1/workspaces`, { headers: { Authorization: `Bearer ${token}` } });

import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const FAKE_IDP = __ENV.FAKE_IDP_URL  || 'http://localhost:28093';
const SPA_HOST = __ENV.SPA_ORIGIN    || 'http://localhost:3000';
const REDIRECT = `${SPA_HOST}/auth/callback`;

function base64UrlEncode(bytes) {
  return encoding.b64encode(bytes, 'rawstd')
                 .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function pkce() {
  // 64-byte verifier + S256 challenge.
  const verifierBytes = crypto.randomBytes(64);
  const verifier = base64UrlEncode(verifierBytes);
  const challengeBytes = crypto.hmac('sha256', verifier, '', 'binary');
  // SHA-256 directly:
  const challenge = base64UrlEncode(crypto.sha256(verifier, 'binary'));
  return { verifier, challenge };
}

/**
 * Run one full PKCE flow against fake-IdP. Returns the access_token.
 * Persona id maps to the personas configured in fake-IdP's
 * application.yml: alice@envestnet.local, bob@envestnet.local,
 * carol@envestnet.local.
 */
export function acquireToken(personaId) {
  personaId = personaId || 'alice@envestnet.local';
  const { verifier, challenge } = pkce();

  const authorizeUrl = `${FAKE_IDP}/authorize`
      + `?client_id=atlas-spa`
      + `&redirect_uri=${encodeURIComponent(REDIRECT)}`
      + `&response_type=code`
      + `&scope=openid+profile+email+roles`
      + `&code_challenge=${challenge}`
      + `&code_challenge_method=S256`
      + `&state=k6`;

  // 1. /authorize without session → 302 to /login.
  const r1 = http.get(authorizeUrl, { redirects: 0 });
  if (r1.status !== 302) throw new Error(`/authorize step 1 expected 302, got ${r1.status}`);

  // 2. POST /login with persona; receives session cookie.
  const r2 = http.post(`${FAKE_IDP}/login`, {
    personaId: personaId,
    resume: authorizeUrl,
  }, {
    redirects: 0,
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  });
  if (r2.status !== 302) throw new Error(`/login expected 302, got ${r2.status}`);
  const setCookie = r2.headers['Set-Cookie'] || r2.headers['set-cookie'] || '';
  if (!setCookie.startsWith('FAKEIDP_SESSION=')) {
    throw new Error(`expected session cookie, got: ${setCookie}`);
  }
  const cookie = setCookie.split(';')[0];

  // 3. /authorize again with cookie → 302 with `?code=…` to redirect_uri.
  const r3 = http.get(authorizeUrl, {
    redirects: 0,
    headers: { Cookie: cookie },
  });
  if (r3.status !== 302) throw new Error(`/authorize step 3 expected 302, got ${r3.status}`);
  const back = r3.headers['Location'] || r3.headers['location'] || '';
  const codeMatch = back.match(/[?&]code=([^&]+)/);
  if (!codeMatch) throw new Error(`expected ?code= in callback URL, got: ${back}`);
  const code = decodeURIComponent(codeMatch[1]);

  // 4. POST /token with PKCE verifier; returns the JWT.
  const r4 = http.post(`${FAKE_IDP}/token`, {
    grant_type: 'authorization_code',
    code,
    redirect_uri: REDIRECT,
    client_id: 'atlas-spa',
    code_verifier: verifier,
  }, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  });
  if (r4.status !== 200) throw new Error(`/token expected 200, got ${r4.status}: ${r4.body}`);
  const json = JSON.parse(r4.body);
  if (!json.access_token) throw new Error(`/token response missing access_token`);
  return json.access_token;
}
