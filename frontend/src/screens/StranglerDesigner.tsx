import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Network, Sparkles, RefreshCw, ChevronUp, ChevronDown,
  Trash2, Lock, Save, ArrowRight, ChefHat, Boxes, FileCode, Activity
} from 'lucide-react';
import { api, Project, StranglerStep } from '../api/client';
import { hasRole } from '../auth/authClient';
import {
  TwoPane, EmptyState, LoadingState, ErrorState,
  IconButton, useStageChrome, useAnnouncer
} from '../components/ui';

export default function StranglerDesigner({
  projectId, project
}: { projectId: string; project: Project }) {
  const qc = useQueryClient();
  const { setStatus, setActions, setNotice } = useStageChrome();
  const { announce } = useAnnouncer();

  const { data: status, isLoading, error, refetch } = useQuery({
    queryKey: ['strangler', projectId],
    queryFn: () => api.stranglerStatus(projectId),
    refetchInterval: 10000
  });

  const projectQ = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.project(projectId)
  });
  const stageCPassed = (projectQ.data as any)?.gates?.find((g: any) => g.label === 'C')?.state === 'passed';

  const [seedError, setSeedError] = useState<string | null>(null);
  const seedM = useMutation({
    mutationFn: () => api.seedStrangler(projectId),
    onSuccess: () => {
      setSeedError(null);
      announce('Strangler plan seeded');
      qc.invalidateQueries({ queryKey: ['strangler', projectId] });
    },
    onError: (e: any) => setSeedError(e?.message ?? 'Seed failed')
  });

  const [finalizeError, setFinalizeError] = useState<string | null>(null);
  const finalizeM = useMutation({
    mutationFn: () => api.finalizeStrangler(projectId),
    onSuccess: () => {
      setFinalizeError(null);
      announce('Stage C finalized — advancing to Module Migration');
      qc.invalidateQueries({ queryKey: ['strangler', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    },
    onError: (e: any) => setFinalizeError(e?.message ?? 'Finalize failed')
  });

  const [selectedId, setSelectedId] = useState<string | null>(null);

  // Inject chrome content.
  useEffect(() => {
    if (!status || status.steps.length === 0) {
      setStatus([]);
      setActions(null);
      setNotice(null);
      return;
    }
    setStatus([
      { label: 'planned',   value: status.counts.planned,   tone: 'brand' },
      { label: 'ready',     value: status.counts.ready,     tone: 'ok' },
      { label: 'extracted', value: status.counts.extracted, tone: 'ok' }   // C5 fix: terminal good state is ok, not agent
    ]);
    setActions(
      <>
        <IconButton icon={<RefreshCw size={13} />} ariaLabel="Refresh plan" onClick={() => refetch()} />
        <button
          className="btn-secondary btn-sm"
          onClick={() => seedM.mutate()}
          disabled={seedM.isPending || stageCPassed}
          title="Refresh plan from accepted recipes (preserves existing order/notes)"
        >
          <Sparkles size={13} aria-hidden="true" /> {seedM.isPending ? 'Seeding…' : 'Re-seed'}
        </button>
        <button
          className="btn-primary btn-sm"
          onClick={() => finalizeM.mutate()}
          disabled={finalizeM.isPending || !status.readyForGate || stageCPassed || !hasRole('TECH_LEAD')}
          title={
            stageCPassed ? 'Stage C already finalized'
            : !hasRole('TECH_LEAD') ? 'Finalizing a stage requires the Tech Lead role'
            : !status.readyForGate ? 'Mark at least one step as ready to finalize'
                                  : 'Finalize Stage C and advance to Module Migration'
          }
        >
          <Lock size={13} aria-hidden="true" />
          {finalizeM.isPending ? 'Finalizing…' : stageCPassed ? 'Finalized' : 'Finalize Stage C'}
        </button>
      </>
    );
    if (finalizeError) {
      setNotice(<div role="alert" className="notice-err">{finalizeError}</div>);
    } else {
      setNotice(null);
    }
  }, [status, stageCPassed, seedM.isPending, finalizeM.isPending, finalizeError]); // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <LoadingState />;
  if (error) {
    return <ErrorState
      title="Uplift service unreachable"
      detail="The uplift-service didn't respond."
      onRetry={() => refetch()}
    />;
  }

  const s = status!;
  const empty = s.steps.length === 0;
  const selected = s.steps.find(st => st.id === selectedId) ?? s.steps[0];

  if (empty) {
    return <NotStarted onSeed={() => seedM.mutate()} seeding={seedM.isPending}
                       error={seedError} project={project} />;
  }

  return (
    <TwoPane
      rail="default"
      left={
        <PlanList
          steps={s.steps}
          selectedId={selected?.id ?? null}
          onSelect={setSelectedId}
          projectId={projectId}
          disabled={stageCPassed}
        />
      }
    >
      {selected
        ? <StepDetail
            key={selected.id}
            projectId={projectId}
            step={selected}
            disabled={stageCPassed}
            isFirst={selected.sequenceNo === 1}
            isLast={selected.sequenceNo === s.steps.length}
          />
        : <div className="p-8 text-fg-3 text-sm">Select a step to review.</div>}
    </TwoPane>
  );
}

/* ---------------- Empty state ---------------- */

function NotStarted({
  onSeed, seeding, error, project
}: { onSeed: () => void; seeding: boolean; error: string | null; project: Project }) {
  return (
    <EmptyState
      icon={Network}
      accent="gradient"
      title="Design your strangler plan"
      body="Atlas builds an ordered migration plan from the recipes you accepted in Stage B — one step per module, hardest-first. Reorder, mark steps ready, and capture façade routing notes for each extraction."
      hint={project.sourcePath
        ? <>Source: <span className="font-mono text-fg-1">{project.sourcePath}</span></>
        : undefined}
      primaryAction={
        <button className="btn-brand" disabled={seeding} onClick={onSeed}>
          <Sparkles size={14} aria-hidden="true" /> {seeding ? 'Seeding…' : 'Seed plan from recipes'}
        </button>
      }
    />
  );
}

/* ---------------- Plan list (left) ---------------- */

function PlanList({
  steps, selectedId, onSelect, projectId, disabled
}: {
  steps: StranglerStep[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  projectId: string;
  disabled: boolean;
}) {
  const qc = useQueryClient();
  const reorderM = useMutation({
    mutationFn: ({ stepId, delta }: { stepId: string; delta: number }) =>
      api.reorderStranglerStep(projectId, stepId, delta),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['strangler', projectId] })
  });

  return (
    <>
      <div className="px-4 py-3 border-b border-line flex items-center">
        <div>
          <div className="eyebrow">Migration plan</div>
          <div className="mt-1 text-xs text-fg-3">{steps.length} step{steps.length === 1 ? '' : 's'} · top-to-bottom order</div>
        </div>
      </div>
      <ul className="flex-1 overflow-y-auto py-1" role="list">
        {steps.map((s, i) => {
          const active = s.id === selectedId;
          return (
            <li key={s.id}>
              <div
                className={`flex items-stretch border-b border-line/60 transition-colors
                  ${active ? 'bg-brand-50 border-l-2 border-l-brand' : 'hover:bg-surface/80'}`}
              >
                <div className="flex flex-col justify-center items-center w-9 border-r border-line/40">
                  <button
                    type="button"
                    aria-label="Move step up"
                    disabled={disabled || i === 0 || reorderM.isPending}
                    onClick={() => reorderM.mutate({ stepId: s.id, delta: -1 })}
                    className="p-0.5 text-fg-4 hover:text-fg-1 disabled:opacity-30 disabled:cursor-not-allowed"
                  >
                    <ChevronUp size={14} />
                  </button>
                  <span className="font-mono text-2xs text-fg-3 my-0.5">{s.sequenceNo}</span>
                  <button
                    type="button"
                    aria-label="Move step down"
                    disabled={disabled || i === steps.length - 1 || reorderM.isPending}
                    onClick={() => reorderM.mutate({ stepId: s.id, delta: 1 })}
                    className="p-0.5 text-fg-4 hover:text-fg-1 disabled:opacity-30 disabled:cursor-not-allowed"
                  >
                    <ChevronDown size={14} />
                  </button>
                </div>

                <button
                  onClick={() => onSelect(s.id)}
                  className="flex-1 text-left px-3 py-2.5 min-w-0"
                >
                  <div className="flex items-center gap-2">
                    <StatusDot status={s.status} />
                    <span className={`text-sm font-medium ${active ? 'text-brand' : 'text-fg-1'} truncate`}>
                      {s.module?.name ?? '— missing module'}
                    </span>
                    {s.module && (
                      <span className="ml-auto inline-flex items-center gap-1 text-2xs text-fg-3">
                        <Activity size={11} className="text-fg-4" /> {s.module.difficulty}
                      </span>
                    )}
                  </div>
                  <div className="mt-1 text-2xs font-mono text-fg-3 truncate">
                    {s.module?.packageName ?? ''}
                  </div>
                  <div className="mt-1 flex items-center gap-3 text-2xs text-fg-3">
                    <span className="inline-flex items-center gap-1">
                      <ChefHat size={11} /> {s.recipes.length} recipe{s.recipes.length === 1 ? '' : 's'}
                    </span>
                    {s.module && (
                      <span className="inline-flex items-center gap-1">
                        <FileCode size={11} /> {s.module.fileCount} files
                      </span>
                    )}
                    <StatusBadge status={s.status} />
                  </div>
                </button>
              </div>
            </li>
          );
        })}
      </ul>
    </>
  );
}

function StatusDot({ status }: { status: StranglerStep['status'] }) {
  const cls =
    status === 'extracted' ? 'bg-ok'
  : status === 'ready'     ? 'bg-ok'
                           : 'bg-brand dot-pulse';
  return <span className={`dot ${cls}`} aria-hidden="true" />;
}

function StatusBadge({ status }: { status: StranglerStep['status'] }) {
  // C5 fix: 'extracted' is the terminal good state — use ok, not agent.
  if (status === 'extracted') return <span className="badge-ok badge-mono">extracted</span>;
  if (status === 'ready')     return <span className="badge-ok badge-mono">ready</span>;
  return <span className="badge-brand badge-mono">planned</span>;
}

/* ---------------- Step detail (right) ---------------- */

function StepDetail({
  projectId, step, disabled, isFirst, isLast
}: {
  projectId: string;
  step: StranglerStep;
  disabled: boolean;
  isFirst: boolean;
  isLast: boolean;
}) {
  const qc = useQueryClient();
  const [notes, setNotes] = useState(step.facadeNotes ?? '');
  const [notesDirty, setNotesDirty] = useState(false);

  // When the user clicks a different step, reset the notes editor.
  useEffect(() => {
    setNotes(step.facadeNotes ?? '');
    setNotesDirty(false);
  }, [step.id]);

  const updateM = useMutation({
    mutationFn: (body: { status?: StranglerStep['status']; facadeNotes?: string }) =>
      api.updateStranglerStep(projectId, step.id, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['strangler', projectId] });
      setNotesDirty(false);
    }
  });

  const reorderM = useMutation({
    mutationFn: (delta: number) => api.reorderStranglerStep(projectId, step.id, delta),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['strangler', projectId] })
  });

  const deleteM = useMutation({
    mutationFn: () => api.deleteStranglerStep(projectId, step.id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['strangler', projectId] })
  });

  const m = step.module;

  return (
    <div className="flex flex-col min-w-0">
      {/* Header */}
      <div className="px-6 py-5 border-b border-line">
        <div className="flex items-start gap-3">
          <div className="h-9 w-9 rounded-md bg-canvas border border-line flex items-center justify-center text-fg-2 font-mono text-sm font-semibold">
            {step.sequenceNo}
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className="text-lg font-semibold text-fg-1 leading-tight">
                {m?.name ?? 'Missing module'}
              </h3>
              <StatusBadge status={step.status} />
              {m && (
                <span className="text-2xs text-fg-3 inline-flex items-center gap-1">
                  <Activity size={11} /> difficulty {m.difficulty}/10
                </span>
              )}
            </div>
            <div className="mt-1 font-mono text-2xs text-fg-3 truncate">{m?.packageName ?? '—'}</div>
            {m && (
              <div className="mt-2 flex items-center gap-4 text-2xs text-fg-3">
                <span><FileCode size={11} className="inline mr-1" />{m.fileCount} files · {m.loc.toLocaleString()} LOC</span>
                <span>{m.findingCount} findings</span>
              </div>
            )}
          </div>

          <div className="flex items-center gap-1">
            <button
              className="btn-ghost btn-sm"
              disabled={disabled || isFirst || reorderM.isPending}
              onClick={() => reorderM.mutate(-1)}
              title="Move earlier"
            >
              <ChevronUp size={13} />
            </button>
            <button
              className="btn-ghost btn-sm"
              disabled={disabled || isLast || reorderM.isPending}
              onClick={() => reorderM.mutate(1)}
              title="Move later"
            >
              <ChevronDown size={13} />
            </button>
            <button
              className="btn-ghost btn-sm text-err"
              disabled={disabled || deleteM.isPending}
              onClick={() => deleteM.mutate()}
              title="Remove from plan"
            >
              <Trash2 size={13} />
            </button>
          </div>
        </div>

        {step.decidedBy && step.decidedAt && (
          <div className="mt-2 text-2xs text-fg-3">
            last touched by <span className="font-mono">{step.decidedBy}</span>{' '}
            {new Date(step.decidedAt).toLocaleString('en-US', { hour12: false })}
          </div>
        )}
      </div>

      {/* Status switcher */}
      <div className="px-6 py-4 border-b border-line bg-surface/40 flex items-center gap-2">
        {(['planned', 'ready', 'extracted'] as const).map(s => (
          <button
            key={s}
            disabled={disabled || updateM.isPending || step.status === s}
            onClick={() => updateM.mutate({ status: s, facadeNotes: notesDirty ? notes : undefined })}
            className={`btn-sm ${step.status === s ? 'btn-primary' : 'btn-secondary'}`}
          >
            {s === 'planned'   && <Network size={13} />}
            {s === 'ready'     && <ArrowRight size={13} />}
            {s === 'extracted' && <Boxes size={13} />}
            {s.charAt(0).toUpperCase() + s.slice(1)}
          </button>
        ))}
        {disabled && <span className="text-2xs text-fg-3 ml-2">Stage C finalized — read only.</span>}
      </div>

      <div className="p-6 space-y-6">
        {/* Façade notes */}
        <div>
          <div className="flex items-center justify-between mb-2">
            <div className="eyebrow">Façade notes</div>
            {notesDirty && (
              <button
                className="btn-secondary btn-sm"
                disabled={disabled || updateM.isPending}
                onClick={() => updateM.mutate({ facadeNotes: notes })}
              >
                <Save size={12} /> Save notes
              </button>
            )}
          </div>
          <textarea
            className="input w-full min-h-[100px] font-mono text-sm"
            placeholder="How will the façade route between legacy and new? E.g. 'Routes /api/orders/* via OrderFacade; reads from new module, writes to both during shadow window.'"
            value={notes}
            disabled={disabled}
            onChange={e => { setNotes(e.target.value); setNotesDirty(true); }}
          />
        </div>

        {/* Recipes applied */}
        <div>
          <div className="eyebrow mb-2">Recipes that apply to this module</div>
          {step.recipes.length === 0 ? (
            <div className="text-sm text-fg-3">No recipes attached.</div>
          ) : (
            <ul className="space-y-2">
              {step.recipes.map(r => (
                <li key={r.id} className="surface p-3 flex items-center gap-3">
                  <ChefHat size={14} className="text-agent" />
                  <div className="min-w-0 flex-1">
                    <div className="text-sm font-medium text-fg-1">{r.label}</div>
                    <div className="font-mono text-2xs text-fg-3 truncate">{r.recipeId}</div>
                  </div>
                  <span className={`badge-mono ${r.kind === 'custom' ? 'badge-agent' : 'badge-neutral'}`}>
                    {r.kind}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
