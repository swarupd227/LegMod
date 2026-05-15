import React from 'react';
import { IconType } from './icons';

type Tone = 'brand' | 'ok' | 'warn' | 'err' | 'agent' | 'fg';

const TINT: Record<Tone, string> = {
  brand: 'text-brand bg-brand-50',
  ok:    'text-ok bg-ok-50',
  warn:  'text-warn bg-warn-50',
  err:   'text-err bg-err-50',
  agent: 'text-agent bg-agent-50',
  fg:    'text-fg-1 bg-canvas border border-line'
};

const TEXT_TONE: Record<Tone, string> = {
  brand: 'text-brand',
  ok:    'text-ok',
  warn:  'text-warn',
  err:   'text-err',
  agent: 'text-agent',
  fg:    'text-fg-1'
};

/**
 * The "icon + big number + label" tile that's reinvented as <StatCard>,
 * <Tile>, <Stat>, <SummaryStat> across 4 screens. One implementation.
 */
export function MetricTile({
  icon: Icon, label, value, tone = 'brand', sub, accent = false
}: {
  icon?: IconType;
  label: string;
  value: string | number;
  tone?: Tone;
  sub?: string;
  accent?: boolean;     // when true, the number itself takes the tone colour
}) {
  return (
    <div className="card px-4 py-3 flex items-center gap-3 min-w-0">
      {Icon && (
        <div className={`h-9 w-9 rounded-md inline-flex items-center justify-center shrink-0 ${TINT[tone]}`}>
          <Icon size={16} strokeWidth={1.75} aria-hidden="true" />
        </div>
      )}
      <div className="min-w-0">
        <div className={`text-2xl font-semibold tracking-tight leading-none tabular-nums truncate
                         ${accent ? TEXT_TONE[tone] : 'text-fg-1'}`}>
          {typeof value === 'number' ? value.toLocaleString() : value}
        </div>
        <div className="text-xs text-fg-3 mt-0.5 truncate">
          {label}{sub && <span className="text-fg-4"> · {sub}</span>}
        </div>
      </div>
    </div>
  );
}
