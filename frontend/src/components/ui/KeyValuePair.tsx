import React from 'react';

/**
 * Replaces hand-rolled <Pair>, <Row>, <Field>, <Meta>, <Stat>, <KV>
 * micro-components scattered across most stage screens. Use this in any
 * "label / value" definition list.
 */
export function KeyValuePair({
  label, value, mono = false, dense = false
}: {
  label: string;
  value: React.ReactNode;
  mono?: boolean;
  dense?: boolean;
}) {
  return (
    <div className={dense ? 'flex gap-3 py-1' : 'flex gap-4 py-1.5'}>
      <dt className={`shrink-0 text-xs text-fg-3 uppercase tracking-wider pt-0.5 ${dense ? 'w-24' : 'w-32'}`}>
        {label}
      </dt>
      <dd className={`min-w-0 break-words ${mono ? 'font-mono text-sm text-fg-1' : 'text-sm text-fg-1'}`}>
        {value}
      </dd>
    </div>
  );
}

export function KeyValueList({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <dl className={className}>{children}</dl>;
}
