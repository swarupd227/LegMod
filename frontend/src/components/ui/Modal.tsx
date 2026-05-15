import React, { useEffect, useRef, useId } from 'react';
import { X } from 'lucide-react';
import { IconButton } from './IconButton';

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'textarea:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  '[tabindex]:not([tabindex="-1"])'
].join(',');

/**
 * Production-grade modal: focus trap, scroll lock, return-focus on close,
 * Escape-to-close, click-outside-to-close, aria-modal + aria-labelledby.
 * Replaces ad-hoc <div className="fixed inset-0 …"> patterns in
 * NewProjectModal, etc.
 */
export function Modal({
  open, onClose, title, children, footer, size = 'md', dismissOnOverlay = true
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  dismissOnOverlay?: boolean;
}) {
  const titleId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) return;
    previouslyFocused.current = document.activeElement as HTMLElement;

    // Lock body scroll while modal is open.
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    // Focus the first tabbable element inside the dialog.
    const first = dialogRef.current?.querySelector<HTMLElement>(FOCUSABLE);
    first?.focus();

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
      } else if (e.key === 'Tab') {
        const focusables = dialogRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE);
        if (!focusables || focusables.length === 0) return;
        const arr = Array.from(focusables);
        const firstEl = arr[0];
        const lastEl = arr[arr.length - 1];
        if (e.shiftKey && document.activeElement === firstEl) {
          e.preventDefault();
          lastEl.focus();
        } else if (!e.shiftKey && document.activeElement === lastEl) {
          e.preventDefault();
          firstEl.focus();
        }
      }
    };
    document.addEventListener('keydown', onKey);

    return () => {
      document.body.style.overflow = prevOverflow;
      document.removeEventListener('keydown', onKey);
      previouslyFocused.current?.focus?.();
    };
  }, [open, onClose]);

  if (!open) return null;

  const widthCls =
    size === 'sm' ? 'max-w-md'
  : size === 'lg' ? 'max-w-2xl'
  : size === 'xl' ? 'max-w-4xl'
                  : 'max-w-xl';

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-ink/40 backdrop-blur-sm"
      onClick={dismissOnOverlay ? onClose : undefined}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={`card shadow-pop w-full ${widthCls}`}
        onClick={e => e.stopPropagation()}
      >
        <header className="px-6 py-4 border-b border-line flex items-start gap-3">
          <h2 id={titleId} className="flex-1 text-lg font-semibold text-fg-1 tracking-tight">{title}</h2>
          <IconButton icon={<X size={14} />} ariaLabel="Close dialog" onClick={onClose} variant="ghost" />
        </header>
        <div className="px-6 py-5">{children}</div>
        {footer && (
          <footer className="px-6 py-3 border-t border-line bg-canvas/50 rounded-b-lg flex justify-end gap-2 flex-wrap">
            {footer}
          </footer>
        )}
      </div>
    </div>
  );
}
