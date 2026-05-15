import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Activity, Sparkles, Hash, Users, Boxes, Cpu, Calendar, DollarSign, AlertCircle
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { api, WorkspaceCost as WorkspaceCostShape, Project } from '../api/client';
import { EmptyState, LoadingState, ErrorState, MetricTile } from '../components/ui';

/**
 * Phase 2L — LLM cost attribution for the active workspace.
 *
 * Renders four rollups returned by {@code GET /api/v1/workspaces/{ws}/cost}:
 *   - Totals tile row (calls, tokensIn, tokensOut, USD)
 *   - By-user table (sorted by spend descending)
 *   - By-project table (joined client-side against the project list
 *     so we render friendly names, not UUIDs)
 *   - By-model table
 *   - Daily sparkline of the last 30 days
 *
 * The page is read-only — actions (set budget, change rates, drill into
 * a specific call) are deferred to a per-call audit view linked from
 * each row. The current scope is "show me the bill."
 */
export default function WorkspaceCost() {
  const wsId = api.defaultWorkspaceId;
  const [windowDays, setWindowDays] = useState<7 | 30 | 90>(30);

  // Use UTC midnight boundaries so the per-day bucket alignment in the
  // sparkline is stable.
  const { from, to } = useMemo(() => {
    const now = new Date();
    const toUtc = new Date(Date.UTC(
      now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1));
    const fromUtc = new Date(toUtc.getTime() - windowDays * 86_400_000);
    return { from: fromUtc.toISOString(), to: toUtc.toISOString() };
  }, [windowDays]);

  const { data: cost, isLoading, error } = useQuery({
    queryKey: ['workspaceCost', wsId, from, to],
    queryFn: () => api.workspaceCost(wsId, { from, to }),
    // The rollup is read-side-only; refetch every 60s so the spend
    // tile feels live without hammering the DB.
    refetchInterval: 60_000
  });

  const { data: projects } = useQuery({
    queryKey: ['projects', wsId],
    queryFn: () => api.projects(wsId)
  });

  return (
    <>
      <div className="flex items-end justify-between gap-6">
        <div>
          <div className="eyebrow">Cost attribution</div>
          <h1 className="mt-1 text-3xl font-semibold tracking-tight text-fg-1 text-balance">
            Workspace Spend
          </h1>
          <p className="mt-2 text-sm text-fg-2 max-w-2xl">
            LLM token usage and USD cost for every agent run and human-
            triggered invocation in this workspace, broken down by user,
            project, and model. Cost is computed from the model price
            catalog at <code>/api/v1/llm/pricing</code> and persisted
            with each call so a future price change doesn't restate
            history.
          </p>
        </div>

        <WindowPicker value={windowDays} onChange={setWindowDays} />
      </div>

      {isLoading && <LoadingState label="Loading workspace cost…" />}
      {!isLoading && error && (
        <ErrorState
          title="Couldn't load workspace cost"
          detail={String((error as Error).message ?? error)}
        />
      )}
      {cost && (
        <>
          <TotalsRow cost={cost} />
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mt-6">
            <ByUserCard cost={cost} />
            <ByModelCard cost={cost} />
            <ByProjectCard cost={cost} projects={projects ?? []} />
            <DailySparklineCard cost={cost} />
          </div>
        </>
      )}
    </>
  );
}

/* ---------------- header window picker ---------------- */

