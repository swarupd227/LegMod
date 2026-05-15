import React, { createContext, useContext, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Check, Cog, ChevronLeft, Eye } from 'lucide-react';
import { StageChip } from './StageChip';
import { CountChip, CountChipRow } from './CountChipRow';
import { InlineNotice } from './StateCards';

export type Track = 'SOAP' | 'UPLIFT';

export interface StageDescriptor {
  label: string;       // 'A'..'F'
  name: string;        // 'Code Archaeology'
}

/* ---------------- Chrome host + hook (slot pattern) ---------------- */

interface StageChromeSlots {
  setStatus: (chips: CountChip[]) => void;
  setActions: (actions: React.ReactNode) => void;
  setNotice: (notice: React.ReactNode) => void;
  setSubtitle: (subtitle: React.ReactNode) => void;
}

const StageChromeContext = createContext<StageChromeSlots>({
  setStatus: () => {},
  setActions: () => {},
  setNotice: () => {},
  setSubtitle: () => {}
});

/**
 * Wraps the StageChrome and exposes setters via context. Stage screens use
 * `useStageChrome()` to inject their status chips, action buttons, and any
 * inline notices. The slots reset whenever `viewing` changes so a stage
 * swap starts from a clean slate.
 */
export function StageChromeHost({
  stages, current, viewing, onSelectStage, track, title, reviewing, children
}: {
  stages: StageDescriptor[];
  current: string;
  viewing: string;
  onSelectStage: (label: string) => void;
  track: Track;
  title: string;
  reviewing?: { onReturn: () => void };
  children: React.ReactNode;
}) {
  const [status, setStatus] = useState<CountChip[]>([]);
  const [actions, setActions] = useState<React.ReactNode>(null);
  const [notice, setNotice] = useState<React.ReactNode>(null);
  const [subtitle, setSubtitle] = useState<React.ReactNode>(null);
  // Track the last viewing for synchronous reset (see below).
  const prevViewingRef = useRef(viewing);

  // Reset slots synchronously when the viewing stage changes so the next
  // screen starts blank. We *cannot* use useEffect here: React runs child
  // effects before parent effects, so a useEffect-based reset would clobber
  // any actions the new stage screen already registered during its mount.
  // The sentinel-during-render pattern guarantees the reset is committed
  // before the child screen's mount effect fires.
  if (prevViewingRef.current !== viewing) {
    prevViewingRef.current = viewing;
    // Schedule a state reset for the next render. The new child stage's
    // useEffect will then run after this reset is committed, and its
    // setActions(...) call wins as expected.
    setStatus([]);
    setActions(null);
    setNotice(null);
    setSubtitle(null);
  }

  const slots = useMemo<StageChromeSlots>(
    () => ({ setStatus, setActions, setNotice, setSubtitle }),
    []
  );

  return (
    <StageChromeContext.Provider value={slots}>
      <StageChrome
        stages={stages}
        current={current}
        viewing={viewing}
        onSelectStage={onSelectStage}
        track={track}
        title={title}
        subtitle={subtitle}
        status={status}
        actions={actions}
        notice={notice}
        reviewing={reviewing}
      >
        {children}
      </StageChrome>
    </StageChromeContext.Provider>
  );
}

export function useStageChrome() {
  return useContext(StageChromeContext);
}

/**
 * The stage pipeline (top-of-screen navigation across stages) merged into
 * a single chrome that *replaces* both the previous floating pipeline card
 * and every per-screen stage header. This resolves audit issues C1, C2,
 * C4, C6 in one component.
 *
 * Layout:
 *   ┌───────────────────────────────────────────────────────┐
 *   │ ●━━━●━━━●━━━○━━━○━━━○                                │ ← pipeline
 *   │ A   B   C   D   E   F                                 │
 *   ├───────────────────────────────────────────────────────┤
 *   │ [B] Stage B · Recipe Authoring   [chips]   [actions]  │ ← header
 *   ├───────────────────────────────────────────────────────┤
 *   │ <screen content>                                      │
 *   └───────────────────────────────────────────────────────┘
 *
 * The `status` and `actions` props are filled by the per-stage screen via
 * the StageHeaderSlot helper below — see ProjectDetail.tsx for the
 * coordinator pattern.
 */
