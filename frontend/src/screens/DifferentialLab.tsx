import { useEffect, useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Play, Sparkles, RefreshCw, AlertTriangle, Check, ShieldAlert,
  Zap, ArrowRight
} from 'lucide-react';
import { api, DiffStatus, Divergence, Project } from '../api/client';
import {
  ThreePane, EmptyState, LoadingState, ErrorState,
  MetricTile, IconButton,
  useStageChrome, useAnnouncer
} from '../components/ui';

type Bucket = 'red' | 'amber' | 'benign' | 'all';

const KIND_LABEL: Record<string, string> = {
  namespace_prefix: 'Namespace prefix',
  element_ordering: 'Element ordering',
  date_format:      'Date format',
  type_precision:   'Type precision',
  missing_element:  'Missing element',
  extra_element:    'Extra element',
  enum_miss:        'Enum miss',
  value_diff:       'Value diff',
  fault_diff:       'Fault diff'
};

const ACTION_LABEL: Record<string, string> = {
  fix_date_adapter:      'Patch date adapter',
  fix_decimal_precision: 'Pin decimal precision',
  accept_benign:         'Accept (benign)',
  escalate:              'Escalate'
};

export default function DifferentialLab({
  projectId, project: _project
}: { projectId: string; project: Project }) {
  const qc = useQueryClient();
  const { setStatus, setActions } = useStageChrome();
  const { announce } = useAnnouncer();

  const { data: status, isLoading, error, refetch } = useQuery({
    queryKey: ['diff', projectId],
    queryFn: () => api.diffStatus(projectId),
    refetchInterval: 8000
  });

  const runM = useMutation({
    mutationFn: () => api.runDiff(projectId, 250),
    onSuccess: () => {
      announce('Differential replay complete');
      qc.invalidateQueries({ queryKey: ['diff', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    }
  });

  const [bucket, setBucket] = useState<Bucket>('red');
  const [selectedId, setSelectedId] = useState<string | null>(null);

  // Inject chrome content.
  useEffect(() => {
    if (!status?.run) {
      setStatus([]);
      setActions(null);
      return;
    }
    const r = status.run;
    const passPct = r.envelopesReplayed === 0 ? 0 : Math.round((r.passCount / r.envelopesReplayed) * 100);
    setStatus([
      { label: 'envelopes',     value: r.envelopesReplayed, tone: 'brand' },
      { label: 'byte-equiv',    value: `${passPct}%`,       tone: 'ok' }
    ]);
    setActions(
      <>
        <IconButton icon={<RefreshCw size={13} />} ariaLabel="Refresh differential status" onClick={() => refetch()} />
        <button className="btn-primary btn-sm" onClick={() => runM.mutate()} disabled={runM.isPending}>
          <Play size={13} aria-hidden="true" /> {runM.isPending ? 'Replaying…' : 'Re-run'}
        </button>
      </>
    );
  }, [status, runM.isPending]); // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <LoadingState />;
  if (error) {
    return <ErrorState
      title="Differential service unreachable"
      detail="The diff-service didn't respond."
      onRetry={() => refetch()}
    />;
  }

  const s = status!;
  if (!s.run) {
    return <NotStarted onRun={() => runM.mutate()} running={runM.isPending} />;
  }

  const filtered = filterByBucket(s.divergences, bucket);
  const selected = s.divergences.find(d => d.id === selectedId) ?? filtered[0];

  return (
    <>
      <SummaryBoard status={s} />
      <ThreePane
        leftRail="narrow"
        left={
          <DivergenceQueue
            status={s}
            bucket={bucket} onBucket={setBucket}
            selectedId={selected?.id}
            onSelect={setSelectedId}
          />
        }
        right={
          <AgentPane
            projectId={projectId}
            divergence={selected}
            onResolved={() => qc.invalidateQueries({ queryKey: ['diff', projectId] })}
          />
        }
      >
        <CenterPane divergence={selected} />
      </ThreePane>
    </>
  );
}

function filterByBucket(list: Divergence[], bucket: Bucket): Divergence[] {
  if (bucket === 'all') return list;
  return list.filter(d => d.bucket === bucket);
}

/* ---------------- Empty state ---------------- */

function NotStarted({ onRun, running }: { onRun: () => void; running: boolean }) {
  return (
    <EmptyState
      icon={Sparkles}
      accent="gradient"
      title="Replay the captured corpus"
      body="Atlas replays each captured envelope through the regenerated wire shape, canonicalises both, and emits a divergence per non-equivalent comparison. The Diff Triage Agent classifies each as benign, amber, or red."
      primaryAction={
        <button className="btn-brand" disabled={running} onClick={onRun}>
          <Play size={14} aria-hidden="true" /> {running ? 'Replaying…' : 'Begin replay'}
        </button>
      }
    />
  );
}

/* ---------------- Summary board (lives outside the chrome) ---------------- */

function SummaryBoard({ status }: { status: DiffStatus }) {
  const r = status.run!;
  return (
    <section className="card mt-4 p-5" aria-label="Differential summary">
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <MetricTile icon={Check}         label="Pass"   value={r.passCount}   tone="ok"    sub="byte equivalent" accent />
        <MetricTile icon={Zap}           label="Benign" value={r.benignCount} tone="brand" sub="cosmetic" accent />
        <MetricTile icon={AlertTriangle} label="Amber"  value={r.amberCount}  tone="warn"  sub="needs review" accent />
        <MetricTile icon={ShieldAlert}   label="Red"    value={r.redCount}    tone="err"   sub="real regression" accent />
      </div>

      {status.byOperation.length > 0 && (
        <div className="mt-5">
          <div className="eyebrow mb-2">By operation</div>
          <div className="space-y-1.5">
            {status.byOperation.map(op => <OperationRow key={op.name} op={op} />)}
          </div>
        </div>
      )}
    </section>
  );
}

function OperationRow({
  op
}: { op: { name: string; total: number; pass: number; benign: number; amber: number; red: number } }) {
  // When total is 0, render the bar as a uniform muted strip rather than letting
  // every segment collapse to 0 and the bar disappear.
  const empty = op.total === 0;
  return (
    <div className="flex items-center gap-3 text-xs flex-wrap sm:flex-nowrap">
      <div className="w-full sm:w-44 font-mono text-fg-1 truncate">{op.name}</div>
      <div className="flex-1 min-w-[120px] h-2 bg-canvas border border-line rounded-full overflow-hidden flex"
           role="progressbar"
           aria-valuenow={op.pass}
           aria-valuemin={0}
           aria-valuemax={op.total}
           aria-label={`${op.name}: ${op.pass} pass, ${op.benign} benign, ${op.amber} amber, ${op.red} red`}>
        {empty ? (
          <div className="h-full w-full bg-line2/40" aria-hidden="true" />
        ) : (
          <>
            <div className="h-full bg-ok"    style={{ width: pct(op.pass,   op.total) }} />
            <div className="h-full bg-brand" style={{ width: pct(op.benign, op.total) }} />
            <div className="h-full bg-warn"  style={{ width: pct(op.amber,  op.total) }} />
            <div className="h-full bg-err"   style={{ width: pct(op.red,    op.total) }} />
          </>
        )}
      </div>
      <div className="w-full sm:w-44 text-right font-mono text-fg-3 tabular-nums">
        <span className="text-ok">{op.pass}</span>
        <span aria-hidden="true" className="text-fg-4 mx-1">·</span>
        <span className="text-brand">{op.benign}</span>
        <span aria-hidden="true" className="text-fg-4 mx-1">·</span>
        <span className="text-warn">{op.amber}</span>
        <span aria-hidden="true" className="text-fg-4 mx-1">·</span>
        <span className="text-err">{op.red}</span>
      </div>
    </div>
  );
}

function pct(n: number, total: number) {
  return total === 0 ? '0%' : `${(n / total) * 100}%`;
}

/* ---------------- Left pane: divergence queue ---------------- */

function DivergenceQueue({
  status, bucket, onBucket, selectedId, onSelect
}: {
  status: DiffStatus;
  bucket: Bucket; onBucket: (b: Bucket) => void;
  selectedId?: string; onSelect: (id: string) => void;
}) {
  const filtered = useMemo(
    () => filterByBucket(status.divergences, bucket),
    [status.divergences, bucket]
  );

  const buckets: { id: Bucket; label: string; count: number; cls: string }[] = [
    { id: 'red',    label: 'Red',    count: status.buckets.red,    cls: 'text-err'   },
    { id: 'amber',  label: 'Amber',  count: status.buckets.amber,  cls: 'text-warn'  },
    { id: 'benign', label: 'Benign', count: status.buckets.benign, cls: 'text-brand' },
    { id: 'all',    label: 'All',    count: status.divergences.length, cls: 'text-fg-2'  }
  ];

  return (
    <>
      <div className="px-3 py-2 border-b border-line">
        <div role="radiogroup" aria-label="Filter divergences by bucket"
             className="grid grid-cols-4 gap-1 p-1 bg-canvas border border-line rounded-md">
          {buckets.map(b => (
            <button
              key={b.id}
              type="button"
              role="radio"
              aria-checked={bucket === b.id}
              onClick={() => onBucket(b.id)}
              className={`flex flex-col items-center justify-center h-11 rounded
                          transition-colors leading-tight ${
                bucket === b.id
                  ? 'bg-surface shadow-xs'
                  : 'hover:text-fg-1'
              }`}
            >
              <span className="font-mono text-base font-semibold tabular-nums">{b.count}</span>
              <span className={`text-[10px] font-semibold uppercase tracking-wider ${bucket === b.id ? b.cls : 'text-fg-3'}`}>
                {b.label}
              </span>
            </button>
          ))}
        </div>
      </div>

      <ul className="flex-1 overflow-y-auto py-1" role="list">
        {filtered.length === 0 && (
          <li className="px-4 py-8 text-center text-sm text-fg-3">
            No divergences in this bucket.
          </li>
        )}
        {filtered.map(d =>
          <DivergenceRow
            key={d.id}
            d={d}
            selected={d.id === selectedId}
            onClick={() => onSelect(d.id)}
          />
        )}
      </ul>
    </>
  );
}

function DivergenceRow({
  d, selected, onClick
}: { d: Divergence; selected: boolean; onClick: () => void }) {
  const dotCls =
    d.bucket === 'red'    ? 'bg-err'
  : d.bucket === 'amber'  ? 'bg-warn'
                          : 'bg-brand';
  // Drain to muted appearance once resolved so the list naturally sediments.
  const opacity = d.resolved && !selected ? 'opacity-60' : '';
  return (
    <li>
      <button
        onClick={onClick}
        aria-current={selected ? 'true' : undefined}
        className={`w-full text-left px-4 py-2.5 border-l-2 transition-colors duration-150 ${opacity} ${
          selected ? 'bg-surface border-brand' : 'border-transparent hover:bg-subtle'
        }`}>
        <div className="flex items-center gap-2">
          <span className={`dot ${dotCls}`} aria-hidden="true" />
          <span className="font-mono text-sm text-fg-1 truncate flex-1">
            {KIND_LABEL[d.kind] ?? d.kind}
          </span>
          {d.resolved && (
            <>
              <span className="sr-only">Resolved</span>
              <Check size={12} className="text-ok shrink-0" aria-hidden="true" />
            </>
          )}
        </div>
        <div className="mt-0.5 text-2xs text-fg-3 truncate font-mono">
          {d.xpath ?? '—'}
        </div>
      </button>
    </li>
  );
}

/* ---------------- Center pane: legacy vs new ---------------- */

function CenterPane({ divergence }: { divergence?: Divergence }) {
  if (!divergence) {
    return <div className="p-8 text-sm text-fg-3">Select a divergence to inspect.</div>;
  }
  const d = divergence;
  return (
    <div className="flex flex-col min-w-0">
      <div className="px-5 pt-4 pb-3 border-b border-line">
        <div className="flex items-center gap-2">
          <h3 className="font-mono text-base font-semibold text-fg-1 truncate">
            {KIND_LABEL[d.kind] ?? d.kind}
          </h3>
          <BucketBadge bucket={d.bucket} />
        </div>
        <div className="mt-1 text-xs text-fg-3 font-mono break-all">
          {d.xpath ?? '—'}
        </div>
        {d.humanSummary && (
          <p className="mt-2 text-sm text-fg-2">{d.humanSummary}</p>
        )}
      </div>

      <div className="grid grid-cols-2 flex-1 min-h-0">
        <ValueBox label="Legacy wire" value={d.legacyValue} tint="ok" />
        <ValueBox label="Regenerated" value={d.newValue}    tint="brand" />
      </div>
    </div>
  );
}

function ValueBox({
  label, value, tint
}: { label: string; value?: string; tint: 'ok' | 'brand' }) {
  const tintCls = tint === 'ok' ? 'text-ok' : 'text-brand';
  return (
    <div className="border-r border-line last:border-0 flex flex-col min-h-0">
      <div className="px-4 py-2 bg-canvas/60 border-b border-line">
        <div className={`eyebrow ${tintCls}`}>{label}</div>
      </div>
      <div className="flex-1 overflow-auto p-4">
        {value ? (
          <pre className="font-mono text-sm text-fg-1 whitespace-pre-wrap break-words m-0">{value}</pre>
        ) : (
          <span className="text-fg-4 italic text-sm">(no value)</span>
        )}
      </div>
    </div>
  );
}

function BucketBadge({ bucket }: { bucket: string }) {
  const cls =
    bucket === 'red'    ? 'badge-err'
  : bucket === 'amber'  ? 'badge-warn'
                        : 'badge-brand';
  return <span className={`${cls} badge-mono`}>{bucket}</span>;
}

/* ---------------- Right pane: agent ---------------- */

function AgentPane({
  projectId, divergence, onResolved
}: {
  projectId: string;
  divergence?: Divergence;
  onResolved: () => void;
}) {
  const autofix = useMutation({
    mutationFn: (divId: string) => api.autoFixDivergence(projectId, divId),
    onSuccess: onResolved
  });

  if (!divergence) {
    return (
      <aside className="agent-surface flex flex-col h-full" aria-label="Diff Triage agent">
        <div className="px-5 py-4 border-b border-line">
          <div className="text-2xs font-mono uppercase tracking-wider text-fg-3">Agent</div>
          <div className="text-sm font-semibold text-fg-1">Diff Triage</div>
        </div>
      </aside>
    );
  }

  const d = divergence;
  const benign = d.bucket === 'benign';

  return (
    <aside className="agent-surface flex flex-col h-full" aria-label="Diff Triage agent">
      <div className="px-5 py-4 border-b border-line">
        <div className="flex items-center gap-2">
          <div className="h-7 w-7 rounded-full bg-gradient-to-br from-agent to-brand text-white inline-flex items-center justify-center shadow-sm"
               aria-hidden="true">
            <Sparkles size={13} strokeWidth={2} />
          </div>
          <div>
            <div className="text-2xs font-mono uppercase tracking-wider text-fg-3">Agent</div>
            <div className="text-sm font-semibold text-fg-1 leading-tight">Diff Triage</div>
          </div>
        </div>
      </div>

      <div className="px-5 py-4 flex-1 overflow-y-auto space-y-5">
        <Section title="Bucket">
          <BucketBadge bucket={d.bucket} />
        </Section>

        <Section title="Recommended action">
          <div className="surface p-3">
            <div className="flex items-center gap-2">
              <ArrowRight size={14} className="text-brand" />
              <span className="text-sm font-medium text-fg-1">
                {d.agent.action ? (ACTION_LABEL[d.agent.action] ?? d.agent.action) : '—'}
              </span>
            </div>
            {d.agent.rationale && (
              <p className="mt-2 text-sm text-fg-2 leading-relaxed">{d.agent.rationale}</p>
            )}
            <div className="mt-3 flex items-center gap-2 text-2xs text-fg-3 font-mono">
              <span className="badge-agent">{d.agent.model ?? 'agent'}</span>
            </div>
          </div>
        </Section>

        {d.resolved && d.autofixProposal && (
          <Section title="Auto-fix patch">
            <pre className="surface p-3 text-2xs font-mono whitespace-pre-wrap break-words text-fg-1 m-0">
              {d.autofixProposal}
            </pre>
            {d.resolvedBy && (
              <div className="mt-2 text-2xs text-fg-3">
                applied by {d.resolvedBy}
              </div>
            )}
          </Section>
        )}
      </div>

      <div className="p-3 border-t border-line bg-surface/60 flex gap-1.5">
        <button
          className="btn-primary flex-1 btn-sm justify-center"
          disabled={d.resolved || autofix.isPending || benign}
          onClick={() => autofix.mutate(d.id)}
          title={benign ? 'Benign — no fix needed' : "Apply agent's auto-fix"}>
          <Zap size={13} /> {d.resolved ? 'Applied' : autofix.isPending ? 'Patching…' : 'Auto-fix'}
        </button>
        <button
          className="btn-secondary btn-sm"
          disabled={d.resolved || autofix.isPending}
          onClick={() => autofix.mutate(d.id)}
          title="Mark accepted">
          <Check size={13} />
        </button>
      </div>
    </aside>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="eyebrow mb-1.5">{title}</div>
      {children}
    </div>
  );
}
