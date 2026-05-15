import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import {
  Sparkles, Clock, Target, AlertTriangle, ChevronDown, ChevronUp,
  TrendingUp, RefreshCw
} from 'lucide-react';
import { api, MigrationForecast } from '../api/client';

/**
 * Pre-Stage-A Migration Forecast card. Renders inline above the Stage A
 * empty state when the source has been ingested but no archaeology run
 * has completed yet.
 *
 * The forecast is the "before you spend the Stage A LLM budget, let me
 * tell you what you're getting into" moment in the demo — and the most
 * commercially defensible part of the Atlas product for first-time
 * customer conversations.
 */
export function MigrationForecastCard({ projectId }: { projectId: string }) {
  const qc = useQueryClient();
  const forecastQ = useQuery({
    queryKey: ['forecast', projectId],
    queryFn: () => api.getForecast(projectId)
  });

  const runM = useMutation({
    mutationFn: () => api.runForecast(projectId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['forecast', projectId] })
  });

  // Local UI state — collapse the long rationale by default once it
  // lands; the demo viewer can expand to read the full paragraph.
  const [expandedRationale, setExpandedRationale] = useState(true);

  if (forecastQ.isLoading) return null;

  const f = forecastQ.data ?? null;

  // Empty state — no forecast yet. Show a CTA that frames the value
  // explicitly so the engineer (and demo viewer) understands what they
  // are about to get.
  if (!f) {
    return (
      <section
        aria-labelledby="forecast-empty-title"
        className="rounded-lg border border-line bg-bg-2 p-5 mb-5"
      >
        <div className="flex items-start gap-4">
          <div className="rounded-md bg-brand-50 p-2 text-brand-700">
            <TrendingUp size={20} aria-hidden="true" />
          </div>
          <div className="flex-1">
            <div className="eyebrow text-brand-700">Migration Forecast</div>
            <h3 id="forecast-empty-title" className="text-lg font-semibold mt-1">
              Estimate the migration before paying for Stage A
            </h3>
            <p className="text-sm text-fg-2 mt-2 max-w-2xl">
              Atlas reads the cloned source tree, counts the SOAP operations and
              custom adapters, then grounds a calibrated effort estimate against
              prior migrations. You get an honest answer on weeks of work and the
              top three risks — before the team commits.
            </p>
            <div className="mt-4 flex items-center gap-3">
              <button
                className="btn-brand"
                onClick={() => runM.mutate()}
                disabled={runM.isPending}
              >
                <Sparkles size={14} aria-hidden="true" />
                {runM.isPending ? 'Forecasting…' : 'Generate forecast'}
              </button>
              <span className="text-2xs text-fg-3">~10–15 seconds · one LLM call</span>
            </div>
            {runM.error && (
              <p className="mt-3 text-2xs text-err">
                {(runM.error as Error).message}
              </p>
            )}
          </div>
        </div>
      </section>
    );
  }

  // Loaded state — render the forecast tiles + risks + rationale.
  return (
    <section
      aria-labelledby="forecast-title"
      className="rounded-lg border border-line bg-bg-2 p-5 mb-5"
    >
      <header className="flex items-center justify-between gap-4">
        <div>
          <div className="eyebrow text-brand-700">Migration Forecast</div>
          <h3 id="forecast-title" className="text-lg font-semibold mt-0.5">
            {weeksHeadline(f)} ·{' '}
            <span className={`inline-flex items-center rounded px-2 py-0.5 text-2xs font-medium ${confidenceClasses(f.confidence)}`}>
              {f.confidence} confidence
            </span>
          </h3>
        </div>
        <button
          className="btn-ghost btn-sm"
          onClick={() => runM.mutate()}
          disabled={runM.isPending}
          aria-label="Re-run forecast"
          title="Re-run forecast"
        >
          <RefreshCw size={13} aria-hidden="true" />
          {runM.isPending ? 'Forecasting…' : 'Re-run'}
        </button>
      </header>

      {/* Tiles — high-level structural facts grounding the estimate */}
      <dl className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-4">
        <Tile icon={Clock} label="Estimated effort"
              value={`${f.estimatedWeeks} weeks`} />
        <Tile icon={Target} label="SOAP operations"
              value={`${f.structuralFacts.operationCount ?? 0}`} />
        <Tile icon={Sparkles} label="Custom adapters"
              value={`${f.structuralFacts.adapterCount ?? 0}`} />
        <Tile icon={AlertTriangle} label="Complexity flags"
              value={(f.structuralFacts.complexityFlags?.length ?? 0).toString()}
              hint={(f.structuralFacts.complexityFlags ?? []).slice(0, 3).join(' · ') || 'none'} />
      </dl>

      {/* Risks */}
      <div className="mt-5">
        <div className="eyebrow mb-2">Top risks</div>
        <ol className="space-y-2">
          {f.topRisks.map((r, i) => (
            <li key={i} className="rounded border border-line bg-bg-1 p-3">
              <div className="flex items-start gap-3">
                <span
                  className={`inline-block w-2 h-2 rounded-full mt-1.5 shrink-0 ${severityDot(r.severity)}`}
                  aria-label={`${r.severity} severity`}
                />
                <div className="flex-1">
                  <div className="text-sm font-medium">{r.title}</div>
                  <div className="text-2xs text-fg-2 mt-0.5">{r.detail}</div>
                </div>
                <span className={`inline-flex items-center rounded px-2 py-0.5 text-2xs font-medium shrink-0 ${severityChip(r.severity)}`}>
                  {r.severity}
                </span>
              </div>
            </li>
          ))}
        </ol>
      </div>

      {/* Rationale — collapsible because it's a 3-4 sentence paragraph */}
      <div className="mt-5">
        <button
          className="btn-ghost btn-sm w-full justify-between"
          onClick={() => setExpandedRationale(v => !v)}
          aria-expanded={expandedRationale}
        >
          <span className="eyebrow">Rationale</span>
          {expandedRationale
            ? <ChevronUp size={14} aria-hidden="true" />
            : <ChevronDown size={14} aria-hidden="true" />}
        </button>
        {expandedRationale && (
          <p className="text-sm text-fg-2 mt-2 leading-relaxed">{f.rationale}</p>
        )}
      </div>

      {/* Provenance — small print, costs + model */}
      <footer className="mt-4 pt-3 border-t border-line flex items-center justify-between text-2xs text-fg-3">
        <span>
          Generated by <span className="font-mono">{f.model ?? 'agent'}</span>
          {f.latencyMs ? ` · ${(f.latencyMs / 1000).toFixed(1)} s` : ''}
          {f.costUsd != null ? ` · $${f.costUsd.toFixed(4)}` : ''}
        </span>
        <span>{f.generatedAt ? new Date(f.generatedAt).toLocaleString() : ''}</span>
      </footer>
    </section>
  );
}

