import { defineConfig, devices } from '@playwright/test';

/**
 * Atlas Migrate · Playwright config
 *
 * Default mode is **API-mocked** — Playwright boots the Vite dev server on
 * port 4173 (preview) so the page bundle is the production build, then each
 * spec wires `page.route('/api/v1/**', …)` to inject deterministic fixtures.
 * No backend services are needed.
 *
 * To run against the **real docker-compose stack** instead, bring `.\up.ps1`
 * up and override the base URL:
 *
 *   E2E_BASE_URL=http://localhost:3000 npm run e2e
 *
 * In that mode the route mocks become no-ops because the spec only mocks
 * paths it explicitly wants to override; everything else falls through to
 * the api-gateway on :8080.
 */
const PORT = Number(process.env.E2E_PORT ?? 4173);
const BASE_URL = process.env.E2E_BASE_URL ?? `http://127.0.0.1:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  // The webServer is a single vite-preview process; running every spec in
  // parallel hammers it. One worker per CPU keeps the suite fast without
  // overwhelming the preview.
  fullyParallel: true,
  workers: process.env.CI ? 2 : 4,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['html', { open: 'never' }], ['list']] : 'list',
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure'
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } }
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        // `vite preview` serves the built bundle — closer to production than
        // dev mode and eliminates HMR / source-map noise from CI logs. The
        // build is run as part of the command so a fresh checkout works
        // out of the box; subsequent runs reuse `dist/` if it exists.
        command: 'npm run build && npx vite preview --port 4173 --strictPort',
        port: PORT,
        reuseExistingServer: !process.env.CI,
        timeout: 240_000,
        stdout: 'pipe',
        stderr: 'pipe'
      }
});
