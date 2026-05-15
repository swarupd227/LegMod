import { useEffect, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Sparkles, Package, Download, FileText, ScrollText, RefreshCw,
  AlertTriangle, FileCode, Tags, Activity, Hash, Check, FileBox
} from 'lucide-react';
import { api, Bundle, Project } from '../api/client';
import { hasRole } from '../auth/authClient';
import {
  TwoPane, EmptyState, LoadingState, ErrorState,
  Tabs, TabPanel, IconButton, MarkdownView,
  useStageChrome, useAnnouncer
} from '../components/ui';

export default function ReportsHub({
  projectId, project
}: { projectId: string; project: Project }) {
  const qc = useQueryClient();
  const { setStatus, setActions } = useStageChrome();
  const { announce } = useAnnouncer();

  const { data: status, isLoading, error, refetch } = useQuery({
    queryKey: ['reports', projectId],
    queryFn: () => api.reportsStatus(projectId),
    refetchInterval: 8000
  });

  const { data: closure } = useQuery({
    queryKey: ['closure', projectId],
    queryFn: () => api.closureMarkdown(projectId),
    enabled: !!status?.bundle && status.bundle.status === 'completed'
  });

  const buildM = useMutation({
    mutationFn: () => api.buildBundle(projectId),
    onSuccess: () => {
      announce('Migration package built');
      qc.invalidateQueries({ queryKey: ['reports', projectId] });
      qc.invalidateQueries({ queryKey: ['closure', projectId] });
      qc.invalidateQueries({ queryKey: ['project', projectId] });
    }
  });

  // Inject chrome content.
  useEffect(() => {
    const bundle = status?.bundle;
    if (!bundle) {
      setStatus([]);
      setActions(null);
      return;
    }
    const ok = bundle.status === 'completed';
    setStatus([
      { label: bundle.status, value: ok ? '✓' : bundle.status === 'building' ? '…' : '!',
        tone: ok ? 'ok' : bundle.status === 'building' ? 'warn' : 'err' },
      { label: 'files',  value: bundle.fileCount ?? 0, tone: 'brand' },
      { label: 'size',   value: fmtSize(bundle.sizeBytes ?? 0), tone: 'neutral' }
    ]);
    setActions(
      <>
        <IconButton icon={<RefreshCw size={13} />} ariaLabel="Refresh bundle status" onClick={() => refetch()} />
        <a
          className={`btn-secondary btn-sm ${ok ? '' : 'opacity-40 pointer-events-none'}`}
          href={api.bundleDownloadUrl(projectId)}
          aria-disabled={!ok}
        >
          <Download size={13} aria-hidden="true" /> Download ZIP
        </a>
        <button
          className="btn-primary btn-sm"
          onClick={() => buildM.mutate()}
          disabled={buildM.isPending || !hasRole('TECH_LEAD')}
          title={!hasRole('TECH_LEAD')
            ? 'Building the migration package requires the Tech Lead role'
            : 'Rebuild the migration package from current data'}
        >
          <Package size={13} aria-hidden="true" /> {buildM.isPending ? 'Building…' : 'Re-build'}
        </button>
      </>
    );
  }, [status, buildM.isPending]); // eslint-disable-line react-hooks/exhaustive-deps

  if (isLoading) return <LoadingState />;
  if (error) {
    return <ErrorState
      title="Reports service unreachable"
      detail="The reports-service didn't respond."
      onRetry={() => refetch()}
    />;
  }

  const bundle = status?.bundle;
  if (!bundle) {
    return <NotBuilt onBuild={() => buildM.mutate()} building={buildM.isPending} project={project} />;
  }

  return (
    <TwoPane
      rail="default"
      left={<ManifestPane bundle={bundle} project={project} />}
    >
      <ClosurePane closure={closure} bundle={bundle} />
    </TwoPane>
  );
}

/* ---------------- Empty state ---------------- */

function NotBuilt({
  onBuild, building, project
}: { onBuild: () => void; building: boolean; project: Project }) {
  return (
    <EmptyState
      icon={Package}
      accent="gradient"
      title="Build the migration package"
      body={<>
        Atlas folds together every Stage A–E artifact: the Authoritative WSDL,
        generated Java sources, JAXB bindings, decision log, parity report, and the
        Migration Closure document — into a single ZIP that the engagement
        architect hands to{' '}
        <span className="font-medium text-fg-1">{project.vendorPartner ?? 'the client'}</span>.
      </>}
      primaryAction={
        <button
          className="btn-brand"
          disabled={building || !hasRole('TECH_LEAD')}
          onClick={onBuild}
          title={!hasRole('TECH_LEAD')
            ? 'Building the migration package requires the Tech Lead role'
            : undefined}
        >
          <Package size={14} aria-hidden="true" /> {building ? 'Building…' : 'Build migration package'}
        </button>
      }
    />
  );
}

