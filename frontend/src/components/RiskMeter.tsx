import { meterPosition } from './risk';

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
