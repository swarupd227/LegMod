import { test as base, expect, Page, Route } from '@playwright/test';
import {
  ME, WORKSPACES, PROJECTS, HEALTH_UP,
  PROJECT_DETAIL_SOAP,
  CAPTURE_STATUS_EMPTY,
  PROVENANCE_ENTRIES, PROVENANCE_AGGREGATE,
  PROJECT_ID_SOAP
} from './sample-data';

/**
 * Manufactures an unsigned JWT whose payload matches the shape the
 * frontend's authClient.decodeIdentity() expects. The signature is fake
 * — fine because in API-mocked mode the gateway is never contacted; the
 * token's only purpose is to satisfy AuthGate's "has a non-expired
 * token" check before the page mounts.
 */
function fakeJwt(claims: Record<string, unknown> = {}): string {
  const enc = (obj: unknown) =>
    Buffer.from(JSON.stringify(obj))
          .toString('base64')
          .replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  const header = enc({ alg: 'RS256', typ: 'JWT' });
  const payload = enc({
    sub: 'alice@envestnet.local',
    email: 'alice@envestnet.local',
    name: 'Alice',
    roles: ['ENGINEER'],
    atlas_demo: true,
    iss: 'http://localhost:8093',
    aud: 'atlas-spa',
    exp: Math.floor(Date.now() / 1000) + 3600,
    iat: Math.floor(Date.now() / 1000),
    ...claims
  });
  return `${header}.${payload}.e2e-fake-signature`;
}

type Json = Record<string, unknown> | unknown[];

function json(route: Route, body: Json, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body)
  });
}

/**
 * Default route table. Specs override individual paths via `page.route`
 * before the test navigates; later registrations win in Playwright, so this
 * is a safe baseline. The match patterns use `/api/v1/**` glob — that
 * covers both the relative URL the SPA emits and the absolute URL the
 * fetch layer constructs (`http://localhost:8080/api/v1/...`).
 */
async function installBaseRoutes(page: Page) {
  // Inject a token into localStorage BEFORE the SPA mounts, so AuthGate
  // sees a logged-in user and doesn't try to redirect to fake-idp.
  await page.addInitScript((token: string) => {
    try { window.localStorage.setItem('atlas_token', token); } catch {}
  }, fakeJwt());

  // Health probe (footer status pill polls every 30 s).
  await page.route('**/actuator/health', r => json(r, HEALTH_UP));

  // Auth config — the SPA only calls this during a login redirect (which
  // E2E specs skip), but mocking it keeps any defensive lookup working.
  await page.route('**/api/v1/auth/config', r => json(r, {
    mode: 'sim',
    loginUrl: 'http://localhost:8093/authorize',
    issuer:   'http://localhost:8093',
    clientId: 'atlas-spa'
  }));

  await page.route('**/api/v1/me',         r => json(r, ME));
  await page.route('**/api/v1/workspaces', r => json(r, WORKSPACES));

  await page.route('**/api/v1/workspaces/*/projects', r => json(r, PROJECTS));

  // Project detail fixture; specs that need a different shape override.
  await page.route(`**/api/v1/projects/${PROJECT_ID_SOAP}`, r => {
    if (r.request().method() === 'GET') return json(r, PROJECT_DETAIL_SOAP);
    return r.continue();
  });

  await page.route(`**/api/v1/projects/${PROJECT_ID_SOAP}/gates`,
    r => json(r, PROJECT_DETAIL_SOAP.gates));

  // Stage status endpoints — empty defaults so the page renders without
  // the spec needing to enumerate every one.
  await page.route(`**/api/v1/projects/${PROJECT_ID_SOAP}/stages/capture/status`,
    r => json(r, CAPTURE_STATUS_EMPTY));
  await page.route(`**/api/v1/projects/${PROJECT_ID_SOAP}/operations`, r => json(r, []));
  await page.route(`**/api/v1/projects/${PROJECT_ID_SOAP}/adapters`,   r => json(r, []));
  await page.route(`**/api/v1/projects/${PROJECT_ID_SOAP}/stages/reconciliation/status`,
    r => json(r, { decisions: [], elements: [], counts: { total: 0, pending: 0, resolved: 0 } }));
  await page.route(`**/api/v1/projects/${PROJECT_ID_SOAP}/stages/generation/status`,
    r => json(r, { run: null, files: [] }));
  await page.route(`**/api/v1/projects/${PROJECT_ID_SOAP}/stages/diff/status`,
    r => json(r, {
      run: null, byOperation: [],
      buckets: { benign: 0, amber: 0, red: 0 }, divergences: []
    }));
  await page.route(`**/api/v1/projects/${PROJECT_ID_SOAP}/stages/reports/status`,
    r => json(r, { bundle: null }));

  // Provenance.
  await page.route(`**/api/v1/projects/${PROJECT_ID_SOAP}/provenance**`, r => {
    if (r.request().url().includes('/aggregate'))
      return json(r, PROVENANCE_AGGREGATE);
    return json(r, PROVENANCE_ENTRIES);
  });
}

type Fixtures = {
  /** Helper exposed to specs to add additional route mocks before navigation. */
  mockApi: { json: (path: string | RegExp, body: Json, status?: number) => Promise<void> };
};

export const test = base.extend<Fixtures>({
  page: async ({ page }, use) => {
    await installBaseRoutes(page);
    await use(page);
  },
  mockApi: async ({ page }, use) => {
    await use({
      json: async (path, body, status = 200) => {
        await page.route(path as any, r => json(r, body as Json, status));
      }
    });
  }
});

export { expect };
