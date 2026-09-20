import { useRef, useState, type ReactNode } from 'react';

/**
 * Windowed list: renders only the rows currently visible (plus a small overscan) inside a
 * fixed-height scroll container, so a 10,000-row table costs the same DOM as a 20-row one. Rows
 * must share one fixed height (that is what makes "which rows are visible" O(1) arithmetic
 * instead of measuring every row). Hand-rolled to avoid a dependency for ~40 lines.
 */
export default function VirtualList<T>({
  items,
  rowHeight,
  height,
  renderRow,
  overscan = 6,
}: {
  items: T[];
  rowHeight: number;
  height: number;
  renderRow: (item: T, index: number) => ReactNode;
  overscan?: number;
}) {
  const [scrollTop, setScrollTop] = useState(0);
  const ref = useRef<HTMLDivElement>(null);

  const first = Math.max(0, Math.floor(scrollTop / rowHeight) - overscan);
  const last = Math.min(items.length, Math.ceil((scrollTop + height) / rowHeight) + overscan);

  return (
    <div ref={ref} style={{ height, overflowY: 'auto' }} onScroll={(e) => setScrollTop(e.currentTarget.scrollTop)}>
      <div style={{ height: items.length * rowHeight, position: 'relative' }}>
        {items.slice(first, last).map((item, i) => (
          <div key={first + i} style={{ position: 'absolute', top: (first + i) * rowHeight, height: rowHeight, left: 0, right: 0 }}>
            {renderRow(item, first + i)}
          </div>
        ))}
      </div>
    </div>
  );
}
