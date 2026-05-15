import React, { useEffect, useRef, useState } from 'react';

export interface TreemapItem {
  id: string;
  weight: number;            // typically LOC or row count
}

export interface TreemapTile<T extends TreemapItem> {
  item: T;
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * Squarified treemap. Implements Bruls/Huijsen/van Wijk's algorithm — keeps
 * tile aspect ratios close to 1:1, so the heatmap reads cleanly regardless of
 * how many modules / how lopsided the weights are.
 */
export function Treemap<T extends TreemapItem>({
  items, render, ariaLabel, minHeight = 240
}: {
  items: T[];
  render: (tile: TreemapTile<T>) => React.ReactNode;
  ariaLabel?: string;
  minHeight?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ w: 800, h: minHeight });

  useEffect(() => {
    if (!ref.current) return;
    const ro = new ResizeObserver(([entry]) => {
      const r = entry.contentRect;
      setSize({ w: Math.max(120, r.width), h: Math.max(minHeight, r.height) });
    });
    ro.observe(ref.current);
    return () => ro.disconnect();
  }, [minHeight]);

  const tiles = layout(items, size.w, size.h);

  return (
    <div
      ref={ref}
      role="group"
      aria-label={ariaLabel ?? 'Treemap'}
      className="relative w-full"
      style={{ height: size.h }}
    >
      {tiles.map(t => (
        <div
          key={t.item.id}
          style={{
            position: 'absolute',
            left: t.x, top: t.y,
            width: t.width - 4,         // 4px gap between tiles
            height: t.height - 4
          }}
        >
          {render(t)}
        </div>
      ))}
    </div>
  );
}

/* ---------------- squarify ---------------- */

function layout<T extends TreemapItem>(
  items: T[], width: number, height: number
): TreemapTile<T>[] {
  if (items.length === 0 || width <= 0 || height <= 0) return [];

  // Sort weights descending (squarify expects this).
  const sorted = [...items].sort((a, b) => b.weight - a.weight);
  const total = sorted.reduce((s, x) => s + Math.max(1, x.weight), 0);
  // Scale weights to area equal to the container.
  const scale = (width * height) / total;
  const data = sorted.map(it => ({
    item: it,
    area: Math.max(1, it.weight) * scale
  }));

  const tiles: TreemapTile<T>[] = [];
  squarify(data, [], { x: 0, y: 0, w: width, h: height }, tiles);
  return tiles;
}

interface Box { x: number; y: number; w: number; h: number; }
interface Datum<T extends TreemapItem> { item: T; area: number; }

function squarify<T extends TreemapItem>(
  remaining: Datum<T>[],
  row: Datum<T>[],
  box: Box,
  out: TreemapTile<T>[]
) {
  if (remaining.length === 0) {
    layoutRow(row, box, out);
    return;
  }
  const w = shortSide(box);
  const head = remaining[0];
  const candidate = [...row, head];

  if (row.length === 0 || worst(candidate, w) <= worst(row, w)) {
    squarify(remaining.slice(1), candidate, box, out);
  } else {
    const newBox = layoutRow(row, box, out);
    squarify(remaining, [], newBox, out);
  }
}

function shortSide(b: Box) { return Math.min(b.w, b.h); }

function worst<T extends TreemapItem>(row: Datum<T>[], w: number): number {
  if (row.length === 0) return Infinity;
  const sum = row.reduce((s, x) => s + x.area, 0);
  let max = -Infinity, min = Infinity;
  for (const x of row) {
    if (x.area > max) max = x.area;
    if (x.area < min) min = x.area;
  }
  const w2 = w * w;
  const sum2 = sum * sum;
  return Math.max((w2 * max) / sum2, sum2 / (w2 * min));
}

function layoutRow<T extends TreemapItem>(
  row: Datum<T>[], box: Box, out: TreemapTile<T>[]
): Box {
  if (row.length === 0) return box;
  const sum = row.reduce((s, x) => s + x.area, 0);
  const horizontal = box.w >= box.h;

  if (horizontal) {
    const rowH = sum / box.w;
    let x = box.x;
    for (const d of row) {
      const w = d.area / rowH;
      out.push({ item: d.item, x, y: box.y, width: w, height: rowH });
      x += w;
    }
    return { x: box.x, y: box.y + rowH, w: box.w, h: box.h - rowH };
  } else {
    const rowW = sum / box.h;
    let y = box.y;
    for (const d of row) {
      const h = d.area / rowW;
      out.push({ item: d.item, x: box.x, y, width: rowW, height: h });
      y += h;
    }
    return { x: box.x + rowW, y: box.y, w: box.w - rowW, h: box.h };
  }
}
