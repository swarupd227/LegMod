import { useEffect, useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Play, Sparkles, Check, X as XIcon, ArrowUpRight,
  Pencil, MessageSquare, RefreshCw, Library
} from 'lucide-react';
import {
  api, ElementView, Project, ReconDecision, ReconPattern, ReconStatus
} from '../api/client';
import { currentIdentity } from '../auth/authClient';
import { ConfidenceBadge } from '../components/Badges';
import {
  ThreePane, EmptyState, LoadingState, ErrorState,
  SegmentedControl, IconButton,
  useStageChrome, useAnnouncer
} from '../components/ui';

type Filter = 'pending' | 'resolved' | 'escalated' | 'all';

/* ---------------- Pattern library lookup ---------------- */

type PatternIndex = {
  byExact: Map<string, ReconPattern>;
  byField: Map<string, ReconPattern>;
};

/**
 * Build a two-level lookup so we can resolve a decision's
 * (kind, path) to a pattern in O(1). Patterns with paths like
 * "*.accountId" hit the byField map; "AllocationRequest.tradeDate"
 * hits byExact. The field map is keyed by the suffix after the
 * dot — the same normalization PatternService applies on insert.
 */
function buildPatternIndex(patterns: ReconPattern[]): PatternIndex {
  const byExact = new Map<string, ReconPattern>();
  const byField = new Map<string, ReconPattern>();
  for (const p of patterns) {
    const k = `${p.kind}|${p.path}`;
    if (p.path.startsWith('*.')) {
      byField.set(`${p.kind}|${p.path.slice(2)}`, p);
    } else {
      byExact.set(k, p);
    }
  }
  return { byExact, byField };
}

function lookupPattern(idx: PatternIndex, kind: string, path: string): ReconPattern | undefined {
  const exact = idx.byExact.get(`${kind}|${path}`);
  if (exact) return exact;
  const dot = path.indexOf('.');
  const field = dot >= 0 ? path.slice(dot + 1) : path;
  return idx.byField.get(`${kind}|${field}`);
}

/* ---------------- Pattern banner ---------------- */

function PatternBanner({ pattern }: { pattern: ReconPattern }) {
  const conf =
    pattern.confidence === 'high'   ? 'bg-violet-100 text-violet-900 border-violet-200'
  : pattern.confidence === 'medium' ? 'bg-violet-50  text-violet-800 border-violet-100'
                                     : 'bg-bg-2       text-fg-2       border-line';
  return (
    <div
      className={`mt-3 rounded border ${conf} px-3 py-2 flex items-start gap-2`}
      role="note"
      aria-label="Pattern-library recommendation"
    >
      <Library size={14} className="mt-0.5 shrink-0" aria-hidden="true" />
      <div className="flex-1 text-xs">
        <div className="font-medium">
          Pattern library · recommended <span className="font-mono">{pattern.recommendation}</span>
          {' '}— based on {pattern.occurrence_count} prior migration{pattern.occurrence_count === 1 ? '' : 's'}
        </div>
        <div className="text-2xs mt-0.5 opacity-90">{pattern.summary}</div>
      </div>
      <span className="text-2xs uppercase tracking-wide font-medium shrink-0">
        {pattern.confidence}
      </span>
    </div>
  );
}

const KIND_LABEL: Record<string, string> = {
  format_difference: 'Format difference',
  type_lenience:     'Type lenience',
  enum_promotion:    'Enum promotion',
  missing_vendor:    'Missing in vendor',
  rename:            'Rename',
  fault_diff:        'Fault difference'
};

const ACTION_LABEL: Record<string, string> = {
  preserve_legacy:  'Preserve legacy wire shape',
  adopt_vendor:     'Adopt vendor schema',
  promote_to_enum:  'Promote to closed enum',
  add_to_schema:    'Add to authoritative schema',
  escalate:         'Escalate to SME'
};

