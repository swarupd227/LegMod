import { Check, Cog, Circle as CircleIcon } from 'lucide-react';

type Track = 'SOAP' | 'UPLIFT';
type State = 'pending' | 'current' | 'passed';

const TRACK_TINTS: Record<Track, string> = {
  SOAP:   'bg-brand-50 text-brand',
  UPLIFT: 'bg-agent-50 text-agent'
};

/**
 * The 28×28 lettered stage marker used in headers and stage pipelines.
 * Replaces 11 hand-rolled instances across the stage screens.
 */
export function StageChip({
  stage, track = 'SOAP', state = 'current', size = 'md', ariaLabel
}: {
  stage: string;
  track?: Track;
  state?: State;
  size?: 'sm' | 'md' | 'lg';
  ariaLabel?: string;
}) {
  const dim =
    size === 'sm' ? 'h-6 w-6 text-2xs'
  : size === 'lg' ? 'h-9 w-9 text-base'
                  : 'h-7 w-7 text-xs';

  const cls =
    state === 'passed'
      ? 'bg-ok text-white'
  : state === 'current'
      ? `${TRACK_TINTS[track]} ring-2 ring-current/20`
      : 'bg-canvas text-fg-4 border border-line';

  const Icon = state === 'passed' ? Check : state === 'current' ? Cog : CircleIcon;

  return (
    <span
      className={`inline-flex items-center justify-center rounded-md font-mono font-semibold shrink-0 ${dim} ${cls}`}
      aria-label={ariaLabel ?? `Stage ${stage}${state === 'passed' ? ' passed' : state === 'current' ? ' in progress' : ''}`}
    >
      {state === 'passed'
        ? <Icon size={size === 'lg' ? 16 : 13} strokeWidth={2.5} aria-hidden="true" />
        : <span>{stage}</span>}
    </span>
  );
}
