import React from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';
import { IconType } from './icons';

/**
 * Empty state — replaces the 12 hand-rolled "card p-10 text-center" blocks
 * across stage screens. Use this for "service has no data yet, take action."
 */
export function EmptyState({
  icon, title, body, primaryAction, secondaryAction, hint, accent = 'brand'
}: {
  icon: IconType;
  title: string;
  body?: React.ReactNode;
  primaryAction?: React.ReactNode;
  secondaryAction?: React.ReactNode;
  hint?: React.ReactNode;
  accent?: 'brand' | 'agent' | 'ok' | 'gradient';
}) {
  const Icon = icon;
  const tint =
    accent === 'gradient' ? 'bg-gradient-to-br from-brand to-agent text-white shadow-md'
  : accent === 'agent'    ? 'bg-agent-50 text-agent'
  : accent === 'ok'       ? 'bg-ok-50 text-ok'
                          : 'bg-brand-50 text-brand';

  return (
    <section className="card p-10 text-center">
      <div className={`inline-flex items-center justify-center h-12 w-12 rounded-xl ${tint}`}>
        <Icon size={20} strokeWidth={1.75} aria-hidden="true" />
      </div>
      <h2 className="mt-4 text-lg font-semibold text-fg-1 tracking-tight text-balance">
        {title}
      </h2>
      {body && (
        <div className="mt-2 text-sm text-fg-2 max-w-md mx-auto">{body}</div>
      )}
      {hint && (
        <div className="mt-1 text-2xs text-fg-3 max-w-md mx-auto">{hint}</div>
      )}
      {(primaryAction || secondaryAction) && (
        <div className="mt-5 flex justify-center gap-2 flex-wrap">
          {secondaryAction}
          {primaryAction}
        </div>
      )}
    </section>
  );
}

/**
 * Error state — used when an async query/mutation fails. Always offers a
 * retry path and a contextual hint instead of the previous bare "service
 * unreachable" red banner.
 */
export function ErrorState({
  title = 'Something went wrong', detail, onRetry, hint
}: {
  title?: string;
  detail?: React.ReactNode;
  onRetry?: () => void;
  hint?: React.ReactNode;
}) {
  return (
    <div role="alert" className="card p-5 border-err/30 bg-err-50">
      <div className="flex items-start gap-3">
        <AlertTriangle size={18} className="text-err shrink-0 mt-0.5" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <div className="text-sm font-semibold text-fg-1">{title}</div>
          {detail && <div className="mt-1 text-xs text-fg-2">{detail}</div>}
          {hint && <div className="mt-2 text-2xs text-fg-3">{hint}</div>}
        </div>
        {onRetry && (
          <button
            onClick={onRetry}
            className="btn-secondary btn-sm shrink-0"
          >
            <RefreshCw size={13} aria-hidden="true" /> Retry
          </button>
        )}
      </div>
    </div>
  );
}

/**
 * Loading state — three variants for the three skeleton shapes used today
 * (header+detail, grid of cards, list rows).
 */
export function LoadingState({
  variant = 'detail', label = 'Loading'
}: {
  variant?: 'detail' | 'grid' | 'list';
  label?: string;
}) {
  if (variant === 'grid') {
    return (
      <div role="status" aria-label={label}
           className="mt-6 grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {[0, 1, 2].map(i => (
          <div key={i} className="card p-5">
            <div className="skel h-4 w-20 mb-3" />
            <div className="skel h-5 w-2/3 mb-2" />
            <div className="skel h-3 w-1/2" />
            <div className="skel h-1 w-full mt-6" />
            <div className="skel h-3 w-1/3 mt-4" />
          </div>
        ))}
      </div>
    );
  }
  if (variant === 'list') {
    return (
      <div role="status" aria-label={label} className="p-6 space-y-2">
        {[0, 1, 2, 3].map(i => <div key={i} className="skel h-12 w-full" />)}
      </div>
    );
  }
  return (
    <div role="status" aria-label={label} className="card p-8 space-y-3">
      <div className="skel h-3 w-32" />
      <div className="skel h-8 w-2/3" />
      <div className="skel h-32 w-full" />
    </div>
  );
}

/**
 * Thin info banner used inside cards / pages. Replaces ad-hoc `card` boxes
 * that were doubling as notices (P6 in the audit).
 */
export function InlineNotice({
  tone = 'info', icon: Icon, children, action
}: {
  tone?: 'info' | 'warn' | 'err' | 'ok';
  icon?: IconType;
  children: React.ReactNode;
  action?: React.ReactNode;
}) {
  const cls =
    tone === 'warn' ? 'notice-warn'
  : tone === 'err'  ? 'notice-err'
  : tone === 'ok'   ? 'notice-ok'
                    : 'notice-info';
  return (
    <div role={tone === 'err' ? 'alert' : 'status'} className={cls}>
      {Icon && <Icon size={14} aria-hidden="true" />}
      <div className="min-w-0 flex-1">{children}</div>
      {action && <div className="ml-auto shrink-0">{action}</div>}
    </div>
  );
}
