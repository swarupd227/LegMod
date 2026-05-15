import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Rocket, Sparkles, RefreshCw, Lock, Loader2,
  CheckCircle2, AlertOctagon, Activity, FileDown, FileText,
  Boxes, Calendar
} from 'lucide-react';
import { api, Project, Cutover, CutoverChecklistItem } from '../api/client';
import { hasRole } from '../auth/authClient';
import {
  TwoPane, EmptyState, LoadingState, ErrorState, MarkdownView,
  IconButton, useStageChrome, useAnnouncer
} from '../components/ui';
import { BuildTestPanel } from './BuildTestPanel';

const STATE_FLOW: Cutover['state'][] = ['planned', 'shadow', 'canary', 'live'];

export default function CutoverDecommission({
  projectId, project
}: { projectId: string; project: Project }) {
  const qc = useQueryClient();
  const { setStatus, setActions, setNotice } = useStageChrome();
  const { announce } = useAnnouncer();

  const { data: status, isLoading, error, refetch } = useQuery({
    queryKey: ['cutover', projectId],
    queryFn: () => api.cutoverStatus(projectId),
    refetchInterval: 12000
  });

  const projectQ = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.project(projectId)
  });
  const stageFPassed = (projectQ.data as any)?.gates?.find((g: any) => g.label === 'F')?.state === 'passed';

  const [seedError, setSeedError] = useState<string | null>(null);
  const seedM = useMutation({
    mutationFn: () => api.seedCutover(projectId),
    onSuccess: () => {
      setSeedError(null);
      announce('Cutover plan seeded');
      qc.invalidateQueries({ queryKey: ['cutover', projectId] });
    },
    onError: (e: any) => setSeedError(e?.message ?? 'Seed failed')
  });

  const [finalizeError, setFinalizeError] = useState<string | null>(null);
  const finalizeM = useMutation({
    mutationFn: () => api.finalizeCutover(projectId),
    onSuccess: () => {
      setFinalizeError(null);
      announce('Migration complete — Stage F finalized');
      qc.invalidateQueries({ queryKey: ['cutover', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    },
    onError: (e: any) => setFinalizeError(e?.message ?? 'Finalize failed')
  });

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [showClosure, setShowClosure] = useState(false);

  // Inject chrome content.
  useEffect(() => {
    if (!status || status.cutovers.length === 0) {
      setStatus([]);
      setActions(null);
      setNotice(null);
      return;
    }
    const c = status.counts;
    setStatus([
      { label: 'planned', value: c.planned, tone: 'neutral' },
      { label: 'shadow',  value: c.shadow,  tone: 'brand' },
      { label: 'canary',  value: c.canary,  tone: 'warn' },
      { label: 'live',    value: c.live,    tone: 'ok' },
      ...(c.decommissioned > 0 ? [{ label: 'decom', value: c.decommissioned, tone: 'agent' as const }] : []),
      ...(c.rolledBack > 0    ? [{ label: 'rolled back', value: c.rolledBack, tone: 'err' as const }] : [])
    ]);
    setActions(
      <>
        <IconButton icon={<RefreshCw size={13} />} ariaLabel="Refresh cutover status" onClick={() => refetch()} />
        <button className="btn-secondary btn-sm" onClick={() => setShowClosure(true)}>
          <FileText size={13} aria-hidden="true" /> Closure doc
        </button>
        <button
          className="btn-secondary btn-sm"
          onClick={() => seedM.mutate()}
          disabled={seedM.isPending || stageFPassed}
          title="Refresh from latest strangler steps (preserves existing rows)"
        >
          <Sparkles size={13} aria-hidden="true" /> {seedM.isPending ? 'Seeding…' : 'Re-seed'}
        </button>
        <button
          className="btn-primary btn-sm"
          onClick={() => finalizeM.mutate()}
          disabled={finalizeM.isPending || !status.readyForGate || stageFPassed || !hasRole('TECH_LEAD')}
          title={
            stageFPassed ? 'Migration complete — Stage F finalized'
            : !hasRole('TECH_LEAD') ? 'Finalizing the migration requires the Tech Lead role'
            : !status.readyForGate ? 'Take every cutover live and complete every checklist to finalize'
                              : 'Finalize Stage F — closes out the migration'
          }
        >
          <Lock size={13} aria-hidden="true" />
          {finalizeM.isPending ? 'Finalizing…' : stageFPassed ? 'Migration complete' : 'Finalize Stage F'}
        </button>
      </>
    );
    const errMsg = seedError ?? finalizeError;
    setNotice(errMsg ? <div role="alert" className="notice-err">{errMsg}</div> : null);
  }, [status, stageFPassed, seedM.isPending, finalizeM.isPending, seedError, finalizeError]); // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <LoadingState />;
  if (error) {
    return <ErrorState
      title="Uplift service unreachable"
      detail="The uplift-service didn't respond."
      onRetry={() => refetch()}
    />;
  }

  const s = status!;
  const empty = s.cutovers.length === 0;
  const selected = s.cutovers.find(c => c.id === selectedId) ?? s.cutovers[0];

  if (empty) {
    return (
      <div className="p-5 space-y-5">
        <BuildTestPanel projectId={projectId} track="UPLIFT" />
        <NotStarted onSeed={() => seedM.mutate()} seeding={seedM.isPending}
                    error={seedError} project={project} />
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <div className="px-5 pt-5">
        <BuildTestPanel projectId={projectId} track="UPLIFT" />
      </div>

      {showClosure && (
        <ClosureDocumentDrawer projectId={projectId} onClose={() => setShowClosure(false)} />
      )}

      <TwoPane
        rail="default"
        left={
          <CutoverListPane
            cutovers={s.cutovers}
            selectedId={selected?.id ?? null}
            onSelect={setSelectedId}
          />
        }
      >
        {selected
          ? <CutoverDetailPane
              key={selected.id}
              projectId={projectId}
              cutover={selected}
              disabled={stageFPassed}
            />
          : <div className="p-8 text-fg-3 text-sm">Select a cutover to plan.</div>}
      </TwoPane>
    </div>
  );
}

/* ---------------- Empty state ---------------- */

function NotStarted({
  onSeed, seeding, error, project
}: { onSeed: () => void; seeding: boolean; error: string | null; project: Project }) {
  return (
    <EmptyState
      icon={Rocket}
      accent="gradient"
      title="Plan the cutover"
      body="Atlas builds a per-module cutover plan with shadow / canary / live / decommissioned states, default rollback notes, and a kind-aware checklist. Once every cutover is live and every checklist item is checked, finalize the migration and download the closure document."
      hint={project.sourcePath
        ? <>Source: <span className="font-mono text-fg-1">{project.sourcePath}</span></>
        : undefined}
      primaryAction={
        <button className="btn-brand" disabled={seeding} onClick={onSeed}>
          <Sparkles size={14} aria-hidden="true" /> {seeding ? 'Seeding…' : 'Seed cutover plan'}
        </button>
      }
    />
  );
}

/* ---------------- Cutover list (left) ---------------- */

function CutoverListPane({
  cutovers, selectedId, onSelect
}: {
  cutovers: Cutover[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  return (
    <>
      <div className="px-4 py-3 border-b border-line">
        <div className="eyebrow">Cutovers</div>
        <div className="mt-1 text-xs text-fg-3">{cutovers.length} module{cutovers.length === 1 ? '' : 's'}</div>
      </div>
      <ul className="flex-1 overflow-y-auto py-1" role="list">
        {cutovers.map(c => {
          const active = c.id === selectedId;
          const checked = c.checklist.filter(i => i.done).length;
          const total = c.checklist.length;
          return (
            <li key={c.id}>
              <button
                onClick={() => onSelect(c.id)}
                className={`w-full text-left px-4 py-3 border-b border-line/60 transition-colors
                  ${active ? 'bg-brand-50 border-l-2 border-l-brand' : 'hover:bg-surface/80'}`}
              >
                <div className="flex items-center gap-2">
                  <StateDot state={c.state} />
                  <span className={`text-sm font-medium ${active ? 'text-brand' : 'text-fg-1'} truncate`}>
                    {c.module?.name ?? '(missing module)'}
                  </span>
                  <StateBadge state={c.state} />
                </div>
                <div className="mt-1 ml-5 font-mono text-2xs text-fg-3 truncate">
                  {c.module?.packageName ?? ''}
                </div>
                <div className="mt-2 ml-5 flex items-center gap-3 text-2xs text-fg-3">
                  <span>traffic <span className="font-mono text-fg-1">{c.trafficPercent}%</span></span>
                  <span>checklist <span className="font-mono text-fg-1">{checked}/{total}</span></span>
                </div>
              </button>
            </li>
          );
        })}
      </ul>
    </>
  );
}

function StateDot({ state }: { state: Cutover['state'] }) {
  const cls =
    state === 'live'           ? 'bg-ok'
  : state === 'canary'         ? 'bg-warn dot-pulse'
  : state === 'shadow'         ? 'bg-brand dot-pulse'
  : state === 'decommissioned' ? 'bg-agent'
  : state === 'rolled_back'    ? 'bg-err'
                               : 'bg-fg-4';
  return <span className={`dot ${cls}`} />;
}

function StateBadge({ state }: { state: Cutover['state'] }) {
  const map: Record<Cutover['state'], string> = {
    planned:        'badge-neutral',
    shadow:         'badge-brand',
    canary:         'badge-warn',
    live:           'badge-ok',
    rolled_back:    'badge-err',
    decommissioned: 'badge-agent'
  };
  const label = state === 'rolled_back' ? 'rolled-back' : state;
  return <span className={`ml-auto ${map[state]} badge-mono`}>{label}</span>;
}

/* ---------------- Detail pane ---------------- */

function CutoverDetailPane({
  projectId, cutover, disabled
}: { projectId: string; cutover: Cutover; disabled: boolean }) {
  const qc = useQueryClient();
  const [draftTraffic, setDraftTraffic] = useState<number | null>(null);
  const traffic = draftTraffic ?? cutover.trafficPercent;
  const trafficDirty = draftTraffic !== null && draftTraffic !== cutover.trafficPercent;

  useEffect(() => { setDraftTraffic(null); }, [cutover.id]);

  const updateM = useMutation({
    mutationFn: (body: { state?: Cutover['state']; trafficPercent?: number; notes?: string }) =>
      api.updateCutover(projectId, cutover.id, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['cutover', projectId] });
      setDraftTraffic(null);
    }
  });

  const checklistM = useMutation({
    mutationFn: ({ itemId, done }: { itemId: string; done: boolean }) =>
      api.toggleCutoverChecklist(projectId, cutover.id, itemId, done),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cutover', projectId] })
  });

  const m = cutover.module;
  const checked = cutover.checklist.filter(i => i.done).length;
  const total = cutover.checklist.length;

  return (
    <div className="flex flex-col min-w-0 overflow-y-auto">
      {/* Header */}
      <div className="px-6 py-5 border-b border-line">
        <div className="flex items-start gap-3">
          <div className="h-9 w-9 rounded-md bg-canvas border border-line flex items-center justify-center text-fg-2">
            <Boxes size={14} />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className="text-lg font-semibold text-fg-1 leading-tight">
                {m?.name ?? 'Missing module'}
              </h3>
              <StateBadge state={cutover.state} />
              {m && (
                <span className="text-2xs text-fg-3 inline-flex items-center gap-1">
                  <Activity size={11} /> difficulty {m.difficulty}/10
                </span>
              )}
            </div>
            <div className="mt-1 font-mono text-2xs text-fg-3 truncate">{m?.packageName ?? '—'}</div>
            {cutover.cutoverDate && (
              <div className="mt-2 inline-flex items-center gap-1 text-2xs text-fg-3">
                <Calendar size={11} /> Cut over at{' '}
                <span className="font-mono">{new Date(cutover.cutoverDate).toLocaleString('en-US', { hour12: false })}</span>
                {cutover.approvedBy && <span> · by <span className="font-mono">{cutover.approvedBy}</span></span>}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* State stepper */}
      <div className="px-6 py-4 border-b border-line bg-surface/40">
        <div className="eyebrow mb-2">State</div>
        <div className="flex items-center gap-1">
          {STATE_FLOW.map((s, i) => {
            const idx = STATE_FLOW.indexOf(cutover.state);
            const here = cutover.state === s;
            const past = idx > i;
            return (
              <button
                key={s}
                disabled={disabled || updateM.isPending || here}
                onClick={() => updateM.mutate({ state: s })}
                className={`btn-sm ${here ? 'btn-primary' : past ? 'btn-secondary' : 'btn-ghost'}`}
                title={`Move to ${s}`}
              >
                {past && <CheckCircle2 size={13} />}
                {here && <Loader2 size={13} className="animate-spin" />}
                {s}
              </button>
            );
          })}
          <span className="mx-2 text-fg-4">·</span>
          <button
            disabled={disabled || updateM.isPending || cutover.state === 'rolled_back' || cutover.state === 'planned'}
            onClick={() => updateM.mutate({ state: 'rolled_back' })}
            className={`btn-sm ${cutover.state === 'rolled_back' ? 'btn-primary' : 'btn-ghost'} text-err`}
            title="Roll back"
          >
            <AlertOctagon size={13} /> Roll back
          </button>
          <button
            disabled={disabled || updateM.isPending || cutover.state !== 'live'}
            onClick={() => updateM.mutate({ state: 'decommissioned' })}
            className={`btn-sm ${cutover.state === 'decommissioned' ? 'btn-primary' : 'btn-secondary'}`}
            title="Decommission legacy module"
          >
            Decommission
          </button>
        </div>
        {disabled && <div className="mt-2 text-2xs text-fg-3">Stage F finalized — read only.</div>}
      </div>

      {/* Traffic + rollback */}
      <div className="grid grid-cols-2 gap-4 px-6 py-4 border-b border-line">
        <div>
          <div className="flex items-center mb-2">
            <div className="eyebrow">Traffic %</div>
            <span className="ml-auto font-mono text-lg font-semibold text-fg-1">{traffic}%</span>
          </div>
          <input
            type="range"
            min={0} max={100} step={1}
            value={traffic}
            disabled={disabled || updateM.isPending}
            onChange={e => setDraftTraffic(parseInt(e.target.value, 10))}
            className="w-full accent-brand"
          />
          <div className="mt-1 flex items-center justify-between text-2xs text-fg-3">
            <span>0% (shadow)</span>
            <span>100% (live)</span>
          </div>
          {trafficDirty && (
            <div className="mt-2 flex items-center gap-2">
              <button
                className="btn-primary btn-sm"
                disabled={updateM.isPending || disabled}
                onClick={() => draftTraffic !== null && updateM.mutate({ trafficPercent: draftTraffic })}
              >
                Save traffic
              </button>
              <button className="btn-ghost btn-sm" onClick={() => setDraftTraffic(null)}>Cancel</button>
            </div>
          )}
        </div>

        <div>
          <div className="eyebrow mb-2">Rollback plan</div>
          <p className="surface p-3 text-xs text-fg-2 leading-relaxed font-mono whitespace-pre-wrap">
            {cutover.rollbackPlan ?? 'No rollback plan recorded.'}
          </p>
        </div>
      </div>

      {/* Checklist */}
      <div className="px-6 py-5">
        <div className="flex items-center mb-3">
          <div className="eyebrow">Decommission checklist</div>
          <span className="ml-auto text-2xs text-fg-3">
            <span className={`font-mono font-semibold ${checked === total ? 'text-ok' : 'text-fg-1'}`}>
              {checked}
            </span> / {total}
          </span>
        </div>
        <ul className="space-y-2">
          {cutover.checklist.map(item => (
            <ChecklistRow key={item.id} item={item}
                          disabled={disabled || checklistM.isPending}
                          onToggle={(d) => checklistM.mutate({ itemId: item.id, done: d })} />
          ))}
        </ul>
      </div>
    </div>
  );
}

function ChecklistRow({ item, disabled, onToggle }: {
  item: CutoverChecklistItem;
  disabled: boolean;
  onToggle: (done: boolean) => void;
}) {
  const catCls: Record<string, string> = {
    test: 'badge-ok', code: 'badge-brand', docs: 'badge-neutral',
    ops: 'badge-warn', infra: 'badge-agent'
  };
  return (
    <li className={`surface p-3 flex items-center gap-3 transition-opacity ${item.done ? 'opacity-70' : ''}`}>
      <input
        type="checkbox"
        className="accent-brand h-4 w-4"
        disabled={disabled}
        checked={item.done}
        onChange={e => onToggle(e.target.checked)}
      />
      <div className="min-w-0 flex-1">
        <div className={`text-sm ${item.done ? 'text-fg-3 line-through' : 'text-fg-1'}`}>
          {item.text}
        </div>
      </div>
      <span className={`badge-mono ${catCls[item.category] ?? 'badge-neutral'}`}>{item.category}</span>
    </li>
  );
}

/* ---------------- Closure document drawer ---------------- */

function ClosureDocumentDrawer({
  projectId, onClose
}: { projectId: string; onClose: () => void }) {
  const closureQ = useQuery({
    queryKey: ['cutover', projectId, 'closure'],
    queryFn: () => api.cutoverClosureMarkdown(projectId)
  });

  return (
    <section className="card mt-4 px-6 py-4" aria-label="Closure document">
      <div className="flex items-center mb-2">
        <div className="eyebrow">Closure document</div>
        <a
          href={api.cutoverClosureUrl(projectId)}
          download="closure.md"
          className="ml-auto btn-secondary btn-sm"
        >
          <FileDown size={13} aria-hidden="true" /> Download .md
        </a>
        <button onClick={onClose} className="btn-ghost btn-sm ml-1" aria-label="Close closure preview">
          Close
        </button>
      </div>
      <div className="surface p-4 max-h-[480px] overflow-auto">
        {closureQ.isLoading
          ? <div className="text-fg-3 text-sm">Generating…</div>
          : closureQ.error
            ? <ErrorState title="Failed to load closure document" onRetry={() => closureQ.refetch()} />
            : <MarkdownView body={closureQ.data ?? ''} />}
      </div>
    </section>
  );
}
