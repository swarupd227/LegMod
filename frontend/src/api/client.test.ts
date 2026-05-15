import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { api, ForbiddenError } from './client';

/**
 * The api client uses `fetch` against a base URL derived from
 * `import.meta.env.VITE_API_BASE` or `window.location` at module-eval time.
 * Tests stub `fetch` per-case and inspect URL, method, and body.
 */

type FetchInput = Parameters<typeof fetch>[0];
type FetchInit  = Parameters<typeof fetch>[1];

interface FetchCall { url: string; init: FetchInit | undefined; }

const calls: FetchCall[] = [];

function jsonResponse(payload: unknown, init: { status?: number; statusText?: string } = {}) {
  return new Response(JSON.stringify(payload), {
    status: init.status ?? 200,
    statusText: init.statusText ?? 'OK',
    headers: { 'Content-Type': 'application/json' }
  });
}

function textResponse(payload: string, init: { status?: number; statusText?: string } = {}) {
  return new Response(payload, {
    status: init.status ?? 200,
    statusText: init.statusText ?? 'OK',
    headers: { 'Content-Type': 'text/plain' }
  });
}

beforeEach(() => {
  calls.length = 0;
  globalThis.fetch = vi.fn(async (input: FetchInput, init?: FetchInit) => {
    const url = typeof input === 'string' ? input : (input as URL).toString();
    calls.push({ url, init });
    return jsonResponse({ ok: true });
  }) as unknown as typeof fetch;
});

afterEach(() => {
  vi.restoreAllMocks();
});

const PROJECT_ID = '11111111-2222-3333-4444-555555555555';

describe('api · path building', () => {
  it('me() hits /api/v1/me with no body', async () => {
    await api.me();
    expect(calls[0].url).toMatch(/\/api\/v1\/me$/);
    expect(calls[0].init?.method).toBeUndefined();
  });

  it('projects() defaults to the seeded workspace id', async () => {
    await api.projects();
    expect(calls[0].url).toMatch(/\/api\/v1\/workspaces\/00000000-0000-0000-0000-000000000001\/projects$/);
  });

  it('projects(wsId) honours the explicit workspace id', async () => {
    await api.projects('99999999-9999-9999-9999-999999999999');
    expect(calls[0].url).toMatch(/\/workspaces\/99999999-9999-9999-9999-999999999999\/projects$/);
  });

  it('createProject POSTs JSON with workspaceId injected', async () => {
    await api.createProject({ name: 'Demo', mode: 'SOAP' });
    const c = calls[0];
    expect(c.init?.method).toBe('POST');
    const body = JSON.parse(c.init!.body as string);
    expect(body.workspaceId).toBe('00000000-0000-0000-0000-000000000001');
    expect(body.name).toBe('Demo');
    expect(body.mode).toBe('SOAP');
  });

  it('attachSource POSTs the path payload', async () => {
    await api.attachSource(PROJECT_ID, '/legacy/src');
    expect(calls[0].url).toMatch(new RegExp(`/api/v1/projects/${PROJECT_ID}/sources$`));
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ path: '/legacy/src' });
  });

  it('runArchaeology POSTs to the correct endpoint', async () => {
    await api.runArchaeology(PROJECT_ID);
    expect(calls[0].url).toMatch(/\/stages\/archaeology\/run$/);
    expect(calls[0].init?.method).toBe('POST');
  });

  it('startCapture POSTs targetEnvelopes payload', async () => {
    await api.startCapture(PROJECT_ID, 500);
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ targetEnvelopes: 500 });
  });

  it('startCapture defaults to 250 envelopes', async () => {
    await api.startCapture(PROJECT_ID);
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ targetEnvelopes: 250 });
  });

  it('updateDeployment uses PUT', async () => {
    await api.updateDeployment(PROJECT_ID, 'dep-1', { sampleRate: 0.5 });
    expect(calls[0].init?.method).toBe('PUT');
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ sampleRate: 0.5 });
  });

  it('deleteRecipe uses DELETE', async () => {
    await api.deleteRecipe(PROJECT_ID, 'rec-1');
    expect(calls[0].init?.method).toBe('DELETE');
  });

  it('runGeneration sends basePackage', async () => {
    await api.runGeneration(PROJECT_ID, 'com.acme');
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ basePackage: 'com.acme' });
  });

  it('runGeneration defaults basePackage to com.envestnet.broadridge', async () => {
    await api.runGeneration(PROJECT_ID);
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ basePackage: 'com.envestnet.broadridge' });
  });

  it('runDiff sends sampleSize', async () => {
    await api.runDiff(PROJECT_ID, 1000);
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ sampleSize: 1000 });
  });

  it('migrationDiff URL-encodes the path query parameter', async () => {
    await api.migrationDiff(PROJECT_ID, 'run-1', 'src/main/java/com/acme/Foo Bar.java');
    expect(calls[0].url).toContain('runId=run-1');
    expect(calls[0].url).toContain('path=src%2Fmain%2Fjava%2Fcom%2Facme%2FFoo%20Bar.java');
  });
});

