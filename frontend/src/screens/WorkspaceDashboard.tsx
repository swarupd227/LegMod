import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  Plus, ArrowUpRight, FolderOpen,
  Boxes, GitBranch, ShieldCheck
} from 'lucide-react';
import { api, Project } from '../api/client';
import { ModeBadge, RiskBadge } from '../components/Badges';
import {
  EmptyState, LoadingState, ErrorState, Modal, MetricTile,
  useAnnouncer
} from '../components/ui';

const SOAP_STAGE_LABEL: Record<string, string> = {
  A: 'Code Archaeology',
  B: 'Runtime Capture',
  C: 'Schema Reconciliation',
  D: 'Code Generation',
  E: 'Differential Validation',
  F: 'Reports & Deliverables'
};
const UPLIFT_STAGE_LABEL: Record<string, string> = {
  A: 'Inventory & Heatmap',
  B: 'Recipe Authoring',
  C: 'Strangler Designer',
  D: 'Module Migration',
  E: 'Characterization Validation',
  F: 'Cutover & Decommission'
};
function stageLabel(mode: string | undefined, stage: string) {
  return (mode === 'UPLIFT' ? UPLIFT_STAGE_LABEL : SOAP_STAGE_LABEL)[stage] ?? stage;
}

export default function WorkspaceDashboard() {
  const qc = useQueryClient();
  const { data: projects, isLoading, error, refetch } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.projects()
  });

  const [showNew, setShowNew] = useState(false);

  const stats = computeStats(projects ?? []);
  const hasProjects = (projects ?? []).length > 0;

  return (
    <>
      <PageHeader onCreate={() => setShowNew(true)} stats={stats} hasProjects={hasProjects} />

      {error && (
        <ErrorState
          title="Workspace service unreachable"
          detail="The API gateway didn't respond."
          hint={<>Bring the stack up with <code className="code-chip">.\up.ps1</code></>}
          onRetry={() => refetch()}
        />
      )}

      {isLoading && <LoadingState variant="grid" />}

      {!isLoading && projects && projects.length === 0 && (
        <EmptyProjectState onCreate={() => setShowNew(true)} />
      )}

      {!isLoading && projects && projects.length > 0 && (
        <div className="mt-6 grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {projects.map(p => <ProjectCard key={p.id} p={p} />)}
        </div>
      )}

      <Modal
        open={showNew}
        onClose={() => setShowNew(false)}
        title="Create migration project"
        size="lg"
      >
        <NewProjectForm
          onCreated={() => {
            qc.invalidateQueries({ queryKey: ['projects'] });
            setShowNew(false);
          }}
          onCancel={() => setShowNew(false)}
        />
      </Modal>
    </>
  );
}

/* ---------------- Page header ---------------- */

function PageHeader({
  onCreate, stats, hasProjects
}: { onCreate: () => void; stats: Stats; hasProjects: boolean }) {
  return (
    <header>
      <div className="flex items-end justify-between gap-6 flex-wrap">
        <div className="min-w-0">
          <div className="eyebrow">Migration workspace</div>
          <h1 className="mt-1 text-3xl font-semibold tracking-tight text-fg-1 text-balance">
            Projects
          </h1>
          <p className="mt-2 text-sm text-fg-2 max-w-xl">
            Track Apache Axis to JAX-WS migrations and Spring/Jakarta uplifts. Each
            project carries its own knowledge graph, captured corpus, and audit trail.
          </p>
        </div>

        <button className="btn-primary" onClick={onCreate}>
          <Plus size={14} aria-hidden="true" /> New project
        </button>
      </div>

      {hasProjects && (
        <div className="mt-6 grid grid-cols-1 sm:grid-cols-3 gap-3">
          <MetricTile icon={Boxes}       label="Active projects"  value={stats.active}   tone="brand" />
          <MetricTile icon={GitBranch}   label="Stages in flight" value={stats.inflight} tone="agent" />
          <MetricTile icon={ShieldCheck} label="Gates passed"     value={stats.gates}    tone="ok" />
        </div>
      )}
    </header>
  );
}

type Stats = { active: number; inflight: number; gates: number };
function computeStats(projects: Project[]): Stats {
  const active = projects.length;
  const inflight = projects.filter(p => p.currentStage !== 'F'
                                     || gateState(p, 'F') !== 'passed').length;
  const stages = ['A', 'B', 'C', 'D', 'E', 'F'];
  // A passed gate counts as a passed gate. We don't have full gate state here
  // because the project list endpoint doesn't include it; approximate by the
  // currentStage cursor (gates A..currentStage-1 are guaranteed passed). When
  // currentStage=F and gate F is passed, we'd undercount by 1 — accept that
  // ~1-project rounding error in the dashboard summary; the project detail
  // page is authoritative.
  const gates = projects.reduce((acc, p) => acc + Math.max(0, stages.indexOf(p.currentStage)), 0);
  return { active, inflight, gates };
}

