import { useEffect, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Play, Sparkles, AlertTriangle, Check, Circle as CircleIcon,
  Cpu, FileCode, Tags, Flag, Pencil, X as XIcon, MessageSquare,
  RefreshCw
} from 'lucide-react';
import { api, Operation, Project } from '../api/client';
import { ConfidenceBadge, FlagBadge } from '../components/Badges';
import {
  ThreePane, EmptyState, LoadingState, ErrorState,
  Tabs, TabPanel, KeyValuePair, IconButton,
  useStageChrome, useAnnouncer
} from '../components/ui';
import { MigrationForecastCard } from './MigrationForecast';

type Tab = 'source' | 'mappings' | 'wire' | 'flags';

export default function CodeArchaeology({
  projectId, project
}: { projectId: string; project: Project }) {
  const qc = useQueryClient();
  const { setStatus, setActions } = useStageChrome();
  const { announce } = useAnnouncer();

  const operationsQ = useQuery({
    queryKey: ['operations', projectId],
    queryFn: () => api.operations(projectId)
  });
  const adaptersQ = useQuery({
    queryKey: ['adapters', projectId],
    queryFn: () => api.adapters(projectId)
  });

  const operations = operationsQ.data ?? [];
  const adapters = adaptersQ.data ?? [];
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = operations.find(o => o.id === selectedId) ?? operations[0];

  const runM = useMutation({
    mutationFn: () => api.runArchaeology(projectId),
    onSuccess: () => {
      announce('Archaeology run complete');
      qc.invalidateQueries({ queryKey: ['operations', projectId] });
      qc.invalidateQueries({ queryKey: ['adapters', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    }
  });

  const attachM = useMutation({
    mutationFn: (path: string) => api.attachSource(projectId, path),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['project', projectId] })
  });

  // Inject chrome status + actions whenever they change.
  useEffect(() => {
    if (!project.sourcePath || operations.length === 0) {
      setStatus([]);
      setActions(null);
      return;
    }
    setStatus([
      { label: 'operations', value: operations.length, tone: 'brand' },
      { label: adapters.length === 1 ? 'adapter' : 'adapters', value: adapters.length, tone: 'agent' }
    ]);
    setActions(
      <button
        className="btn-secondary btn-sm"
        onClick={() => runM.mutate()}
        disabled={runM.isPending}
      >
        <Play size={13} aria-hidden="true" />
        {runM.isPending ? 'Running…' : 'Re-run agent'}
      </button>
    );
  }, [project.sourcePath, operations.length, adapters.length, runM.isPending]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!project.sourcePath) {
    return <NoSource onAttach={p => attachM.mutate(p)} attaching={attachM.isPending} />;
  }
  if (operationsQ.isLoading) return <LoadingState />;
  if (operationsQ.error) {
    return <ErrorState
      title="Archaeology service unreachable"
      detail="The arch-service didn't respond. Once it's back online, retry to load the recovered operations."
      onRetry={() => operationsQ.refetch()}
    />;
  }
  if (operations.length === 0) {
    return (
      <div className="p-5">
        <MigrationForecastCard projectId={projectId} />
        <NoRunYet
          sourcePath={project.sourcePath}
          onRun={() => runM.mutate()}
          running={runM.isPending}
          error={runM.error as Error | undefined}
        />
      </div>
    );
  }

  return (
    <ThreePane
      leftRail="default"
      left={
        <OperationsList
          operations={operations}
          selectedId={selected?.id}
          onSelect={setSelectedId}
        />
      }
      right={<AgentPane operation={selected} />}
    >
      <CenterPane operation={selected} />
    </ThreePane>
  );
}

/* ---------------- Empty states ---------------- */

function NoSource({
  onAttach, attaching
}: { onAttach: (p: string) => void; attaching: boolean }) {
  const [path, setPath] = useState('');
  return (
    <EmptyState
      icon={FileCode}
      title="Attach a source tree"
      body="Point this project at a directory accessible by the archaeology service."
      hint={
        <span>The path lives inside the arch-service container — typically a mount of the source repository's working tree.</span>
      }
      primaryAction={
        <div className="flex gap-2 max-w-lg w-full">
          <label className="flex-1">
            <span className="sr-only">Source path</span>
            <input
              className="input font-mono text-xs"
              placeholder="/path/to/source"
              value={path}
              onChange={e => setPath(e.target.value)}
              aria-label="Source path"
            />
          </label>
          <button
            className="btn-primary"
            disabled={attaching || !path.trim()}
            onClick={() => onAttach(path.trim())}
          >
            {attaching ? 'Attaching…' : 'Attach source'}
          </button>
        </div>
      }
    />
  );
}

function NoRunYet({
  sourcePath, onRun, running, error
}: { sourcePath: string; onRun: () => void; running: boolean; error?: Error }) {
  return (
    <EmptyState
      icon={Sparkles}
      accent="gradient"
      title="Source attached. Ready for archaeology."
      body={
        <>
          <code className="code-chip">{sourcePath}</code>
          <p className="mt-3">
            Atlas walks the source, extracts SOAP operations, type mappings, and custom
            adapters, then narrates each operation with a confidence rating.
          </p>
        </>
      }
      primaryAction={
        <button className="btn-brand" disabled={running} onClick={onRun}>
          <Play size={14} aria-hidden="true" /> {running ? 'Running archaeology…' : 'Run agent'}
        </button>
      }
      hint={error && <span className="text-err">{error.message}</span>}
    />
  );
}

/* ---------------- Operations list (left rail) ---------------- */

function OperationsList({
  operations, selectedId, onSelect
}: {
  operations: Operation[];
  selectedId?: string;
  onSelect: (id: string) => void;
}) {
  const accepted = operations.filter(o => o.decisionState === 'accepted').length;
  const flagged  = operations.filter(o => o.flags.length > 0 && o.decisionState !== 'accepted').length;
  const pending  = operations.length - accepted - flagged;

  return (
    <>
      <div className="px-4 py-3 border-b border-line">
        <div className="eyebrow">Operations</div>
        <div className="mt-1.5 flex items-center gap-3 text-2xs">
          <span className="inline-flex items-center gap-1 text-ok"
                aria-label={`${accepted} accepted`}>
            <span className="dot bg-ok" aria-hidden="true" /> {accepted}
          </span>
          <span className="inline-flex items-center gap-1 text-warn"
                aria-label={`${flagged} flagged`}>
            <span className="dot bg-warn" aria-hidden="true" /> {flagged}
          </span>
          <span className="inline-flex items-center gap-1 text-fg-3"
                aria-label={`${pending} pending`}>
            <span className="dot bg-line2" aria-hidden="true" /> {pending}
          </span>
        </div>
      </div>

      <ul className="flex-1 overflow-y-auto py-1" role="list">
        {operations.map(op => {
          const isSel = op.id === selectedId;
          const status = op.decisionState === 'accepted' ? 'ok'
                       : op.flags.length > 0 ? 'warn' : 'pending';
          const Icon = status === 'ok' ? Check
                     : status === 'warn' ? AlertTriangle
                     : CircleIcon;
          const iconCls =
            status === 'ok'   ? 'text-ok'
          : status === 'warn' ? 'text-warn'
                              : 'text-fg-4';
          return (
            <li key={op.id}>
              <button
                onClick={() => onSelect(op.id)}
                aria-current={isSel ? 'true' : undefined}
                className={`w-full text-left px-4 py-2.5 flex items-start gap-2.5
                           border-l-2 transition-colors duration-150
                           ${isSel
                             ? 'bg-surface border-brand'
                             : 'border-transparent hover:bg-subtle'}`}
              >
                <Icon size={14} strokeWidth={2} className={`mt-1 ${iconCls}`} aria-hidden="true" />
                <div className="min-w-0 flex-1">
                  <div className="font-mono text-sm text-fg-1 truncate">{op.name}</div>
                  <div className="text-2xs text-fg-3 truncate font-mono">
                    {op.inputType} → {op.outputType}
                  </div>
                  {op.flags.length > 0 && (
                    <div className="mt-1.5 flex flex-wrap gap-1">
                      {op.flags.slice(0, 2).map(f => (
                        <FlagBadge key={f} flag={f} />
                      ))}
                    </div>
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

/* ---------------- Center pane ---------------- */

function CenterPane({ operation }: { operation?: Operation }) {
  const [tab, setTab] = useState<Tab>('source');
  if (!operation) {
    return <div className="p-8 text-sm text-fg-3">Select an operation to inspect.</div>;
  }
  const tabs = [
    { id: 'source' as const,   label: 'Source',        icon: FileCode },
    { id: 'mappings' as const, label: 'Type mappings', icon: Tags, count: operation.typeMappings?.length },
    { id: 'wire' as const,     label: 'Wire hints',    icon: Cpu },
    { id: 'flags' as const,    label: 'Flags',         icon: Flag, count: operation.flags?.length }
  ];

  return (
    <div className="flex flex-col min-w-0 h-full">
      <div className="px-5 pt-4 pb-2">
        <div className="flex items-baseline gap-3 min-w-0 flex-wrap">
          <h3 className="font-mono text-base font-semibold text-fg-1 truncate">{operation.name}</h3>
          <ConfidenceBadge confidence={operation.confidence} />
        </div>
        <div className="mt-1 text-xs text-fg-3 font-mono truncate">
          {operation.namespace}
        </div>
        <div className="mt-0.5 text-xs text-fg-3 truncate">
          {operation.sourceClass}
          {operation.sourceLines?.[0] != null && (
            <>
              <span aria-hidden="true" className="text-fg-4 ml-2">·</span>
              <span className="ml-2 font-mono">line {operation.sourceLines[0]}</span>
            </>
          )}
        </div>
      </div>

      <Tabs<Tab> tabs={tabs} value={tab} onChange={setTab} ariaLabel="Operation detail tabs" />

      <div className="p-5 flex-1 overflow-auto">
        <TabPanel id="op-panel-source"   tabId="op-tab-source"   active={tab === 'source'}>
          {tab === 'source' && <SourceTab op={operation} />}
        </TabPanel>
        <TabPanel id="op-panel-mappings" tabId="op-tab-mappings" active={tab === 'mappings'}>
          {tab === 'mappings' && <MappingsTab op={operation} />}
        </TabPanel>
        <TabPanel id="op-panel-wire"     tabId="op-tab-wire"     active={tab === 'wire'}>
          {tab === 'wire' && <WireTab op={operation} />}
        </TabPanel>
        <TabPanel id="op-panel-flags"    tabId="op-tab-flags"    active={tab === 'flags'}>
          {tab === 'flags' && <FlagsTab op={operation} />}
        </TabPanel>
      </div>
    </div>
  );
}

function SourceTab({ op }: { op: Operation }) {
  return (
    <dl>
      <KeyValuePair label="Source class" value={<code className="font-mono text-fg-1">{op.sourceClass}</code>} />
      <KeyValuePair label="Lines"        value={<code className="code-chip">{op.sourceLines?.join('–') ?? '—'}</code>} />
      <KeyValuePair label="Input"        value={<code className="font-mono text-fg-1">{op.inputType}</code>} />
      <KeyValuePair label="Output"       value={<code className="font-mono text-fg-1">{op.outputType}</code>} />
      <KeyValuePair label="Faults" value={
        op.faultTypes?.length
          ? <code className="font-mono text-fg-1">{op.faultTypes.join(', ')}</code>
          : <span className="text-fg-4">none</span>
      } />
    </dl>
  );
}

function MappingsTab({ op }: { op: Operation }) {
  if (!op.typeMappings?.length) {
    return <div className="text-sm text-fg-3">No type mappings recovered.</div>;
  }
  return (
    <div className="surface-soft overflow-auto">
      <table className="w-full text-sm">
        <thead className="bg-canvas border-b border-line text-2xs uppercase tracking-wider text-fg-3">
          <tr>
            <th scope="col" className="text-left font-medium px-4 py-2">Java type</th>
            <th scope="col" className="text-left font-medium px-4 py-2">QName</th>
            <th scope="col" className="text-left font-medium px-4 py-2">Adapter</th>
          </tr>
        </thead>
        <tbody>
          {op.typeMappings.map((m, i) => (
            <tr key={i} className="border-b border-line last:border-0">
              <td className="px-4 py-2 font-mono text-fg-1">{m.javaType}</td>
              <td className="px-4 py-2 font-mono text-fg-2 break-all">
                <span className="text-fg-4">{`{${m.qnameNamespace}}`}</span>
                <span className="text-fg-1">{m.qnameLocal}</span>
              </td>
              <td className="px-4 py-2 font-mono text-fg-2">
                {m.adapterFqn || <span className="text-fg-4">—</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function WireTab({ op }: { op: Operation }) {
  return (
    <dl>
      <KeyValuePair label="SOAP style" value={<code className="code-chip">DOCUMENT</code>} />
      <KeyValuePair label="SOAP use"   value={<code className="code-chip">LITERAL</code>} />
      <KeyValuePair label="Namespace"  value={<code className="font-mono text-fg-1 break-all">{op.namespace}</code>} />
      <p className="mt-3 text-xs text-fg-3 max-w-md">
        Wire shape inferred from the interface declaration. Empirical confirmation
        arrives in Stage B (Runtime Capture).
      </p>
    </dl>
  );
}

function FlagsTab({ op }: { op: Operation }) {
  if (!op.flags?.length) {
    return <div className="text-sm text-fg-3">No flags raised.</div>;
  }
  return (
    <ul className="space-y-2">
      {op.flags.map(f => (
        <li key={f} className="flex items-center gap-2.5">
          <AlertTriangle size={14} className="text-warn" aria-hidden="true" />
          <FlagBadge flag={f} />
          <span className="text-xs text-fg-3">— review for wire compatibility impact.</span>
        </li>
      ))}
    </ul>
  );
}

/* ---------------- Agent pane (right rail) ---------------- */

function AgentPane({ operation }: { operation?: Operation }) {
  if (!operation) return <div className="agent-surface flex-1" />;

  return (
    <aside className="agent-surface flex flex-col h-full" aria-label="Code Archaeology agent">
      <div className="px-5 py-4 border-b border-line">
        <div className="flex items-center gap-2">
          <div className="h-7 w-7 rounded-full bg-gradient-to-br from-agent to-brand text-white inline-flex items-center justify-center shadow-sm">
            <Sparkles size={13} strokeWidth={2} aria-hidden="true" />
          </div>
          <div>
            <div className="text-2xs font-mono uppercase tracking-wider text-fg-3">Agent</div>
            <div className="text-sm font-semibold text-fg-1 leading-tight">Code Archaeology</div>
          </div>
        </div>
      </div>

      <div className="px-5 py-4 flex-1 overflow-y-auto space-y-5">
        <Section title="Confidence">
          <ConfidenceBadge confidence={operation.confidence} />
        </Section>

        <Section title="Narrative">
          {operation.narrative ? (
            <>
              <p className="text-sm text-fg-1 leading-relaxed">
                {operation.narrative.text}
              </p>
              <div className="mt-3 flex items-center flex-wrap gap-2 text-2xs text-fg-3 font-mono">
                <span className="badge-agent">{operation.narrative.model}</span>
                {!operation.narrative.stub && (
                  <>
                    <span aria-hidden="true">·</span>
                    <span>{operation.narrative.tokensIn}+{operation.narrative.tokensOut} tok</span>
                    <span aria-hidden="true">·</span>
                    <span>{operation.narrative.latencyMs}ms</span>
                  </>
                )}
              </div>
            </>
          ) : (
            <p className="text-sm text-fg-4 italic">Awaiting narrative.</p>
          )}
        </Section>

        <Section title="Flags">
          {operation.flags.length === 0
            ? <span className="text-sm text-fg-4">None</span>
            : <div className="flex flex-wrap gap-1.5">
                {operation.flags.map(f => <FlagBadge key={f} flag={f} />)}
              </div>}
        </Section>
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
