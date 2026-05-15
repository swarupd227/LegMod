/**
 * Deterministic fixtures used by the API-mocked Playwright specs. These
 * mirror the JSON shapes returned by the api-gateway (see
 * `services/prj-service/src/main/java/.../web/ProjectController.java`) so the
 * frontend renders the same way it does against the real stack.
 */

export const WORKSPACE_ID = '00000000-0000-0000-0000-000000000001';
export const PROJECT_ID_SOAP   = '11111111-1111-1111-1111-111111111111';
export const PROJECT_ID_UPLIFT = '22222222-2222-2222-2222-222222222222';

export const ME = {
  email: 'alice@envestnet.local',
  name:  'Alice',
  role:  'engineer',
  roles: ['ENGINEER'],
  // The fake-IdP issues `atlas_demo: true`; mirror it here so the
  // "DEMO IDENTITY" banner is visible end-to-end in test runs.
  demo:  true
};

export const WORKSPACES = [
  { id: WORKSPACE_ID, name: 'Envestnet', ownerEmail: 'dev@envestnet.local' }
];

export const HEALTH_UP = { status: 'UP' };

export const PROJECTS = [
  {
    id: PROJECT_ID_SOAP,
    name: 'BR Order Mgmt Migration',
    description: 'Apache Axis 1.4 → JAX-WS RI 4.0 for the Broadridge order endpoint.',
    mode: 'SOAP',
    sourceFramework: 'Apache Axis 1.4',
    targetFramework: 'JAX-WS RI 4.0',
    vendorPartner: 'Broadridge',
    owner: 'alice@envestnet.local',
    riskTier: 'MEDIUM',
    currentStage: 'B',
    sourcePath: '/legacy/broadridge',
    updatedAt: '2026-05-04T10:00:00Z'
  },
  {
    id: PROJECT_ID_UPLIFT,
    name: 'Tax Lot Aggregator uplift',
    description: 'Spring 4.3 → Spring Boot 3.3 with Jakarta migration.',
    mode: 'UPLIFT',
    sourceFramework: 'Spring 4.3',
    targetFramework: 'Spring Boot 3.3',
    vendorPartner: '—',
    owner: 'bob@envestnet.local',
    riskTier: 'HIGH',
    currentStage: 'A',
    sourcePath: '/legacy/tax-lot',
    updatedAt: '2026-05-05T14:00:00Z'
  }
];

export const PROJECT_DETAIL_SOAP = {
  ...PROJECTS[0],
  targetJavaVersion: '21',
  createdAt: '2026-05-01T00:00:00Z',
  gates: [
    { id: 'g1', projectId: PROJECT_ID_SOAP, label: 'A', state: 'passed',     transitionedAt: '2026-05-02T10:00:00Z', transitionedBy: 'system' },
    { id: 'g2', projectId: PROJECT_ID_SOAP, label: 'B', state: 'in_progress', transitionedAt: '2026-05-02T11:00:00Z', transitionedBy: 'system' },
    { id: 'g3', projectId: PROJECT_ID_SOAP, label: 'C', state: 'pending',     transitionedAt: null, transitionedBy: null },
    { id: 'g4', projectId: PROJECT_ID_SOAP, label: 'D', state: 'pending',     transitionedAt: null, transitionedBy: null },
    { id: 'g5', projectId: PROJECT_ID_SOAP, label: 'E', state: 'pending',     transitionedAt: null, transitionedBy: null },
    { id: 'g6', projectId: PROJECT_ID_SOAP, label: 'F', state: 'pending',     transitionedAt: null, transitionedBy: null }
  ]
};

export const CAPTURE_STATUS_EMPTY = {
  deployments: [],
  totalEnvelopes: 0,
  liveDeployments: 0,
  operationDistribution: [],
  recentEnvelopes: [],
  sanitizationRules: []
};

export const PROVENANCE_ENTRIES = [
  {
    id: 'p1',
    projectId: PROJECT_ID_SOAP,
    ts: '2026-05-04T09:30:00Z',
    actorKind: 'agent',
    actorId: 'archaeology-agent',
    action: 'archaeology_run',
    model: 'claude-opus-4-7',
    tokensIn: 1200, tokensOut: 800, costUsd: 0.012, latencyMs: 4200
  },
  {
    id: 'p2',
    projectId: PROJECT_ID_SOAP,
    ts: '2026-05-04T09:31:00Z',
    actorKind: 'human',
    actorId: 'alice@envestnet.local',
    action: 'archaeology_accepted',
    latencyMs: 0
  }
];

export const PROVENANCE_AGGREGATE = {
  total: 2,
  byActor:  [{ kind: 'agent', count: 1 }, { kind: 'human', count: 1 }],
  byAction: [{ action: 'archaeology_run', count: 1 }, { action: 'archaeology_accepted', count: 1 }],
  tokens:   { in: 1200, out: 800, cost: 0.012 }
};
