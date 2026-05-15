import React from 'react';

export type ChipTone = 'brand' | 'ok' | 'warn' | 'err' | 'agent' | 'neutral';

const TONE_CLS: Record<ChipTone, string> = {
  brand:   'text-brand',
  ok:      'text-ok',
  warn:    'text-warn',
  err:     'text-err',
  agent:   'text-agent',
  neutral: 'text-fg-1'
};

export interface CountChip {
  label: string;
  value: number | string;
  tone?: ChipTone;
  hint?: string;        // shown as title attribute
}

/**
 * Wrapping flex of label+value chips. Replaces the
 * `ml-4 flex items-center gap-3 text-xs text-fg-3` row that's hand-coded in
 * 11 stage screens and was the source of the "overlapping labels"
 * regression on narrow viewports (audit C4).
 */
export function CountChipRow({ chips, ariaLabel }: { chips: CountChip[]; ariaLabel?: string }) {
  if (chips.length === 0) return null;
  return (
    <ul
      className="stage-chrome-meta"
      aria-label={ariaLabel ?? 'Status counts'}
    >
      {chips.map((c, i) => (
        <li key={`${c.label}-${i}`} className="inline-flex items-center gap-1.5" title={c.hint}>
          <span className={`font-mono text-sm font-semibold tabular-nums ${TONE_CLS[c.tone ?? 'neutral']}`}>
            {typeof c.value === 'number' ? c.value.toLocaleString() : c.value}
          </span>
          <span>{c.label}</span>
        </li>
      ))}
    </ul>
  );
}
