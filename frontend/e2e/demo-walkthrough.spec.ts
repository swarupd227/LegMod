/**
 * Atlas Migrate · customer-facing demo walkthrough
 *
 * Recording-only spec. Walks the SOAP migration happy path end-to-end
 * against the real docker-compose stack:
 *
 *   1. Sign in as the engineering persona (PKCE done programmatically
 *      so the recording starts on the workspace dashboard, not the
 *      developer-flavoured fake-idp /login page)
 *   2. Open the create-project modal, fill it with the Apache WS-I
 *      Supply Chain Management sample, submit
 *   3. Open the new project at Stage A · Code Archaeology
 *   4. Run the agent — wait for real operations + LLM-generated narratives
 *      to populate
 *   5. Drill into one operation, show the narrative
 *   6. Advance to Stage B · Runtime Capture, deploy a capture run, wait
 *      for synthesised envelopes to flow in
 *
 * Run it with the stack up:
 *
 *   $env:E2E_BASE_URL = "http://localhost:3000"
 *   npx playwright test demo-walkthrough.spec.ts --project=chromium --headed
 *
 * The .webm lands under frontend/test-results/.
 */

import { test, expect, Page } from '@playwright/test';
import crypto from 'node:crypto';

// fake-idp endpoints
const IDP = 'http://localhost:8093';
const CLIENT_ID = 'atlas-spa';
const REDIRECT_URI = 'http://localhost:3000/auth/callback';

// The GitHub repo we point at — Apache's own Axis 1.x samples. The
// sparse-checkout subpath narrows the clone to ~52 .java files across
// the WS-I Supply Chain Management reference implementation, which is
// enough to feed all stages without dragging in the full ~200 MB tree.
const GITHUB_URL    = 'https://github.com/apache/axis-axis1-java';
const GITHUB_BRANCH = '';   // default branch (master)
const GITHUB_SUBPATH = 'distribution/src/main/files/samples/ws-i/scm/source/java/implemented';

/**
 * Drive fake-idp's PKCE authorization-code flow end-to-end using only
 * HTTP calls (no UI). The persona controls who shows up as the
 * signed-in user in the recording.
 *
 * For the customer demo we use Bob (ENGINEER + TECH_LEAD). The
 * TECH_LEAD role is required to finalize gates (the SecurityConfig
 * gates {@code /stages/*\/finalize} on it), which matters because the
 * Stage B → Stage C transition runs through {@code capture/finalize}.
 * Using one persona keeps the narrative simple — no mid-recording
 * persona swap.
 */
const DEMO_PERSONA = 'bob@envestnet.local';

