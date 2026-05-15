// Reusable status / mode / risk badges. Co-located so the design stays
// consistent across screens.

export function ModeBadge({ mode }: { mode: 'SOAP' | 'UPLIFT' | string }) {
  const isSoap = mode === 'SOAP';
  return (
    <span className={isSoap ? 'badge-brand badge-mono' : 'badge-agent badge-mono'}>
      {isSoap ? 'SOAP' : 'UPLIFT'}
    </span>
  );
}

export function RiskBadge({ tier }: { tier: string }) {
  const cls =
    tier === 'LOW'      ? 'badge-ok'
  : tier === 'MEDIUM'   ? 'badge-warn'
  : tier === 'HIGH'     ? 'badge-err'
  : tier === 'CRITICAL' ? 'badge-err'
                        : 'badge-neutral';
  const label = tier.charAt(0) + tier.slice(1).toLowerCase();
  return <span className={cls}>{label}</span>;
}

export function ConfidenceBadge({ confidence }: { confidence: string }) {
  const cls =
    confidence === 'high'   ? 'badge-ok'
  : confidence === 'medium' ? 'badge-warn'
  :                           'badge-err';
  return (
    <span className={cls}>
      <span className="dot bg-current opacity-90" /> {confidence}
    </span>
  );
}

export function FlagBadge({ flag }: { flag: string }) {
  return <span className="badge-warn badge-mono">{flag}</span>;
}