function gateState(_p: Project, _label: string): string | undefined {
  // Placeholder — workspace project list doesn't include gate detail today.
  return undefined;
}

/* ---------------- Project card ---------------- */

function ProjectCard({ p }: { p: Project }) {
  return (
    <Link
      to={`/projects/${p.id}`}
      className="card card-hover p-5 group block focus:outline-none focus:ring-2 focus:ring-brand/40"
    >
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <ModeBadge mode={p.mode} />
            {p.vendorPartner && (
              <span className="text-2xs text-fg-3">· {p.vendorPartner}</span>
            )}
          </div>
          <div className="mt-1.5 flex items-center gap-2">
            <h3 className="font-semibold tracking-tight text-fg-1 truncate">{p.name}</h3>
            <ArrowUpRight size={14}
              aria-hidden="true"
              className="text-fg-4 group-hover:text-fg-1 group-hover:translate-x-0.5 group-hover:-translate-y-0.5 transition-all" />
          </div>
          <div className="mt-1 text-xs text-fg-3 truncate">
            {p.sourceFramework ?? '—'}
            <span aria-hidden="true" className="text-fg-4 mx-1">→</span>
            {p.targetFramework ?? '—'}
          </div>
        </div>

        <RiskBadge tier={p.riskTier} />
      </div>

      {p.description && (
        <p className="mt-3 text-sm text-fg-2 line-clamp-2">{p.description}</p>
      )}

      <StageDots current={p.currentStage} mode={p.mode} />

      <div className="mt-3 pt-3 border-t border-line flex items-center text-xs text-fg-3 flex-wrap gap-2">
        <span>Owner · {p.owner ?? '—'}</span>
        <span className="ml-auto inline-flex items-center gap-1">
          <span className="dot bg-brand" aria-hidden="true" />
          Stage {p.currentStage} · {stageLabel(p.mode, p.currentStage)}
        </span>
      </div>
    </Link>
  );
}

