import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  decodeIdentity,
  getToken,
  setToken,
  clearToken,
  currentIdentity,
  currentRoles,
  hasRole,
  logout,
  _resetAuthConfigCache
} from './authClient';

const enc = (o: unknown) =>
  btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');

function makeJwt(payload: Record<string, unknown>) {
  return `${enc({ alg: 'RS256', typ: 'JWT' })}.${enc(payload)}.sig`;
}

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('decodeIdentity', () => {
  it('reads email/name/roles/demo/exp out of the JWT payload', () => {
    const token = makeJwt({
      sub: 'alice@envestnet.local',
      email: 'alice@envestnet.local',
      name: 'Alice',
      roles: ['ENGINEER', 'TECH_LEAD'],
      atlas_demo: true,
      exp: 9999999999
    });
    const id = decodeIdentity(token);
    expect(id).not.toBeNull();
    expect(id!.email).toBe('alice@envestnet.local');
    expect(id!.name).toBe('Alice');
    expect(id!.roles).toEqual(['ENGINEER', 'TECH_LEAD']);
    expect(id!.demo).toBe(true);
    expect(id!.exp).toBe(9999999999);
  });

  it('falls back to sub when email is missing', () => {
    const id = decodeIdentity(makeJwt({ sub: 'bob@envestnet.local', exp: 9999999999 }));
    expect(id!.email).toBe('bob@envestnet.local');
    expect(id!.name).toBe('bob@envestnet.local');
  });

  it('returns an empty roles array when the claim is absent', () => {
    const id = decodeIdentity(makeJwt({ sub: 'x', exp: 9999999999 }));
    expect(id!.roles).toEqual([]);
  });

  it('returns null when the JWT is malformed', () => {
    expect(decodeIdentity('not-a-jwt')).toBeNull();
    expect(decodeIdentity('a.b')).toBeNull();
    expect(decodeIdentity('a.invalid-base64!.c')).toBeNull();
  });

  it('treats atlas_demo as boolean-coerced (false when missing)', () => {
    const id = decodeIdentity(makeJwt({ sub: 'x', exp: 9999999999 }));
    expect(id!.demo).toBe(false);
  });
});

describe('token store', () => {
  it('roundtrips token through setToken/getToken', () => {
    const token = makeJwt({ sub: 'x', exp: Math.floor(Date.now() / 1000) + 3600 });
    setToken(token);
    expect(getToken()).toBe(token);
  });

  it('returns null after clearToken', () => {
    setToken(makeJwt({ sub: 'x', exp: Math.floor(Date.now() / 1000) + 3600 }));
    clearToken();
    expect(getToken()).toBeNull();
  });

  it('returns null when the stored token is expired', () => {
    const expired = makeJwt({ sub: 'x', exp: Math.floor(Date.now() / 1000) - 30 });
    setToken(expired);
    expect(getToken()).toBeNull();
  });

  it('returns null when the stored token cannot be decoded', () => {
    localStorage.setItem('atlas_token', 'garbage');
    expect(getToken()).toBeNull();
  });

  it('currentIdentity returns the decoded payload regardless of expiry', () => {
    // Even an expired token should yield a decodable identity (different
    // contract from getToken which gates on expiry).
    const expired = makeJwt({ sub: 'x', email: 'x@y', exp: Math.floor(Date.now() / 1000) - 30 });
    setToken(expired);
    const id = currentIdentity();
    expect(id).not.toBeNull();
    expect(id!.email).toBe('x@y');
  });

  it('currentIdentity returns null when no token is stored', () => {
    expect(currentIdentity()).toBeNull();
  });
});

describe('roles', () => {
  it('currentRoles returns the JWT roles claim', () => {
    setToken(makeJwt({
      sub: 'bob',
      roles: ['ENGINEER', 'TECH_LEAD'],
      exp: Math.floor(Date.now() / 1000) + 3600
    }));
    expect(currentRoles()).toEqual(['ENGINEER', 'TECH_LEAD']);
  });

  it('currentRoles returns an empty array when no token exists', () => {
    expect(currentRoles()).toEqual([]);
  });

  it('hasRole matches the JWT roles claim case-sensitively', () => {
    setToken(makeJwt({
      sub: 'bob',
      roles: ['ENGINEER', 'TECH_LEAD'],
      exp: Math.floor(Date.now() / 1000) + 3600
    }));
    expect(hasRole('ENGINEER')).toBe(true);
    expect(hasRole('TECH_LEAD')).toBe(true);
    expect(hasRole('ADMIN')).toBe(false);
    // Case-sensitive — important so demo "TECH_LEAD" doesn't match a
    // misconfigured customer claim like "tech_lead".
    expect(hasRole('tech_lead')).toBe(false);
  });

  it('hasRole returns false for any role when no token is stored', () => {
    expect(hasRole('ENGINEER')).toBe(false);
    expect(hasRole('TECH_LEAD')).toBe(false);
  });
});

describe('logout', () => {
  // jsdom's window.location.assign is unimplemented; stub it so we can
  // assert on the destination URL instead of seeing it throw.
  let assigned: string | null;
  let originalLocation: Location;

  beforeEach(() => {
    assigned = null;
    _resetAuthConfigCache();
    originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      writable: true,
      value: {
        ...originalLocation,
        origin: 'http://localhost:3000',
        assign: vi.fn((url: string) => { assigned = url; })
      } as unknown as Location
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { writable: true, value: originalLocation });
  });

  it('clears the local token and bounces to / when no end-session URL is configured', async () => {
    setToken(makeJwt({ sub: 'alice', exp: Math.floor(Date.now() / 1000) + 3600 }));
    globalThis.fetch = vi.fn(async () =>
      new Response(JSON.stringify({
        mode: 'sim', loginUrl: 'http://localhost:8093/authorize',
        issuer: 'http://localhost:8093', clientId: 'atlas-spa',
        endSessionUrl: ''
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    ) as unknown as typeof fetch;

    await logout();

    expect(localStorage.getItem('atlas_token')).toBeNull();
    expect(assigned).toBe('/');
  });

  it('redirects through the IdP end-session endpoint when configured', async () => {
    const token = makeJwt({ sub: 'alice', exp: Math.floor(Date.now() / 1000) + 3600 });
    setToken(token);
    globalThis.fetch = vi.fn(async () =>
      new Response(JSON.stringify({
        mode: 'real', loginUrl: 'https://idp.example.com/authorize',
        issuer: 'https://idp.example.com', clientId: 'atlas-spa',
        endSessionUrl: 'https://idp.example.com/logout'
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    ) as unknown as typeof fetch;

    await logout();

    expect(localStorage.getItem('atlas_token')).toBeNull();
    expect(assigned).toMatch(/^https:\/\/idp\.example\.com\/logout\?/);
    const params = new URLSearchParams(assigned!.split('?')[1]);
    expect(params.get('post_logout_redirect_uri')).toBe('http://localhost:3000/');
    expect(params.get('client_id')).toBe('atlas-spa');
    expect(params.get('id_token_hint')).toBe(token);
  });

  it('falls back to local-only logout when the auth-config fetch fails', async () => {
    setToken(makeJwt({ sub: 'alice', exp: Math.floor(Date.now() / 1000) + 3600 }));
    globalThis.fetch = vi.fn(async () => {
      throw new Error('config server down');
    }) as unknown as typeof fetch;

    await logout();

    expect(localStorage.getItem('atlas_token')).toBeNull();
    expect(assigned).toBe('/');
  });
});
