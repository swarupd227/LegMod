import React, { forwardRef } from 'react';

type Variant = 'ghost' | 'secondary' | 'primary' | 'brand' | 'danger';

const VARIANT_CLS: Record<Variant, string> = {
  ghost:     'btn-ghost',
  secondary: 'btn-secondary',
  primary:   'btn-primary',
  brand:     'btn-brand',
  danger:    'btn-danger-ghost'
};

/**
 * Icon-only button with mandatory aria-label. Replaces ~30 hand-rolled
 * icon buttons that have a `title` but no accessible name.
 */
export const IconButton = forwardRef<HTMLButtonElement, {
  icon: React.ReactNode;
  ariaLabel: string;
  onClick?: (e: React.MouseEvent<HTMLButtonElement>) => void;
  variant?: Variant;
  size?: 'sm' | 'md';
  disabled?: boolean;
  title?: string;
  type?: 'button' | 'submit' | 'reset';
}>(({ icon, ariaLabel, onClick, variant = 'ghost', size = 'sm', disabled, title, type = 'button' }, ref) => {
  const sizeCls = size === 'sm' ? 'btn-sm btn-icon' : 'btn-icon';
  return (
    <button
      ref={ref}
      type={type}
      onClick={onClick}
      disabled={disabled}
      aria-label={ariaLabel}
      title={title ?? ariaLabel}
      className={`${VARIANT_CLS[variant]} ${sizeCls}`}
    >
      <span aria-hidden="true" className="inline-flex">{icon}</span>
    </button>
  );
});

IconButton.displayName = 'IconButton';