describe('api · provenance query string', () => {
  it('omits the query string when no filters are provided', async () => {
    await api.provenance(PROJECT_ID);
    expect(calls[0].url).not.toContain('?');
  });

  it('includes only filters that are non-empty', async () => {
    await api.provenance(PROJECT_ID, {
      action: 'recipe.decision',
      actorKind: '',
      q: 'alice',
      limit: 50
    });
    const url = calls[0].url;
    expect(url).toContain('action=recipe.decision');
    expect(url).toContain('q=alice');
    expect(url).toContain('limit=50');
    expect(url).not.toContain('actorKind');
  });

  it('URL-encodes filter values', async () => {
    await api.provenance(PROJECT_ID, { q: 'foo bar/baz' });
    expect(calls[0].url).toContain('q=foo+bar%2Fbaz');
  });
});

describe('api · headers and content type', () => {
  it('sets Content-Type: application/json on every JSON request', async () => {
    await api.me();
    const headers = calls[0].init?.headers as Record<string, string>;
    expect(headers['Content-Type']).toBe('application/json');
  });
});

describe('api · error handling', () => {
  it('throws "<status> <statusText>" when the response is not ok', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse({ error: 'boom' }, { status: 500, statusText: 'Internal Server Error' })
    ) as unknown as typeof fetch;

    await expect(api.me()).rejects.toThrow('500 Internal Server Error');
  });

  it('parses the JSON body when the response is ok', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse({ email: 'a@b.co', name: 'A', role: 'dev' })
    ) as unknown as typeof fetch;

    await expect(api.me()).resolves.toEqual({ email: 'a@b.co', name: 'A', role: 'dev' });
  });

  it('clears the stored token and throws on 401', async () => {
    localStorage.setItem('atlas_token', 'whatever');
    globalThis.fetch = vi.fn(async () =>
      jsonResponse({ error: 'expired' }, { status: 401, statusText: 'Unauthorized' })
    ) as unknown as typeof fetch;

    await expect(api.me()).rejects.toThrow('401 Unauthorized');
    expect(localStorage.getItem('atlas_token')).toBeNull();
  });

  it('throws ForbiddenError on 403 without clearing the token', async () => {
    const enc = (o: unknown) =>
      btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
    const payload = enc({ exp: Math.floor(Date.now() / 1000) + 3600 });
    const token = `${enc({ alg: 'none' })}.${payload}.sig`;
    localStorage.setItem('atlas_token', token);

    globalThis.fetch = vi.fn(async () =>
      jsonResponse({ error: 'no role' }, { status: 403, statusText: 'Forbidden' })
    ) as unknown as typeof fetch;

    await expect(api.finalizeRecipes('11111111-1111-1111-1111-111111111111'))
      .rejects.toBeInstanceOf(ForbiddenError);
    // Token survives — 403 is "you're authenticated but lacking a role",
    // distinct from 401 ("you're not authenticated").
    expect(localStorage.getItem('atlas_token')).toBe(token);
  });

  it('ForbiddenError carries the path it was thrown for', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse({}, { status: 403, statusText: 'Forbidden' })
    ) as unknown as typeof fetch;

    try {
      await api.finalizeStrangler('11111111-1111-1111-1111-111111111111');
      expect.unreachable();
    } catch (e) {
      expect(e).toBeInstanceOf(ForbiddenError);
      expect((e as ForbiddenError).path).toContain('/stages/strangler/finalize');
    }
  });
});

describe('api · auth header', () => {
  it('omits Authorization when no token is stored', async () => {
    localStorage.removeItem('atlas_token');
    await api.me();
    const headers = calls[0].init?.headers as Record<string, string>;
    expect(headers['Authorization']).toBeUndefined();
  });

  it('attaches Authorization: Bearer <token> when a non-expired token exists', async () => {
    // Mint a JWT with exp in the future so authClient.getToken() accepts it.
    const enc = (o: unknown) =>
      btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
    const payload = enc({ exp: Math.floor(Date.now() / 1000) + 3600 });
    const token = `${enc({ alg: 'none' })}.${payload}.sig`;
    localStorage.setItem('atlas_token', token);

    await api.me();
    const headers = calls[0].init?.headers as Record<string, string>;
    expect(headers['Authorization']).toBe(`Bearer ${token}`);
  });

  it('omits Authorization when the stored token is expired', async () => {
    const enc = (o: unknown) =>
      btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
    const payload = enc({ exp: Math.floor(Date.now() / 1000) - 60 }); // 1 min ago
    const token = `${enc({ alg: 'none' })}.${payload}.sig`;
    localStorage.setItem('atlas_token', token);

    await api.me();
    const headers = calls[0].init?.headers as Record<string, string>;
    expect(headers['Authorization']).toBeUndefined();
  });
});