export function StageChrome({
  stages,
  current,
  viewing,
  onSelectStage,
  track = 'SOAP',
  title,
  subtitle,
  status = [],
  actions,
  notice,
  reviewing,
  children
}: {
  stages: StageDescriptor[];
  current: string;
  viewing: string;
  onSelectStage: (label: string) => void;
  track?: Track;
  title: string;
  subtitle?: React.ReactNode;
  status?: CountChip[];
  actions?: React.ReactNode;
  notice?: React.ReactNode;
  /** When set, a thin "Reviewing past stage" banner appears with a return action. */
  reviewing?: { onReturn: () => void };
  children?: React.ReactNode;
}) {
  const currentIdx = stages.findIndex(s => s.label === current);
  const viewIdx    = stages.findIndex(s => s.label === viewing);

  return (
    <div className="card mt-4 overflow-hidden" role="region" aria-label="Stage workspace">
      <Pipeline
        stages={stages}
        currentIdx={currentIdx}
        viewIdx={viewIdx}
        onSelect={onSelectStage}
        track={track}
      />

      <div className="stage-chrome border-t border-line">
        <div className="stage-chrome-title">
          <StageChip stage={viewing} track={track} state="current" />
          <div className="min-w-0">
            <div className="text-2xs font-mono uppercase tracking-wider text-fg-3">
              Stage {viewing}{track === 'UPLIFT' ? ' · Track B' : ''}
            </div>
            <h2 className="text-base font-semibold text-fg-1 leading-tight truncate">{title}</h2>
            {subtitle && <div className="mt-0.5 text-2xs text-fg-3 truncate">{subtitle}</div>}
          </div>
        </div>

        {status.length > 0 && <CountChipRow chips={status} />}

        {actions && <div className="stage-chrome-actions">{actions}</div>}
      </div>

      {reviewing && (
        <div className="px-5 py-2 border-t border-line bg-brand-50/60">
          <InlineNotice
            tone="info"
            icon={Eye}
            action={
              <button onClick={reviewing.onReturn} className="btn-ghost btn-sm">
                Return to Stage {current}
              </button>
            }
          >
            <span className="text-fg-2">
              Reviewing <span className="font-semibold text-fg-1">Stage {viewing}</span>.
              The project is currently at Stage {current}.
            </span>
          </InlineNotice>
        </div>
      )}

      {notice && <div className="px-5 py-2 border-t border-line">{notice}</div>}

      {children && (
        <div className="border-t border-line">{children}</div>
      )}
    </div>
  );
}

/* ---------------- Pipeline strip ---------------- */

function Pipeline({
  stages, currentIdx, viewIdx, onSelect, track
}: {
  stages: StageDescriptor[];
  currentIdx: number;
  viewIdx: number;
  onSelect: (label: string) => void;
  track: Track;
}) {
  return (
    <nav aria-label="Project stages" className="px-2 py-3">
      <ol className="flex items-center" role="list">
        {stages.map((s, i) => {
          const passed    = i <  currentIdx;
          const isCurrent = i === currentIdx;
          const isViewing = i === viewIdx;
          const reachable = passed || isCurrent;
          const Icon      = passed ? Check : isCurrent ? Cog : null;

          const dotCls =
            isViewing && !isCurrent
              ? 'bg-brand text-white shadow-[0_0_0_3px_rgba(31,79,217,0.18)]'
          : isCurrent
              ? (track === 'UPLIFT'
                  ? 'bg-agent text-white shadow-[0_0_0_3px_rgba(110,71,229,0.18)]'
                  : 'bg-brand text-white shadow-[0_0_0_3px_rgba(31,79,217,0.18)]')
          : passed
              ? 'bg-ok text-white'
              : 'bg-canvas text-fg-4 border border-line';

          const labelCls =
            isViewing ? 'text-fg-1 font-semibold'
          : passed    ? 'text-fg-2'
                      : 'text-fg-4';

          const node = (
            <span className="flex items-center min-w-0 px-2 py-1">
              <span
                aria-hidden="true"
                className={`h-7 w-7 rounded-full inline-flex items-center justify-center
                            text-xs font-mono font-semibold transition-shadow ${dotCls}`}
              >
                {Icon ? <Icon size={13} strokeWidth={2.5} /> : s.label}
              </span>
              <span className="ml-2.5 min-w-0 text-left">
                <span className="block text-2xs font-mono uppercase tracking-wider text-fg-3">
                  Stage {s.label}
                </span>
                <span className={`block text-sm whitespace-nowrap ${labelCls}`}>{s.name}</span>
              </span>
            </span>
          );

          return (
            <li key={s.label} className="flex items-center min-w-0 flex-1 last:flex-none">
              {reachable ? (
                <button
                  type="button"
                  onClick={() => onSelect(s.label)}
                  aria-current={isViewing ? 'step' : undefined}
                  aria-label={
                    `Stage ${s.label} ${s.name}${passed ? ', passed' : isCurrent ? ', in progress' : ''}`
                  }
                  className="rounded-md hover:bg-canvas focus:outline-none focus:ring-2 focus:ring-brand/40"
                >
                  {node}
                </button>
              ) : (
                <span
                  aria-disabled="true"
                  aria-label={`Stage ${s.label} ${s.name}, not yet started`}
                  className="opacity-60 cursor-not-allowed"
                >
                  {node}
                </span>
              )}

              {i < stages.length - 1 && (
                <span
                  aria-hidden="true"
                  className={`flex-1 h-px mx-1 ${i < currentIdx ? 'bg-ok' : 'bg-line'}`}
                />
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/**
 * Back-link the project detail uses above the chrome.
 * Restyled for accessibility — "Workspaces" reads as nav, not a button.
 */
export function ProjectBackLink() {
  return (
    <Link to="/" className="inline-flex items-center gap-1.5 -ml-1 mb-3 text-sm text-fg-3 hover:text-fg-1">
      <ChevronLeft size={14} aria-hidden="true" />
      <span>Workspaces</span>
    </Link>
  );
}
