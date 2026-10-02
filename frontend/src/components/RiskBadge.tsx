import { riskBand, type RiskBand } from './risk';

const LABEL: Record<RiskBand, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' };

/** Colour is never the only signal: the band is always spelled out in text. */
export default function RiskBadge({ probability }: { probability: number }) {
  const band = riskBand(probability);
  return <span className={`badge badge-${band.toLowerCase()}`}>{LABEL[band]}</span>;
}
