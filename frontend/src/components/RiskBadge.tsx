export type RiskBand = 'LOW' | 'MEDIUM' | 'HIGH';

/** Thresholds are a judgment call, roughly matched to the spread actually observed across this
 * project's own sampled/seeded loans (which ranges from well under 1% to the mid-20s%). */
export function riskBand(calibratedProbability: number): RiskBand {
  if (calibratedProbability < 0.02) return 'LOW';
  if (calibratedProbability < 0.1) return 'MEDIUM';
  return 'HIGH';
}

const LABEL: Record<RiskBand, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' };

/** Colour is never the only signal: the band is always spelled out in text. */
export default function RiskBadge({ probability }: { probability: number }) {
  const band = riskBand(probability);
  return <span className={`badge badge-${band.toLowerCase()}`}>{LABEL[band]}</span>;
}
