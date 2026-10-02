export type RiskBand = 'LOW' | 'MEDIUM' | 'HIGH';

/** Thresholds are a judgment call, roughly matched to the spread actually observed across this
 * project's own sampled/seeded loans (which ranges from well under 1% to the mid-20s%). */
export function riskBand(calibratedProbability: number): RiskBand {
  if (calibratedProbability < 0.02) return 'LOW';
  if (calibratedProbability < 0.1) return 'MEDIUM';
  return 'HIGH';
}

/** Places a probability of default on the green-amber-red spectrum. The scale is banded, not linear:
 * each risk band (low < 2%, medium < 10%, high beyond) owns a third of the track, so a 1% and a 6%
 * loan are visibly different even though both are small on a linear axis. */
export function meterPosition(p: number): number {
  if (p < 0.02) return (p / 0.02) * 33.3;
  if (p < 0.1) return 33.3 + ((p - 0.02) / 0.08) * 33.3;
  return 66.6 + Math.min((p - 0.1) / 0.15, 1) * 33.4;
}
