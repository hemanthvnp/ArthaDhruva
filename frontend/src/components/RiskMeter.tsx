/** Places a probability of default on the green-amber-red spectrum. The scale is banded, not linear:
 * each risk band (low < 2%, medium < 10%, high beyond) owns a third of the track, so a 1% and a 6%
 * loan are visibly different even though both are small on a linear axis. */
export function meterPosition(p: number): number {
  if (p < 0.02) return (p / 0.02) * 33.3;
  if (p < 0.1) return 33.3 + ((p - 0.02) / 0.08) * 33.3;
  return 66.6 + Math.min((p - 0.1) / 0.15, 1) * 33.4;
}

export default function RiskMeter({ probability, small = false, scale = false }: { probability: number; small?: boolean; scale?: boolean }) {
  const pos = Math.max(0, Math.min(100, meterPosition(probability)));
  return (
    <div>
      <div className={`meter${small ? ' sm' : ''}`} role="img" aria-label={`Default probability ${(probability * 100).toFixed(2)} percent`}>
        <span className="pin" style={{ left: `${pos}%` }} />
      </div>
      {scale && (
        <div className="meter-scale" aria-hidden="true">
          <span>Low</span>
          <span>Medium</span>
          <span>High</span>
        </div>
      )}
    </div>
  );
}
