import { useEffect, useMemo, useState } from 'react';
import { segmentGraph, segmentNeighbors } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { SegmentNeighbor } from '../api/types';

// Hop distance is not risk, so it uses a neutral ink scale (tokens), never the risk spectrum.
const HOP_COLORS = ['var(--hop-1)', 'var(--hop-2)', 'var(--hop-3)', 'var(--hop-4)', 'var(--hop-5)'];
const SIZE = 420;
const CENTER = SIZE / 2;
const RADIUS = 165;

export default function SegmentGraphPage() {
  const [states, setStates] = useState<string[]>([]);
  const [edges, setEdges] = useState<[string, string][]>([]);
  const [loadingGraph, setLoadingGraph] = useState(true);
  const [graphError, setGraphError] = useState<unknown>(null);

  const [source, setSource] = useState('');
  const [maxHops, setMaxHops] = useState(2);
  const [neighbors, setNeighbors] = useState<SegmentNeighbor[] | null>(null);
  const [queryError, setQueryError] = useState<unknown>(null);
  const [querying, setQuerying] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const graph = await segmentGraph();
        setStates(graph.states);
        if (graph.states.length > 0) setSource(graph.states[0]);
        setEdges(graph.edges);
      } catch (e) {
        setGraphError(e);
      } finally {
        setLoadingGraph(false);
      }
    })();
  }, []);

  const positions = useMemo(() => {
    const map = new Map<string, { x: number; y: number }>();
    states.forEach((s, i) => {
      const angle = (i / states.length) * 2 * Math.PI - Math.PI / 2;
      map.set(s, { x: CENTER + RADIUS * Math.cos(angle), y: CENTER + RADIUS * Math.sin(angle) });
    });
    return map;
  }, [states]);

  const hopByState = useMemo(() => {
    const map = new Map<string, number>();
    neighbors?.forEach((n) => map.set(n.state, n.hops));
    return map;
  }, [neighbors]);

  const runQuery = async () => {
    setQuerying(true);
    setQueryError(null);
    setNeighbors(null);
    try {
      setNeighbors(await segmentNeighbors(source, maxHops));
    } catch (e) {
      setQueryError(e);
    } finally {
      setQuerying(false);
    }
  };

  return (
    <div>
      <h2>Segment graph</h2>
      <p className="page-subtitle">
        States whose monthly delinquency rates have moved together (correlation of 0.95 or more) are joined by an
        edge. Pick a state to see which others are within a few steps of it: where trouble in one market has
        tended to show up next.
      </p>

      <div className="card">
        {loadingGraph && <p className="page-subtitle">Loading graph...</p>}
        <ErrorBanner error={graphError} />
        {!loadingGraph && states.length > 0 && (
          <>
            <div className="row-inline">
              <div className="field">
                <label htmlFor="source">Source state</label>
                <select id="source" value={source} onChange={(e) => setSource(e.target.value)}>
                  {states.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="maxHops">Max hops</label>
                <input
                  id="maxHops"
                  type="number"
                  min={1}
                  max={5}
                  value={maxHops}
                  onChange={(e) => setMaxHops(Number(e.target.value))}
                />
              </div>
              <button onClick={runQuery} disabled={querying}>
                {querying ? 'Querying...' : 'Find neighbors'}
              </button>
            </div>
            <ErrorBanner error={queryError} />

            <svg width={SIZE} height={SIZE} style={{ display: 'block', margin: '1rem auto' }}>
              {edges.map(([a, b]) => {
                const pa = positions.get(a);
                const pb = positions.get(b);
                if (!pa || !pb) return null;
                return <line key={`${a}-${b}`} x1={pa.x} y1={pa.y} x2={pb.x} y2={pb.y} stroke="var(--border-strong)" strokeWidth={1.5} />;
              })}
              {states.map((s) => {
                const p = positions.get(s);
                if (!p) return null;
                const hops = hopByState.get(s);
                const isSource = s === source && neighbors !== null;
                const fill = isSource
                  ? 'var(--text)'
                  : hops !== undefined
                    ? HOP_COLORS[Math.min(hops - 1, HOP_COLORS.length - 1)]
                    : 'var(--border-strong)';
                const onDark = isSource || (hops !== undefined && hops <= 3);
                return (
                  <g key={s}>
                    <circle cx={p.x} cy={p.y} r={16} fill={fill} stroke="var(--surface)" strokeWidth={2} />
                    <text x={p.x} y={p.y + 4} textAnchor="middle" fontSize={11} fill={onDark ? 'var(--bg)' : 'var(--text)'} fontWeight={600}>
                      {s}
                    </text>
                  </g>
                );
              })}
            </svg>
            {neighbors !== null && (
              <div className="mix-legend" style={{ justifyContent: 'center' }}>
                <span><i style={{ background: 'var(--text)' }} />Source</span>
                {HOP_COLORS.slice(0, 3).map((c, i) => <span key={i}><i style={{ background: c }} />{i + 1} hop{i ? 's' : ''}</span>)}
                <span><i style={{ background: 'var(--border-strong)' }} />Not reached</span>
              </div>
            )}
          </>
        )}
      </div>

      {neighbors && (
        <div className="card">
          <h3>
            States within {maxHops} hop{maxHops > 1 ? 's' : ''} of {source}
          </h3>
          {neighbors.length === 0 ? (
            <p className="page-subtitle">No correlated states within this hop range.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>State</th>
                  <th>Hops</th>
                </tr>
              </thead>
              <tbody>
                {neighbors.map((n) => (
                  <tr key={n.state}>
                    <td>{n.state}</td>
                    <td>{n.hops}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}
