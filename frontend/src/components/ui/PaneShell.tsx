import React from 'react';

type RailSize = 'narrow' | 'default' | 'agent';

const LEFT_CLS: Record<RailSize, string> = {
  narrow:  'pane-grid-2-narrow',
  default: 'pane-grid-2',
  agent:   'pane-grid-2'   // agent rail isn't typical for left only; alias to default
};

/**
 * Two-pane layout (left rail + main content). The rail width is
 * standardised via CSS variables, replacing the 8 magic widths the
 * audit (C3) catalogued.
 */
export function TwoPane({
  rail = 'default', left, children
}: {
  rail?: RailSize;
  left: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className={LEFT_CLS[rail]}>
      <div className="pane-rail-l">{left}</div>
      <div className="pane-main">{children}</div>
    </div>
  );
}

/**
 * Three-pane layout (left rail + main content + right rail). The right
 * rail is always agent-sized; the left can be narrow or default.
 */
export function ThreePane({
  leftRail = 'default', left, right, children
}: {
  leftRail?: 'narrow' | 'default';
  left: React.ReactNode;
  right: React.ReactNode;
  children: React.ReactNode;
}) {
  const grid = leftRail === 'narrow' ? 'pane-grid-3-narrow' : 'pane-grid-3';
  return (
    <div className={grid}>
      <div className="pane-rail-l">{left}</div>
      <div className="pane-main">{children}</div>
      <div className="pane-rail-r">{right}</div>
    </div>
  );
}
