// Scenario: project detail page (Stage chrome + active stage).
//
// Models a user clicking into a project. The SPA's ProjectDetail
// issues:
//   GET /api/v1/me                                            (chrome)
//   GET /api/v1/workspaces                                    (chrome)
//   GET /api/v1/projects/<id>                                 (hero)
//   GET /api/v1/projects/<id>/operations OR
//       /api/v1/projects/<id>/stages/<stage>/status            (active-stage data)
//
// PROJECT_ID needs to be set by the caller (typically the seed run
// emits a project id we can reuse). Fall back to a known seed id.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { acquireToken } from '../helpers/auth.js';

const GATEWAY     = __ENV.GATEWAY_URL || 'http://localhost:8080';
const PROJECT_ID  = __ENV.PROJECT_ID  || '11111111-1111-1111-1111-111111111111';

const projectTrend  = new Trend('atlas_project_detail_ms',  true);
const opsTrend      = new Trend('atlas_project_ops_ms',     true);
const fivexx        = new Counter('atlas_project_5xx_total');

export const options = {
  scenarios: {
    detail: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '20s', target: 8 },
        { duration: '60s', target: 8 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    'atlas_project_detail_ms': ['p(95) < 300'],
    'atlas_project_ops_ms':    ['p(95) < 600'],
    'atlas_project_5xx_total': ['count < 5'],
  },
};

export function setup() {
  return { token: acquireToken('alice@envestnet.local') };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };

  const project = http.get(`${GATEWAY}/api/v1/projects/${PROJECT_ID}`, { headers });
  projectTrend.add(project.timings.duration);
  if (!check(project, { 'project 200': r => r.status === 200 })) {
    if (project.status >= 500) fivexx.add(1);
  }

  // Operations endpoint — the heaviest read on a fresh project.
  const ops = http.get(`${GATEWAY}/api/v1/projects/${PROJECT_ID}/operations`,
                       { headers });
  opsTrend.add(ops.timings.duration);
  if (!check(ops, { 'ops 2xx-or-404': r => r.status === 200 || r.status === 404 })) {
    if (ops.status >= 500) fivexx.add(1);
  }

  sleep(2);
}
