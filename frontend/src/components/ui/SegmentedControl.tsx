import React from 'react';

export interface Segment<T extends string> {
  id: T;
  label: string;
  count?: number;
  tone?: 'brand' | 'ok' | 'warn' | 'err' | 'agent' | 'neutral';
}

const TONE_CLS: Record<NonNullable<Segment<string>['tone']>, string> = {
  brand:   'text-brand',
  ok:      'text-ok',
  warn:    'text-warn',
  err:     'text-err',
  agent:   'text-agent',
  neutral: 'text-fg-2'
};

/**
 * Filter pill row used in Reconciliation, Diff Lab, Recipe Authoring,
 * Characterization, Audit Browser. Replaces 5+ ad-hoc implementations
 * with one accessible toggle group.
 */
export function SegmentedControl<T extends string>({
  segments, value, onChange, ariaLabel, size = 'md'
}: {
  segments: Segment<T>[];
  value: T;
  onChange: (next: T) => void;
  ariaLabel: string;
  size?: 'sm' | 'md' | 'lg';
}) {
  const padY = size === 'sm' ? 'py-1' : size === 'lg' ? 'py-2.5' : 'py-1.5';
  const fontSz = size === 'sm' ? 'text-2xs' : 'text-xs';
  return (
    <div
      role="radiogroup"
      aria-label={ariaLabel}
      className="flex items-center gap-1 p-1 bg-canvas border border-line rounded-md"
    >
      {segments.map(s => {
        const active = s.id === value;
        const toneCls = active && s.tone ? TONE_CLS[s.tone] : '';
        return (
          <button
            key={s.id}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(s.id)}
            className={`flex-1 ${padY} px-2 rounded text-center ${fontSz} font-medium uppercase tracking-wider
                        transition-colors min-w-0
                        ${active
                          ? `bg-surface shadow-xs text-fg-1 ${toneCls}`
                          : 'text-fg-3 hover:text-fg-1'}`}
          >
            <span className="truncate">{s.label}</span>
            {typeof s.count === 'number' && (
              <span className={`ml-1.5 font-mono tabular-nums ${active ? toneCls || 'text-fg-2' : 'text-fg-4'}`}>
                {s.count.toLocaleString()}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
