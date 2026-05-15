import { useEffect, useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Play, Square, Activity, Hash, Shield, ArrowDownUp,
  RefreshCw, ChevronLeft, Server, Sliders, ChevronRight
} from 'lucide-react';
import { api, CaptureStatus, CaptureEnvelope, CaptureDeployment,
         SanitizationRuleStat, Project } from '../api/client';
import { hasRole } from '../auth/authClient';
import {
  ThreePane, EmptyState, LoadingState, ErrorState,
  IconButton, useStageChrome, useAnnouncer
} from '../components/ui';

export default function RuntimeCapture({
  projectId, project
}: { projectId: string; project: Project }) {
  const qc = useQueryClient();
  const { setStatus, setActions } = useStageChrome();
  const { announce } = useAnnouncer();
  const [selectedDeploymentId, setSelectedDeploymentId] = useState<string | null>(null);

  const { data: status, isLoading, error, refetch } = useQuery({
    queryKey: ['capture', projectId],
    queryFn: () => api.captureStatus(projectId),
    refetchInterval: 5000
  });

  // Need gate state to know if Stage B is already finalized.
  const projectQ = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.project(projectId)
  });
  const gateBPassed = (projectQ.data as any)?.gates?.find((g: any) => g.label === 'B')?.state === 'passed';

  const startM = useMutation({
    mutationFn: () => api.startCapture(projectId, 250),
    onSuccess: () => {
      announce('Capture started');
      qc.invalidateQueries({ queryKey: ['capture', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    }
  });

  const finalizeM = useMutation({
    mutationFn: () => api.finalizeCapture(projectId),
    onSuccess: () => {
      announce('Stage B finalized — advancing to Reconciliation');
      qc.invalidateQueries({ queryKey: ['capture', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    }
  });

  const live = (status?.liveDeployments ?? 0) > 0;
  const empty = (status?.totalEnvelopes ?? 0) === 0;
  const canFinalize = !empty && !gateBPassed;

  // Inject chrome content.
  useEffect(() => {
    if (!status || empty) {
      setStatus([]);
      setActions(null);
      return;
    }
    const stateLabel = gateBPassed ? 'finalized' : live ? 'live' : 'paused';
    setStatus([
      { label: stateLabel, value: stateLabel === 'live' ? '●' : stateLabel === 'finalized' ? '✓' : '◌',
        tone: stateLabel === 'finalized' ? 'ok' : stateLabel === 'live' ? 'ok' : 'neutral' },
      { label: 'envelopes', value: status.totalEnvelopes, tone: 'brand' }
    ]);
    setActions(
      <>
        <IconButton icon={<RefreshCw size={13} />} ariaLabel="Refresh capture status" onClick={() => refetch()} />
        <button
          className="btn-secondary btn-sm"
          onClick={() => startM.mutate()}
          disabled={startM.isPending || gateBPassed}
        >
          <Play size={13} aria-hidden="true" />
          {startM.isPending ? 'Capturing…' : live ? 'Restart' : 'Resume'}
        </button>
        <button
          className="btn-primary btn-sm"
          onClick={() => finalizeM.mutate()}
          disabled={finalizeM.isPending || !canFinalize || !hasRole('TECH_LEAD')}
          title={
            gateBPassed ? 'Stage B already finalized'
            : !hasRole('TECH_LEAD') ? 'Finalizing a stage requires the Tech Lead role'
            : empty     ? 'Capture some envelopes first'
                        : 'Mark Stage B complete and advance to Stage C'
          }
        >
          <Square size={13} aria-hidden="true" />
          {finalizeM.isPending ? 'Finalizing…' : gateBPassed ? 'Finalized' : 'Stop & finalize'}
        </button>
      </>
    );
  }, [status, empty, live, gateBPassed, startM.isPending, finalizeM.isPending, canFinalize]); // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <LoadingState />;
  if (error) {
    return <ErrorState
      title="Capture service unreachable"
      detail="The cap-service did not respond. Once it's back online, retry to load capture state."
      onRetry={() => refetch()}
    />;
  }

  const s = status!;

  if (empty && !startM.isPending) {
    return <NotStarted onStart={() => startM.mutate()} starting={startM.isPending} project={project} />;
  }

  return (
    <ThreePane
      leftRail="default"
      left={
        <DeploymentsPane
          status={s}
          selectedId={selectedDeploymentId}
          onSelect={setSelectedDeploymentId}
        />
      }
      right={
        <SanitizationPane
          status={s}
          projectId={projectId}
          gateBPassed={gateBPassed}
        />
      }
    >
      {selectedDeploymentId
        ? <DeploymentDetailPane
            projectId={projectId}
            deploymentId={selectedDeploymentId}
            onClose={() => setSelectedDeploymentId(null)}
            gateBPassed={gateBPassed}
          />
        : <CenterPane status={s} />}
    </ThreePane>
  );
}

/* ---------------- Empty state ---------------- */

function NotStarted({
  onStart, starting, project
}: { onStart: () => void; starting: boolean; project: Project }) {
  return (
    <EmptyState
      icon={Activity}
      accent="gradient"
      title="Start runtime capture"
      body="Atlas deploys capture sidecars to begin recording wire envelopes. Each envelope is sanitized at the edge before reaching the corpus."
      hint={
        project.sourcePath
          ? <>Source: <span className="font-mono text-fg-1">{project.sourcePath}</span></>
          : undefined
      }
      primaryAction={
        <button className="btn-brand" disabled={starting} onClick={onStart}>
          <Play size={14} aria-hidden="true" /> {starting ? 'Capturing…' : 'Begin capture'}
        </button>
      }
    />
  );
}

/* ---------------- Left pane: deployments ---------------- */

function DeploymentsPane({
  status, selectedId, onSelect
}: {
  status: CaptureStatus;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
}) {
  const sampleRate = status.deployments[0]?.sampleRate ?? 100;
  return (
    <>
      <div className="px-4 py-3 border-b border-line">
        <div className="eyebrow">Deployments</div>
        <div className="mt-1 text-xs text-fg-3">
          {status.liveDeployments} live · {status.deployments.length} configured
        </div>
      </div>

      <ul className="flex-1 overflow-y-auto py-2" role="list">
        {status.deployments.map(d => {
          const active = d.id === selectedId;
          const stateTone = d.status === 'live' ? 'bg-ok dot-pulse'
                          : d.status === 'stopped' ? 'bg-fg-4'
                                                   : 'bg-warn';
          return (
            <li key={d.id}>
              <button
                onClick={() => onSelect(active ? null : d.id)}
                aria-pressed={active}
                className={`w-full text-left px-4 py-2.5 border-b border-line/60 transition-colors
                  ${active ? 'bg-brand-50 border-l-2 border-l-brand' : 'hover:bg-surface/80'}`}
              >
                <div className="flex items-center gap-2">
                  <span className={`dot ${stateTone}`} aria-hidden="true" />
                  <span className={`font-mono text-sm ${active ? 'text-brand font-semibold' : 'text-fg-1'}`}>
                    {d.environment}
                  </span>
                  <span className="badge-neutral ml-auto badge-mono">{d.method}</span>
                  <ChevronRight size={12}
                                className={active ? 'text-brand' : 'text-fg-4'}
                                aria-hidden="true" />
                </div>
                <div className="mt-1 text-2xs text-fg-3 ml-4">
                  {d.status} · sample {d.sampleRate}%
                </div>
              </button>
            </li>
          );
        })}
      </ul>

      <div className="p-3 border-t border-line bg-surface/60">
        <div className="eyebrow mb-2">Configuration</div>
        <ConfigRow icon={Hash}    label="Sample rate" value={`${sampleRate}%`} />
        <ConfigRow icon={ArrowDownUp} label="Max payload" value="64 KB" />
        <ConfigRow icon={Shield}  label="PII rule set" value="default" />
      </div>
    </>
  );
}

function ConfigRow({ icon: Icon, label, value }: { icon: any; label: string; value: string }) {
  return (
    <div className="flex items-center gap-2 py-1 text-xs">
      <Icon size={12} className="text-fg-4" />
      <span className="text-fg-3">{label}</span>
      <span className="ml-auto font-mono text-fg-1">{value}</span>
    </div>
  );
}

/* ---------------- Center pane: aggregated metrics + envelope feed ---------------- */

function CenterPane({ status }: { status: CaptureStatus }) {
  const total = status.totalEnvelopes;
  const ops = status.operationDistribution;
  const recentDir = useMemo(() => {
    const d: Record<string, number> = { REQUEST: 0, RESPONSE: 0, FAULT: 0 };
    for (const e of status.recentEnvelopes) d[e.direction] = (d[e.direction] ?? 0) + 1;
    return d;
  }, [status.recentEnvelopes]);

  return (
    <div className="flex flex-col min-w-0 h-full">
      <div className="grid grid-cols-1 sm:grid-cols-3 border-b border-line">
        <PaneStat label="Total envelopes" value={total.toLocaleString()} tone="brand" />
        <PaneStat label="Faults observed" value={recentDir.FAULT?.toLocaleString() ?? '0'}
                  tone="warn" subtle="last 25" />
        <PaneStat label="Sanitization errors" value="0" tone="ok" />
      </div>

      <div className="p-5 space-y-6">
        <section>
          <div className="eyebrow mb-2">Operation distribution</div>
          <OperationBars operations={ops} />
        </section>

        <section>
          <div className="flex items-center justify-between mb-2">
            <div className="eyebrow">Recent envelopes</div>
            <span className="text-2xs text-fg-3">last {status.recentEnvelopes.length}</span>
          </div>
          <EnvelopeTable rows={status.recentEnvelopes} />
        </section>

        <p className="text-2xs text-fg-3">
          Select a deployment on the left to drill in and adjust its sample rate.
        </p>
      </div>
    </div>
  );
}

/* ---------------- Center pane (alt): per-deployment drill-in ---------------- */

function DeploymentDetailPane({
  projectId, deploymentId, onClose, gateBPassed
}: {
  projectId: string;
  deploymentId: string;
  onClose: () => void;
  gateBPassed: boolean;
}) {
  const qc = useQueryClient();
  const { data, isLoading, error } = useQuery({
    queryKey: ['capture', projectId, 'deployment', deploymentId],
    queryFn: () => api.deploymentDetail(projectId, deploymentId),
    refetchInterval: 5000
  });

  const updateM = useMutation({
    mutationFn: (sampleRate: number) =>
      api.updateDeployment(projectId, deploymentId, { sampleRate }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['capture', projectId] });
      qc.invalidateQueries({ queryKey: ['capture', projectId, 'deployment', deploymentId] });
    }
  });

  // Local draft state for the sample-rate slider so it doesn't fight the server.
  const [draftRate, setDraftRate] = useState<number | null>(null);
  const liveRate = data?.deployment.sampleRate ?? 100;
  const rate = draftRate ?? liveRate;
  const dirty = draftRate !== null && draftRate !== liveRate;

  if (isLoading) {
    return (
      <div className="flex flex-col min-w-0 p-6 space-y-3">
        <div className="skel h-3 w-32" />
        <div className="skel h-8 w-2/3" />
        <div className="skel h-24 w-full" />
      </div>
    );
  }
  if (error || !data) {
    return (
      <div className="flex flex-col min-w-0 p-6 gap-3">
        <button onClick={onClose} className="btn-ghost btn-sm self-start">
          <ChevronLeft size={13} aria-hidden="true" /> Back
        </button>
        <ErrorState title="Could not load deployment." />
      </div>
    );
  }

  const d = data.deployment;
  const dirCounts = data.directionDistribution.reduce<Record<string, number>>(
    (acc, x) => { acc[x.direction] = x.count; return acc; }, {});

  return (
    <div className="flex flex-col min-w-0 h-full">
      <div className="px-5 py-4 border-b border-line flex items-center gap-3 flex-wrap">
        <button onClick={onClose} className="btn-ghost btn-sm" aria-label="Back to deployments overview">
          <ChevronLeft size={13} aria-hidden="true" /> Back
        </button>
        <div className="h-7 w-7 rounded-md bg-agent-50 text-agent inline-flex items-center justify-center"
             aria-hidden="true">
          <Server size={13} strokeWidth={2} />
        </div>
        <div className="min-w-0">
          <div className="text-2xs font-mono uppercase tracking-wider text-fg-3">Deployment</div>
          <div className="text-sm font-semibold text-fg-1 leading-tight">
            <span className="font-mono">{d.environment}</span>
            <span aria-hidden="true" className="text-fg-3 mx-2">·</span>
            <span className="text-fg-2 capitalize">{d.method}</span>
          </div>
        </div>
        <span className={`ml-auto badge-mono ${
          d.status === 'live' ? 'badge-ok' : d.status === 'stopped' ? 'badge-neutral' : 'badge-warn'
        }`}>
          {d.status}
        </span>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-4 border-b border-line">
        <PaneStat label="Envelopes" value={data.totalEnvelopes.toLocaleString()} tone="brand" />
        <PaneStat label="Requests"  value={(dirCounts.REQUEST  ?? 0).toLocaleString()} tone="ok" />
        <PaneStat label="Responses" value={(dirCounts.RESPONSE ?? 0).toLocaleString()} tone="ok" />
        <PaneStat label="Faults"    value={(dirCounts.FAULT    ?? 0).toLocaleString()} tone="warn" />
      </div>

      <div className="p-5 space-y-6 overflow-y-auto">
        <section className="surface p-4">
          <div className="flex items-center gap-2 mb-3">
            <Sliders size={14} className="text-agent" aria-hidden="true" />
            <h3 className="eyebrow">Sample rate</h3>
            <span className="ml-auto font-mono text-lg text-fg-1 font-semibold tabular-nums" aria-live="polite">
              {rate}%
            </span>
          </div>
          <label className="block">
            <span className="sr-only">Sample rate</span>
            <input
              type="range"
              min={1} max={100} step={1}
              value={rate}
              onChange={e => setDraftRate(parseInt(e.target.value, 10))}
              disabled={gateBPassed || updateM.isPending}
              className="w-full accent-brand"
              aria-valuemin={1}
              aria-valuemax={100}
              aria-valuenow={rate}
            />
          </label>
          <div className="mt-1 flex items-center justify-between text-2xs text-fg-3">
            <span>1% (light)</span>
            <span>100% (full)</span>
          </div>
          <div className="mt-3 flex items-center gap-2 flex-wrap">
            <button
              className="btn-primary btn-sm"
              disabled={!dirty || gateBPassed || updateM.isPending}
              onClick={() => draftRate !== null && updateM.mutate(draftRate)}
            >
              {updateM.isPending ? 'Saving…' : 'Save sample rate'}
            </button>
            {dirty && !updateM.isPending && (
              <button className="btn-ghost btn-sm" onClick={() => setDraftRate(null)}>
                Cancel
              </button>
            )}
            {gateBPassed && (
              <span className="text-2xs text-fg-3">Stage B finalized — read only.</span>
            )}
          </div>
        </section>

        <section>
          <h3 className="eyebrow mb-2">Operation distribution · this deployment</h3>
          <OperationBars operations={data.operationDistribution} />
        </section>

        <section>
          <div className="flex items-center justify-between mb-2">
            <h3 className="eyebrow">Recent envelopes · this deployment</h3>
            <span className="text-2xs text-fg-3">last {data.recentEnvelopes.length}</span>
          </div>
          <EnvelopeTable rows={data.recentEnvelopes} />
        </section>
      </div>
    </div>
  );
}

function PaneStat({ label, value, tone, subtle }:
  { label: string; value: string; tone: 'brand' | 'warn' | 'ok'; subtle?: string }) {
  const cls =
    tone === 'brand' ? 'text-brand'
  : tone === 'warn'  ? 'text-warn'
                     : 'text-ok';
  return (
    <div className="px-5 py-4 border-r border-line last:border-0 border-b sm:border-b-0">
      <div className="eyebrow">{label}</div>
      <div className={`mt-1 text-3xl font-semibold tracking-tight tabular-nums ${cls}`}>
        {value}
      </div>
      {subtle && <div className="text-2xs text-fg-3 mt-0.5">{subtle}</div>}
    </div>
  );
}

function OperationBars({ operations }: { operations: Array<{ name: string; count: number }> }) {
  if (!operations.length) {
    return <div className="text-sm text-fg-3">No operations captured yet.</div>;
  }
  const max = Math.max(...operations.map(o => o.count));
  return (
    <div className="space-y-1.5">
      {operations.map(op => {
        const pct = Math.max(2, Math.round((op.count / max) * 100));
        return (
          <div key={op.name} className="flex items-center gap-3">
            <div className="w-44 font-mono text-xs text-fg-1 truncate">{op.name}</div>
            <div className="flex-1 bg-canvas border border-line rounded-md h-5 overflow-hidden">
              <div
                className="h-full bg-gradient-to-r from-brand to-brand-700"
                style={{ width: `${pct}%` }}
              />
            </div>
            <div className="w-16 text-right font-mono text-xs text-fg-2">
              {op.count.toLocaleString()}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function EnvelopeTable({ rows }: { rows: CaptureEnvelope[] }) {
  if (!rows.length) {
    return <div className="text-sm text-fg-3">No envelopes yet.</div>;
  }
  return (
    <div className="surface-soft overflow-hidden">
      <table className="w-full text-sm">
        <thead className="bg-canvas border-b border-line text-2xs uppercase tracking-wider text-fg-3">
          <tr>
            <th className="text-left font-medium px-3 py-2 w-20">Time</th>
            <th className="text-left font-medium px-3 py-2 w-24">Dir</th>
            <th className="text-left font-medium px-3 py-2">Operation</th>
            <th className="text-left font-medium px-3 py-2 w-28">Partner</th>
            <th className="text-left font-medium px-3 py-2 w-20">Env</th>
            <th className="text-right font-medium px-3 py-2 w-20">Size</th>
            <th className="text-right font-medium px-3 py-2 w-16">PII</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.id} className="border-b border-line last:border-0 hover:bg-canvas">
              <td className="px-3 py-1.5 font-mono text-2xs text-fg-3">
                {fmtTime(r.capturedAt)}
              </td>
              <td className="px-3 py-1.5">
                <DirectionPill dir={r.direction} />
              </td>
              <td className="px-3 py-1.5 font-mono text-fg-1 truncate">{r.operation}</td>
              <td className="px-3 py-1.5 text-fg-2">{r.partner}</td>
              <td className="px-3 py-1.5 font-mono text-2xs text-fg-3">{r.environment}</td>
              <td className="px-3 py-1.5 text-right font-mono text-2xs text-fg-3">
                {fmtSize(r.size)}
              </td>
              <td className="px-3 py-1.5 text-right font-mono text-2xs">
                {r.sanitizationHits > 0
                  ? <span className="text-warn">{r.sanitizationHits}</span>
                  : <span className="text-fg-4">—</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function DirectionPill({ dir }: { dir: string }) {
  const cls =
    dir === 'REQUEST'  ? 'badge-brand'
  : dir === 'RESPONSE' ? 'badge-ok'
                       : 'badge-err';
  return <span className={`${cls} badge-mono`}>{dir.toLowerCase()}</span>;
}

function fmtSize(b: number) {
  if (b < 1024) return `${b} B`;
  return `${(b / 1024).toFixed(1)} KB`;
}
function fmtTime(iso: string) {
  try {
    const d = new Date(iso);
    return d.toLocaleTimeString('en-US', { hour12: false });
  } catch { return iso; }
}

/* ---------------- Right pane: sanitization audit (with inline edit) ---------------- */

function SanitizationPane({
  status, projectId, gateBPassed
}: {
  status: CaptureStatus;
  projectId: string;
  gateBPassed: boolean;
}) {
  const totalHits = status.sanitizationRules.reduce((a, r) => a + (r.hits ?? 0), 0);
  return (
    <aside className="flex flex-col h-full" aria-label="PII sanitization audit">
      <div className="px-5 py-4 border-b border-line">
        <div className="flex items-center gap-2">
          <div className="h-7 w-7 rounded-full bg-ok-50 text-ok inline-flex items-center justify-center"
               aria-hidden="true">
            <Shield size={13} strokeWidth={2} />
          </div>
          <div>
            <div className="text-2xs font-mono uppercase tracking-wider text-fg-3">Audit</div>
            <div className="text-sm font-semibold text-fg-1 leading-tight">PII sanitization</div>
          </div>
        </div>
        <div className="mt-3 text-xs text-fg-2">
          <span className="font-mono text-fg-1 font-semibold tabular-nums">{totalHits.toLocaleString()}</span> total redactions
        </div>
      </div>

      <ul className="flex-1 overflow-y-auto p-3 space-y-2">
        {status.sanitizationRules.map(r => (
          <SanitizationRuleCard
            key={r.id ?? r.name}
            rule={r}
            projectId={projectId}
            disabled={gateBPassed}
          />
        ))}
      </ul>

      <div className="p-3 border-t border-line bg-surface/60 text-2xs text-fg-3">
        Raw envelopes never persist. Sanitization runs synchronously at the edge.
      </div>
    </aside>
  );
}

const STRATEGIES: Array<SanitizationRuleStat['strategy']> = ['hash', 'redact', 'tokenize', 'leave'];

function SanitizationRuleCard({
  rule, projectId, disabled
}: {
  rule: SanitizationRuleStat;
  projectId: string;
  disabled: boolean;
}) {
  const qc = useQueryClient();
  const updateM = useMutation({
    mutationFn: (body: { enabled?: boolean; strategy?: string }) =>
      api.updateSanitizationRule(projectId, rule.id, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['capture', projectId] })
  });

  return (
    <li className={`surface p-3 ${rule.enabled === false ? 'opacity-60' : ''}`}>
      <div className="flex items-center gap-2">
        <span className="font-mono text-sm text-fg-1 truncate">{rule.name}</span>
        {rule.global && <span className="badge-neutral">global</span>}
        <label className="ml-auto inline-flex items-center gap-1.5 cursor-pointer select-none">
          <input
            type="checkbox"
            className="accent-brand"
            checked={rule.enabled !== false}
            disabled={disabled || updateM.isPending}
            onChange={e => updateM.mutate({ enabled: e.target.checked })}
          />
          <span className="text-2xs text-fg-3">{rule.enabled !== false ? 'on' : 'off'}</span>
        </label>
      </div>

      {rule.pattern && (
        <div className="mt-1 font-mono text-2xs text-fg-3 truncate" title={rule.pattern}>
          {rule.pattern}
        </div>
      )}

      <div className="mt-2 flex items-center justify-between text-xs">
        <span className="text-fg-3">Hits</span>
        <span className="font-mono font-semibold text-fg-1">
          {(rule.hits ?? 0).toLocaleString()}
        </span>
      </div>

      <div className="mt-2 flex items-center gap-1">
        {STRATEGIES.map(s => {
          const active = rule.strategy === s;
          return (
            <button
              key={s}
              disabled={disabled || updateM.isPending || active}
              onClick={() => updateM.mutate({ strategy: s })}
              className={`flex-1 px-2 py-1 rounded-md text-2xs font-mono border transition-colors
                ${active
                  ? 'bg-agent text-white border-agent shadow-sm'
                  : 'bg-canvas text-fg-2 border-line hover:bg-surface'}
                disabled:opacity-50 disabled:cursor-not-allowed
              `}
            >
              {s}
            </button>
          );
        })}
      </div>
    </li>
  );
}