async function mintAliceToken(): Promise<string> {
  const verifier = base64url(crypto.randomBytes(40));
  const challenge = base64url(crypto.createHash('sha256').update(verifier).digest());

  // 1. POST /login as the configured persona — sets the session cookie.
  const loginResp = await fetch(`${IDP}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `personaId=${encodeURIComponent(DEMO_PERSONA)}`,
    redirect: 'manual'
  });
  const setCookie = loginResp.headers.get('set-cookie') ?? '';
  const sessionCookie = setCookie.split(';')[0];     // "fakeidp_session=…"

  // 2. GET /authorize → 302 with ?code=… in Location.
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

  // 3. POST /token — exchange the code for a real JWT.
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

/**
 * Install the JWT in localStorage BEFORE the SPA's first script runs.
 * The recording starts on the workspace dashboard — which is what a
 * customer-grade demo wants.
 *
 * `addInitScript` is the documented way to seed pre-mount state:
 * Playwright injects the script in every frame's first script slot, so
 * the SPA's AuthGate sees a valid token on initial render and skips
 * the persona picker entirely.
 */
async function authenticateAsAlice(page: Page) {
  const token = await mintAliceToken();
  // Two init-time tweaks injected before the SPA first renders:
  //   1) plant the token so AuthGate accepts the session
  //   2) hide the "Demo identity" pill — accurate (fake-idp) but
  //      unhelpful for a customer-facing recording. The role +
  //      aria-label make it a stable, future-proof selector. In
  //      auth-real deployments atlas_demo is never set, so the
  //      hide rule is a no-op.
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

// Slow human-like input + page-level slowdown make the recording
// watchable rather than instant flicker.
//
// The launch flags work around a gateway-side CORS gap: Spring Cloud
// Gateway's `globalcors` config covers proxied routes, but the
// locally-handled @RestController endpoints in the gateway JVM
// (/api/v1/me, /api/v1/auth/config) and /actuator/* don't emit the
// Access-Control-Allow-Origin header. From a real browser at
// http://localhost:3000 they preflight-fail. Disabling web security
// at the test browser level is a recording-only workaround;
// production fix is a CorsWebFilter bean in the gateway (tracked).
test.use({
  video: { mode: 'on', size: { width: 1440, height: 900 } },
  viewport: { width: 1440, height: 900 },
  actionTimeout: 30_000,
  launchOptions: {
    args: ['--disable-web-security', '--disable-features=IsolateOrigins,site-per-process']
  }
});

// Override the 60 s suite-default. Empirical end-to-end runtime on the
// WS-I Supply Chain sample is ~8-9 minutes: Stage A archaeology ~50 s,
// Stage C reconciliation ~30 s, Stage E differential replay against
// 250 envelopes ~3-4 min (LLM triage per amber+red), Stage F bundle
// build ~30-90 s including the LLM-generated closure document.
// 720 s leaves comfortable headroom on a warm stack.
test.setTimeout(720_000);

test('SOAP migration end-to-end — Apache WS-I Supply Chain sample (A → B)', async ({ page }) => {
  // ---------- diagnostics: print page URL + flag failed fetches ----------
  page.on('console', msg => {
    if (msg.type() === 'error') console.log('[spa-console]', msg.text());
  });
  page.on('requestfailed', req => {
    console.log('[req-failed]', req.url(), req.failure()?.errorText);
  });
  page.on('request', req => {
    const url = req.url();
    if (url.includes('/api/v1/') && req.method() !== 'OPTIONS') {
      const auth = req.headers()['authorization'];
      console.log('[req]', req.method(), url.replace(/^.+\/api\/v1\//, '/api/v1/'),
                  auth ? `auth=${auth.slice(0, 20)}...` : 'NO AUTH');
    }
  });

  // ---------- 1. Authenticate (off-camera) ----------
  // Token is injected via addInitScript so it's in localStorage
  // before the SPA's first render fires its initial queries.
  await authenticateAsAlice(page);
  await page.goto('/');
  // The dashboard renders project cards in a grid OR an empty state.
  // Either way the "New project" CTA is always present.
  await expect(page.getByRole('button', { name: 'New project' })).toBeVisible();
  await page.waitForTimeout(1500);  // beat for the recording

  // ---------- 3. Open create-project modal ----------
  await page.getByRole('button', { name: 'New project' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('heading', { name: 'Create migration project' })).toBeVisible();
  await page.waitForTimeout(800);

  // ---------- 4. Fill the form with the WS-I Supply Chain sample ----------
  // Type a realistic-sounding project name slowly so the recording
  // captures the engineer's intent.
  await typeSlowly(dialog.getByPlaceholder(/Order Management uplift/i),
                   'Apache WS-I Supply Chain - Modernization');
  await page.waitForTimeout(300);

  // SOAP migration is the default radio, but click it anyway so the
  // viewer sees the choice being made. The track buttons are
  // role="radio" inside a role="radiogroup".
  const trackGroup = dialog.getByRole('radiogroup', { name: 'Migration track' });
  await trackGroup.locator('[role="radio"]').filter({ hasText: 'SOAP migration' }).click();
  await page.waitForTimeout(300);

  await typeSlowly(dialog.getByPlaceholder('e.g. Apache Axis 1.3'), 'Apache Axis 1.4');
  await page.waitForTimeout(200);
  await typeSlowly(dialog.getByPlaceholder('e.g. JAX-WS RI 4.0'), 'JAX-WS RI 4.0');
  await page.waitForTimeout(200);

  const vendorField = dialog.locator('input.input').nth(3);  // "Vendor partner"
  await typeSlowly(vendorField, 'Apache Software Foundation');
  await page.waitForTimeout(400);

  // Source code — the "GitHub URL" tab is selected by default. Type
  // the repo URL + sparse-checkout subpath so we pull only the WS-I
  // SCM sample, not the whole Axis monorepo.
  await typeSlowly(dialog.getByLabel('Repository URL'), GITHUB_URL);
  await page.waitForTimeout(200);
  if (GITHUB_BRANCH) {
    await typeSlowly(dialog.getByLabel('Branch'), GITHUB_BRANCH);
    await page.waitForTimeout(200);
  }
  await typeSlowly(dialog.getByLabel('Subpath'), GITHUB_SUBPATH);
  await page.waitForTimeout(800);

  // Submit. The button label transitions Creating… → Cloning… → Done.
  // The dashboard refreshes once the clone returns, ~10–15 s for the
  // WS-I SCM subpath with depth=1 + sparse-checkout.
  await dialog.getByRole('button', { name: /Create project/i }).click();
  await expect(dialog).not.toBeVisible({ timeout: 120_000 });
  await page.waitForTimeout(1500);

  // ---------- 5. Click into the new project ----------
  const projectCard = page.getByRole('link', {
    name: /Apache WS-I Supply Chain/i
  });
  await expect(projectCard.first()).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(800);
  await projectCard.first().click();

  // ---------- 6. Stage A: Migration Forecast (pre-Stage-A) ----------
  // Stage A loads in its "Source attached, ready for archaeology"
  // empty state — but ABOVE that empty state we now render the
  // Migration Forecast card. It's the Nous moat surface: "Estimate
  // the migration before paying for Stage A." Click to generate the
  // forecast, hold a beat on the resulting tiles + risks + rationale.
  await expect(page.getByRole('heading', { name: /Code Archaeology/i })).toBeVisible();
  await page.waitForTimeout(1500);

  const forecastBtn = page.getByRole('button', { name: /Estimate this migration/i });
  if (await forecastBtn.isVisible().catch(() => false)) {
    await forecastBtn.click();
    // The Forecast service walks the source via arch-service /peek
    // (no LLM, sub-second), then makes one sonnet call (~10-15s).
    // The "What could slow the team down" header is the done-signal —
    // it only renders once the LLM result is parsed into top risks.
    await expect(page.getByText(/What could slow the team down/i))
          .toBeVisible({ timeout: 60_000 });
    await page.waitForTimeout(6000);
  }

  // Run the agent.
  const runButton = page.getByRole('button', { name: /Run agent/i });
  await expect(runButton).toBeEnabled();
  await runButton.click();

  // The synchronous archaeology run takes ~50 s end-to-end against live
  // Anthropic (7 narrations sequentially). On completion the controller
  // advances Stage A → Stage B and the SPA's StageChrome marks Stage A
  // as passed. The aria-label "Stage A Code Archaeology, passed" is the
  // semantic completion signal — strictly different from the always-
  // visible "Runtime Capture" label of Stage B in the chrome.
  await expect(page.getByRole('button', {
    name: /Stage A Code Archaeology, passed/i
  })).toBeVisible({ timeout: 240_000 });

  // ---------- 7. Back to Stage A to surface the operations + narrative ----------
  // The SPA auto-advanced the active stage to B; click the Stage A
  // pill in the chrome to revisit. The route stays at /projects/{id} —
  // stage selection is React state, not a URL segment.
  await page.getByRole('button', { name: /Stage A Code Archaeology/i }).click();
  await expect(page.getByText(/getCatalog|submitOrder|submitPO|logEvent/i).first())
        .toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(2000);

  // Drill into one operation. submitPO (Manufacturer service) makes a
  // nice demo because it's a write SOAP call with a complex type.
  const submitPO = page.getByText(/submitPO/i).first();
  await submitPO.scrollIntoViewIfNeeded();
  await submitPO.click();
  // Hold the shot — this is the wow moment (AI-generated narrative).
  await page.waitForTimeout(5000);

  // ---------- 8. Forward to Stage B · Runtime Capture ----------
  await page.getByRole('button', { name: /Stage B Runtime Capture/i }).click();
  // "Start runtime capture" is the panel's title text on the Stage B
  // empty-state — a stable, unique tell.
  await expect(page.getByText(/Start runtime capture/i)).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(1500);

  // ---------- 9. Begin a capture run ----------
  const beginCapture = page.getByRole('button', { name: /Begin capture/i });
  await expect(beginCapture).toBeEnabled();
  await beginCapture.click();

  // Wait for the synthesised envelopes to populate. The DemoIngester
  // produces ~50 envelopes across 4 SOAP operations in a couple
  // hundred ms; the dashboard polling picks them up within 5-10 s.
  await expect(page.getByText(/50|envelopes|submitAllocation|batchAllocate/i).first())
        .toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(3000);

  // ---------- 10. Finalize capture (sign-off as tech lead) ----------
  // The "Stop & finalize" button on Stage B hits /capture/finalize,
  // which the SecurityConfig restricts to TECH_LEAD. Passing Gate B
  // makes Stage C reachable in the chrome.
  await page.getByRole('button', { name: /Stop & finalize/i }).click();
  // Wait for the SPA to acknowledge the mutation (Finalized state).
  await expect(page.getByText(/finalized/i).first()).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(1500);
  // Force a fresh project fetch so the stage chrome sees the new
  // currentStage. Without this, React Query keeps the stale project
  // and Stage C remains rendered as "not yet started" even though the
  // server has already advanced.
  await page.reload();
  await expect(page.getByRole('button', { name: /Stage C Schema Reconciliation/i }))
        .toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(1500);

  // ---------- 11. Stage C - Schema Reconciliation ----------
  await page.getByRole('button', { name: /Stage C Schema Reconciliation/i }).click();
  await page.waitForTimeout(1500);

  // "Begin reconciliation" — the agent loads vendor WSDL + captured
  // envelopes + the code-derived schema, merges them, and produces
  // decisions for every divergence. The post-run pass in prj-service
  // ALSO auto-resolves high-confidence pattern matches: for the
  // Apache vendor family in the seeded library, that's accountId /
  // fundSymbol / tradeDate. So most decisions arrive already accepted
  // with an "Atlas decided" badge — AND the gate closes, advancing
  // the SPA to Stage D automatically.
  await page.getByRole('button', { name: /Begin reconciliation/i }).click();

  // Wait until the chrome shows Stage C as "passed" (auto-resolve
  // closed the queue) OR Stage D is reachable. Either signal means
  // the recon run + auto-resolve are done.
  await expect(page.getByRole('button', { name: /Stage C Schema Reconciliation, passed/i }).first())
        .toBeVisible({ timeout: 180_000 });

  // The SPA has already swapped the active pane to Stage D. Navigate
  // BACK to Stage C so the recording captures the auto-resolved
  // decision cards (with their "Atlas decided" badges) and the
  // pattern-library provenance.
  await page.getByRole('button', { name: /Stage C Schema Reconciliation/i }).click();
  await page.waitForTimeout(2000);

  // Switch to the "All" filter so the auto-resolved decisions show.
  // (Default filter is "Pending" — which is empty after auto-resolve.)
  const allFilter = page.getByRole('button', { name: /^All\b/i }).first();
  if (await allFilter.isVisible().catch(() => false)) {
    await allFilter.click();
    await page.waitForTimeout(1500);
  }

  await expect(page.getByText(/accountId|fundSymbol|tradeDate/i).first())
        .toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(3000);   // viewer reads the decision cards

  // Drill into the first decision so the right pane shows the agent's
  // rationale, the three-way comparison, AND the new "Atlas decided"
  // attribution — the moment the customer sees Atlas auto-resolve a
  // decision based on prior project history.
  await page.getByText(/accountId/i).first().click();
  await page.waitForTimeout(6000);

  // For any decisions that ARE still pending (vendor not in seed
  // library, or pattern library has occurrence_count < 3), accept
  // them manually. With the Apache seed all three typical decisions
  // auto-resolve, so this loop is usually a no-op.
  for (const field of ['accountId', 'fundSymbol', 'tradeDate']) {
    const card = page.getByText(new RegExp(`Request\\.${field}`, 'i')).first();
    if (await card.isVisible().catch(() => false)) {
      await card.click();
      await page.waitForTimeout(1200);
      const accept = page.getByRole('button', { name: /^Accept$/i });
      if (await accept.isEnabled().catch(() => false)) {
        await accept.click();
        await page.waitForTimeout(1500);
      }
    }
  }
  await page.waitForTimeout(2000);

  // ---------- 12. Stage D · Code Generation ----------
  // The decision-resolve mutations only invalidate the recon query
  // cache, not the project query — so the SPA's stage chrome still
  // shows Stage D as unreachable even though the server has advanced
  // currentStage. A reload refetches the project and unlocks the
  // Stage D button.
  await page.reload();
  await expect(page.getByRole('button', { name: /Stage D Code Generation/i }))
        .toBeVisible({ timeout: 15_000 });
  await page.waitForTimeout(1500);
  await page.getByRole('button', { name: /Stage D Code Generation/i }).click();
  await page.waitForTimeout(1500);

  // "Generate" — invokes wsimport against the synthesised authoritative
  // WSDL. The reconciliation step has already written that WSDL to
  // MinIO; this step turns it into JAX-WS Java source.
  await page.getByRole('button', { name: /^Generate$/i }).click();
  // wsimport on a small WSDL takes a few seconds; the controller
  // advances currentStage D → E on a clean run, which the chrome
  // surfaces as "Stage D Code Generation, passed". That's the
  // unambiguous done-signal and survives the auto-pane-advance.
  await expect(page.getByRole('button', { name: /Stage D Code Generation, passed/i }))
        .toBeVisible({ timeout: 90_000 });
  await page.waitForTimeout(2000);

  // The SPA has already swapped the active pane to Stage E now that
  // Gate D passed. Click back to Stage D to re-render the Code
  // Generation pane so the Download ZIP affordance + generated
  // source tree are visible for the recording.
  await page.getByRole('button', { name: /Stage D Code Generation, passed/i }).click();
  await expect(page.getByRole('link', { name: /Download ZIP/i }).or(
                page.getByRole('button', { name: /Download ZIP/i })))
        .toBeVisible({ timeout: 15_000 });
  await page.waitForTimeout(2500);

  // Expand the source tree so the generated Java files are visible.
  for (const seg of ['com', 'envestnet', 'broadridge']) {
    const node = page.locator('aside[aria-label="Generated output tree"]')
                    .getByText(seg, { exact: true }).first();
    if (await node.isVisible().catch(() => false)) {
      await node.click().catch(() => {});
      await page.waitForTimeout(400);
    }
  }
  await page.waitForTimeout(3500);

  // ---------- 13. Stage E · Differential Validation ----------
  // After generation the controller advances to Stage E. Reload to
  // pick up the fresh project state, then run the differential.
  await page.reload();
  await expect(page.getByRole('button', { name: /Stage E Differential/i }))
        .toBeVisible({ timeout: 15_000 });
  await page.waitForTimeout(1500);
  await page.getByRole('button', { name: /Stage E Differential/i }).click();
  await page.waitForTimeout(1500);

  // "Begin replay" — the diff service replays the captured envelopes
  // through the generated stubs and classifies each comparison as
  // pass / benign / amber / red. 250 envelopes against 7 operations
  // takes ~2 min end-to-end (LLM triage runs per amber+red finding).
  await page.getByRole('button', { name: /Begin replay/i }).click();
  // The controller advances currentStage E → F after the replay
  // completes (regardless of red count), so the chrome's Stage F
  // pill flips from "not yet started" to a reachable button. That
  // role transition is the strongest done-signal — it survives the
  // SPA auto-advancing the active pane to F.
  await expect(page.getByRole('button', { name: /Stage F Reports/i }))
        .toBeVisible({ timeout: 240_000 });
  await page.waitForTimeout(2000);

  // Click back to Stage E so the recording captures the divergence
  // summary tiles (pass/benign/amber/red) and the replay grid before
  // we move on to Stage F.
  await page.getByRole('button', { name: /Stage E Differential/i }).click();
  await page.waitForTimeout(4000);

  // ---------- 14. Stage F · Reports & Deliverables ----------
  await page.reload();
  await expect(page.getByRole('button', { name: /Stage F Reports/i }))
        .toBeVisible({ timeout: 15_000 });
  await page.waitForTimeout(1500);
  await page.getByRole('button', { name: /Stage F Reports/i }).click();
  await page.waitForTimeout(2000);

  // ---------- 14a. Stage F · Build & Test gate (enterprise) ----------
  // Before the bundle, Atlas compiles the wsimport-generated tree and
  // surfaces the pass/fail result. "Build & test now" kicks off
  // `mvn compile` in a sandboxed JVM; the panel shows tiles for
  // files compiled, compile errors, tests passed/failed.
  const buildBtn = page.getByRole('button', { name: /Build & test now|Build .{1,3} test now/i });
  if (await buildBtn.isVisible().catch(() => false)) {
    await buildBtn.click();
    // Wait for a result tile or the "Passed" / "failed" status to
    // appear (whichever comes first). Maven first run cold-fetches
    // dependencies so allow up to 4 minutes.
    await expect(page.getByText(/Passed|Compile failed|Tests failed|Build did not complete/i).first())
          .toBeVisible({ timeout: 240_000 });
    // Hold so the viewer reads the tiles + status banner.
    await page.waitForTimeout(6000);
  }

  // "Build migration package" — assembles every produced artefact
  // (authoritative WSDL, bindings.xjb, generated Java zip, decisions
  // JSON, differential JSON, closure markdown) into a single ZIP.
  await page.getByRole('button', { name: /Build migration package/i }).click();
  // The bundle build takes ~30 s including the LLM-generated closure
  // document. Wait for the "Download ZIP" affordance which is the
  // canonical "bundle is ready" signal.
  await expect(page.getByRole('link', { name: /Download ZIP/i }).or(
                page.getByRole('button', { name: /Download ZIP/i })))
        .toBeVisible({ timeout: 120_000 });
  await page.waitForTimeout(2500);

  // Scroll the closure markdown into view if rendered, so the final
  // shot captures real LLM-written closure prose.
  const closureHeader = page.getByText(/Closure document|Scope and target/i).first();
  if (await closureHeader.isVisible().catch(() => false)) {
    await closureHeader.scrollIntoViewIfNeeded();
    await page.waitForTimeout(5000);
  } else {
    await page.waitForTimeout(5000);
  }

  // Longer end-hold so the closing voice-over line ("Atlas Migrate. Built
  // by Nous. The platform where AI doesn't replace your engineers — it
  // gives them the empirical ground truth to migrate with confidence.")
  // can land before the video cuts. The original cut felt abrupt.
  await page.waitForTimeout(7000);

  // Done. Playwright auto-finalises the .webm in test-results/
  // demo-walkthrough-…/video.webm
});

/**
 * Press individual characters with a small delay between them. Reads as
 * more "human" than a single fill() — important for a recording that a
 * stakeholder might rewind.
 */
async function typeSlowly(locator: ReturnType<Page['getByPlaceholder']>, text: string) {
  await locator.click();
  await locator.pressSequentially(text, { delay: 35 });
}
