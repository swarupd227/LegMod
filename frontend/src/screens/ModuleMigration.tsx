import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Wand2, Play, RefreshCw, Lock, FileCode, ChefHat,
  CheckCircle2, XCircle, Loader2, Activity
} from 'lucide-react';
import {
  api, Project, StranglerStep, MigrationChange
} from '../api/client';
import { hasRole } from '../auth/authClient';
import {
  TwoPane, EmptyState, LoadingState, ErrorState, DiffViewer,
  IconButton, useStageChrome, useAnnouncer
} from '../components/ui';

export default function ModuleMigration({
  projectId, project: _project
}: { projectId: string; project: Project }) {
  const qc = useQueryClient();
  const { setStatus, setActions, setNotice } = useStageChrome();
  const { announce } = useAnnouncer();

  // Strangler plan from Stage C drives the list of migrations.
  const stranglerQ = useQuery({
    queryKey: ['strangler', projectId],
    queryFn: () => api.stranglerStatus(projectId),
    refetchInterval: 12000
  });

  // Aggregate migration status (counts + readyForGate).
  const statusQ = useQuery({
    queryKey: ['migration', projectId, 'status'],
    queryFn: () => api.migrationStatus(projectId),
    refetchInterval: 12000
  });

  const projectQ = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.project(projectId)
  });
  const stageDPassed = (projectQ.data as any)?.gates?.find((g: any) => g.label === 'D')?.state === 'passed';

  const [finalizeError, setFinalizeError] = useState<string | null>(null);
  const finalizeM = useMutation({
    mutationFn: () => api.finalizeMigration(projectId),
    onSuccess: () => {
      setFinalizeError(null);
      announce('Stage D finalized — advancing to Characterization Validation');
      qc.invalidateQueries({ queryKey: ['migration', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    },
    onError: (e: any) => setFinalizeError(e?.message ?? 'Finalize failed')
  });

  const [selectedStepId, setSelectedStepId] = useState<string | null>(null);

  // Inject chrome content.
  useEffect(() => {
    if (!statusQ.data) {
      setStatus([]);
      setActions(null);
      setNotice(null);
      return;
    }
    const c = statusQ.data.counts;
    setStatus([
      { label: 'completed', value: c.completed, tone: 'ok' },
      { label: 'failed',    value: c.failed,    tone: 'err' },
      { label: 'running',   value: c.running,   tone: 'brand' }
    ]);
    setActions(
      <>
        <IconButton icon={<RefreshCw size={13} />} ariaLabel="Refresh migration status"
          onClick={() => { stranglerQ.refetch(); statusQ.refetch(); }} />
        <button
          className="btn-primary btn-sm"
          onClick={() => finalizeM.mutate()}
          disabled={finalizeM.isPending || !statusQ.data.readyForGate || stageDPassed || !hasRole('TECH_LEAD')}
          title={
            stageDPassed ? 'Stage D already finalized'
            : !hasRole('TECH_LEAD') ? 'Finalizing a stage requires the Tech Lead role'
            : !statusQ.data.readyForGate ? 'Run at least one migration to finalize'
                                         : 'Finalize Stage D and advance to Characterization Validation'
          }
        >
          <Lock size={13} aria-hidden="true" />
          {finalizeM.isPending ? 'Finalizing…' : stageDPassed ? 'Finalized' : 'Finalize Stage D'}
        </button>
      </>
    );
    setNotice(finalizeError
      ? <div role="alert" className="notice-err">{finalizeError}</div>
      : null);
  }, [statusQ.data, stageDPassed, finalizeM.isPending, finalizeError]); // eslint-disable-line react-hooks/exhaustive-deps

  if (stranglerQ.isLoading || statusQ.isLoading) return <LoadingState />;
  if (stranglerQ.error || statusQ.error) {
    return <ErrorState
      title="Uplift service unreachable"
      detail="The uplift-service didn't respond."
      onRetry={() => { stranglerQ.refetch(); statusQ.refetch(); }}
    />;
  }

  const steps = stranglerQ.data?.steps ?? [];
  const status = statusQ.data!;

  if (steps.length === 0) {
    return (
      <EmptyState
        icon={Wand2}
        title="No strangler plan yet"
        body="Module Migration runs against the steps you sequenced in Stage C. Return to Strangler Designer, seed a plan from your accepted recipes, then come back to start migrating."
      />
    );
  }

  const selectedStep = steps.find(s => s.id === selectedStepId) ?? steps[0];

  return (
    <TwoPane
      rail="default"
      left={
        <StepListPane
          projectId={projectId}
          steps={steps}
          selectedId={selectedStep?.id ?? null}
          onSelect={setSelectedStepId}
        />
      }
    >
      {selectedStep
        ? <RunDetailPane
            key={selectedStep.id}
            projectId={projectId}
            step={selectedStep}
            disabled={stageDPassed}
          />
        : <div className="p-8 text-fg-3 text-sm">Select a step to migrate.</div>}
    </TwoPane>
  );
}

/* ---------------- Counts ---------------- */

/* ---------------- Step list (left) ---------------- */

function StepListPane({
  projectId, steps, selectedId, onSelect
}: {
  projectId: string;
  steps: StranglerStep[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  return (
    <>
      <div className="px-4 py-3 border-b border-line">
        <div className="eyebrow">Migration plan</div>
        <div className="mt-1 text-xs text-fg-3">{steps.length} step{steps.length === 1 ? '' : 's'}</div>
      </div>
      <ul className="flex-1 overflow-y-auto py-1" role="list">
        {steps.map(s => (
          <StepRow key={s.id} projectId={projectId} step={s}
                   active={s.id === selectedId} onSelect={() => onSelect(s.id)} />
        ))}
      </ul>
    </>
  );
}

function StepRow({
  projectId, step, active, onSelect
}: {
  projectId: string;
  step: StranglerStep;
  active: boolean;
  onSelect: () => void;
}) {
  const runQ = useQuery({
    queryKey: ['migration', projectId, 'step', step.id],
    queryFn: () => api.migrationForStep(projectId, step.id),
    refetchInterval: 15000
  });
  const r = runQ.data?.run ?? null;
  const summary = parseSummary(r?.summary);

  return (
    <li>
      <button
        onClick={onSelect}
        className={`w-full text-left px-4 py-2.5 border-b border-line/60 transition-colors
          ${active ? 'bg-brand-50 border-l-2 border-l-brand' : 'hover:bg-surface/80'}`}
      >
        <div className="flex items-center gap-2">
          <span className="font-mono text-2xs text-fg-3 w-5">{step.sequenceNo}</span>
          <RunStatusIcon run={r} />
          <span className={`text-sm font-medium ${active ? 'text-brand' : 'text-fg-1'} truncate`}>
            {step.module?.name ?? '— missing module'}
          </span>
        </div>
        <div className="mt-1 ml-7 text-2xs font-mono text-fg-3 truncate">
          {step.module?.packageName ?? ''}
        </div>
        <div className="mt-1 ml-7 flex items-center gap-3 text-2xs text-fg-3">
          <span className="inline-flex items-center gap-1">
            <ChefHat size={11} /> {step.recipes.length}
          </span>
          {r ? (
            <span className="inline-flex items-center gap-1">
              <FileCode size={11} /> {summary.filesChanged ?? 0}/{summary.filesScanned ?? 0}
            </span>
          ) : (
            <span className="text-fg-4">not migrated</span>
          )}
          {r && summary.totalChanges != null && (
            <span className="text-fg-3">{summary.totalChanges} edits</span>
          )}
        </div>
      </button>
    </li>
  );
}

function RunStatusIcon({ run }: { run: { status: string } | null }) {
  if (!run) return <span className="dot bg-fg-4" />;
  if (run.status === 'completed') return <CheckCircle2 size={14} className="text-ok" />;
  if (run.status === 'running')   return <Loader2 size={14} className="text-brand animate-spin" />;
  if (run.status === 'failed')    return <XCircle size={14} className="text-err" />;
  return <span className="dot bg-fg-4" />;
}

/* ---------------- Run detail (right) ---------------- */

function RunDetailPane({
  projectId, step, disabled
}: {
  projectId: string;
  step: StranglerStep;
  disabled: boolean;
}) {
  const qc = useQueryClient();
  const runQ = useQuery({
    queryKey: ['migration', projectId, 'step', step.id],
    queryFn: () => api.migrationForStep(projectId, step.id),
    refetchInterval: 8000
  });

  const runM = useMutation({
    mutationFn: () => api.runMigration(projectId, step.id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['migration', projectId] });
      qc.invalidateQueries({ queryKey: ['strangler', projectId] });
    }
  });

  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  // Reset selection when the step changes.
  useEffect(() => { setSelectedFile(null); }, [step.id]);

  const result = runQ.data;
  const r = result?.run ?? null;
  const changes = result?.changes ?? [];
  const summary = parseSummary(r?.summary);
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
              {r && <RunStatusBadge status={r.status} />}
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
                <span>{step.recipes.length} recipe{step.recipes.length === 1 ? '' : 's'} attached</span>
              </div>
            )}
          </div>

          <button
            className="btn-primary btn-sm"
            disabled={disabled || runM.isPending || r?.status === 'running'}
            onClick={() => runM.mutate()}
          >
            {runM.isPending || r?.status === 'running'
              ? <><Loader2 size={13} className="animate-spin" /> Running…</>
              : <><Play size={13} /> {r ? 'Re-run migration' : 'Run migration'}</>}
          </button>
        </div>

        {r?.errorText && (
          <div className="mt-3 px-3 py-2 rounded-md bg-err-50 text-err text-xs">
            <strong>Failed:</strong> {r.errorText}
          </div>
        )}
      </div>

      {/* Summary strip */}
      {r && (
        <div className="grid grid-cols-4 border-b border-line">
          <SummaryStat label="Files scanned"  value={summary.filesScanned ?? 0} accent="fg" />
          <SummaryStat label="Files changed"  value={summary.filesChanged ?? 0} accent="brand" />
          <SummaryStat label="Total edits"    value={summary.totalChanges ?? 0} accent="ok" />
          <SummaryStat label="Recipes applied" value={summary.recipesApplied ?? step.recipes.length} accent="agent" />
        </div>
      )}

      {/* Files + diff */}
      <div className="grid grid-cols-[360px_1fr] flex-1 min-h-[420px]">
        <div className="border-r border-line bg-canvas/30">
          <div className="px-4 py-2 border-b border-line text-2xs uppercase tracking-wider text-fg-3 font-mono">
            Changed files
          </div>
          {!r ? (
            <div className="p-4 text-sm text-fg-3">
              Click <span className="font-mono">Run migration</span> to apply{' '}
              {step.recipes.length} recipe{step.recipes.length === 1 ? '' : 's'} to{' '}
              <span className="font-mono">{m?.packageName}</span>.
            </div>
          ) : changes.length === 0 ? (
            <div className="p-4 text-sm text-fg-3">No files changed.</div>
          ) : (
            <ul className="overflow-y-auto">
              {changes.map(c => (
                <FileRow key={c.id} change={c}
                         active={c.filePath === selectedFile}
                         onSelect={() => setSelectedFile(c.filePath)} />
              ))}
            </ul>
          )}
        </div>

        <div className="min-w-0 overflow-x-auto">
          {r && selectedFile
            ? <FileDiffPane
                projectId={projectId}
                runId={r.id}
                filePath={selectedFile}
                changes={changes}
              />
            : r && changes.length > 0
              ? <div className="p-6 text-sm text-fg-3">Pick a file on the left to see its diff.</div>
              : <div className="p-6 text-sm text-fg-3">Run the migration to see file diffs.</div>}
        </div>
      </div>
    </div>
  );
}

