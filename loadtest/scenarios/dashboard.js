// Scenario: workspace dashboard.
//
// Models a user landing on /. The SPA's WorkspaceDashboard issues:
//   GET /api/v1/me                              (Layout chrome)
//   GET /api/v1/workspaces                      (left rail)
//   GET /api/v1/workspaces/<wsId>/projects      (project grid)
//
// We replay that triplet repeatedly. Each iteration is one
// "page load."

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { acquireToken } from '../helpers/auth.js';

const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8080';
const WORKSPACE = __ENV.WORKSPACE_ID || '00000000-0000-0000-0000-000000000001';

// Per-endpoint timing — Trend gives us p95/p99 in the summary.
const meTrend         = new Trend('atlas_dashboard_me_ms',         true);
const workspacesTrend = new Trend('atlas_dashboard_workspaces_ms', true);
const projectsTrend   = new Trend('atlas_dashboard_projects_ms',   true);
const fivexx          = new Counter('atlas_dashboard_5xx_total');

export const options = {
  scenarios: {
    dashboard: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 10 },   // ramp
        { duration: '60s', target: 10 },   // soak
        { duration: '15s', target: 0 },    // ramp down
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    // SLOs we'd defend in production. Tweak after measuring baseline.
    'atlas_dashboard_me_ms':         ['p(95) < 200'],
    'atlas_dashboard_workspaces_ms': ['p(95) < 250'],
    'atlas_dashboard_projects_ms':   ['p(95) < 400'],
    'atlas_dashboard_5xx_total':     ['count < 5'],
    'http_req_failed':               ['rate < 0.01'],
  },
};

export function setup() {
  // Acquire one token per VU pool. Setup runs once; the token is shared
  // across iterations. Fresh tokens per VU would distort numbers because
  // the JWKS fetch + cookie dance dominates a single iteration.
  return { token: acquireToken('alice@envestnet.local') };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };

  const me = http.get(`${GATEWAY}/api/v1/me`, { headers });
  meTrend.add(me.timings.duration);
  if (!check(me, { 'me 200': r => r.status === 200 })) {
    if (me.status >= 500) fivexx.add(1);
  }

  const ws = http.get(`${GATEWAY}/api/v1/workspaces`, { headers });
  workspacesTrend.add(ws.timings.duration);
  if (!check(ws, { 'workspaces 200': r => r.status === 200 })) {
    if (ws.status >= 500) fivexx.add(1);
  }

  const projects = http.get(`${GATEWAY}/api/v1/workspaces/${WORKSPACE}/projects`,
                            { headers });
  projectsTrend.add(projects.timings.duration);
  if (!check(projects, { 'projects 200': r => r.status === 200 })) {
    if (projects.status >= 500) fivexx.add(1);
  }

  // Realistic: human dwells on the page after it loads.
  sleep(1);
}
