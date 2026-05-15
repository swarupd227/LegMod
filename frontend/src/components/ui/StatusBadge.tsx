import React from 'react';
import { ChipTone } from './CountChipRow';

const TONE_BADGE: Record<ChipTone, string> = {
  brand:   'badge-brand',
  ok:      'badge-ok',
  warn:    'badge-warn',
  err:     'badge-err',
  agent:   'badge-agent',
  neutral: 'badge-neutral'
};

const TONE_DOT: Record<ChipTone, string> = {
  brand:   'bg-brand',
  ok:      'bg-ok',
  warn:    'bg-warn',
  err:     'bg-err',
  agent:   'bg-agent',
  neutral: 'bg-fg-4'
};

/**
 * Single component covering the per-state badge + dot patterns scattered
 * across Recipe / Strangler / Cutover / Char / Diff Lab. Tone selection is
 * the caller's job; the component just renders. This keeps semantic
 * decisions (e.g. "extracted is ok, not agent") at the call site where
 * they belong, but ensures the visual treatment is consistent.
 */
export function StatusBadge({
  label, tone, mono = true, dot = false, pulse = false, className = ''
}: {
  label: string;
  tone: ChipTone;
  mono?: boolean;
  dot?: boolean;
  pulse?: boolean;
  className?: string;
}) {
  return (
    <span className={`${TONE_BADGE[tone]} ${mono ? 'badge-mono' : ''} ${className}`}>
      {dot && (
        <span
          className={`dot ${TONE_DOT[tone]} ${pulse ? 'dot-pulse' : ''}`}
          aria-hidden="true"
        />
      )}
      {label}
    </span>
  );
}

export function StatusDot({
  tone, pulse = false, className = ''
}: { tone: ChipTone; pulse?: boolean; className?: string }) {
  return (
    <span
      className={`dot ${TONE_DOT[tone]} ${pulse ? 'dot-pulse' : ''} ${className}`}
      aria-hidden="true"
    />
  );
}