function FileRow({
  change, active, onSelect
}: { change: MigrationChange; active: boolean; onSelect: () => void }) {
  const fileName = change.filePath.split('/').pop() ?? change.filePath;
  const dir = change.filePath.substring(0, change.filePath.length - fileName.length);
  return (
    <li>
      <button
        onClick={onSelect}
        className={`w-full text-left px-4 py-2 border-b border-line/40 transition-colors
          ${active ? 'bg-brand-50 border-l-2 border-l-brand' : 'hover:bg-surface/80'}`}
      >
        <div className="flex items-center gap-2">
          <FileCode size={13} className="text-fg-4" />
          <span className={`text-sm truncate ${active ? 'text-brand font-semibold' : 'text-fg-1'}`}>
            {fileName}
          </span>
          <span className="ml-auto badge-mono badge-brand">{change.changes}</span>
        </div>
        <div className="mt-0.5 ml-5 font-mono text-2xs text-fg-3 truncate" title={dir}>
          {dir}
        </div>
        <div className="mt-0.5 ml-5 font-mono text-2xs text-fg-4 truncate" title={change.recipeId}>
          {shortRecipe(change.recipeId)}
        </div>
      </button>
    </li>
  );
}

function shortRecipe(id: string) {
  const idx = id.lastIndexOf('.');
  return idx > 0 ? id.substring(idx + 1) : id;
}