export default function Reconciliation({
  projectId, project: _project
}: { projectId: string; project: Project }) {
  const qc = useQueryClient();
  const { setStatus, setActions } = useStageChrome();
  const { announce } = useAnnouncer();

  const { data: status, isLoading, error, refetch } = useQuery({
    queryKey: ['recon', projectId],
    queryFn: () => api.reconStatus(projectId),
    refetchInterval: 8000
  });

  // Cross-project pattern library — fetched once per Stage C visit so
  // we can decorate decision rows + the center pane with "Recommended
  // (based on N prior migrations)" hints. Empty list when no patterns
  // exist for this vendor.
  const { data: patternsData } = useQuery({
    queryKey: ['recon-patterns', projectId],
    queryFn: () => api.reconPatterns(projectId),
    staleTime: 60_000
  });
  const patternIndex = useMemo(
    () => buildPatternIndex(patternsData?.patterns ?? []),
    [patternsData]
  );

  const runM = useMutation({
    mutationFn: () => api.runReconciliation(projectId),
    onSuccess: () => {
      announce('Reconciliation complete');
      qc.invalidateQueries({ queryKey: ['recon', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    }
  });

  const [filter, setFilter] = useState<Filter>('pending');
  const [selectedId, setSelectedId] = useState<string | null>(null);

  // Inject chrome status + actions.
  useEffect(() => {
    if (!status?.run) {
      setStatus([]);
      setActions(null);
      return;
    }
    const c = status.counts;
    setStatus([
      { label: 'pending',  value: c.pending,  tone: 'warn' },
      { label: 'resolved', value: c.resolved, tone: 'ok' },
      { label: 'total',    value: c.total,    tone: 'neutral' }
    ]);
    setActions(
      <>
        <IconButton icon={<RefreshCw size={13} />} ariaLabel="Refresh reconciliation" onClick={() => refetch()} />
        <button className="btn-secondary btn-sm" onClick={() => runM.mutate()} disabled={runM.isPending}>
          <Play size={13} aria-hidden="true" /> {runM.isPending ? 'Reconciling…' : 'Re-run'}
        </button>
      </>
    );
  }, [status, runM.isPending]); // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <LoadingState />;
  if (error) {
    return <ErrorState
      title="Reconciliation service unreachable"
      detail="The recon-service didn't respond."
      onRetry={() => refetch()}
    />;
  }

  const s = status!;
  const empty = (s.decisions?.length ?? 0) === 0 && !s.run;
  if (empty) {
    return <NotStarted onRun={() => runM.mutate()} running={runM.isPending} />;
  }

  // Progress strip, kept inside the screen rather than chrome (it's a tall element).
  return (
    <>
      <ProgressStrip status={s} />
      <ThreePane
        leftRail="default"
        left={
          <DecisionQueue
            status={s}
            filter={filter}
            onFilter={setFilter}
            selectedId={selectedId ?? s.decisions[0]?.id}
            onSelect={setSelectedId}
            patternIndex={patternIndex}
          />
        }
        right={
          <AgentPane
            projectId={projectId}
            status={s}
            selectedId={selectedId ?? s.decisions[0]?.id}
            onResolved={() => qc.invalidateQueries({ queryKey: ['recon', projectId] })}
          />
        }
      >
        <ThreeWayCenter
          status={s}
          selectedId={selectedId ?? s.decisions[0]?.id}
          patternIndex={patternIndex}
        />
      </ThreePane>
    </>
  );
}

/* ---------------- Empty state ---------------- */

function NotStarted({ onRun, running }: { onRun: () => void; running: boolean }) {
  return (
    <EmptyState
      icon={Sparkles}
      accent="gradient"
      title="Run schema reconciliation"
      body="Atlas folds together the vendor WSDL, the legacy code-derived schema, and the empirical schema inferred from the captured corpus. Each divergence is classified by the Reconciliation Agent — accept, override, or escalate."
      primaryAction={
        <button className="btn-brand" disabled={running} onClick={onRun}>
          <Play size={14} aria-hidden="true" /> {running ? 'Reconciling…' : 'Begin reconciliation'}
        </button>
      }
    />
  );
}

/* ---------------- Progress strip ---------------- */

function ProgressStrip({ status }: { status: ReconStatus }) {
  const c = status.counts;
  const progress = c.total === 0 ? 0 : Math.round((c.resolved / c.total) * 100);
  return (
    <div className="card mt-4 px-5 py-3 flex items-center gap-4 flex-wrap">
      <div className="flex-1 min-w-[240px]">
        <div className="flex items-center justify-between text-xs">
          <span className="text-fg-3">Decisions resolved</span>
          <span className="font-mono text-fg-1 font-semibold tabular-nums" aria-live="polite">
            {c.resolved} / {c.total}
          </span>
        </div>
        <div
          className="mt-1 h-1.5 bg-canvas border border-line rounded-full overflow-hidden"
          role="progressbar"
          aria-valuenow={progress}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label="Reconciliation progress"
        >
          <div
            className="h-full bg-gradient-to-r from-brand to-brand-700 transition-all"
            style={{ width: `${progress}%` }}
          />
        </div>
      </div>
    </div>
  );
}

/* ---------------- Left pane: decision queue ---------------- */

function DecisionQueue({
  status, filter, onFilter, selectedId, onSelect, patternIndex
}: {
  status: ReconStatus; filter: Filter; onFilter: (f: Filter) => void;
  selectedId?: string; onSelect: (id: string) => void;
  patternIndex: PatternIndex;
}) {
  const filtered = useMemo(() => {
    return status.decisions.filter(d => {
      if (filter === 'all') return true;
      if (filter === 'resolved') return d.resolution !== 'pending' && d.resolution !== 'escalated';
      if (filter === 'escalated') return d.resolution === 'escalated';
      return d.resolution === 'pending';
    });
  }, [status.decisions, filter]);

  const counts = useMemo(() => {
    const all = status.decisions;
    return {
      pending:   all.filter(d => d.resolution === 'pending').length,
      escalated: all.filter(d => d.resolution === 'escalated').length,
      resolved:  all.filter(d => d.resolution !== 'pending' && d.resolution !== 'escalated').length,
      all:       all.length
    };
  }, [status.decisions]);

  return (
    <>
      <div className="px-3 py-2 border-b border-line">
        <SegmentedControl<Filter>
          ariaLabel="Filter decisions by resolution status"
          segments={[
            { id: 'pending',   label: 'Pending',   count: counts.pending,   tone: 'warn' },
            { id: 'escalated', label: 'Escalated', count: counts.escalated, tone: 'agent' },
            { id: 'resolved',  label: 'Resolved',  count: counts.resolved,  tone: 'ok' },
            { id: 'all',       label: 'All',       count: counts.all,       tone: 'neutral' }
          ]}
          value={filter}
          onChange={onFilter}
          size="sm"
        />
      </div>

      <ul className="flex-1 overflow-y-auto py-1" role="list">
        {filtered.length === 0 && (
          <li className="px-4 py-8 text-center text-sm text-fg-3">
            No decisions in this filter.
          </li>
        )}
        {filtered.map(d => (
          <DecisionRow
            key={d.id}
            decision={d}
            selected={d.id === selectedId}
            onClick={() => onSelect(d.id)}
            pattern={lookupPattern(patternIndex, d.kind, d.path)}
          />
        ))}
      </ul>
    </>
  );
}

function DecisionRow({
  decision, selected, onClick, pattern
}: {
  decision: ReconDecision; selected: boolean; onClick: () => void;
  pattern?: ReconPattern;
}) {
  const dotCls =
    decision.resolution === 'pending'    ? 'bg-warn'
  : decision.resolution === 'escalated'  ? 'bg-agent'
  : decision.resolution === 'accepted'   ? 'bg-ok'
                                          : 'bg-fg-4';
  const impactCls =
    decision.impact === 'high'   ? 'badge-err'
  : decision.impact === 'medium' ? 'badge-warn'
                                  : 'badge-neutral';
  return (
    <li>
      <button
        onClick={onClick}
        className={`w-full text-left px-4 py-2.5 border-l-2 transition-colors duration-150 ${
          selected ? 'bg-surface border-brand' : 'border-transparent hover:bg-subtle'
        }`}
      >
        <div className="flex items-center gap-2">
          <span className={`dot ${dotCls}`} />
          <span className="font-mono text-sm text-fg-1 truncate flex-1">{decision.path}</span>
          <span className={`${impactCls} badge-mono`}>{decision.impact}</span>
        </div>
        <div className="mt-1 flex items-center gap-2 text-2xs text-fg-3">
          <span className="truncate flex-1">{KIND_LABEL[decision.kind] ?? decision.kind}</span>
          {pattern && (
            <span
              className="inline-flex items-center gap-1 rounded bg-violet-100 text-violet-800 px-1.5 py-0.5 font-medium shrink-0"
              title={pattern.summary}
            >
              <Library size={10} aria-hidden="true" />
              {pattern.occurrence_count} prior
            </span>
          )}
        </div>
      </button>
    </li>
  );
}

/* ---------------- Center pane: three-way comparison ---------------- */

function ThreeWayCenter({
  status, selectedId, patternIndex
}: {
  status: ReconStatus; selectedId?: string; patternIndex: PatternIndex;
}) {
  const decision = status.decisions.find(d => d.id === selectedId);
  const element = decision
    ? status.elements.find(e => e.path === decision.path)
    : undefined;

  if (!decision || !element) {
    return <div className="p-8 text-sm text-fg-3">Select a decision to inspect.</div>;
  }

  // Compute agreement against the consensus across present views — replaces
  // the previous heuristic that always returned 'diverge'.
  const tags = computeAgreement(element);
  const pattern = lookupPattern(patternIndex, decision.kind, decision.path);

  return (
    <div className="flex flex-col min-w-0 h-full">
      <div className="px-5 pt-4 pb-3 border-b border-line">
        <div className="flex items-baseline gap-2 flex-wrap">
          <h3 className="font-mono text-base font-semibold text-fg-1 truncate">{decision.path}</h3>
          <ConfidenceBadge confidence={decision.confidence} />
        </div>
        <div className="mt-1 text-xs text-fg-3">
          Divergence: <span className="text-fg-1">{KIND_LABEL[decision.kind] ?? decision.kind}</span>
        </div>
        {pattern && <PatternBanner pattern={pattern} />}
      </div>

      <div className="grid grid-cols-3 border-b border-line">
        <ColumnHeader label="Vendor WSDL"        tint="fg-3"  agree={tags.vendor} />
        <ColumnHeader label="Code-derived"       tint="brand" agree={tags.code} />
        <ColumnHeader label="Empirical (corpus)" tint="agent" agree={tags.empiric} />
      </div>

      <div className="grid grid-cols-3 flex-1 min-h-0">
        <ViewColumn view={element.vendorView}  agree={tags.vendor}  emptyHint="No vendor declaration." />
        <ViewColumn view={element.codeView}    agree={tags.code}    emptyHint="Not declared in code." />
        <ViewColumn view={element.empiricView} agree={tags.empiric} emptyHint="Not observed in corpus." />
      </div>
    </div>
  );
}

type AgreementTag = 'agree' | 'diverge' | 'absent';

/**
 * For each of the three views, label whether it agrees with the others
 * (`agree`), differs from at least one (`diverge`), or is missing (`absent`).
 * The fingerprint is per-property — if any property differs across present
 * views, that view is marked `diverge`.
 */
function computeAgreement(e: { vendorView: ElementView; codeView: ElementView; empiricView: ElementView }): {
  vendor: AgreementTag; code: AgreementTag; empiric: AgreementTag;
} {
  const fp = (v: ElementView) =>
    JSON.stringify({
      type: v.type ?? null,
      format: v.format ?? null,
      javaType: v.javaType ?? null,
      adapter: v.adapter ?? null,
      enums: (v.enum ?? []).slice().sort()
    });
  const tag = (mine: ElementView, others: ElementView[]): AgreementTag => {
    if (!mine?.present) return 'absent';
    const present = others.filter(o => o?.present);
    if (present.length === 0) return 'agree'; // alone & present
    return present.every(o => fp(o) === fp(mine)) ? 'agree' : 'diverge';
  };
  return {
    vendor:  tag(e.vendorView,  [e.codeView,    e.empiricView]),
    code:    tag(e.codeView,    [e.vendorView,  e.empiricView]),
    empiric: tag(e.empiricView, [e.vendorView,  e.codeView])
  };
}

function ColumnHeader({ label, tint, agree }:
  { label: string; tint: 'fg-3' | 'brand' | 'agent'; agree: AgreementTag }) {
  const tintCls =
    tint === 'brand' ? 'text-brand'
  : tint === 'agent' ? 'text-agent'
                     : 'text-fg-3';
  const tagCls =
    agree === 'agree'   ? 'badge-ok'
  : agree === 'diverge' ? 'badge-warn'
                        : 'badge-neutral';
  return (
    <div className="px-4 py-2.5 bg-canvas/60 border-r border-line last:border-0 flex items-center gap-2">
      <div className={`eyebrow ${tintCls}`}>{label}</div>
      <span className={`${tagCls} badge-mono ml-auto`} aria-label={`${label} ${agree}`}>{agree}</span>
    </div>
  );
}

function ViewColumn({
  view, agree, emptyHint
}: { view: ElementView; agree: 'agree' | 'diverge' | 'absent'; emptyHint: string }) {
  const bg =
    agree === 'absent'  ? 'bg-canvas/30'
  : agree === 'diverge' ? 'bg-warn-50/40'
                        : 'bg-ok-50/40';

  return (
    <div className={`p-4 border-r border-line last:border-0 ${bg}`}>
      {!view.present ? (
        <div className="text-sm text-fg-4 italic">{emptyHint}</div>
      ) : (
        <div className="space-y-2 text-sm">
          {view.type && <Field k="Type"   v={<code className="font-mono text-fg-1">{view.type}</code>} />}
          {view.format && <Field k="Format" v={<code className="font-mono text-warn">{view.format}</code>} />}
          {view.javaType && <Field k="Java" v={<code className="font-mono text-fg-1">{view.javaType}</code>} />}
          {view.adapter && (
            <Field k="Adapter" v={
              <code className="badge-agent badge-mono">{view.adapter}</code>
            } />
          )}
          {view.observed != null && (
            <Field k="Observed" v={
              <span className="font-mono text-fg-1">{view.observed.toLocaleString()}× envelopes</span>
            } />
          )}
          {view.enum && view.enum.length > 0 && (
            <Field k="Enum" v={
              <div className="flex flex-wrap gap-1">
                {view.enum.map(v => <span key={v} className="code-chip">{v}</span>)}
              </div>
            } />
          )}
          {view.samples && view.samples.length > 0 && (
            <Field k="Samples" v={
              <div className="space-y-1 mt-0.5">
                {view.samples.slice(0, 3).map((v, i) =>
                  <code key={i} className="block font-mono text-xs text-fg-2 truncate">{v}</code>)}
              </div>
            } />
          )}
        </div>
      )}
    </div>
  );
}

function Field({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div>
      <div className="text-2xs font-mono uppercase tracking-wider text-fg-3">{k}</div>
      <div className="mt-0.5">{v}</div>
    </div>
  );
}

/* ---------------- Right pane: agent + actions ---------------- */

function AgentPane({
  projectId, status, selectedId, onResolved
}: {
  projectId: string;
  status: ReconStatus;
  selectedId?: string;
  onResolved: () => void;
}) {
  const decision = status.decisions.find(d => d.id === selectedId);
  const [note, setNote] = useState('');

  const resolveM = useMutation({
    mutationFn: (choice: 'accepted' | 'overridden' | 'escalated' | 'rejected') =>
      api.resolveDecision(projectId, decision!.id, {
        choice,
        action: decision!.agent.action,
        note: note || undefined,
        // Stamp the actual signed-in engineer rather than a hardcoded
        // dev string — the closure audit reads this back as "resolved
        // by ...". `currentIdentity()` returns the JWT-derived
        // identity; `?.email` covers the (effectively impossible)
        // post-logout race where the token was just cleared.
        user: currentIdentity()?.email
      }),
    onSuccess: () => { setNote(''); onResolved(); }
  });

  if (!decision) return <div className="agent-surface flex-1" />;

  const resolved = decision.resolution !== 'pending';
  const agent = decision.agent;

  return (
    <aside className="agent-surface flex flex-col h-full" aria-label="Reconciliation agent">
      <div className="px-5 py-4 border-b border-line">
        <div className="flex items-center gap-2">
          <div className="h-7 w-7 rounded-full bg-gradient-to-br from-agent to-brand text-white inline-flex items-center justify-center shadow-sm"
               aria-hidden="true">
            <Sparkles size={13} strokeWidth={2} />
          </div>
          <div>
            <div className="text-2xs font-mono uppercase tracking-wider text-fg-3">Agent</div>
            <div className="text-sm font-semibold text-fg-1 leading-tight">Reconciliation</div>
          </div>
        </div>
      </div>

      <div className="px-5 py-4 flex-1 overflow-y-auto space-y-5">
        <Section title="Confidence">
          <ConfidenceBadge confidence={decision.confidence} />
        </Section>

        <Section title="Recommendation">
          <div className="surface p-3">
            <div className="flex items-center gap-2">
              <ArrowUpRight size={14} className="text-brand" />
              <span className="text-sm font-medium text-fg-1">
                {ACTION_LABEL[agent.action] ?? agent.action}
              </span>
            </div>
            <p className="mt-2 text-sm text-fg-2 leading-relaxed">
              {agent.rationale}
            </p>
            <div className="mt-3 flex items-center gap-2 text-2xs text-fg-3 font-mono">
              <span className="badge-agent">{agent.model ?? 'agent'}</span>
            </div>
          </div>
        </Section>

        {agent.alternatives && agent.alternatives.length > 0 && (
          <Section title="Alternatives">
            <div className="flex flex-wrap gap-1.5">
              {agent.alternatives.map(a =>
                <span key={a} className="badge-neutral badge-mono">
                  {ACTION_LABEL[a] ?? a}
                </span>)}
            </div>
          </Section>
        )}

        {!resolved && (
          <Section title="Note">
            <textarea
              className="textarea text-xs"
              placeholder="Optional note for the audit trail…"
              value={note}
              onChange={e => setNote(e.target.value)}
              rows={3}
            />
          </Section>
        )}

        {resolved && (
          <Section title="Resolution">
            <div className="surface p-3">
              <div className="flex items-center gap-2">
                <ResolutionBadge resolution={decision.resolution} />
                <span className="text-xs text-fg-3 font-mono">{decision.chosenAction}</span>
              </div>
              {decision.note && (
                <p className="mt-2 text-sm text-fg-2">{decision.note}</p>
              )}
              {decision.resolvedBy && (
                <div className="mt-2 text-2xs text-fg-3">
                  by {decision.resolvedBy} · {decision.resolvedAt ? new Date(decision.resolvedAt).toLocaleString() : ''}
                </div>
              )}
            </div>
          </Section>
        )}
      </div>

      <div className="p-3 border-t border-line bg-surface/60 flex gap-1.5">
        <button
          className="btn-primary flex-1 btn-sm justify-center"
          disabled={resolved || resolveM.isPending}
          onClick={() => resolveM.mutate('accepted')}
        >
          <Check size={13} /> Accept
        </button>
        <button
          className="btn-secondary btn-sm"
          disabled={resolved || resolveM.isPending}
          onClick={() => resolveM.mutate('overridden')}
          title="Override agent — record alternative">
          <Pencil size={13} />
        </button>
        <button
          className="btn-secondary btn-sm"
          disabled={resolved || resolveM.isPending}
          onClick={() => resolveM.mutate('escalated')}
          title="Escalate to SME">
          <MessageSquare size={13} />
        </button>
        <button
          className="btn-danger-ghost btn-sm"
          disabled={resolved || resolveM.isPending}
          onClick={() => resolveM.mutate('rejected')}
          title="Reject — flag agent suggestion as wrong">
          <XIcon size={13} />
        </button>
      </div>
    </aside>
  );
}

function ResolutionBadge({ resolution }: { resolution: string }) {
  const cls =
    resolution === 'accepted'   ? 'badge-ok'
  : resolution === 'overridden' ? 'badge-warn'
  : resolution === 'escalated'  ? 'badge-agent'
                                 : 'badge-err';
  return <span className={`${cls} badge-mono`}>{resolution}</span>;
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="eyebrow mb-1.5">{title}</div>
      {children}
    </div>
  );
}
