// Scenario: audit browser.
//
// Models a user opening /audit. The SPA hits:
//   GET /api/v1/me
//   GET /api/v1/workspaces
//   GET /api/v1/workspaces/<wsId>/projects   (to populate the picker)
//   GET /api/v1/projects/<id>/provenance?limit=500
//   GET /api/v1/projects/<id>/provenance/aggregate
//
// The provenance query is the heaviest endpoint in the system on
// projects with significant audit history; this scenario is the one
// to watch when looking for indexing / pagination gaps.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { acquireToken } from '../helpers/auth.js';

const GATEWAY    = __ENV.GATEWAY_URL || 'http://localhost:8080';
const PROJECT_ID = __ENV.PROJECT_ID  || '11111111-1111-1111-1111-111111111111';
const PROV_LIMIT = __ENV.PROV_LIMIT  || '500';

const provQueryTrend  = new Trend('atlas_audit_provenance_ms',  true);
const provAggTrend    = new Trend('atlas_audit_aggregate_ms',   true);
const fivexx          = new Counter('atlas_audit_5xx_total');

export const options = {
  scenarios: {
    audit: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '15s', target: 5 },
        { duration: '45s', target: 5 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    // The 500-row provenance list is the hot spot. p95 < 800ms is a
    // soft target; we'll measure baseline and adjust.
    'atlas_audit_provenance_ms': ['p(95) < 800'],
    'atlas_audit_aggregate_ms':  ['p(95) < 400'],
    'atlas_audit_5xx_total':     ['count < 5'],
  },
};

export function setup() {
  return { token: acquireToken('alice@envestnet.local') };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };

  const list = http.get(
      `${GATEWAY}/api/v1/projects/${PROJECT_ID}/provenance?limit=${PROV_LIMIT}`,
      { headers });
  provQueryTrend.add(list.timings.duration);
  if (!check(list, { 'prov 2xx': r => r.status === 200 })) {
    if (list.status >= 500) fivexx.add(1);
  }

  const agg = http.get(`${GATEWAY}/api/v1/projects/${PROJECT_ID}/provenance/aggregate`,
                       { headers });
  provAggTrend.add(agg.timings.duration);
  if (!check(agg, { 'agg 2xx': r => r.status === 200 })) {
    if (agg.status >= 500) fivexx.add(1);
  }

  sleep(3);
}
