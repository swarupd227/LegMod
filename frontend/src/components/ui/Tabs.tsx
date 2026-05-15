import React, { useId, useCallback, KeyboardEvent } from 'react';
import { IconType } from './icons';

export interface TabDescriptor<T extends string> {
  id: T;
  label: string;
  icon?: IconType;
  count?: number;
  countTone?: 'brand' | 'ok' | 'warn' | 'err' | 'neutral';
}

const COUNT_TONE: Record<NonNullable<TabDescriptor<string>['countTone']>, string> = {
  brand:   'badge-brand',
  ok:      'badge-ok',
  warn:    'badge-warn',
  err:     'badge-err',
  neutral: 'badge-neutral'
};

/**
 * Accessible tabs primitive. Replaces the .tab + .tab-active utility-class
 * pattern with proper roles, keyboard navigation (Left/Right/Home/End),
 * and aria-controls wiring.
 */
export function Tabs<T extends string>({
  tabs, value, onChange, ariaLabel
}: {
  tabs: TabDescriptor<T>[];
  value: T;
  onChange: (next: T) => void;
  ariaLabel: string;
}) {
  const id = useId();

  const onKeyDown = useCallback((e: KeyboardEvent<HTMLDivElement>) => {
    const idx = tabs.findIndex(t => t.id === value);
    if (idx < 0) return;
    let next = idx;
    if (e.key === 'ArrowRight') next = (idx + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') next = (idx - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = tabs.length - 1;
    else return;
    e.preventDefault();
    onChange(tabs[next].id);
    // Focus the newly selected tab so screen readers track it.
    requestAnimationFrame(() => {
      const el = document.getElementById(`${id}-tab-${tabs[next].id}`);
      el?.focus();
    });
  }, [tabs, value, onChange, id]);

  return (
    <div
      role="tablist"
      aria-label={ariaLabel}
      className="flex border-b border-line px-3"
      onKeyDown={onKeyDown}
    >
      {tabs.map(t => {
        const active = t.id === value;
        const Icon = t.icon;
        return (
          <button
            key={t.id}
            id={`${id}-tab-${t.id}`}
            role="tab"
            type="button"
            aria-selected={active}
            aria-controls={`${id}-panel-${t.id}`}
            tabIndex={active ? 0 : -1}
            onClick={() => onChange(t.id)}
            className={`tab ${active ? 'tab-active' : ''}`}
          >
            {Icon && <Icon size={14} aria-hidden="true" />}
            <span>{t.label}</span>
            {typeof t.count === 'number' && t.count > 0 && (
              <span className={COUNT_TONE[t.countTone ?? 'neutral']}>{t.count}</span>
            )}
          </button>
        );
      })}
    </div>
  );
}

/**
 * Companion panel — pair with the Tabs above. Use the same `tabsId` you
 * implicitly get from `Tabs` (we expose a wrapper helper below to make this
 * easy without exposing internal IDs).
 */
export function TabPanel({
  id, tabId, active, children
}: {
  id: string;          // panel id, must match `${tabsId}-panel-${tabId}`
  tabId: string;       // tab id, must match `${tabsId}-tab-${tabId}`
  active: boolean;
  children: React.ReactNode;
}) {
  return (
    <div
      id={id}
      role="tabpanel"
      aria-labelledby={tabId}
      hidden={!active}
      tabIndex={0}
      className="focus:outline-none focus:ring-2 focus:ring-brand/30 rounded"
    >
      {children}
    </div>
  );
}
