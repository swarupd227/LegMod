import { useEffect, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Play, Sparkles, RefreshCw, FileCode, FolderTree,
  ScrollText, Hash, Boxes
} from 'lucide-react';
import { api, Project, UpliftFinding, UpliftModule, UpliftStatus } from '../api/client';
import {
  EmptyState, LoadingState, ErrorState,
  IconButton, Treemap,
  useStageChrome, useAnnouncer
} from '../components/ui';
import { MigrationForecastCard } from './MigrationForecast';

const RULE_PALETTE: Record<string, string> = {
  javax_persistence:  'bg-err-50 text-err',
  javax_servlet:      'bg-err-50 text-err',
  javax_validation:   'bg-warn-50 text-warn',
  javax_xml:          'bg-warn-50 text-warn',
  ibm_websphere:      'bg-err-50 text-err',
  struts1:            'bg-err-50 text-err',
  spring5_extension:  'bg-brand-50 text-brand'
};

export default function InventoryHeatmap({
  projectId, project
}: { projectId: string; project: Project }) {
  const qc = useQueryClient();
  const { setStatus, setActions } = useStageChrome();
  const { announce } = useAnnouncer();

  const { data: status, isLoading, error, refetch } = useQuery({
    queryKey: ['uplift', projectId],
    queryFn: () => api.inventoryStatus(projectId),
    refetchInterval: 8000
  });

  const runM = useMutation({
    mutationFn: () => api.runInventory(projectId),
    onSuccess: () => {
      announce('Inventory scan complete');
      qc.invalidateQueries({ queryKey: ['uplift', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    }
  });

  const [selectedModuleId, setSelectedModuleId] = useState<string | null>(null);

  // Inject chrome content.
  useEffect(() => {
    if (!status?.run) {
      setStatus([]);
      setActions(null);
      return;
    }
    const totalLoc = status.modules.reduce((a, m) => a + m.loc, 0);
    const totalFindings = status.findings.length;
    const avgDifficulty = status.modules.length > 0
      ? Math.round((status.modules.reduce((a, m) => a + m.difficulty, 0) / status.modules.length) * 10) / 10
      : 0;
    setStatus([
      { label: 'modules',  value: status.modules.length, tone: 'brand' },
      { label: 'LOC',      value: totalLoc.toLocaleString(), tone: 'neutral' },
      { label: 'findings', value: totalFindings, tone: 'warn' },
      { label: 'avg difficulty', value: `${avgDifficulty}/10`, tone: 'neutral' }
    ]);
    setActions(
      <>
        <IconButton icon={<RefreshCw size={13} />} ariaLabel="Refresh inventory" onClick={() => refetch()} />
        <button className="btn-primary btn-sm" onClick={() => runM.mutate()} disabled={runM.isPending}>
          <Play size={13} aria-hidden="true" /> {runM.isPending ? 'Scanning…' : 'Re-scan'}
        </button>
      </>
    );
  }, [status, runM.isPending]); // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <LoadingState />;
  if (error) {
    return <ErrorState
      title="Uplift service unreachable"
      detail="The uplift-service didn't respond."
      onRetry={() => refetch()}
    />;
  }
  const s = status!;
  if (!s.run) {
    return (
      <div className="p-5">
        <MigrationForecastCard projectId={projectId} />
        <NotStarted onRun={() => runM.mutate()} running={runM.isPending} project={project} />
      </div>
    );
  }

  const selectedModule = s.modules.find(m => m.id === selectedModuleId) ?? s.modules[0];

  return (
    <section className="mt-6 space-y-6">
      <ModuleTreemap
        modules={s.modules}
        selectedId={selectedModule?.id}
        onSelect={setSelectedModuleId}
      />

      <div className="grid grid-cols-1 lg:grid-cols-[var(--rail-default)_1fr] gap-5">
        <RuleSummary status={s} />
        <ModuleDetail module={selectedModule}
                      findings={s.findings.filter(f => f.moduleId === selectedModule?.id)} />
      </div>
    </section>
  );
}

/* ---------------- Empty state ---------------- */

function NotStarted({
  onRun, running, project
}: { onRun: () => void; running: boolean; project: Project }) {
  return (
    <EmptyState
      icon={FolderTree}
      accent="gradient"
      title="Scan the legacy codebase"
      body={<>
        Atlas walks the source tree, classifies each file's modernization
        antipatterns ({' '}<code className="code-chip">javax→jakarta</code>,{' '}
        <code className="code-chip">com.ibm.*</code>, Struts, Spring 5, JDK
        deprecations) and produces a heatmap with a per-module difficulty score.
      </>}
      hint={
        project.sourcePath
          ? <>Source: <span className="font-mono text-fg-1">{project.sourcePath}</span></>
          : undefined
      }
      primaryAction={
        <button className="btn-brand" disabled={running} onClick={onRun}>
          <Play size={14} aria-hidden="true" /> {running ? 'Scanning…' : 'Begin inventory scan'}
        </button>
      }
    />
  );
}

/* ---------------- Module heatmap ---------------- */

/**
 * Squarified treemap of modules. Tiles are sized proportional to LOC and
 * coloured by difficulty (0-10). The squarify algorithm (Bruls et al.) keeps
 * aspect ratios near 1:1 regardless of how lopsided the weights are.
 */
function ModuleTreemap({
  modules, selectedId, onSelect
}: { modules: UpliftModule[]; selectedId?: string; onSelect: (id: string) => void }) {
  if (modules.length === 0) {
    return <div className="card p-6 text-sm text-fg-3">No modules detected.</div>;
  }

  return (
    <section className="card p-5">
      <div className="eyebrow mb-2">Modules · sized by LOC · coloured by difficulty</div>
      <div className="-mx-1">
        <Treemap
          ariaLabel="Module heatmap"
          minHeight={260}
          items={modules.map(m => ({ id: m.id, weight: Math.max(1, m.loc) }))}
          render={(tile) => {
            const m = modules.find(mm => mm.id === tile.item.id);
            if (!m) return null;
            const isSel = m.id === selectedId;
            return (
              <button
                onClick={() => onSelect(m.id)}
                aria-pressed={isSel}
                style={{
                  background: difficultyToBg(m.difficulty),
                  borderColor: isSel ? '#1F4FD9' : 'transparent',
                  width: '100%',
                  height: '100%'
                }}
                className={`relative rounded-md border-2 p-3 text-left transition-all
                            hover:shadow-md ${isSel ? 'shadow-md ring-4 ring-brand/10' : ''}`}>
                <div className="flex items-start gap-2">
                  <FolderTree size={14} className={difficultyTextCls(m.difficulty)} aria-hidden="true" />
                  <div className="min-w-0 flex-1">
                    <div className={`font-mono text-sm font-semibold truncate ${difficultyTextCls(m.difficulty)}`}>
                      {m.name}
                    </div>
                    <div className={`text-2xs font-mono truncate opacity-70 ${difficultyTextCls(m.difficulty)}`}>
                      {m.packageName}
                    </div>
                  </div>
                </div>
                {tile.height > 80 && (
                  <div className={`mt-3 flex items-baseline gap-3 ${difficultyTextCls(m.difficulty)}`}>
                    <div>
                      <div className="text-2xs uppercase tracking-wider opacity-70">Difficulty</div>
                      <div className="text-2xl font-semibold tracking-tight tabular-nums">
                        {m.difficulty}
                        <span className="text-xs opacity-60">/10</span>
                      </div>
                    </div>
                    <div className="ml-auto text-right text-2xs font-mono opacity-80">
                      <div>{m.fileCount} files</div>
                      <div>{m.loc.toLocaleString()} LOC</div>
                      <div>{m.findingCount} findings</div>
                    </div>
                  </div>
                )}
              </button>
            );
          }}
        />
      </div>

      <Legend />
    </section>
  );
}

/** 0 → emerald-50, 5 → amber-100, 10 → rose-200 */
function difficultyToBg(d: number) {
  // Interpolate hue/saturation in HSL space.
  // green (140°) at 0 → amber (40°) at 5 → red (0°) at 10
  const clamped = Math.max(0, Math.min(10, d));
  const hue = clamped <= 5
    ? 140 - (clamped / 5) * 100   // 140 → 40
    : 40  - ((clamped - 5) / 5) * 40; // 40 → 0
  const sat = 60 + clamped * 4;
  const light = 95 - clamped * 2;
  return `hsl(${hue}deg ${sat}% ${light}%)`;
}

function difficultyTextCls(d: number) {
  if (d >= 7) return 'text-err';
  if (d >= 4) return 'text-warn';
  return 'text-ok';
}

function Legend() {
  return (
    <div className="mt-3 flex items-center gap-3 text-2xs text-fg-3">
      <span>Difficulty</span>
      <div className="flex h-2 w-48 rounded-full overflow-hidden border border-line">
        {Array.from({ length: 11 }).map((_, i) => (
          <div key={i} style={{ background: difficultyToBg(i), flex: 1 }} />
        ))}
      </div>
      <span>0</span><span className="font-mono text-fg-4">·</span><span>5</span>
      <span className="font-mono text-fg-4">·</span><span>10</span>
    </div>
  );
}

/* ---------------- Rule summary (left) ---------------- */

function RuleSummary({ status }: { status: UpliftStatus }) {
  const total = status.findings.length;
  return (
    <div className="surface p-4 self-start">
      <div className="flex items-center gap-2 mb-3">
        <Hash size={14} className="text-fg-3" />
        <div className="eyebrow">Findings by rule</div>
        <span className="ml-auto text-2xs text-fg-3 font-mono">{total}</span>
      </div>

      {status.ruleCounts.length === 0 ? (
        <div className="text-sm text-fg-3">No findings.</div>
      ) : (
        <ul className="space-y-2">
          {status.ruleCounts.map(rc => {
            const pct = total === 0 ? 0 : Math.round((rc.count / total) * 100);
            const cls = RULE_PALETTE[rc.ruleId] ?? 'bg-subtle text-fg-2';
            return (
              <li key={rc.ruleId}>
                <div className="flex items-center gap-2 text-xs mb-1">
                  <span className={`${cls} badge-mono`}>{rc.ruleId}</span>
                  <span className="ml-auto font-mono text-fg-1 font-semibold">{rc.count}</span>
                </div>
                <div className="h-1.5 bg-canvas border border-line rounded-full overflow-hidden">
                  <div className="h-full bg-gradient-to-r from-brand to-agent"
                       style={{ width: `${pct}%` }} />
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/* ---------------- Module detail (right) ---------------- */

function ModuleDetail({
  module, findings
}: { module?: UpliftModule; findings: UpliftFinding[] }) {
  if (!module) {
    return <div className="surface p-6 text-sm text-fg-3">Select a module.</div>;
  }
  return (
    <div className="surface p-4">
      <div className="flex items-start gap-3 pb-3 border-b border-line">
        <Boxes size={16} className={difficultyTextCls(module.difficulty)} />
        <div className="min-w-0 flex-1">
          <div className="font-mono text-base font-semibold text-fg-1">{module.name}</div>
          <div className="text-xs text-fg-3 font-mono break-all">{module.packageName}</div>
        </div>
        <div className="text-right">
          <div className="eyebrow">Difficulty</div>
          <div className={`text-2xl font-semibold tracking-tight ${difficultyTextCls(module.difficulty)}`}>
            {module.difficulty}
            <span className="text-xs opacity-60">/10</span>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-3 gap-3 py-3 border-b border-line text-xs">
        <Stat icon={FileCode}   label="Files"    value={module.fileCount.toString()} />
        <Stat icon={ScrollText} label="LOC"      value={module.loc.toLocaleString()} />
        <Stat icon={Sparkles}   label="Findings" value={module.findingCount.toString()} />
      </div>

      <div className="mt-3">
        <div className="eyebrow mb-2">Findings ({findings.length})</div>
        {findings.length === 0 ? (
          <div className="text-sm text-fg-3">No findings in this module.</div>
        ) : (
          <ul className="space-y-2 max-h-72 overflow-y-auto pr-1">
            {findings.map(f => <FindingRow key={f.id} f={f} />)}
          </ul>
        )}
      </div>
    </div>
  );
}

function Stat({ icon: Icon, label, value }: { icon: any; label: string; value: string }) {
  return (
    <div className="flex items-center gap-2">
      <Icon size={13} className="text-fg-4" />
      <div>
        <div className="text-2xs text-fg-3">{label}</div>
        <div className="font-mono text-sm font-semibold text-fg-1">{value}</div>
      </div>
    </div>
  );
}

function FindingRow({ f }: { f: UpliftFinding }) {
  const sevCls =
    f.severity === 'high'   ? 'badge-err'
  : f.severity === 'medium' ? 'badge-warn'
                             : 'badge-neutral';
  return (
    <li className="surface-soft p-2.5">
      <div className="flex items-center gap-2 text-xs">
        <span className={`${sevCls} badge-mono`}>{f.severity}</span>
        <span className="font-mono text-fg-1 truncate flex-1">{f.ruleLabel}</span>
        <span className="font-mono text-2xs text-fg-3 shrink-0">
          line {f.lineStart}
        </span>
      </div>
      <div className="mt-1.5 text-2xs text-fg-3 font-mono truncate">{f.filePath}</div>
      {f.snippet && (
        <pre className="mt-1.5 text-2xs font-mono text-fg-2 bg-canvas border border-line rounded p-1.5 overflow-x-auto m-0">
          {f.snippet}
        </pre>
      )}
      {f.suggestedRecipe && (
        <div className="mt-1.5 flex items-center gap-1.5 text-2xs">
          <Sparkles size={10} className="text-agent" />
          <span className="text-fg-3">recipe:</span>
          <code className="text-agent font-mono break-all">{f.suggestedRecipe}</code>
        </div>
      )}
    </li>
  );
}
