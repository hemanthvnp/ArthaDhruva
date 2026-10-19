import { useEffect, useMemo, useState } from 'react';
import { segmentGraph, segmentNeighbors } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { SegmentGraph, SegmentNeighbor } from '../api/types';
import { STATE_TILES, TILE_COLUMNS, TILE_ROWS } from '../segmentTiles';

// Hop distance is not risk, so it uses a neutral ink scale (tokens), never the risk spectrum.
const HOP_COLORS = ['var(--hop-1)', 'var(--hop-2)', 'var(--hop-3)', 'var(--hop-4)', 'var(--hop-5)'];
const TILE = 40;
const GAP = 14;
const PAD = 8;
const WIDTH = PAD * 2 + TILE_COLUMNS * TILE + (TILE_COLUMNS - 1) * GAP;
const HEIGHT = PAD * 2 + TILE_ROWS * TILE + (TILE_ROWS - 1) * GAP;

function corner(code: string) {
  const [c, r] = STATE_TILES[code] ?? [0, 0];
  return { x: PAD + c * (TILE + GAP), y: PAD + r * (TILE + GAP) };
}

function centre(code: string) {
  const p = corner(code);
  return { x: p.x + TILE / 2, y: p.y + TILE / 2 };
}

/** Tiles side by side or one above the other: their link shows as a bridge in the gap, under the tiles. */
function adjacent(a: string, b: string) {
  const [ca, ra] = STATE_TILES[a] ?? [0, 0];
  const [cb, rb] = STATE_TILES[b] ?? [0, 0];
  return Math.abs(ca - cb) + Math.abs(ra - rb) === 1;
}

function signed(x: number | null) {
  return x === null ? 'n/a' : `${x >= 0 ? '+' : ''}${x.toFixed(2)}`;
}

function pValue(p: number | null) {
  if (p === null) return 'not tested';
  return p < 0.001 ? 'p < 0.001' : `p = ${p.toFixed(p < 0.01 ? 4 : 3)}`;
}

