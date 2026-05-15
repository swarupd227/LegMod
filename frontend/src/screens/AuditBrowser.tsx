import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  ShieldCheck, Sparkles, User, Cog, Search, Download, RefreshCw,
  ChevronDown, ChevronRight, Hash, Activity, Filter, ExternalLink
} from 'lucide-react';
import { api, ProvEntry, Project } from '../api/client';
import { loadConfig } from '../auth/authClient';
import {
  EmptyState, LoadingState, ErrorState, MetricTile, IconButton
} from '../components/ui';

/**
 * Pretty-print a snake_case action name. Renders e.g.
 *   "module_migrated"  → "Module migrated"
 *   "stage_b_finalized" → "Stage B finalized"
 *   "recipe_decision"  → "Recipe decision"
 *
 * The pre-existing hand-maintained label map kept rotting (audit M3); this is
 * good enough for the audit trail and never goes out of date.
 */
function humanize(action: string): string {
  if (!action) return '';
  // Tokenise on underscores; capitalise tokens that aren't single letters
  // (we keep "B" / "C" stage letters as-is).
  return action
    .split('_')
    .map((tok, i) => {
      if (tok.length === 1) return tok.toUpperCase();
      // First word capitalised; rest lower-case to avoid SHOUTY actions.
      return i === 0
        ? tok[0].toUpperCase() + tok.slice(1)
        : tok.toLowerCase();
    })
    .join(' ');
}

export default function AuditBrowser() {
  const [selectedProject, setSelectedProject] = useState<Project | null>(null);

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.projects()
  });

  // Auto-pick first project on load.
  const project = selectedProject ?? projects?.[0];

  return (
    <>
      <div className="flex items-end justify-between gap-6">
        <div>
          <div className="eyebrow">Audit trail</div>
          <h1 className="mt-1 text-3xl font-semibold tracking-tight text-fg-1 text-balance">
            Provenance Browser
          </h1>
          <p className="mt-2 text-sm text-fg-2 max-w-2xl">
            Every AI-generated decision, every human review, every artifact
            transformation. The browser is read-only and append-only — entries
            cannot be edited or deleted.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <ProjectPicker
            value={project}
            options={projects ?? []}
            onChange={setSelectedProject}
          />
          {project && (
            <a className="btn-secondary"
               href={api.provenanceExportUrl(project.id)}>
              <Download size={14} /> Export CSV
            </a>
          )}
        </div>
      </div>

      {project ? (
        <ProjectAudit project={project} />
      ) : (
        <EmptyState
          icon={ShieldCheck}
          title="No projects yet"
          body="Create a project to start collecting provenance entries."
        />
      )}
    </>
  );
}

/* ---------------- Project picker ---------------- */

