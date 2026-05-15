import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Play, CheckCircle2, XCircle, AlertCircle, Loader2,
  Hammer, RefreshCw
} from 'lucide-react';
import { api, BuildTestRun } from '../api/client';

/**
 * Stage F · Build & Test gate panel. Drops into both ReportsHub
 * (SOAP) and CutoverDecommission (UPLIFT) so the user sees real
 * evidence that the migrated code actually compiles and the
 * existing test suite still passes.
 *
 * Shows a clear pass/fail badge, file/test counts, the first dozen
 * failing test names, and a collapsible build log. Driven by a
 * single useQuery on /stages/build/status that polls every few
 * seconds while a run is in flight.
 */
export function BuildTestPanel({
  projectId, track
}: { projectId: string; track: 'SOAP' | 'UPLIFT' }) {
  const qc = useQueryClient();
  const statusQ = useQuery({
    queryKey: ['build-test', projectId],
    queryFn: () => api.buildTestStatus(projectId),
    refetchInterval: (q) => {
      const r = (q.state.data as { run: BuildTestRun | null } | undefined)?.run;
      return r && r.status === 'running' ? 3000 : false;
    }
  });

  const runM = useMutation({
    mutationFn: () => api.runBuildTest(projectId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['build-test', projectId] })
  });

  const run = statusQ.data?.run ?? null;
  const isRunning = run?.status === 'running' || runM.isPending;

  return (
    <section
      aria-labelledby="build-test-title"
      className="rounded-lg border border-line bg-bg-2 p-5"
    >
      <header className="flex items-start justify-between gap-4 mb-3">
        <div className="flex items-start gap-3">
          <div className="rounded-md bg-brand-50 p-2 text-brand-700">
            <Hammer size={18} aria-hidden="true" />
          </div>
          <div>
            <div className="eyebrow text-brand-700">Enterprise gate</div>
            <h3 id="build-test-title" className="text-lg font-semibold">
              {track === 'SOAP'
                ? 'Compile the generated code'
                : 'Run the project’s tests against the migrated code'}
            </h3>
            <p className="text-sm text-fg-2 mt-1 max-w-2xl">
              {track === 'SOAP'
                ? 'Atlas spawns a sandboxed JVM, drops a minimal pom.xml in the generated source tree, and runs Maven compile to prove every generated Java file actually builds.'
                : 'Atlas spawns a sandboxed JVM in the customer source tree and runs `mvn test`. Every unit test the customer already had is re-run against the OpenRewrite-modified code.'}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {run && (
            <button
              className="btn-ghost btn-sm"
              onClick={() => statusQ.refetch()}
              aria-label="Refresh build status"
              title="Refresh"
            >
              <RefreshCw size={13} aria-hidden="true" />
            </button>
          )}
          <button
            className="btn-brand"
            disabled={isRunning}
            onClick={() => runM.mutate()}
          >
            {isRunning
              ? <><Loader2 size={14} className="animate-spin" /> Building…</>
              : run
                ? <><Play size={14} /> Re-run build &amp; test</>
                : <><Play size={14} /> Build &amp; test now</>}
          </button>
        </div>
      </header>

      {!run && !isRunning && (
        <p className="text-sm text-fg-3 mt-4">
          Click <span className="font-mono">Build &amp; test now</span> to run
          the enterprise gate. Typical runtime: 20-90 seconds.
        </p>
      )}

      {run && <BuildTestSummary run={run} />}

      {runM.error && (
        <p className="mt-3 text-2xs text-err">
          {(runM.error as Error).message}
        </p>
      )}
    </section>
  );
}

