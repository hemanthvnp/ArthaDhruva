interface Props {
  label: string;
  point: number;
  lower: number;
  upper: number;
  color: string;
  format: (n: number) => string;
}

/** A small track showing a bootstrap confidence interval with the point estimate marked --
 * the visual point of the CVaR endpoint: a range, not a single number. */
export default function RangeBar({ label, point, lower, upper, color, format }: Props) {
  const span = Math.max(upper - lower, 1e-9);
  const pct = (v: number) => ((v - lower) / span) * 100;

  return (
    <div style={{ marginBottom: '1rem' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.85rem', marginBottom: '0.3rem' }}>
        <span style={{ color: 'var(--text-muted)' }}>{label}</span>
        <span style={{ fontWeight: 600 }}>{format(point)}</span>
      </div>
      <div style={{ position: 'relative', height: 10, background: '#eef1f5', borderRadius: 5 }}>
        <div
          style={{
            position: 'absolute',
            left: 0,
            width: '100%',
            height: '100%',
            borderRadius: 5,
            background: color,
            opacity: 0.25,
          }}
        />
        <div
          style={{
            position: 'absolute',
            left: `calc(${pct(point)}% - 2px)`,
            top: -3,
            width: 4,
            height: 16,
            background: color,
            borderRadius: 2,
          }}
        />
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: '0.2rem' }}>
        <span>{format(lower)}</span>
        <span>{format(upper)}</span>
      </div>
    </div>
  );
}