function ProjectPicker({
  value, options, onChange
}: { value?: Project; options: Project[]; onChange: (p: Project) => void }) {
  return (
    <label className="surface px-3 h-9 flex items-center gap-2 text-sm">
      <Filter size={13} className="text-fg-3" aria-hidden="true" />
      <span className="text-xs text-fg-3">Project</span>
      <select
        aria-label="Select a project to inspect provenance for"
        value={value?.id ?? ''}
        onChange={e => {
          const p = options.find(o => o.id === e.target.value);
          if (p) onChange(p);
        }}
        className="bg-transparent border-0 text-fg-1 font-medium focus:outline-none cursor-pointer pr-1"
      >
        {options.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
      </select>
    </label>
  );
}

/* ---------------- Project-scoped pane ---------------- */

function ProjectAudit({ project }: { project: Project }) {
  const [filter, setFilter] = useState<{
    actorKind?: string; action?: string; q: string;
  }>({ q: '' });
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  // Read the gateway-advertised tracing UI URL once and remember it for
  // the lifetime of the screen. Empty string means "no tracing UI is
  // wired" — `EntryRow` hides the link in that case.
  const [traceUiUrl, setTraceUiUrl] = useState<string>('');
  useEffect(() => {
    let cancelled = false;
    loadConfig().then(cfg => {
      if (!cancelled) setTraceUiUrl(cfg.traceUiUrl ?? '');
    }).catch(() => { /* leave empty — link stays hidden */ });
    return () => { cancelled = true; };
  }, []);

  const { data: entries, isLoading, refetch } = useQuery({
    queryKey: ['prov', project.id, filter],
    queryFn: () => api.provenance(project.id, {
      actorKind: filter.actorKind,
      action: filter.action,
      q: filter.q || undefined,
      limit: 500
    })
  });
  const { data: agg } = useQuery({
    queryKey: ['prov-agg', project.id],
    queryFn: () => api.provenanceAggregate(project.id)
  });

  return (
    <div className="mt-6 grid grid-cols-1 lg:grid-cols-[var(--rail-narrow)_1fr] gap-4">
      <FilterRail
        filter={filter}
        onChange={setFilter}
        agg={agg}
        onRefresh={() => refetch()}
      />

      <div className="min-w-0">
        <Stats agg={agg} />

        <div className="card mt-4 overflow-hidden">
          <div className="px-4 py-3 border-b border-line flex items-center gap-3">
            <h3 className="text-sm font-semibold text-fg-1">Entries</h3>
            <span className="text-xs text-fg-3">
              {isLoading ? '…' : `${entries?.length ?? 0} shown`}
            </span>
            <IconButton icon={<RefreshCw size={13} />} ariaLabel="Refresh entries"
                        onClick={() => refetch()} />
          </div>

          {isLoading && <LoadingState variant="list" label="Loading provenance entries" />}

          {!isLoading && (entries?.length ?? 0) === 0 && (
            <div className="p-10 text-center text-sm text-fg-3">
              No entries match the current filter.
            </div>
          )}

          {!isLoading && entries && entries.length > 0 && (
            <ul className="divide-y divide-line">
              {entries.map(e =>
                <EntryRow
                  key={e.id}
                  entry={e}
                  traceUiUrl={traceUiUrl}
                  expanded={expanded.has(e.id)}
                  onToggle={() => {
                    const s = new Set(expanded);
                    if (s.has(e.id)) s.delete(e.id); else s.add(e.id);
                    setExpanded(s);
                  }}
                />)}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}

/* ---------------- Left filter rail ---------------- */

function FilterRail({
  filter, onChange, agg, onRefresh
}: {
  filter: { actorKind?: string; action?: string; q: string };
  onChange: (f: { actorKind?: string; action?: string; q: string }) => void;
  agg?: { byActor: Array<{ kind: string; count: number }>;
          byAction: Array<{ action: string; count: number }> };
  onRefresh: () => void;
}) {
  return (
    <div className="card p-3 self-start lg:sticky lg:top-4 space-y-4 lg:max-h-[calc(100vh-7rem)] lg:overflow-y-auto"
         role="search"
         aria-label="Audit filters">
      <div>
        <label className="eyebrow block mb-1.5">Search</label>
        <div className="relative">
          <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-fg-4" />
          <input
            value={filter.q}
            onChange={e => onChange({ ...filter, q: e.target.value })}
            placeholder="action / actor / output…"
            className="input pl-8 text-xs h-8" />
        </div>
      </div>

      <div>
        <label className="eyebrow block mb-1.5">Actor</label>
        <div className="space-y-0.5">
          <FilterPill
            active={!filter.actorKind}
            onClick={() => onChange({ ...filter, actorKind: undefined })}
            label="All actors"
            count={(agg?.byActor.reduce((a, b) => a + b.count, 0) ?? 0)}
            icon={Activity}
          />
          {(agg?.byActor ?? []).map(a => (
            <FilterPill
              key={a.kind}
              active={filter.actorKind === a.kind}
              onClick={() => onChange({ ...filter, actorKind: a.kind })}
              label={a.kind}
              count={a.count}
              icon={a.kind === 'agent' ? Sparkles : a.kind === 'human' ? User : Cog}
              tint={a.kind === 'agent' ? 'agent' : a.kind === 'human' ? 'brand' : 'fg'}
            />
          ))}
        </div>
      </div>

      <div>
        <label className="eyebrow block mb-1.5">Action</label>
        <div className="space-y-0.5 max-h-72 overflow-y-auto -mr-1 pr-1">
          <FilterPill
            active={!filter.action}
            onClick={() => onChange({ ...filter, action: undefined })}
            label="Any action"
            count={(agg?.byAction.reduce((a, b) => a + b.count, 0) ?? 0)}
            icon={Hash}
          />
          {(agg?.byAction ?? []).map(a => (
            <FilterPill
              key={a.action}
              active={filter.action === a.action}
              onClick={() => onChange({ ...filter, action: a.action })}
              label={humanize(a.action)}
              count={a.count}
              icon={Hash}
              mono
            />
          ))}
        </div>
      </div>
    </div>
  );
}

function FilterPill({
  active, onClick, label, count, icon: Icon, tint, mono
}: {
  active: boolean; onClick: () => void;
  label: string; count: number; icon: any;
  tint?: 'agent' | 'brand' | 'fg'; mono?: boolean;
}) {
  const iconCls =
    tint === 'agent' ? 'text-agent'
  : tint === 'brand' ? 'text-brand'
                     : 'text-fg-4';
  return (
    <button
      onClick={onClick}
      className={`w-full flex items-center gap-2 px-2 py-1.5 rounded-md text-left
                  text-xs transition-colors ${
        active
          ? 'bg-subtle text-fg-1 font-medium'
          : 'text-fg-2 hover:bg-subtle/60'
      }`}>
      <Icon size={12} className={iconCls} />
      <span className={`truncate flex-1 ${mono ? 'font-mono' : ''}`}>{label}</span>
      <span className="font-mono text-2xs text-fg-3">{count.toLocaleString()}</span>
    </button>
  );
}

/* ---------------- Stats strip ---------------- */

function Stats({ agg }: { agg?: { total: number; tokens: { in: number; out: number; cost: number } } }) {
  const total = agg?.total ?? 0;
  const tIn   = Number(agg?.tokens?.in  ?? 0);
  const tOut  = Number(agg?.tokens?.out ?? 0);
  const cost  = Number(agg?.tokens?.cost ?? 0);
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-3">
      <MetricTile icon={ShieldCheck} label="Total entries"  value={total.toLocaleString()}                  tone="brand" />
      <MetricTile icon={Sparkles}    label="LLM tokens in"  value={tIn.toLocaleString()}                    tone="agent" />
      <MetricTile icon={Sparkles}    label="LLM tokens out" value={tOut.toLocaleString()}                   tone="agent" />
      <MetricTile icon={Hash}        label="Cost (USD)"     value={cost === 0 ? '—' : `$${cost.toFixed(2)}`} tone="ok" />
    </div>
  );
}

/* ---------------- Entry row + expanded detail ---------------- */

function EntryRow({
  entry, traceUiUrl, expanded, onToggle
}: { entry: ProvEntry; traceUiUrl: string; expanded: boolean; onToggle: () => void }) {
  const ActorIcon = entry.actorKind === 'agent' ? Sparkles
                  : entry.actorKind === 'human' ? User : Cog;
  const actorTint = entry.actorKind === 'agent' ? 'text-agent bg-agent-50'
                  : entry.actorKind === 'human' ? 'text-brand bg-brand-50'
                                                : 'text-fg-3 bg-subtle';
  const Chevron = expanded ? ChevronDown : ChevronRight;

  return (
    <li>
      <button
        onClick={onToggle}
        className="w-full text-left px-4 py-3 flex items-start gap-3 hover:bg-subtle/40 transition-colors">
        <div className={`h-7 w-7 rounded-full inline-flex items-center justify-center shrink-0 ${actorTint}`}>
          <ActorIcon size={13} strokeWidth={1.75} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 text-sm">
            <span className="font-medium text-fg-1">
              {humanize(entry.action)}
            </span>
            {entry.model && (
              <span className="badge-agent badge-mono">{entry.model}</span>
            )}
          </div>
          <div className="mt-0.5 text-xs text-fg-3 font-mono truncate">
            {entry.actorId}
          </div>
        </div>
        <div className="text-2xs text-fg-3 font-mono shrink-0 text-right">
          <div>{fmtTime(entry.ts)}</div>
          {(entry.tokensIn ?? 0) + (entry.tokensOut ?? 0) > 0 && (
            <div className="mt-0.5">{(entry.tokensIn ?? 0)}+{(entry.tokensOut ?? 0)} tok</div>
          )}
        </div>
        <Chevron size={14} className="text-fg-4 mt-0.5 shrink-0" />
      </button>

      {expanded && (
        <div className="px-4 pb-4 ml-10 mr-4 grid grid-cols-2 gap-4 text-xs">
          {entry.prompt && <KV k="Prompt" v={entry.prompt} />}
          {entry.output && <KV k="Output" v={entry.output} />}
          {entry.toolCalls && <KV k="Tool calls" v={entry.toolCalls} />}
          {entry.humanReview && <KV k="Human review" v={entry.humanReview} />}
          {entry.links && entry.links.length > 0 && (
            <div className="col-span-2">
              <div className="eyebrow mb-1">Linked artifacts</div>
              <div className="flex flex-wrap gap-1.5">
                {entry.links.map((l, i) =>
                  <code key={i} className="code-chip break-all">{l}</code>)}
              </div>
            </div>
          )}
          <div className="col-span-2 flex items-center gap-3 pt-2 border-t border-line text-fg-4 font-mono">
            <span>id: {entry.id}</span>
            {entry.latencyMs != null && entry.latencyMs > 0 && <span>· {entry.latencyMs}ms</span>}
            {entry.traceId && (
              <span className="ml-auto">
                trace: <code className="code-chip">{entry.traceId.slice(0, 8)}…</code>
                {traceUiUrl && (
                  <a
                    className="inline-flex items-center gap-1 ml-2 text-brand hover:underline normal-case"
                    href={`${traceUiUrl}/trace/${entry.traceId}`}
                    target="_blank"
                    rel="noopener noreferrer"
                    aria-label={`View trace ${entry.traceId} in tracing UI`}>
                    View trace
                    <ExternalLink size={11} aria-hidden="true" />
                  </a>
                )}
              </span>
            )}
          </div>
        </div>
      )}
    </li>
  );
}

function KV({ k, v }: { k: string; v: any }) {
  return (
    <div>
      <div className="eyebrow mb-1">{k}</div>
      <pre className="surface-soft p-2 font-mono text-2xs text-fg-1 whitespace-pre-wrap break-words m-0 max-h-48 overflow-auto">
        {typeof v === 'string' ? v : JSON.stringify(v, null, 2)}
      </pre>
    </div>
  );
}

function fmtTime(iso: string) {
  try {
    const d = new Date(iso);
    return d.toLocaleTimeString('en-US', { hour12: false }) + ' · ' +
           d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  } catch { return iso; }
}
