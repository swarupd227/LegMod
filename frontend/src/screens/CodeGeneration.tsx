import { useEffect, useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Play, Sparkles, Download, FileCode, Folder, ChevronRight, ChevronDown,
  Check, ScrollText, FileText, RefreshCw
} from 'lucide-react';
import { api, GenFile, Project } from '../api/client';
import {
  ThreePane, EmptyState, LoadingState, ErrorState,
  Tabs, TabPanel, IconButton,
  useStageChrome, useAnnouncer
} from '../components/ui';

export default function CodeGeneration({
  projectId, project: _project
}: { projectId: string; project: Project }) {
  const qc = useQueryClient();
  const { setStatus, setActions } = useStageChrome();
  const { announce } = useAnnouncer();

  const { data: status, isLoading, error, refetch } = useQuery({
    queryKey: ['generation', projectId],
    queryFn: () => api.generationStatus(projectId),
    refetchInterval: 8000
  });

  const { data: bindings } = useQuery({
    queryKey: ['generation-bindings', projectId],
    queryFn: () => api.generationBindings(projectId),
    enabled: !!status?.run
  });

  const runM = useMutation({
    mutationFn: () => api.runGeneration(projectId),
    onSuccess: () => {
      announce('Code generation complete');
      qc.invalidateQueries({ queryKey: ['generation', projectId] });
      qc.invalidateQueries({ queryKey: ['generation-bindings', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    }
  });

  // Inject chrome content.
  useEffect(() => {
    if (!status?.run) {
      setStatus([]);
      setActions(null);
      return;
    }
    const r = status.run;
    setStatus([
      { label: 'files',    value: r.fileCount ?? 0,    tone: 'brand' },
      { label: 'errors',   value: r.errorCount ?? 0,   tone: (r.errorCount ?? 0) > 0 ? 'err' : 'ok' },
      { label: 'warnings', value: r.warningCount ?? 0, tone: (r.warningCount ?? 0) > 0 ? 'warn' : 'neutral' }
    ]);
    setActions(
      <>
        <IconButton icon={<RefreshCw size={13} />} ariaLabel="Refresh generation status" onClick={() => refetch()} />
        <a
          className={`btn-secondary btn-sm ${(r.fileCount ?? 0) === 0 ? 'opacity-40 pointer-events-none' : ''}`}
          href={api.generationDownloadUrl(projectId)}
          aria-disabled={(r.fileCount ?? 0) === 0}
        >
          <Download size={13} aria-hidden="true" /> Download ZIP
        </a>
        <button className="btn-primary btn-sm" onClick={() => runM.mutate()} disabled={runM.isPending}>
          <Play size={13} aria-hidden="true" /> {runM.isPending ? 'Generating…' : 'Re-generate'}
        </button>
      </>
    );
  }, [status, runM.isPending]); // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <LoadingState />;
  if (error) {
    return <ErrorState
      title="Generation service unreachable"
      detail="The gen-service didn't respond."
      onRetry={() => refetch()}
    />;
  }
  const s = status!;
  if (!s.run) return <NotStarted onRun={() => runM.mutate()} running={runM.isPending} />;

  return (
    <ThreePane
      leftRail="default"
      left={<InputsPane projectId={projectId} status={s} />}
      right={<OutputTreePane status={s} />}
    >
      <BindingsPane bindings={bindings} status={s} />
    </ThreePane>
  );
}

/* ---------------- Empty state ---------------- */

function NotStarted({ onRun, running }: { onRun: () => void; running: boolean }) {
  return (
    <EmptyState
      icon={Sparkles}
      accent="gradient"
      title="Generate JAX-WS sources"
      body={<>
        Atlas reads the Authoritative WSDL synthesised in Stage C, derives JAXB
        bindings from the adapters detected in Stage A, and runs{' '}
        <code className="code-chip">wsimport</code> in a sandboxed JVM. The output
        is a clean Java source tree, ready to drop into the migrated project.
      </>}
      primaryAction={
        <button className="btn-brand" disabled={running} onClick={onRun}>
          <Play size={14} aria-hidden="true" /> {running ? 'Generating…' : 'Generate'}
        </button>
      }
    />
  );
}

/* ---------------- Left pane: inputs ---------------- */

function InputsPane({ projectId, status }: { projectId: string; status: any }) {
  return (
    <>
      <div className="px-4 py-3 border-b border-line">
        <div className="eyebrow">Inputs</div>
      </div>

      <div className="p-3 space-y-3 flex-1 overflow-y-auto">
        <ArtifactCard
          icon={ScrollText}
          tint="brand"
          title="Authoritative WSDL"
          subtitle="from reconciliation"
          href={api.authoritativeWsdlUrl(projectId)}
        />
        <ArtifactCard
          icon={FileText}
          tint="agent"
          title="Bindings (.xjb)"
          subtitle="auto-derived from arch"
          href={`${api.generationDownloadUrl(projectId).replace('/output.zip', '/bindings')}`}
        />

        <div className="surface p-3">
          <div className="eyebrow mb-2">Build summary</div>
          <Row k="Status"   v={<span className="font-mono text-fg-1">{status.run.status}</span>} />
          <Row k="Files"    v={<span className="font-mono text-fg-1">{status.run.fileCount ?? 0}</span>} />
          <Row k="Errors"   v={
            (status.run.errorCount ?? 0) > 0
              ? <span className="font-mono text-err">{status.run.errorCount}</span>
              : <span className="font-mono text-ok">0</span>
          } />
          <Row k="Warnings" v={
            (status.run.warningCount ?? 0) > 0
              ? <span className="font-mono text-warn">{status.run.warningCount}</span>
              : <span className="font-mono text-fg-3">0</span>
          } />
        </div>
      </div>

      <div className="p-3 border-t border-line bg-surface/60 text-2xs text-fg-3">
        Sandbox: gen-service JVM (Java 21) ·
        <span className="font-mono"> wsimport · jaxws-tools 4.0.2</span>
      </div>
    </>
  );
}

function ArtifactCard({
  icon: Icon, tint, title, subtitle, href
}: { icon: any; tint: 'brand' | 'agent'; title: string; subtitle: string; href: string }) {
  const tintCls = tint === 'brand' ? 'bg-brand-50 text-brand' : 'bg-agent-50 text-agent';
  return (
    <a href={href} target="_blank" rel="noreferrer"
       className="surface p-3 flex items-start gap-3 hover:shadow-md transition-shadow group">
      <div className={`h-8 w-8 rounded-md inline-flex items-center justify-center ${tintCls}`}>
        <Icon size={14} strokeWidth={1.75} />
      </div>
      <div className="min-w-0 flex-1">
        <div className="text-sm font-medium text-fg-1 truncate group-hover:text-brand">
          {title}
        </div>
        <div className="text-2xs text-fg-3 truncate">{subtitle}</div>
      </div>
    </a>
  );
}

function Row({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div className="flex justify-between items-center py-0.5 text-xs">
      <span className="text-fg-3">{k}</span>
      <span>{v}</span>
    </div>
  );
}

/* ---------------- Center pane: bindings .xjb viewer ---------------- */

function BindingsPane({
  bindings, status
}: { bindings?: string; status: any }) {
  const [tab, setTab] = useState<'bindings' | 'log'>('bindings');
  const issueCount = (status.run.errorCount ?? 0) + (status.run.warningCount ?? 0);
  const issueTone = (status.run.errorCount ?? 0) > 0 ? 'err' : 'warn';

  return (
    <div className="flex flex-col min-w-0 h-full">
      <Tabs<'bindings' | 'log'>
        ariaLabel="Generation outputs"
        value={tab}
        onChange={setTab}
        tabs={[
          { id: 'bindings', label: 'Bindings', icon: FileText },
          { id: 'log',      label: 'Generation log', icon: ScrollText,
            count: issueCount, countTone: issueTone }
        ]}
      />

      <TabPanel id="gen-panel-bindings" tabId="gen-tab-bindings" active={tab === 'bindings'}>
        {tab === 'bindings' && <CodeBlock body={bindings ?? '<!-- no bindings yet -->'} lang="xml" />}
      </TabPanel>
      <TabPanel id="gen-panel-log" tabId="gen-tab-log" active={tab === 'log'}>
        {tab === 'log' && <CodeBlock body={status.run.logText ?? '(no log)'} lang="text" />}
      </TabPanel>
    </div>
  );
}

function CodeBlock({ body, lang }: { body: string; lang: string }) {
  return (
    <pre
      className="m-0 p-4 text-xs font-mono text-fg-1 leading-relaxed whitespace-pre-wrap break-words bg-canvas/30 overflow-auto"
      role="region"
      aria-label={lang === 'xml' ? 'Bindings XML' : 'Generation log'}
    >
      <code>{body}</code>
    </pre>
  );
}

/* ---------------- Right pane: output tree ---------------- */

function OutputTreePane({ status }: { status: any }) {
  const tree = useMemo(() => buildTree(status.files ?? []), [status.files]);

  return (
    <aside className="flex flex-col h-full" aria-label="Generated output tree">
      <div className="px-5 py-4 border-b border-line">
        <div className="flex items-center gap-2">
          <div className="h-7 w-7 rounded-full bg-gradient-to-br from-brand to-agent text-white inline-flex items-center justify-center shadow-sm"
               aria-hidden="true">
            <FileCode size={13} strokeWidth={2} />
          </div>
          <div>
            <div className="text-2xs font-mono uppercase tracking-wider text-fg-3">Output</div>
            <div className="text-sm font-semibold text-fg-1 leading-tight">Source tree</div>
          </div>
        </div>
        <div className="mt-3 text-xs text-fg-3">
          <span className="font-mono text-fg-1 font-semibold tabular-nums">{(status.files ?? []).length}</span> files
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-2 text-sm" role="tree">
        {(status.files ?? []).length === 0
          ? <div className="px-3 py-4 text-fg-3 text-xs">No output yet.</div>
          : <TreeNode node={tree} depth={0} defaultOpen />}
      </div>
    </aside>
  );
}

type Node = {
  name: string;
  isDir: boolean;
  size?: number;
  children?: Node[];
};

function buildTree(files: GenFile[]): Node {
  const root: Node = { name: '', isDir: true, children: [] };
  for (const f of files) {
    const parts = f.path.split('/');
    let cursor = root;
    for (let i = 0; i < parts.length; i++) {
      const part = parts[i];
      const last = i === parts.length - 1;
      cursor.children = cursor.children ?? [];
      let next = cursor.children.find(c => c.name === part);
      if (!next) {
        next = { name: part, isDir: !last, size: last ? f.sizeBytes : undefined, children: last ? undefined : [] };
        cursor.children.push(next);
      }
      cursor = next;
    }
  }
  // sort: dirs first, then alphabetical
  const sortRec = (n: Node) => {
    if (!n.children) return;
    n.children.sort((a, b) => (a.isDir === b.isDir ? a.name.localeCompare(b.name) : a.isDir ? -1 : 1));
    n.children.forEach(sortRec);
  };
  sortRec(root);
  return root;
}

function TreeNode({
  node, depth, defaultOpen
}: { node: Node; depth: number; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen ?? depth < 2);
  if (!node.children) {
    return (
      <div className="flex items-center gap-1.5 px-2 py-0.5 hover:bg-subtle rounded font-mono text-xs"
           style={{ paddingLeft: 8 + depth * 12 }}>
        <FileCode size={12} className="text-fg-4 shrink-0" />
        <span className="text-fg-1 truncate">{node.name}</span>
        {node.size != null && (
          <span className="ml-auto text-fg-4 text-2xs">{fmtSize(node.size)}</span>
        )}
      </div>
    );
  }
  // dir
  if (node.name === '') {
    return <ul>{node.children?.map((c, i) => <TreeNode key={i} node={c} depth={depth} defaultOpen={defaultOpen} />)}</ul>;
  }
  return (
    <div>
      <button
        onClick={() => setOpen(o => !o)}
        className="w-full flex items-center gap-1 px-2 py-0.5 hover:bg-subtle rounded font-mono text-xs"
        style={{ paddingLeft: 4 + depth * 12 }}>
        {open ? <ChevronDown size={12} className="text-fg-3 shrink-0" />
              : <ChevronRight size={12} className="text-fg-3 shrink-0" />}
        <Folder size={12} className="text-fg-3 shrink-0" />
        <span className="text-fg-1">{node.name}</span>
        <span className="ml-auto text-fg-4 text-2xs">{node.children.length}</span>
      </button>
      {open && (
        <div>{node.children.map((c, i) => <TreeNode key={i} node={c} depth={depth + 1} />)}</div>
      )}
    </div>
  );
}

function fmtSize(b: number) {
  if (b < 1024) return `${b} B`;
  return `${(b / 1024).toFixed(1)} KB`;
}