function WindowPicker({
  value, onChange
}: { value: number; onChange: (v: 7 | 30 | 90) => void }) {
  const opts: Array<{ value: 7 | 30 | 90; label: string }> = [
    { value: 7,  label: 'Last 7d' },
    { value: 30, label: 'Last 30d' },
    { value: 90, label: 'Last 90d' }
  ];
  return (
    <div role="radiogroup" aria-label="Date window" className="surface inline-flex items-center text-sm">
      {opts.map(o => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={value === o.value}
          onClick={() => onChange(o.value)}
          className={`px-3 h-9 ${value === o.value
            ? 'bg-bg-3 text-fg-1 font-medium'
            : 'text-fg-2 hover:text-fg-1'}`}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

/* ---------------- totals tile row ---------------- */

function TotalsRow({ cost }: { cost: WorkspaceCostShape }) {
  const t = cost.totals;
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mt-6">
      <MetricTile icon={Activity} label="Calls"        value={t.calls.toLocaleString()} tone="brand" />
      <MetricTile icon={Sparkles} label="Tokens in"    value={t.tokensIn.toLocaleString()} tone="agent" />
      <MetricTile icon={Sparkles} label="Tokens out"   value={t.tokensOut.toLocaleString()} tone="agent" />
      <MetricTile icon={Hash}     label="Cost (USD)"   value={formatUsd(t.costUsd)} tone="ok" accent />
    </div>
  );
}

/* ---------------- breakdown cards ---------------- */

function ByUserCard({ cost }: { cost: WorkspaceCostShape }) {
  const total = Number(cost.totals.costUsd) || 0;
  return (
    <BreakdownCard
      icon={Users}
      title="By user"
      hint="Top 100 by spend"
      rows={cost.byUser.map(r => ({
        key:   r.userEmail,
        label: r.userEmail,
        calls: r.calls,
        usd:   Number(r.costUsd),
        pct:   total > 0 ? (Number(r.costUsd) / total) * 100 : 0
      }))}
    />
  );
}

function ByModelCard({ cost }: { cost: WorkspaceCostShape }) {
  const total = Number(cost.totals.costUsd) || 0;
  return (
    <BreakdownCard
      icon={Cpu}
      title="By model"
      hint="Which model is driving spend"
      rows={cost.byModel.map(r => ({
        key:   r.model,
        label: r.model,
        calls: r.calls,
        usd:   Number(r.costUsd),
        pct:   total > 0 ? (Number(r.costUsd) / total) * 100 : 0
      }))}
    />
  );
}

function ByProjectCard({
  cost, projects
}: { cost: WorkspaceCostShape; projects: Project[] }) {
  const nameById = new Map(projects.map(p => [p.id, p.name]));
  const total = Number(cost.totals.costUsd) || 0;
  return (
    <BreakdownCard
      icon={Boxes}
      title="By project"
      hint="Newly deleted projects show as their UUID"
      rows={cost.byProject.map(r => ({
        key:   r.projectId,
        label: nameById.get(r.projectId) ?? r.projectId,
        calls: r.calls,
        usd:   Number(r.costUsd),
        pct:   total > 0 ? (Number(r.costUsd) / total) * 100 : 0
      }))}
    />
  );
}

type BreakdownRow = {
  key: string; label: string; calls: number; usd: number; pct: number;
};

function BreakdownCard({
  icon: Icon, title, hint, rows
}: {
  icon: LucideIcon;
  title: string;
  hint: string;
  rows: BreakdownRow[];
}) {
  return (
    <section className="surface p-5">
      <header className="flex items-center justify-between mb-4">
        <h2 className="text-sm font-semibold text-fg-1 flex items-center gap-2">
          <Icon size={14} className="text-fg-3" />
          {title}
        </h2>
        <span className="text-xs text-fg-3">{hint}</span>
      </header>
      {rows.length === 0 ? (
        <EmptyState icon={AlertCircle} title="No spend yet" body="No LLM calls landed in this window." />
      ) : (
        <ul className="space-y-2">
          {rows.slice(0, 12).map(r => (
            <li key={r.key} className="flex items-center gap-3 text-sm">
              <span className="flex-1 truncate text-fg-1" title={r.label}>{r.label}</span>
              <span className="text-fg-3 w-16 text-right tabular-nums">{r.calls.toLocaleString()}</span>
              <span className="text-fg-1 w-20 text-right tabular-nums">{formatUsd(r.usd)}</span>
              <span className="w-24 h-2 rounded-full bg-bg-2 overflow-hidden" aria-hidden="true">
                <span className="block h-full bg-brand-bg" style={{ width: `${Math.min(100, r.pct)}%` }} />
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/* ---------------- daily sparkline ---------------- */

function DailySparklineCard({ cost }: { cost: WorkspaceCostShape }) {
  // Compute the SVG geometry inline — no chart library to keep the
  // bundle small. The values are normalised to the window's max so the
  // sparkline always fills the panel vertically.
  const points = cost.daily.map(d => ({
    day: d.day,
    calls: d.calls,
    usd: Number(d.costUsd)
  }));
  const maxUsd = points.reduce((m, p) => Math.max(m, p.usd), 0);
  const w = 100;   // viewBox width (percent-like)
  const h = 32;    // viewBox height
  const stepX = points.length > 1 ? w / (points.length - 1) : 0;
  const path = points.length === 0 ? '' :
    points.map((p, i) => {
      const x = i * stepX;
      const y = maxUsd > 0 ? h - (p.usd / maxUsd) * h : h;
      return `${i === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`;
    }).join(' ');

  return (
    <section className="surface p-5">
      <header className="flex items-center justify-between mb-4">
        <h2 className="text-sm font-semibold text-fg-1 flex items-center gap-2">
          <Calendar size={14} className="text-fg-3" />
          Daily spend
        </h2>
        <span className="text-xs text-fg-3">
          Max day: {formatUsd(maxUsd)}
        </span>
      </header>

      {points.length === 0 ? (
        <EmptyState icon={DollarSign} title="No daily data" body="No LLM calls in this window." />
      ) : (
        <>
          <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none"
               className="w-full h-24" role="img"
               aria-label={`Daily spend sparkline for ${points.length} days, max ${formatUsd(maxUsd)}`}>
            <path d={path} fill="none" stroke="currentColor"
                  className="text-brand-fg" strokeWidth="1.2" vectorEffect="non-scaling-stroke" />
          </svg>
          <div className="mt-3 flex items-center justify-between text-xs text-fg-3 tabular-nums">
            <span>{points[0].day}</span>
            <span>{points[points.length - 1].day}</span>
          </div>
        </>
      )}
    </section>
  );
}

/* ---------------- helpers ---------------- */

function formatUsd(v: number | string): string {
  const n = typeof v === 'string' ? parseFloat(v) : v;
  if (!Number.isFinite(n) || n === 0) return '—';
  // Three decimal places for small numbers, two for larger — the
  // signal is "every penny counts at low volume; at high volume the
  // pennies are noise."
  if (Math.abs(n) < 1) return `$${n.toFixed(3)}`;
  return `$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}