function Tile({
  icon: Icon, label, value, hint
}: {
  icon: typeof Clock;
  label: string;
  value: string;
  hint?: string;
}) {
  return (
    <div className="rounded border border-line bg-bg-1 p-3">
      <div className="flex items-center gap-2 text-fg-3">
        <Icon size={13} aria-hidden="true" />
        <span className="text-2xs uppercase tracking-wide">{label}</span>
      </div>
      <div className="text-lg font-semibold mt-1">{value}</div>
      {hint && <div className="text-2xs text-fg-3 mt-0.5 truncate" title={hint}>{hint}</div>}
    </div>
  );
}

function weeksHeadline(f: MigrationForecast): string {
  if (f.estimatedWeeks === 1) return '~1 week';
  return `~${f.estimatedWeeks} weeks`;
}

function confidenceClasses(c: 'LOW' | 'MEDIUM' | 'HIGH'): string {
  switch (c) {
    case 'HIGH':   return 'bg-emerald-100 text-emerald-800';
    case 'MEDIUM': return 'bg-amber-100 text-amber-800';
    case 'LOW':    return 'bg-rose-100 text-rose-800';
  }
}

function severityDot(s: 'LOW' | 'MEDIUM' | 'HIGH'): string {
  switch (s) {
    case 'HIGH':   return 'bg-rose-500';
    case 'MEDIUM': return 'bg-amber-500';
    case 'LOW':    return 'bg-sky-500';
  }
}

function severityChip(s: 'LOW' | 'MEDIUM' | 'HIGH'): string {
  switch (s) {
    case 'HIGH':   return 'bg-rose-100 text-rose-800';
    case 'MEDIUM': return 'bg-amber-100 text-amber-800';
    case 'LOW':    return 'bg-sky-100 text-sky-800';
  }
}
