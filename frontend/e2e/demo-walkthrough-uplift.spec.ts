/**
 * Atlas Migrate · customer-facing demo walkthrough — UPLIFT track.
 *
 * Sibling spec to demo-walkthrough.spec.ts but for the Framework
 * Uplift flow (Spring 3.x/4.x → Spring Boot 3). Drives the SPA
 * end-to-end across the six UPLIFT stages:
 *
 *   A. Inventory       (Code heatmap, deprecated-API scan)
 *   B. Recipe Authoring (OpenRewrite + custom recipe library)
 *   C. Strangler Designer (extraction plan, façade ordering)
 *   D. Module Migration (apply recipes per module)
 *   E. Characterization (regression / characterization tests)
 *   F. Cutover & Decommission (production swap + decommission)
 *
 * Run it with the stack up:
 *
 *   $env:E2E_BASE_URL = "http://localhost:3000"
 *   npx playwright test demo-walkthrough-uplift.spec.ts --project=chromium
 *
 * The .webm lands under frontend/test-results/.
 */

import { test, expect, Page } from '@playwright/test';
import crypto from 'node:crypto';

const IDP = 'http://localhost:8093';
const CLIENT_ID = 'atlas-spa';
const REDIRECT_URI = 'http://localhost:3000/auth/callback';

// Spring PetClinic is the canonical "Spring framework sample everybody
// has seen" and reads on screen as a recognizable codebase. We point
// at main and let the uplift-service inventory scan run on the full
// source tree under src/main/java — ~50 .java files, small enough to
// keep the demo brisk.
const GITHUB_URL    = 'https://github.com/spring-projects/spring-petclinic';
const GITHUB_BRANCH = '';
const GITHUB_SUBPATH = 'src/main/java';

const DEMO_PERSONA = 'bob@envestnet.local';   // ENGINEER + TECH_LEAD

async function mintToken(): Promise<string> {
  const verifier = base64url(crypto.randomBytes(40));
  const challenge = base64url(crypto.createHash('sha256').update(verifier).digest());

  const loginResp = await fetch(`${IDP}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `personaId=${encodeURIComponent(DEMO_PERSONA)}`,
    redirect: 'manual'
  });
  const setCookie = loginResp.headers.get('set-cookie') ?? '';
  const sessionCookie = setCookie.split(';')[0];

  const authUrl = new URL(`${IDP}/authorize`);
  authUrl.searchParams.set('response_type', 'code');
  authUrl.searchParams.set('client_id', CLIENT_ID);
  authUrl.searchParams.set('redirect_uri', REDIRECT_URI);
  authUrl.searchParams.set('code_challenge', challenge);
  authUrl.searchParams.set('code_challenge_method', 'S256');
  authUrl.searchParams.set('scope', 'openid');
  authUrl.searchParams.set('state', 'demo');
  const authzResp = await fetch(authUrl.toString(), {
    headers: { cookie: sessionCookie },
    redirect: 'manual'
  });
  const location = authzResp.headers.get('location') ?? '';
  const code = new URL(location).searchParams.get('code');
  if (!code) throw new Error(`authorize did not return a code: ${location}`);

  const tokenBody = new URLSearchParams({
    grant_type:    'authorization_code',
    code,
    redirect_uri:  REDIRECT_URI,
    client_id:     CLIENT_ID,
    code_verifier: verifier
  });
  const tokenResp = await fetch(`${IDP}/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: tokenBody.toString()
  });
  const json = await tokenResp.json() as { access_token: string };
  return json.access_token;
}