function fmtSize(b: number) {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / 1024 / 1024).toFixed(2)} MB`;
}

/* ---------------- Left pane: bundle manifest ---------------- */

function ManifestPane({
  bundle, project
}: { bundle: Bundle; project: Project }) {
  const items: { icon: any; tint: 'brand' | 'agent' | 'ok'; title: string; subtitle: string }[] = [
    { icon: ScrollText, tint: 'brand', title: 'Authoritative WSDL',
      subtitle: 'synthesised from reconciliation' },
    { icon: FileText,   tint: 'agent', title: 'Bindings (.xjb)',
      subtitle: 'derived from arch.adapter' },
    { icon: FileCode,   tint: 'brand', title: 'JAX-WS source tree',
      subtitle: 'wsimport output' },
    { icon: Tags,       tint: 'agent', title: 'Operations report',
      subtitle: 'reports/operations.json' },
    { icon: Activity,   tint: 'agent', title: 'Decisions log',
      subtitle: 'reports/decisions.json' },
    { icon: AlertTriangle, tint: 'agent', title: 'Differential report',
      subtitle: 'reports/differential.json' },
    { icon: Hash,       tint: 'ok',    title: 'Manifest',
      subtitle: 'manifest.json (hashes)' },
    { icon: Sparkles,   tint: 'agent', title: 'Closure document',
      subtitle: 'closure.md' }
  ];

  return (
    <>
      <div className="px-4 py-3 border-b border-line">
        <div className="eyebrow">Bundle contents</div>
        <div className="mt-1 text-2xs text-fg-3 font-mono">
          migration-package.zip
        </div>
      </div>

      <ul className="flex-1 overflow-y-auto p-3 space-y-2" role="list">
        {items.map(it => (
          <li key={it.title} className="surface p-3">
            <div className="flex items-start gap-3">
              <Tinted icon={it.icon} tint={it.tint} />
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium text-fg-1 truncate">{it.title}</div>
                <div className="text-2xs text-fg-3 truncate">{it.subtitle}</div>
              </div>
              <Check size={14} className="text-ok mt-0.5 shrink-0" aria-hidden="true" />
              <span className="sr-only">Included</span>
            </div>
          </li>
        ))}
      </ul>

      <div className="p-3 border-t border-line bg-surface/60 text-2xs text-fg-3">
        Built {new Date(bundle.builtAt).toLocaleString()}
        {project.vendorPartner && <> · for {project.vendorPartner}</>}
      </div>
    </>
  );
}

function Tinted({ icon: Icon, tint }: { icon: any; tint: 'brand' | 'agent' | 'ok' }) {
  const cls =
    tint === 'brand' ? 'text-brand bg-brand-50'
  : tint === 'agent' ? 'text-agent bg-agent-50'
                     : 'text-ok bg-ok-50';
  return (
    <div className={`h-8 w-8 rounded-md inline-flex items-center justify-center shrink-0 ${cls}`}>
      <Icon size={14} strokeWidth={1.75} />
    </div>
  );
}

/* ---------------- Center pane: closure doc preview ---------------- */

function ClosurePane({
  closure, bundle
}: { closure?: string; bundle: Bundle }) {
  const [tab, setTab] = useState<'preview' | 'raw'>('preview');

  if (bundle.status !== 'completed') {
    return (
      <div className="flex items-center justify-center text-sm text-fg-3 p-10">
        Building bundle…
      </div>
    );
  }

  if (!closure) {
    return (
      <div className="flex items-center justify-center text-sm text-fg-3 p-10">
        Closure document not available.
      </div>
    );
  }

  return (
    <div className="flex flex-col min-w-0 h-full">
      <Tabs<'preview' | 'raw'>
        ariaLabel="Closure document view"
        value={tab}
        onChange={setTab}
        tabs={[
          { id: 'preview', label: 'Closure preview', icon: FileBox },
          { id: 'raw',     label: 'Raw markdown',    icon: ScrollText }
        ]}
      />

      <div className="flex-1 overflow-auto">
        <TabPanel id="closure-panel-preview" tabId="closure-tab-preview" active={tab === 'preview'}>
          {tab === 'preview' && (
            <div className="px-8 py-7 max-w-3xl">
              <MarkdownView body={closure} />
            </div>
          )}
        </TabPanel>
        <TabPanel id="closure-panel-raw" tabId="closure-tab-raw" active={tab === 'raw'}>
          {tab === 'raw' && (
            <pre className="m-0 p-5 text-xs font-mono text-fg-1 whitespace-pre-wrap break-words">
              {closure}
            </pre>
          )}
        </TabPanel>
      </div>
    </div>
  );
}
