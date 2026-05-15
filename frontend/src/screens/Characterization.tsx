import { useEffect, useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ShieldCheck, Play, RefreshCw, Lock,
  CheckCircle2, XCircle, Wrench, Sparkles, Activity
} from 'lucide-react';
import { api, Project, CharCase } from '../api/client';
import { hasRole } from '../auth/authClient';
import {
  TwoPane, EmptyState, LoadingState, ErrorState,
  IconButton, useStageChrome, useAnnouncer
} from '../components/ui';

type Bucket = 'pass' | 'benign' | 'regression';

export default function Characterization({
  projectId, project
}: { projectId: string; project: Project }) {
  const qc = useQueryClient();
  const { setStatus, setActions, setNotice } = useStageChrome();
  const { announce } = useAnnouncer();

  const { data: status, isLoading, error, refetch } = useQuery({
    queryKey: ['characterize', projectId],
    queryFn: () => api.characterizeStatus(projectId),
    refetchInterval: 12000
  });

  const projectQ = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.project(projectId)
  });
  const stageEPassed = (projectQ.data as any)?.gates?.find((g: any) => g.label === 'E')?.state === 'passed';

  const [runError, setRunError] = useState<string | null>(null);
  const runM = useMutation({
    mutationFn: (casesPerModule: number) => api.runCharacterization(projectId, casesPerModule),
    onSuccess: (r) => {
      setRunError(null);
      announce(`Characterization complete · ${r.passCount} pass · ${r.benignCount} benign · ${r.regressionCount} regression`);
      qc.invalidateQueries({ queryKey: ['characterize', projectId] });
    },
    onError: (e: any) => setRunError(e?.message ?? 'Run failed')
  });

  const [finalizeError, setFinalizeError] = useState<string | null>(null);
  const finalizeM = useMutation({
    mutationFn: () => api.finalizeCharacterization(projectId),
    onSuccess: () => {
      setFinalizeError(null);
      announce('Stage E finalized — advancing to Cutover & Decommission');
      qc.invalidateQueries({ queryKey: ['characterize', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    },
    onError: (e: any) => setFinalizeError(e?.message ?? 'Finalize failed')
  });

  const [bucketFilter, setBucketFilter] = useState<'all' | Bucket>('regression');
  const [moduleFilter, setModuleFilter] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const filteredCases = useMemo(() => {
    if (!status?.cases) return [];
    return status.cases.filter(c => {
      if (bucketFilter !== 'all' && c.bucket !== bucketFilter) return false;
      if (moduleFilter && c.moduleId !== moduleFilter) return false;
      return true;
    });
  }, [status?.cases, bucketFilter, moduleFilter]);

  // Inject chrome content.
  useEffect(() => {
    if (!status?.run) {
      setStatus([]);
      setActions(null);
      setNotice(null);
      return;
    }
    const r = status.run;
    setStatus([
      { label: 'pass',       value: r.passCount,       tone: 'ok' },
      { label: 'benign',     value: r.benignCount,     tone: 'warn' },
      { label: 'regression', value: r.regressionCount, tone: 'err',
        hint: status.openRegressions > 0 ? `${status.openRegressions} still open` : undefined }
    ]);
    setActions(
      <>
        <IconButton icon={<RefreshCw size={13} />} ariaLabel="Refresh characterization" onClick={() => refetch()} />
        <button
          className="btn-secondary btn-sm"
          onClick={() => runM.mutate(30)}
          disabled={runM.isPending || stageEPassed}
          title="Re-run characterization tests against the latest migrated modules"
        >
          <Sparkles size={13} aria-hidden="true" /> {runM.isPending ? 'Running…' : 'Re-run'}
        </button>
        <button
          className="btn-primary btn-sm"
          onClick={() => finalizeM.mutate()}
          disabled={finalizeM.isPending || !status.readyForGate || stageEPassed || !hasRole('TECH_LEAD')}
          title={
            stageEPassed ? 'Stage E already finalized'
            : !hasRole('TECH_LEAD') ? 'Finalizing a stage requires the Tech Lead role'
            : !status.readyForGate ? 'Resolve all open regressions to finalize'
                              : 'Finalize Stage E and advance to Cutover & Decommission'
          }
        >
          <Lock size={13} aria-hidden="true" />
          {finalizeM.isPending ? 'Finalizing…' : stageEPassed ? 'Finalized' : 'Finalize Stage E'}
        </button>
      </>
    );
    const errMsg = runError ?? finalizeError;
    setNotice(errMsg ? <div role="alert" className="notice-err">{errMsg}</div> : null);
  }, [status, stageEPassed, runM.isPending, finalizeM.isPending, runError, finalizeError]); // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <LoadingState />;
  if (error) {
    return <ErrorState
      title="Uplift service unreachable"
      detail="The uplift-service didn't respond."
      onRetry={() => refetch()}
    />;
  }

  const s = status!;
  const r = s.run;
  if (!r) {
    return <NotStarted onRun={() => runM.mutate(30)} running={runM.isPending}
                       error={runError} project={project} />;
  }

  const selected = filteredCases.find(c => c.id === selectedId) ?? filteredCases[0];

  return (
    <>
      {/* Module summary strip */}
      <nav aria-label="Filter cases by module"
           className="card mt-4 px-5 py-3 flex items-center gap-2 overflow-x-auto flex-wrap">
        <button
          onClick={() => setModuleFilter(null)}
          aria-pressed={moduleFilter === null}
          className={`flex-shrink-0 px-3 py-1.5 rounded-md border transition-colors text-xs
            ${moduleFilter === null
              ? 'bg-brand-50 border-brand text-brand font-semibold'
              : 'border-line text-fg-2 hover:bg-surface'}`}
        >
          All modules · {s.cases.length}
        </button>
        {s.byModule.map(m => {
          const active = moduleFilter === m.moduleId;
          const total = m.pass + m.benign + m.regression;
          return (
            <button
              key={m.moduleId ?? '_'}
              onClick={() => setModuleFilter(active ? null : (m.moduleId ?? null))}
              aria-pressed={active}
              className={`flex-shrink-0 px-3 py-1.5 rounded-md border transition-colors text-xs
                ${active
                  ? 'bg-brand-50 border-brand text-brand font-semibold'
                  : 'border-line text-fg-2 hover:bg-surface'}`}
            >
              <span className="font-mono">{m.moduleName}</span>
              <span className="ml-2 text-fg-3">{total}</span>
              {m.regression > 0 && <span className="ml-2 text-err">{m.regression}!</span>}
            </button>
          );
        })}
      </nav>

      <TwoPane
        rail="default"
        left={
          <CaseListPane
            cases={filteredCases}
            totalCases={s.cases.length}
            bucketFilter={bucketFilter}
            onBucketFilter={setBucketFilter}
            selectedId={selected?.id ?? null}
            onSelect={setSelectedId}
            counts={{ pass: r.passCount, benign: r.benignCount, regression: r.regressionCount }}
          />
        }
      >
        {selected
          ? <CaseDetailPane
              key={selected.id}
              projectId={projectId}
              caseRow={selected}
              disabled={stageEPassed}
            />
          : <div className="p-8 text-fg-3 text-sm">No cases match the current filter.</div>}
      </TwoPane>
    </>
  );
}

/* ---------------- Empty state ---------------- */

function NotStarted({
  onRun, running, error, project
}: { onRun: () => void; running: boolean; error: string | null; project: Project }) {
  return (
    <EmptyState
      icon={ShieldCheck}
      accent="gradient"
      title="Validate the migration's behavior"
      body={<>
        Atlas runs characterization tests against each module migrated in Stage D,
        comparing legacy vs new outputs. Results bucket into
        <span className="font-mono mx-1 text-ok">pass</span> /
        <span className="font-mono mx-1 text-warn">benign</span> /
        <span className="font-mono mx-1 text-err">regression</span> —
        triage every regression before advancing.
      </>}
      hint={project.sourcePath
        ? <>Source: <span className="font-mono text-fg-1">{project.sourcePath}</span></>
        : undefined}
      primaryAction={
        <button className="btn-brand" disabled={running} onClick={onRun}>
          <Play size={14} aria-hidden="true" /> {running ? 'Running…' : 'Run characterization tests'}
        </button>
      }
    />
  );
}

/* ---------------- Case list (left) ---------------- */

function CaseListPane({
  cases, totalCases, bucketFilter, onBucketFilter, selectedId, onSelect, counts
}: {
  cases: CharCase[];
  totalCases: number;
  bucketFilter: 'all' | Bucket;
  onBucketFilter: (b: 'all' | Bucket) => void;
  selectedId: string | null;
  onSelect: (id: string) => void;
  counts: { pass: number; benign: number; regression: number };
}) {
  return (
    <>
      <div className="px-4 py-3 border-b border-line">
        <div className="eyebrow">Test cases</div>
        <div className="mt-2 flex items-center gap-1">
          <FilterChip label="all"        count={totalCases}        active={bucketFilter === 'all'}
                      onClick={() => onBucketFilter('all')} tone="brand" />
          <FilterChip label="pass"       count={counts.pass}       active={bucketFilter === 'pass'}
                      onClick={() => onBucketFilter('pass')} tone="ok" />
          <FilterChip label="benign"     count={counts.benign}     active={bucketFilter === 'benign'}
                      onClick={() => onBucketFilter('benign')} tone="warn" />
          <FilterChip label="regression" count={counts.regression} active={bucketFilter === 'regression'}
                      onClick={() => onBucketFilter('regression')} tone="err" />
        </div>
      </div>
      <ul className="flex-1 overflow-y-auto py-1">
        {cases.length === 0 && (
          <li className="px-4 py-6 text-sm text-fg-3">No cases match.</li>
        )}
        {cases.map(c => {
          const active = c.id === selectedId;
          return (
            <li key={c.id}>
              <button
                onClick={() => onSelect(c.id)}
                className={`w-full text-left px-4 py-2.5 border-b border-line/60 transition-colors
                  ${active ? 'bg-brand-50 border-l-2 border-l-brand' : 'hover:bg-surface/80'}`}
              >
                <div className="flex items-center gap-2">
                  <BucketDot bucket={c.bucket} />
                  <span className={`text-sm truncate ${active ? 'text-brand font-semibold' : 'text-fg-1'}`}>
                    {c.testName}
                  </span>
                  <TriageBadge state={c.triageState} />
                </div>
                <div className="mt-1 ml-5 flex items-center gap-3 text-2xs text-fg-3">
                  <span className="badge-mono badge-neutral">{c.testKind}</span>
                  {c.diffKind && c.diffKind !== 'exact_match' && (
                    <span className="font-mono">{c.diffKind}</span>
                  )}
                </div>
              </button>
            </li>
          );
        })}
      </ul>
    </>
  );
}

function FilterChip({ label, count, active, onClick, tone }:
  { label: string; count: number; active: boolean; onClick: () => void;
    tone: 'brand' | 'ok' | 'warn' | 'err' }) {
  const accent =
    tone === 'brand' ? 'text-brand'
  : tone === 'ok'    ? 'text-ok'
  : tone === 'warn'  ? 'text-warn'
                     : 'text-err';
  return (
    <button
      onClick={onClick}
      className={`flex-1 px-2 py-1.5 rounded-md border text-2xs font-mono transition-colors
        ${active
          ? 'bg-brand-50 border-brand text-fg-1 font-semibold'
          : 'border-line text-fg-2 hover:bg-surface'}`}
    >
      <span className={accent}>{count}</span> {label}
    </button>
  );
}

function BucketDot({ bucket }: { bucket: CharCase['bucket'] }) {
  const cls =
    bucket === 'pass'       ? 'bg-ok'
  : bucket === 'benign'     ? 'bg-warn'
                            : 'bg-err dot-pulse';
  return <span className={`dot ${cls}`} />;
}

function TriageBadge({ state }: { state: CharCase['triageState'] }) {
  if (state === 'open')     return null;
  if (state === 'fixed')    return <span className="ml-auto badge-ok badge-mono text-2xs">fixed</span>;
  if (state === 'accepted') return <span className="ml-auto badge-neutral badge-mono text-2xs">accepted</span>;
  if (state === 'rejected') return <span className="ml-auto badge-err badge-mono text-2xs">rejected</span>;
  return null;
}

/* ---------------- Case detail (right) ---------------- */

function CaseDetailPane({
  projectId, caseRow, disabled
}: { projectId: string; caseRow: CharCase; disabled: boolean }) {
  const qc = useQueryClient();
  const triageM = useMutation({
    mutationFn: (state: CharCase['triageState']) =>
      api.triageCharCase(projectId, caseRow.id, { state }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['characterize', projectId] })
  });

  return (
    <div className="flex flex-col min-w-0">
      {/* Header */}
      <div className="px-6 py-5 border-b border-line">
        <div className="flex items-start gap-3">
          <BucketIcon bucket={caseRow.bucket} />
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className="text-lg font-semibold text-fg-1 leading-tight">
                {caseRow.testName}
              </h3>
              <BucketBadge bucket={caseRow.bucket} />
              {caseRow.diffKind && caseRow.diffKind !== 'exact_match' && (
                <span className="badge-neutral badge-mono">{caseRow.diffKind}</span>
              )}
            </div>
            <div className="mt-1 flex items-center gap-3 text-2xs text-fg-3">
              <span className="badge-mono badge-neutral">{caseRow.testKind}</span>
              {caseRow.inputSummary && (
                <span className="font-mono truncate">{caseRow.inputSummary}</span>
              )}
            </div>
            {caseRow.triagedBy && caseRow.triagedAt && (
              <div className="mt-2 text-2xs text-fg-3">
                <span className="font-semibold capitalize">{caseRow.triageState}</span>{' '}
                by <span className="font-mono">{caseRow.triagedBy}</span>{' '}
                {new Date(caseRow.triagedAt).toLocaleString('en-US', { hour12: false })}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Triage controls */}
      {caseRow.bucket !== 'pass' && (
        <div className="px-6 py-4 border-b border-line bg-surface/40 flex items-center gap-2">
          <span className="text-2xs text-fg-3 font-mono uppercase mr-1">Triage:</span>
          <button
            disabled={disabled || triageM.isPending || caseRow.triageState === 'fixed'}
            onClick={() => triageM.mutate('fixed')}
            className={`btn-sm ${caseRow.triageState === 'fixed' ? 'btn-primary' : 'btn-secondary'}`}
          >
            <Wrench size={13} /> Fixed
          </button>
          <button
            disabled={disabled || triageM.isPending || caseRow.triageState === 'accepted'}
            onClick={() => triageM.mutate('accepted')}
            className={`btn-sm ${caseRow.triageState === 'accepted' ? 'btn-primary' : 'btn-secondary'}`}
          >
            <CheckCircle2 size={13} /> Accept divergence
          </button>
          <button
            disabled={disabled || triageM.isPending || caseRow.triageState === 'rejected'}
            onClick={() => triageM.mutate('rejected')}
            className={`btn-sm ${caseRow.triageState === 'rejected' ? 'btn-primary' : 'btn-secondary'}`}
          >
            <XCircle size={13} /> Reject (revert)
          </button>
          {caseRow.triageState !== 'open' && (
            <button
              disabled={disabled || triageM.isPending}
              onClick={() => triageM.mutate('open')}
              className="btn-ghost btn-sm"
              title="Reopen for further review"
            >
              Reopen
            </button>
          )}
          {disabled && <span className="ml-2 text-2xs text-fg-3">Stage E finalized — read only.</span>}
        </div>
      )}

      <div className="p-6 grid grid-cols-2 gap-5">
        <OutputBlock label="Legacy output" tone="fg" body={caseRow.legacyOutput} />
        <OutputBlock label="New output"    tone={caseRow.bucket === 'pass' ? 'ok' : caseRow.bucket === 'benign' ? 'warn' : 'err'}
                     body={caseRow.newOutput} />
      </div>

      {caseRow.aiRecommendation && (
        <div className="mx-6 mb-6 surface p-4 border-l-2 border-l-agent">
          <div className="flex items-center gap-2">
            <Sparkles size={14} className="text-agent" />
            <div className="eyebrow">Agent recommendation</div>
          </div>
          <p className="mt-2 text-sm text-fg-1 leading-relaxed">
            {caseRow.aiRecommendation}
          </p>
        </div>
      )}
    </div>
  );
}

function OutputBlock({ label, body, tone }:
  { label: string; body?: string; tone: 'ok' | 'warn' | 'err' | 'fg' }) {
  const cls =
    tone === 'ok'   ? 'border-l-ok'
  : tone === 'warn' ? 'border-l-warn'
  : tone === 'err'  ? 'border-l-err'
                    : 'border-l-line';
  return (
    <div className={`surface p-3 border-l-2 ${cls}`}>
      <div className="eyebrow mb-1">{label}</div>
      <pre className="font-mono text-2xs whitespace-pre-wrap break-all text-fg-1 leading-relaxed">
        {body ?? '—'}
      </pre>
    </div>
  );
}

function BucketIcon({ bucket }: { bucket: CharCase['bucket'] }) {
  const cls =
    bucket === 'pass'       ? 'bg-ok-50 text-ok'
  : bucket === 'benign'     ? 'bg-warn-50 text-warn'
                            : 'bg-err-50 text-err';
  const Icon = bucket === 'pass' ? CheckCircle2 : bucket === 'benign' ? Activity : XCircle;
  return (
    <div className={`h-8 w-8 rounded-md inline-flex items-center justify-center ${cls}`}>
      <Icon size={14} strokeWidth={2} />
    </div>
  );
}

function BucketBadge({ bucket }: { bucket: CharCase['bucket'] }) {
  const cls =
    bucket === 'pass'   ? 'badge-ok'
  : bucket === 'benign' ? 'badge-warn'
                        : 'badge-err';
  return <span className={`${cls} badge-mono`}>{bucket}</span>;
}