function StageDots({ current, mode }: { current: string; mode?: string }) {
  const stages = ['A', 'B', 'C', 'D', 'E', 'F'];
  const idx = stages.indexOf(current);
  return (
    <div className="mt-4">
      <div className="flex items-center gap-1" aria-label={`Project at Stage ${current} of ${stageLabel(mode, current)}`}>
        {stages.map((s, i) => {
          const done = i < idx;
          const here = i === idx;
          const cls = done ? 'bg-ok' : here ? 'bg-brand' : 'bg-line';
          return (
            <div key={s} className="flex-1">
              <div className={`h-1 rounded-full ${cls} ${here ? 'shadow-[0_0_0_3px_rgba(31,79,217,0.12)]' : ''}`}
                   aria-hidden="true" />
              <div className={`mt-1.5 text-2xs font-mono ${here ? 'text-fg-1 font-semibold' : done ? 'text-fg-3' : 'text-fg-4'}`}>
                {s}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/* ---------------- Empty state ---------------- */

function EmptyProjectState({ onCreate }: { onCreate: () => void }) {
  return (
    <div className="mt-6">
      <EmptyState
        icon={FolderOpen}
        accent="gradient"
        title="Start your first migration"
        body="Point Atlas at a legacy SOAP service or a Spring application that needs uplift. The Code Archaeology agent will recover its operations, type mappings, and adapters."
        primaryAction={
          <button className="btn-primary" onClick={onCreate}>
            <Plus size={14} aria-hidden="true" /> Create project
          </button>
        }
      />
    </div>
  );
}

/* ---------------- New project form ---------------- */

type SourceMode = 'github' | 'upload' | 'path';

function NewProjectForm({
  onCreated, onCancel
}: { onCreated: () => void; onCancel: () => void }) {
  const { announce } = useAnnouncer();
  const [name, setName] = useState('');
  const [mode, setMode] = useState<'SOAP' | 'UPLIFT'>('SOAP');
  const [src, setSrc] = useState('');
  const [tgt, setTgt] = useState('');
  const [vendor, setVendor] = useState('');

  // Source ingestion: three options. "github" is the default because
  // it's the most credible / customer-friendly. "upload" handles the
  // air-gapped / proprietary case. "path" stays as a power-user escape
  // hatch for engineers pre-staging code in /samples or /uploads.
  const [sourceMode, setSourceMode] = useState<SourceMode>('github');
  const [githubUrl, setGithubUrl] = useState('');
  const [githubBranch, setGithubBranch] = useState('');
  const [githubSubpath, setGithubSubpath] = useState('');
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [sourcePath, setSourcePath] = useState('');

  // Two-step submit: the project create has to succeed before we can
  // run the ingestion (the ingestion endpoint is keyed on project id).
  // We surface a single status string to the user — "Cloning", "Extracting",
  // etc. — by tracking the active step in component state.
  const [step, setStep] = useState<'idle' | 'creating' | 'ingesting' | 'done'>('idle');
  const [error, setError] = useState<string | null>(null);

  const valid =
    name.trim().length >= 3 &&
    (sourceMode !== 'github' || /^https?:\/\/.+/i.test(githubUrl.trim())) &&
    (sourceMode !== 'upload' || uploadFile !== null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!valid || step !== 'idle') return;
    setError(null);
    setStep('creating');
    try {
      const project = await api.createProject({
        name: name.trim(),
        mode,
        sourceFramework: src.trim() || undefined,
        targetFramework: tgt.trim() || undefined,
        vendorPartner:   vendor.trim() || undefined,
        sourcePath:      sourceMode === 'path' && sourcePath.trim()
                          ? sourcePath.trim() : undefined
      });

      // Ingestion (skip for the "path" mode — engineer pre-staged it).
      if (sourceMode !== 'path') {
        setStep('ingesting');
        const status =
          sourceMode === 'github'
            ? await api.ingestGithub(project.id, {
                url:     githubUrl.trim(),
                branch:  githubBranch.trim() || undefined,
                subpath: githubSubpath.trim() || undefined
              })
            : await api.ingestUpload(project.id, uploadFile!);
        if (status.state !== 'ready') {
          throw new Error(status.message || 'Source ingestion failed.');
        }
      }
      setStep('done');
      announce(`Project ${name} created`);
      onCreated();
    } catch (err) {
      setError(String((err as Error).message ?? err));
      setStep('idle');
    }
  }

  const submitLabel =
    step === 'creating'  ? 'Creating…'
  : step === 'ingesting' ? (sourceMode === 'github' ? 'Cloning…' : 'Extracting…')
  : step === 'done'      ? 'Done'
                         : 'Create project';

  return (
    <form onSubmit={submit}>
      <div className="space-y-4">
        <Field label="Project name" required>
          <input
            value={name}
            onChange={e => setName(e.target.value)}
            className="input"
            placeholder="e.g. Order Management uplift"
            required
            minLength={3}
          />
        </Field>

        <Field label="Migration track">
          <div role="radiogroup" aria-label="Migration track"
               className="flex gap-1.5 p-1 bg-canvas border border-line rounded-md">
            {(['SOAP', 'UPLIFT'] as const).map(opt => (
              <button
                key={opt}
                type="button"
                role="radio"
                aria-checked={mode === opt}
                onClick={() => setMode(opt)}
                className={`flex-1 h-8 rounded text-sm font-medium transition-colors ${
                  mode === opt
                    ? 'bg-surface text-fg-1 shadow-xs'
                    : 'text-fg-3 hover:text-fg-1'
                }`}
              >
                {opt === 'SOAP' ? 'SOAP migration' : 'Framework uplift'}
              </button>
            ))}
          </div>
        </Field>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Field label="Source framework">
            <input value={src} onChange={e => setSrc(e.target.value)}
                   className="input" placeholder="e.g. Apache Axis 1.3" />
          </Field>
          <Field label="Target framework">
            <input value={tgt} onChange={e => setTgt(e.target.value)}
                   className="input" placeholder="e.g. JAX-WS RI 4.0" />
          </Field>
        </div>

        <Field label="Vendor partner" hint="Optional — used to scope the closure document.">
          <input value={vendor} onChange={e => setVendor(e.target.value)} className="input" />
        </Field>

        <SourceIngestionField
          mode={sourceMode}
          onModeChange={setSourceMode}
          githubUrl={githubUrl}     onGithubUrl={setGithubUrl}
          githubBranch={githubBranch} onGithubBranch={setGithubBranch}
          githubSubpath={githubSubpath} onGithubSubpath={setGithubSubpath}
          uploadFile={uploadFile}   onUploadFile={setUploadFile}
          sourcePath={sourcePath}   onSourcePath={setSourcePath}
        />

        {error && (
          <div role="alert" className="text-sm text-err">{error}</div>
        )}
      </div>

      <div className="mt-5 flex justify-end gap-2 flex-wrap">
        <button type="button" className="btn-secondary" onClick={onCancel} disabled={step !== 'idle'}>
          Cancel
        </button>
        <button
          type="submit"
          className="btn-primary"
          disabled={!valid || step !== 'idle'}
        >
          {submitLabel}
        </button>
      </div>
    </form>
  );
}

/**
 * The "where does the source come from" picker. Three modes:
 *  - GitHub URL (default) — repo URL + optional branch + optional subpath
 *  - Upload .zip          — drag-drop / file picker; archive extracted server-side
 *  - Container path       — power-user escape hatch (paths under /samples
 *                            or /uploads pre-staged by an engineer)
 */
function SourceIngestionField({
  mode, onModeChange,
  githubUrl, onGithubUrl, githubBranch, onGithubBranch, githubSubpath, onGithubSubpath,
  uploadFile, onUploadFile,
  sourcePath, onSourcePath
}: {
  mode: SourceMode; onModeChange: (m: SourceMode) => void;
  githubUrl: string; onGithubUrl: (s: string) => void;
  githubBranch: string; onGithubBranch: (s: string) => void;
  githubSubpath: string; onGithubSubpath: (s: string) => void;
  uploadFile: File | null; onUploadFile: (f: File | null) => void;
  sourcePath: string; onSourcePath: (s: string) => void;
}) {
  const fileInputId = 'np-upload-file';
  const tabs: { value: SourceMode; label: string }[] = [
    { value: 'github', label: 'GitHub URL' },
    { value: 'upload', label: 'Upload .zip' },
    { value: 'path',   label: 'Container path' }
  ];

  return (
    <div className="block">
      <div className="text-xs font-medium text-fg-2 mb-1">Source code</div>
      <div role="tablist" aria-label="How to provide the source code"
           className="flex gap-1.5 p-1 bg-canvas border border-line rounded-md">
        {tabs.map(t => (
          <button
            key={t.value}
            type="button"
            role="tab"
            aria-selected={mode === t.value}
            onClick={() => onModeChange(t.value)}
            className={`flex-1 h-8 rounded text-sm font-medium transition-colors ${
              mode === t.value
                ? 'bg-surface text-fg-1 shadow-xs'
                : 'text-fg-3 hover:text-fg-1'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="mt-3 space-y-3">
        {mode === 'github' && (
          <>
            <input value={githubUrl}
                   onChange={e => onGithubUrl(e.target.value)}
                   className="input font-mono text-xs"
                   placeholder="https://github.com/apache/axis-axis1-java"
                   aria-label="Repository URL" />
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <input value={githubBranch}
                     onChange={e => onGithubBranch(e.target.value)}
                     className="input font-mono text-xs"
                     placeholder="Branch (optional, e.g. main)"
                     aria-label="Branch" />
              <input value={githubSubpath}
                     onChange={e => onGithubSubpath(e.target.value)}
                     className="input font-mono text-xs"
                     placeholder="Subpath (optional, sparse-checkout)"
                     aria-label="Subpath" />
            </div>
            <div className="text-2xs text-fg-3">
              Atlas does a shallow clone (<code className="code-chip">--depth 1</code>).
              For a monorepo, a <em>subpath</em> like
              <code className="code-chip"> distribution/src/main/files/samples</code>
              keeps the working tree small via sparse-checkout. Only
              github.com / gitlab.com / bitbucket.org URLs are accepted.
            </div>
          </>
        )}

        {mode === 'upload' && (
          <>
            <label htmlFor={fileInputId}
                   className="block border border-dashed border-line rounded-md
                              p-5 text-center cursor-pointer hover:bg-canvas transition-colors">
              <input id={fileInputId}
                     type="file"
                     accept=".zip,application/zip"
                     className="sr-only"
                     onChange={e => onUploadFile(e.target.files?.[0] ?? null)} />
              {uploadFile
                ? <span className="text-sm text-fg-1 font-medium">{uploadFile.name}
                    <span className="text-fg-3 font-normal ml-1">
                      ({Math.round(uploadFile.size / 1024)} KB)
                    </span>
                  </span>
                : <span className="text-sm text-fg-2">
                    Click to choose a <code className="code-chip">.zip</code> archive of your source tree.
                  </span>}
            </label>
            <div className="text-2xs text-fg-3">
              Atlas extracts the archive on the server with zip-slip protection
              and caps individual archives at 500 MB. For larger codebases use
              the GitHub URL option.
            </div>
          </>
        )}

        {mode === 'path' && (
          <>
            <input value={sourcePath}
                   onChange={e => onSourcePath(e.target.value)}
                   className="input font-mono text-xs"
                   placeholder="/samples/your-sample-dir"
                   aria-label="Container path" />
            <div className="text-2xs text-fg-3">
              Power-user escape hatch — the absolute path inside the
              analysis service container. Use this when the source has
              been pre-staged by an engineer (anything under
              <code className="code-chip">/samples</code> or
              <code className="code-chip">/uploads</code>).
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function Field({
  label, hint, required, children
}: { label: string; hint?: string; required?: boolean; children: React.ReactNode }) {
  return (
    <label className="block">
      <div className="text-xs font-medium text-fg-2 mb-1">
        {label}
        {required && <span aria-hidden="true" className="text-err ml-0.5">*</span>}
      </div>
      {children}
      {hint && <div className="mt-1 text-2xs text-fg-3">{hint}</div>}
    </label>
  );
}