export default function SegmentGraphPage() {
  const [graph, setGraph] = useState<SegmentGraph | null>(null);
  const [loadingGraph, setLoadingGraph] = useState(true);
  const [graphError, setGraphError] = useState<unknown>(null);

  const [source, setSource] = useState('');
  const [maxHops, setMaxHops] = useState(2);
  const [neighbors, setNeighbors] = useState<SegmentNeighbor[] | null>(null);
  const [queriedSource, setQueriedSource] = useState('');
  const [queryError, setQueryError] = useState<unknown>(null);
  const [querying, setQuerying] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const g = await segmentGraph();
        setGraph(g);
        // Start on a linked state, so the first query shows something.
        setSource(g.links[0]?.a ?? g.states[0]?.code ?? '');
      } catch (e) {
        setGraphError(e);
      } finally {
        setLoadingGraph(false);
      }
    })();
  }, []);

  const names = useMemo(() => new Map(graph?.states.map((s) => [s.code, s.name]) ?? []), [graph]);
  const regionLabel = useMemo(() => new Map(graph?.regions.map((r) => [r.id, r.label]) ?? []), [graph]);
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
      setQueriedSource(source);
    } catch (e) {
      setQueryError(e);
    } finally {
      setQuerying(false);
    }
  };

  const v = graph?.validation;
  const sortedStates = useMemo(() => [...(graph?.states ?? [])].sort((a, b) => a.name.localeCompare(b.name)), [graph]);

  const linkLine = (l: SegmentGraph['links'][number], opacity: number) => {
    const a = centre(l.a);
    const b = centre(l.b);
    return (
      <line
        key={`${l.a}-${l.b}`}
        x1={a.x}
        y1={a.y}
        x2={b.x}
        y2={b.y}
        stroke="var(--text)"
        strokeOpacity={opacity}
        strokeWidth={4 + 30 * Math.abs(l.weight)}
        strokeLinecap="round"
      >
        <title>{`${names.get(l.a)} and ${names.get(l.b)}: partial correlation ${l.weight.toFixed(2)}${
          l.confidence !== null ? `, kept in ${Math.round(l.confidence * 100)}% of subsamples` : ''
        }`}</title>
      </line>
    );
  };

  return (
    <div>
      <h2>Segment graph</h2>
      <p className="page-subtitle">
        States whose unemployment and house prices move together, beyond what the whole country does, are joined by a
        line: thicker for a stronger link. The links were learned from macro data and checked against mortgage
        delinquency years later. A state without a line is not shown to move on its own: the data could not confirm a
        link for it.
      </p>

      {loadingGraph && <p className="page-subtitle">Loading graph...</p>}
      <ErrorBanner error={graphError} />

      {graph && v && (
        <>
          <div className="kpi-row">
            <div className="kpi">
              <div className="label">Validated links</div>
              <div className="value">{graph.links.length}</div>
              <div className="hint">of {graph.states.length} states; {graph.regions.length} region{graph.regions.length === 1 ? '' : 's'}</div>
            </div>
            <div className="kpi">
              <div className="label">Linked vs other pairs</div>
              <div className="value">{signed(v.linkedMinusUnlinked)}</div>
              <div className="hint">
                correlation of delinquency changes, {pValue(v.pAgainstRandomGraphs)} against random graphs
              </div>
            </div>
            <div className="kpi">
              <div className="label">Linked vs same Census division</div>
              <div className="value">{signed(v.linkedMinusSameDivision)}</div>
              <div className="hint">{pValue(v.pAgainstSameDivision)}; more than geography alone</div>
            </div>
          </div>

          <div className="card">
            <div className="row-inline">
              <div className="field">
                <label htmlFor="source">State</label>
                <select id="source" value={source} onChange={(e) => setSource(e.target.value)}>
                  {sortedStates.map((s) => (
                    <option key={s.code} value={s.code}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="maxHops">Max links away</label>
                <input
                  id="maxHops"
                  type="number"
                  min={1}
                  max={5}
                  value={maxHops}
                  onChange={(e) => setMaxHops(Number(e.target.value))}
                />
              </div>
              <button onClick={runQuery} disabled={querying || !source}>
                {querying ? 'Querying...' : 'Find linked states'}
              </button>
            </div>
            <ErrorBanner error={queryError} />

            <svg
              viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
              width="100%"
              style={{ display: 'block', maxWidth: WIDTH, margin: '1rem auto' }}
              role="img"
              aria-label={`Tile map of ${graph.states.length} states with ${graph.links.length} links`}
            >
              {/* Links between neighbouring tiles go under them, so they show in the gap and never cross a label. */}
              {graph.links.filter((l) => adjacent(l.a, l.b)).map((l) => linkLine(l, 0.9))}
              {graph.states.map((s) => {
                const p = corner(s.code);
                const hops = hopByState.get(s.code);
                const isSource = neighbors !== null && s.code === queriedSource;
                const fill = isSource
                  ? 'var(--text)'
                  : hops !== undefined
                    ? HOP_COLORS[Math.min(hops - 1, HOP_COLORS.length - 1)]
                    : 'var(--surface)';
                const onDark = isSource || (hops !== undefined && hops <= 3);
                return (
                  <g key={s.code}>
                    <title>{`${s.name}${s.region ? `, region ${regionLabel.get(s.region) ?? s.region}` : ''}`}</title>
                    <rect
                      x={p.x}
                      y={p.y}
                      width={TILE}
                      height={TILE}
                      rx={6}
                      fill={fill}
                      stroke={s.region ? 'var(--text)' : 'var(--border-strong)'}
                      strokeWidth={s.region ? 2 : 1}
                      strokeDasharray={s.region ? '4 3' : undefined}
                    />
                    <text
                      x={p.x + TILE / 2}
                      y={p.y + TILE / 2 + 4}
                      textAnchor="middle"
                      fontSize={11}
                      fontWeight={600}
                      fill={onDark ? 'var(--bg)' : 'var(--text)'}
                    >
                      {s.code}
                    </text>
                  </g>
                );
              })}
              {graph.links.filter((l) => !adjacent(l.a, l.b)).map((l) => linkLine(l, 0.45))}
            </svg>

            <div className="mix-legend" style={{ justifyContent: 'center' }}>
              <span><i style={{ background: 'var(--text)' }} />Link (thicker is stronger)</span>
              {graph.regions.length > 0 && (
                <span><i style={{ background: 'transparent', border: '2px dashed var(--text)' }} />In a region</span>
              )}
              {neighbors !== null &&
                HOP_COLORS.slice(0, 3).map((c, i) => (
                  <span key={i}><i style={{ background: c }} />{i + 1} link{i ? 's' : ''} away</span>
                ))}
            </div>
            {graph.regions.map((r) => (
              <p key={r.id} className="page-subtitle" style={{ textAlign: 'center' }}>
                Region {r.label}: {r.states.map((s) => names.get(s) ?? s).join(', ')} (grouped together in{' '}
                {Math.round(r.stability * 100)}% of subsamples)
              </p>
            ))}
          </div>

          {neighbors && (
            <div className="card">
              <h3>
                Linked to {names.get(queriedSource)} within {maxHops} link{maxHops > 1 ? 's' : ''}
              </h3>
              {neighbors.length === 0 ? (
                <p className="page-subtitle">
                  {names.get(queriedSource)} has no validated link: the data could not confirm that its unemployment
                  and house prices move with another state's beyond the national cycle.
                </p>
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th>State</th>
                      <th>Links away</th>
                    </tr>
                  </thead>
                  <tbody>
                    {neighbors.map((n) => (
                      <tr key={n.state}>
                        <td>{names.get(n.state) ?? n.state}</td>
                        <td>{n.hops}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          )}

          <div className="card">
            <h3>How far to trust it</h3>
            <p className="page-subtitle">
              Learned from state unemployment and house prices through {v.learnedThrough}, and tested on mortgage
              delinquency over {v.testedOn}: the delinquency of linked states moved together more than that of other
              pairs
              {v.robustAgainstRandomGraphs && v.robustAgainstSameDivision
                ? ', and more than that of states in the same Census division, in every robustness run (without the forbearance months, without disaster-flagged loans).'
                : '.'}{' '}
              {v.leadsShipped
                ? null
                : 'The graph does not say which state moves first: no link passed the test of one state changing before the other.'}{' '}
              For portfolio-level analysis only, never an individual credit decision.
            </p>
            <p className="page-subtitle">
              Graph {graph.version}, delinquency data to {graph.asOf}. Method and limitations: the model inventory.
            </p>
          </div>
        </>
      )}
    </div>
  );
}