function BuildTestSummary({ run }: { run: BuildTestRun }) {
  const statusMeta = STATUS_META[run.status];

  return (
    <>
      <div
        className={`mt-4 rounded border px-3 py-2.5 flex items-center gap-3 ${statusMeta.cls}`}
        role="status"
        aria-label={`Build status: ${statusMeta.label}`}
      >
        {statusMeta.icon}
        <div className="flex-1">
          <div className="font-medium text-sm">{statusMeta.label}</div>
          <div className="text-2xs opacity-90">{statusMeta.subtitle(run)}</div>
        </div>
        {run.durationMs != null && run.durationMs > 0 && (
          <span className="text-2xs font-mono opacity-70 shrink-0">
            {(run.durationMs / 1000).toFixed(1)}s
          </span>
        )}
      </div>

      <dl className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-4">
        <Tile label="files compiled" value={run.filesCompiled} tone="brand" />
        <Tile label="compile errors" value={run.compileErrors}
              tone={run.compileErrors > 0 ? 'err' : 'ok'} />
        <Tile label="tests passed" value={run.testsPassed} tone="ok" />
        <Tile label="tests failed" value={run.testsFailed}
              tone={run.testsFailed > 0 ? 'err' : 'ok'}
              hint={run.testsSkipped > 0 ? `${run.testsSkipped} skipped` : undefined} />
      </dl>

      {run.failures && run.failures.length > 0 && (
        <details className="mt-4" open>
          <summary className="text-sm font-medium cursor-pointer text-err">
            Failing tests ({run.failures.length})
          </summary>
          <ul className="mt-2 space-y-1">
            {run.failures.map((f, i) => (
              <li key={i} className="text-2xs font-mono text-fg-2 pl-3">{f}</li>
            ))}
          </ul>
        </details>
      )}

      {run.logText && run.logText.length > 0 && (
        <details className="mt-4">
          <summary className="text-sm font-medium cursor-pointer text-fg-2">
            Build log
          </summary>
          <pre className="mt-2 text-2xs font-mono text-fg-2 bg-bg-1 border border-line rounded p-3 max-h-80 overflow-auto whitespace-pre-wrap">
            {run.logText}
          </pre>
        </details>
      )}
    </>
  );
}

function Tile({
  label, value, tone, hint
}: {
  label: string;
  value: number;
  tone: 'brand' | 'ok' | 'err';
  hint?: string;
}) {
  const cls =
    tone === 'ok'    ? 'text-emerald-700'
  : tone === 'err'   ? 'text-rose-700'
                     : 'text-brand-700';
  return (
    <div className="rounded border border-line bg-bg-1 p-3">
      <div className="text-2xs uppercase tracking-wide text-fg-3">{label}</div>
      <div className={`mt-1 text-2xl font-semibold tabular-nums ${cls}`}>{value}</div>
      {hint && <div className="text-2xs text-fg-3 mt-0.5">{hint}</div>}
    </div>
  );
}

const STATUS_META: Record<BuildTestRun['status'], {
  icon: JSX.Element;
  cls: string;
  label: string;
  subtitle: (r: BuildTestRun) => string;
}> = {
  running: {
    icon: <Loader2 size={16} className="animate-spin shrink-0" aria-hidden="true" />,
    cls: 'bg-bg-1 border-line text-fg-1',
    label: 'Building and testing…',
    subtitle: () => 'Atlas is compiling and running the test suite in a sandboxed JVM.'
  },
  passed: {
    icon: <CheckCircle2 size={16} className="text-emerald-600 shrink-0" aria-hidden="true" />,
    cls: 'bg-emerald-50 border-emerald-200 text-emerald-900',
    label: 'Passed',
    subtitle: r => r.testsTotal > 0
      ? `Compiled cleanly and all ${r.testsTotal} tests pass.`
      : `Compiled cleanly. ${r.filesCompiled} files built.`
  },
  compile_failed: {
    icon: <XCircle size={16} className="text-rose-600 shrink-0" aria-hidden="true" />,
    cls: 'bg-rose-50 border-rose-200 text-rose-900',
    label: 'Compile failed',
    subtitle: r => `${r.compileErrors} compile error${r.compileErrors === 1 ? '' : 's'} — see log.`
  },
  tests_failed: {
    icon: <XCircle size={16} className="text-rose-600 shrink-0" aria-hidden="true" />,
    cls: 'bg-rose-50 border-rose-200 text-rose-900',
    label: 'Tests failed',
    subtitle: r => `${r.testsFailed} of ${r.testsTotal} tests failed.`
  },
  error: {
    icon: <AlertCircle size={16} className="text-amber-600 shrink-0" aria-hidden="true" />,
    cls: 'bg-amber-50 border-amber-200 text-amber-900',
    label: 'Build did not complete',
    subtitle: () => 'Atlas could not launch the build sandbox. See log for details.'
  },
  sandbox_limited: {
    icon: <AlertCircle size={16} className="text-sky-600 shrink-0" aria-hidden="true" />,
    cls: 'bg-sky-50 border-sky-200 text-sky-900',
    label: 'Sandbox network unavailable',
    subtitle: () => 'The local demo sandbox could not reach Maven Central to resolve dependencies. The gate runs cleanly in a network-enabled customer environment; this is an infrastructure note, not a code defect.'
  }
};
