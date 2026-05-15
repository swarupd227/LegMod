import React, { useMemo } from 'react';
import { diffWordsWithSpace, Change } from 'diff';

interface DiffLine {
  kind: 'context' | 'add' | 'del' | 'header' | 'hunk';
  text: string;
  pairLine?: string;          // for add/del lines, the matching opposite for token-level diff
}

/**
 * Production diff renderer. Accepts a unified diff string (e.g. from
 * the migration service's UnifiedDiff), parses it into structured rows,
 * and overlays word-level token diffs on +/- pairs so the changed
 * substrings stand out within a line.
 */
export function DiffViewer({
  unifiedDiff, ariaLabel
}: {
  unifiedDiff: string;
  ariaLabel?: string;
}) {
  const lines = useMemo(() => parseUnifiedDiff(unifiedDiff), [unifiedDiff]);

  if (!unifiedDiff?.trim()) {
    return <div className="p-6 text-fg-3 text-sm">No diff available.</div>;
  }

  return (
    <pre
      role="region"
      aria-label={ariaLabel ?? 'Unified diff'}
      className="m-0 overflow-auto font-mono text-2xs leading-relaxed bg-surface"
    >
      <code className="block">
        {lines.map((l, i) => <DiffRow key={i} line={l} />)}
      </code>
    </pre>
  );
}

function DiffRow({ line }: { line: DiffLine }) {
  if (line.kind === 'header') {
    return <div className="px-3 text-fg-1 font-semibold border-b border-line bg-canvas/60 py-1">{line.text}</div>;
  }
  if (line.kind === 'hunk') {
    return <div className="px-3 text-agent bg-agent-50/30 py-0.5 border-b border-line/60">{line.text}</div>;
  }
  if (line.kind === 'context') {
    return <div className="px-3 text-fg-2 py-0.5"> {line.text}</div>;
  }
  // Add/del with token-level highlighting if a pair is available.
  const isAdd = line.kind === 'add';
  const sign = isAdd ? '+' : '-';
  const bg = isAdd ? 'bg-ok-50/50 text-ok' : 'bg-err-50/50 text-err';
  const tokens: Change[] | null = line.pairLine != null
    ? (isAdd
        ? diffWordsWithSpace(line.pairLine, line.text)
        : diffWordsWithSpace(line.text, line.pairLine))
    : null;

  return (
    <div className={`px-3 py-0.5 ${bg}`}>
      <span aria-hidden="true">{sign}</span>
      <span> </span>
      {tokens
        ? tokens.map((t, j) => {
            // For an add line, "added" tokens are the new ones — highlight them.
            // For a del line, "removed" tokens are the original ones — highlight them.
            const highlight = isAdd ? t.added : t.removed;
            const skip = isAdd ? t.removed : t.added;
            if (skip) return null;
            return (
              <span
                key={j}
                className={highlight ? (isAdd ? 'bg-ok/20 rounded-sm' : 'bg-err/20 rounded-sm') : ''}
              >
                {t.value}
              </span>
            );
          })
        : <span>{line.text}</span>}
    </div>
  );
}

/* ---------------- parser ---------------- */

function parseUnifiedDiff(input: string): DiffLine[] {
  const out: DiffLine[] = [];
  if (!input) return out;
  const raw = input.split('\n');

  // Pair adjacent "-" / "+" lines so token-diff can highlight changed words.
  // We stage del lines, then on encountering + we pair them up; everything
  // else flushes the staged del block as plain del lines.
  const dels: string[] = [];
  const adds: string[] = [];

  const flushPair = () => {
    const n = Math.max(dels.length, adds.length);
    for (let i = 0; i < n; i++) {
      const d = dels[i];
      const a = adds[i];
      if (d != null && a != null) {
        out.push({ kind: 'del', text: d, pairLine: a });
        out.push({ kind: 'add', text: a, pairLine: d });
      } else if (d != null) {
        out.push({ kind: 'del', text: d });
      } else if (a != null) {
        out.push({ kind: 'add', text: a });
      }
    }
    dels.length = 0;
    adds.length = 0;
  };

  for (const line of raw) {
    if (line.startsWith('---') || line.startsWith('+++')) {
      flushPair();
      out.push({ kind: 'header', text: line });
      continue;
    }
    if (line.startsWith('@@')) {
      flushPair();
      out.push({ kind: 'hunk', text: line });
      continue;
    }
    if (line.startsWith('-')) {
      // If we were collecting adds and now see another del, flush first
      if (adds.length > 0) flushPair();
      dels.push(line.slice(1));
      continue;
    }
    if (line.startsWith('+')) {
      adds.push(line.slice(1));
      continue;
    }
    // context line — flush any pending pair
    flushPair();
    if (line.startsWith(' ')) {
      out.push({ kind: 'context', text: line.slice(1) });
    } else if (line.length > 0) {
      out.push({ kind: 'context', text: line });
    }
  }
  flushPair();
  return out;
}