function SummaryStat({
  label, value, accent
}: { label: string; value: number; accent: 'brand' | 'ok' | 'agent' | 'fg' }) {
  const cls =
    accent === 'brand' ? 'text-brand'
  : accent === 'ok'    ? 'text-ok'
  : accent === 'agent' ? 'text-agent'
                       : 'text-fg-1';
  return (
    <div className="px-5 py-3 border-r border-line last:border-0">
      <div className="eyebrow">{label}</div>
      <div className={`mt-0.5 text-2xl font-semibold tracking-tight ${cls}`}>
        {value.toLocaleString()}
      </div>
    </div>
  );
}

function RunStatusBadge({ status }: { status: string }) {
  if (status === 'completed') return <span className="badge-ok badge-mono">completed</span>;
  if (status === 'running')   return <span className="badge-brand badge-mono">running</span>;
  if (status === 'failed')    return <span className="badge-err badge-mono">failed</span>;
  return <span className="badge-neutral badge-mono">{status}</span>;
}

/* ---------------- Diff viewer ---------------- */

function FileDiffPane({
  projectId, runId, filePath, changes
}: {
  projectId: string;
  runId: string;
  filePath: string;
  changes: MigrationChange[];
}) {
  const diffQ = useQuery({
    queryKey: ['migration', projectId, runId, filePath, 'diff'],
    queryFn: () => api.migrationDiff(projectId, runId, filePath)
  });

  if (diffQ.isLoading) return <div className="p-6 text-fg-3 text-sm">Loading diff…</div>;
  if (diffQ.error)     return <ErrorState title="Diff fetch failed" detail="Try selecting a different file." />;

  const meta = changes.find(c => c.filePath === filePath);
  const diff = diffQ.data?.diff ?? '';

  return (
    <div className="flex flex-col h-full min-h-0">
      <div className="px-4 py-2 border-b border-line bg-surface/40 flex items-center gap-3 text-xs flex-wrap">
        <span className="font-mono text-fg-1 truncate min-w-0">{filePath}</span>
        {meta && <span className="badge-brand badge-mono">{meta.changes} edits</span>}
        {meta && <span className="text-fg-3 font-mono truncate min-w-0">{meta.recipeId}</span>}
      </div>
      <div className="flex-1 min-h-0">
        <DiffViewer unifiedDiff={diff} ariaLabel={`Diff for ${filePath}`} />
      </div>
    </div>
  );
}

/* ---------------- helpers ---------------- */

function parseSummary(json?: string): {
  filesScanned?: number;
  filesChanged?: number;
  totalChanges?: number;
  recipesApplied?: number;
  byRecipe?: Record<string, number>;
} {
  if (!json) return {};
  try { return JSON.parse(json); } catch { return {}; }
}