describe('api · idempotency key', () => {
  // RFC 4122 v4 shape: 8-4-4-4-12 hex with `4` at the version slot and
  // 8/9/a/b at the variant slot.
  const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

  it('attaches Idempotency-Key on POST', async () => {
    await api.createProject({ name: 'New project', mode: 'SOAP' });
    const headers = calls[0].init?.headers as Record<string, string>;
    expect(headers['Idempotency-Key']).toMatch(UUID_V4);
  });

  it('attaches Idempotency-Key on PUT', async () => {
    await api.updateDeployment('pid', 'dep-1', { sampleRate: 0.5 });
    const headers = calls[0].init?.headers as Record<string, string>;
    expect(headers['Idempotency-Key']).toMatch(UUID_V4);
  });

  it('attaches Idempotency-Key on DELETE', async () => {
    await api.deleteRecipe('pid', 'rec-1');
    const headers = calls[0].init?.headers as Record<string, string>;
    expect(headers['Idempotency-Key']).toMatch(UUID_V4);
  });

  it('omits Idempotency-Key on GET', async () => {
    await api.me();
    const headers = calls[0].init?.headers as Record<string, string>;
    expect(headers['Idempotency-Key']).toBeUndefined();
  });

  it('generates a fresh key per call (no accidental aliasing)', async () => {
    await api.createProject({ name: 'P1', mode: 'SOAP' });
    await api.createProject({ name: 'P2', mode: 'SOAP' });
    const k1 = (calls[0].init?.headers as Record<string, string>)['Idempotency-Key'];
    const k2 = (calls[1].init?.headers as Record<string, string>)['Idempotency-Key'];
    expect(k1).not.toBe(k2);
    expect(k1).toMatch(UUID_V4);
    expect(k2).toMatch(UUID_V4);
  });
});

describe('api · text endpoints (closure / bindings)', () => {
  it('closureMarkdown returns the raw text body', async () => {
    globalThis.fetch = vi.fn(async () => textResponse('## Scope and target\n')) as unknown as typeof fetch;
    await expect(api.closureMarkdown(PROJECT_ID)).resolves.toBe('## Scope and target\n');
  });

  it('closureMarkdown propagates HTTP errors', async () => {
    globalThis.fetch = vi.fn(async () =>
      textResponse('not found', { status: 404, statusText: 'Not Found' })
    ) as unknown as typeof fetch;
    await expect(api.closureMarkdown(PROJECT_ID)).rejects.toThrow('404 Not Found');
  });

  it('generationBindings hits the bindings endpoint and returns text', async () => {
    globalThis.fetch = vi.fn(async () =>
      textResponse('<?xml version="1.0"?><jaxb:bindings/>')
    ) as unknown as typeof fetch;
    const xml = await api.generationBindings(PROJECT_ID);
    expect(xml).toContain('<jaxb:bindings/>');
  });
});

describe('api · download URL builders', () => {
  it('bundleDownloadUrl points to bundle.zip', () => {
    expect(api.bundleDownloadUrl(PROJECT_ID))
      .toMatch(new RegExp(`/api/v1/projects/${PROJECT_ID}/stages/reports/bundle\\.zip$`));
  });

  it('generationDownloadUrl points to output.zip', () => {
    expect(api.generationDownloadUrl(PROJECT_ID))
      .toMatch(new RegExp(`/api/v1/projects/${PROJECT_ID}/stages/generation/output\\.zip$`));
  });

  it('authoritativeWsdlUrl points to the wsdl endpoint', () => {
    expect(api.authoritativeWsdlUrl(PROJECT_ID))
      .toMatch(new RegExp(`/api/v1/projects/${PROJECT_ID}/wsdl/authoritative$`));
  });

  it('provenanceExportUrl points to export.csv', () => {
    expect(api.provenanceExportUrl(PROJECT_ID))
      .toMatch(new RegExp(`/api/v1/projects/${PROJECT_ID}/provenance/export\\.csv$`));
  });
});