function base64url(bytes: Buffer): string {
  return bytes.toString('base64')
              .replace(/=+$/, '')
              .replace(/\+/g, '-')
              .replace(/\//g, '_');
}

async function authenticate(page: Page) {
  const token = await mintToken();
  await page.addInitScript((t) => {
    try { window.localStorage.setItem('atlas_token', t); } catch {}
    const installStyle = () => {
      const style = document.createElement('style');
      style.textContent =
        '[role="status"][aria-label*="Demo identity"]{display:none !important;}';
      (document.head || document.documentElement).appendChild(style);
    };
    if (document.head) installStyle();
    else document.addEventListener('DOMContentLoaded', installStyle, { once: true });
  }, token);
}

test.use({
  video: { mode: 'on', size: { width: 1440, height: 900 } },
  viewport: { width: 1440, height: 900 },
  actionTimeout: 30_000,
  launchOptions: {
    args: ['--disable-web-security', '--disable-features=IsolateOrigins,site-per-process']
  }
});

// UPLIFT is faster end-to-end than SOAP because the heavy LLM stages
// (recon, diff-triage) don't exist here. Empirically ~5-6 min total.
test.setTimeout(600_000);

test('UPLIFT framework migration end-to-end — Spring PetClinic (A → F)', async ({ page }) => {
  page.on('console', msg => {
    if (msg.type() === 'error') console.log('[spa-console]', msg.text());
  });
  page.on('requestfailed', req => {
    console.log('[req-failed]', req.url(), req.failure()?.errorText);
  });
  page.on('request', req => {
    const url = req.url();
    if (url.includes('/api/v1/') && req.method() !== 'OPTIONS') {
      console.log('[req]', req.method(), url.replace(/^.+\/api\/v1\//, '/api/v1/'));
    }
  });

  // ---------- 1. Authenticate (off-camera) ----------
  await authenticate(page);
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'New project' })).toBeVisible();
  await page.waitForTimeout(1500);

  // ---------- 2. Open create-project modal ----------
  await page.getByRole('button', { name: 'New project' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('heading', { name: 'Create migration project' })).toBeVisible();
  await page.waitForTimeout(800);

  // ---------- 3. Fill the form for Spring PetClinic uplift ----------
  await typeSlowly(dialog.getByPlaceholder(/Order Management uplift/i),
                   'Spring PetClinic - Framework Uplift');
  await page.waitForTimeout(300);

  // Switch to the UPLIFT (Framework uplift) track. The track buttons
  // are role="radio" inside a role="radiogroup" labeled "Migration
  // track" — same selector idiom as the SOAP spec, different label.
  const trackGroup = dialog.getByRole('radiogroup', { name: 'Migration track' });
  await trackGroup.locator('[role="radio"]').filter({ hasText: 'Framework uplift' }).click();
  await page.waitForTimeout(400);

  // Source / target framework labels for an Uplift project. The form
  // placeholders are Axis-flavored, but the fields accept anything.
  await typeSlowly(dialog.getByPlaceholder(/Apache Axis/i), 'Spring Framework 4.3');
  await page.waitForTimeout(200);
  await typeSlowly(dialog.getByPlaceholder(/JAX-WS RI/i), 'Spring Boot 3.4');
  await page.waitForTimeout(200);

  const vendorField = dialog.locator('input.input').nth(3);
  await typeSlowly(vendorField, 'Pivotal / Spring Team');
  await page.waitForTimeout(400);

  // Point at the spring-petclinic repo with a subpath that pulls just
  // the Java source under src/main/java (skips resources, tests, and
  // the maven wrapper).
  await typeSlowly(dialog.getByLabel('Repository URL'), GITHUB_URL);
  await page.waitForTimeout(200);
  if (GITHUB_BRANCH) {
    await typeSlowly(dialog.getByLabel('Branch'), GITHUB_BRANCH);
    await page.waitForTimeout(200);
  }
  await typeSlowly(dialog.getByLabel('Subpath'), GITHUB_SUBPATH);
  await page.waitForTimeout(800);

  await dialog.getByRole('button', { name: /Create project/i }).click();
  await expect(dialog).not.toBeVisible({ timeout: 120_000 });
  await page.waitForTimeout(1500);

  // ---------- 4. Open the new project at Stage A (Inventory) ----------
  const projectCard = page.getByRole('link', {
    name: /Spring PetClinic - Framework Uplift/i
  });
  await expect(projectCard.first()).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(800);
  await projectCard.first().click();

  // The UPLIFT track Stage A is "Inventory" (not "Code Archaeology"),
  // so the chrome heading is different from the SOAP demo.
  await expect(page.getByRole('heading', { name: /Inventory/i })).toBeVisible();
  await page.waitForTimeout(1500);

  // ---------- 5. Migration Forecast (pre-Stage-A moat moment) ----------
  // The MigrationForecastCard renders above the Inventory empty state.
  // The Forecast service detects the UPLIFT mode and uses an
  // uplift-flavored prompt (javax→jakarta, Spring API removals, etc.).
  // Wait for the button to appear (it shows after the GET /forecast
  // 404 resolves) rather than doing a single-shot visibility check
  // that races against the query.
  const forecastBtn = page.getByRole('button', { name: /Estimate this migration/i });
  await expect(forecastBtn).toBeVisible({ timeout: 15_000 });
  await forecastBtn.click();
  await expect(page.getByText(/What could slow the team down/i)).toBeVisible({ timeout: 60_000 });
  await page.waitForTimeout(6000);

  // ---------- 6. Stage A: Begin inventory scan ----------
  const scanBtn = page.getByRole('button', { name: /Begin inventory scan/i });
  await expect(scanBtn).toBeEnabled();
  await scanBtn.click();
  // The uplift-service scan walks every .java file looking for known
  // anti-patterns (javax imports, removed Spring APIs, deprecated
  // annotations). On a 50-file petclinic this is ~5-10 s; we wait
  // up to 90s for safety. The UPLIFT chrome labels Stage A as
  // "Inventory & Heatmap", so the regex has to accommodate the
  // ampersand-separator before ", passed".
  await expect(page.getByRole('button', { name: /Stage A Inventory.*passed/i }))
        .toBeVisible({ timeout: 90_000 });
  await page.waitForTimeout(3000);

  // Click back to Stage A to dwell on the heatmap.
  await page.getByRole('button', { name: /Stage A Inventory.*passed/i }).click();
  await page.waitForTimeout(3500);

  // ---------- 7. Stage B: Recipe Authoring ----------
  await page.getByRole('button', { name: /Stage B Recipe/i }).click();
  await page.waitForTimeout(1500);

  const seedRecipesBtn = page.getByRole('button', { name: /^Seed from inventory$/i });
  await expect(seedRecipesBtn).toBeEnabled();
  await seedRecipesBtn.click();
  // The recipe library seeds within seconds; the list renders with
  // Accept/Reject controls per recipe.
  await expect(page.getByRole('button', { name: /^Accept$/i }).first())
        .toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(3000);

  // Accept the first three recipes. readyForGate flips to true after
  // the first acceptance, so finalize unlocks then.
  for (let i = 0; i < 3; i++) {
    const acceptBtn = page.getByRole('button', { name: /^Accept$/i }).first();
    if (await acceptBtn.isEnabled().catch(() => false)) {
      await acceptBtn.click();
      await page.waitForTimeout(1200);
    }
  }
  await page.waitForTimeout(1500);

  await page.getByRole('button', { name: /Finalize Stage B/i }).click();
  await expect(page.getByRole('button', { name: /Stage B Recipe.*passed/i }))
        .toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(2000);

  // ---------- 8. Stage C: Strangler Designer ----------
  await page.reload();
  await expect(page.getByRole('button', { name: /Stage C Strangler/i }))
        .toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: /Stage C Strangler/i }).click();
  await page.waitForTimeout(1500);

  const seedPlanBtn = page.getByRole('button', { name: /Seed plan from recipes/i });
  await expect(seedPlanBtn).toBeEnabled();
  await seedPlanBtn.click();
  // After seeding, the plan renders as an ordered list of strangler
  // steps. The Finalize button only enables once at least one step
  // is in 'ready' state. Atlas may or may not auto-mark the first
  // step as Ready depending on the seed-recipe count - so we walk
  // any enabled Ready buttons and click them, then wait for the
  // Finalize button to enable.
  await expect(page.getByRole('button', { name: /Finalize Stage C/i }))
        .toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(2500);

  // Click up to three enabled Ready buttons (more than enough to
  // flip readyForGate). The CSS filter excludes the already-active
  // Ready button (which is rendered disabled because step.status is
  // already 'ready').
  for (let i = 0; i < 3; i++) {
    const readyBtn = page.locator('button:not([disabled])').filter({ hasText: /^Ready$/ }).first();
    if (await readyBtn.isVisible().catch(() => false)) {
      await readyBtn.click();
      await page.waitForTimeout(1200);
    } else {
      break;
    }
  }
  // Now Finalize Stage C should be enabled. If not, the test will
  // surface a clean error pointing at this assertion.
  await expect(page.getByRole('button', { name: /Finalize Stage C/i }))
        .toBeEnabled({ timeout: 15_000 });
  await page.waitForTimeout(1500);

  await page.getByRole('button', { name: /Finalize Stage C/i }).click();
  await expect(page.getByRole('button', { name: /Stage C Strangler.*passed/i }))
        .toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(2000);

  // ---------- 9. Stage D: Module Migration ----------
  await page.reload();
  await expect(page.getByRole('button', { name: /Stage D Module Migration/i }))
        .toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: /Stage D Module Migration/i }).click();
  await page.waitForTimeout(1500);

  // Run the first migration step to demonstrate OpenRewrite applying
  // the accepted recipes to the module's source.
  const runMigrationBtn = page.getByRole('button', { name: /Run migration/i }).first();
  await expect(runMigrationBtn).toBeVisible({ timeout: 15_000 });
  await runMigrationBtn.click();
  // The migration run is fast (OpenRewrite on a small module is
  // sub-second). Wait for the "Changed files" list to populate so
  // we can dwell on at least one file's diff - that's the wow
  // moment for the UPLIFT track (real source code being rewritten).
  await page.waitForTimeout(6000);

  // Click into the first changed file so the right pane shows its
  // unified diff. The file list is a column inside the run-detail
  // pane; each entry is a button containing the file path.
  const fileRowSelector = page.locator('aside, div').filter({ hasText: /Changed files/i })
                              .locator('ul li button').first();
  if (await fileRowSelector.isVisible().catch(() => false)) {
    await fileRowSelector.click();
    // Hold for a beat so the customer can SEE the modified Java
    // code, line-by-line removals/additions highlighted.
    await page.waitForTimeout(7000);
  } else {
    // Fallback: just wait longer in case the file list locator
    // didn't match. Won't fail the test.
    await page.waitForTimeout(5000);
  }

  await page.getByRole('button', { name: /Finalize Stage D/i }).click();
  await expect(page.getByRole('button', { name: /Stage D Module Migration.*passed/i }))
        .toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(2000);

  // ---------- 10. Stage E: Characterization Validation ----------
  await page.reload();
  await expect(page.getByRole('button', { name: /Stage E Characterization/i }))
        .toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: /Stage E Characterization/i }).click();
  await page.waitForTimeout(1500);

  const charRunBtn = page.getByRole('button', { name: /Run characterization tests/i });
  await expect(charRunBtn).toBeEnabled();
  await charRunBtn.click();
  // Characterization synthesizes regression test results from the
  // module migrations. ~5-10s on a small module set.
  await page.waitForTimeout(8000);

  // Filter the case list to the regression bucket. The filter chip
  // reads e.g. "2 regression" or "4 regression"; use a loose regex
  // that tolerates either single- or double-digit counts.
  const regressionFilter = page.getByRole('button', { name: /\d+\s*regression/i }).first();
  if (await regressionFilter.isVisible().catch(() => false)) {
    await regressionFilter.click();
    await page.waitForTimeout(800);
  }

  // Triage every regression case: click the case in the left list,
  // then click "Accept divergence" in the detail pane. We walk by
  // case-button index in the left list — accepted cases keep their
  // button position (just gain an "accepted" badge), so the index
  // walk stays stable.
  //
  // Use getByRole for the Accept divergence button so the icon
  // prefix doesn't break a textContent regex.
  const caseBtns = page.locator('ul > li > button').filter({ has: page.locator('span.dot') });
  const caseCount = Math.min(await caseBtns.count(), 8);
  for (let i = 0; i < caseCount; i++) {
    await caseBtns.nth(i).click();
    await page.waitForTimeout(600);
    const accept = page.getByRole('button', { name: /Accept divergence/i }).first();
    if (await accept.isEnabled().catch(() => false)) {
      await accept.click();
      await page.waitForTimeout(800);
    }
  }
  await page.waitForTimeout(1500);

  await expect(page.getByRole('button', { name: /Finalize Stage E/i }))
        .toBeEnabled({ timeout: 15_000 });
  await page.getByRole('button', { name: /Finalize Stage E/i }).click();
  await expect(page.getByRole('button', { name: /Stage E Characterization.*passed/i }))
        .toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(2000);

  // ---------- 11. Stage F: Cutover & Decommission ----------
  await page.reload();
  // The chrome calls it "Stage F Cutover & Decommission" - the regex
  // tolerates the ampersand-separator the same way Stage A does.
  await expect(page.getByRole('button', { name: /Stage F Cutover/i }))
        .toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: /Stage F Cutover/i }).click();
  await page.waitForTimeout(1500);

  const seedCutoverBtn = page.getByRole('button', { name: /Seed cutover plan/i });
  await expect(seedCutoverBtn).toBeEnabled();
  await seedCutoverBtn.click();
  await page.waitForTimeout(6000);

  // The Stage F finalize closes Gate F. Even if readyForGate is false
  // (incomplete checklists), the click is recorded for the demo —
  // Atlas would surface an error toast which lands well as "Atlas
  // forces you to actually finish the cutover before signing off".
  const finalizeF = page.getByRole('button', { name: /Finalize Stage F/i });
  if (await finalizeF.isEnabled().catch(() => false)) {
    await finalizeF.click();
    await page.waitForTimeout(3000);
  }
  await page.waitForTimeout(5000);   // hold for closing voice-over

  // Done. Playwright auto-finalises the .webm in test-results/.
});

async function typeSlowly(locator: ReturnType<Page['getByPlaceholder']>, text: string) {
  await locator.click();
  await locator.pressSequentially(text, { delay: 35 });
}
