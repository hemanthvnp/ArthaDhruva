import type { RealizedReportBody } from '../api/types';

/**
 * What a comparison says, in words, from its own numbers: how the stored scores' average sits against what happened, and how well
 * they rank. It states what the report cannot say too (too few resolved loans, no defaults), instead of a verdict it does not have.
 */
export function realizedVerdict(r: RealizedReportBody): string[] {
  if (r.resolved === 0) return ['No stored score has an outcome that resolves it yet.'];
  const lines: string[] = [];
  const pct = (v: number) => `${(v * 100).toFixed(2)}%`;
  if (r.predictedOverRealized === null) {
    lines.push(`Across ${r.resolved.toLocaleString('en-US')} resolved loans the model predicted ${pct(r.meanPredicted)} and none defaulted, so there is no ratio.`);
  } else {
    const off = r.predictedOverRealized - 1;
    const direction = Math.abs(off) < 0.1 ? 'close to' : off > 0 ? 'above' : 'below';
    lines.push(`Across ${r.resolved.toLocaleString('en-US')} resolved loans the model predicted ${pct(r.meanPredicted)} and ${pct(r.realizedRate)} defaulted `
      + `(${r.defaults.toLocaleString('en-US')} loans): predicted over realized is ${r.predictedOverRealized.toFixed(2)}, ${direction} what happened.`);
  }
  if (r.auc !== null) lines.push(`The stored scores rank the loans that defaulted above those that did not with AUC ${r.auc.toFixed(3)}.`);
  if (r.note) lines.push(r.note);
  return lines;
}

/** Outcomes that could not resolve a score, by reason, for the line under the table. */
export function unresolvedLine(r: RealizedReportBody): string | null {
  const parts: string[] = [];
  if (r.unresolvedDefaultedOrPrepaidBeforeScored > 0) parts.push(`${r.unresolvedDefaultedOrPrepaidBeforeScored} defaulted or prepaid before they were scored`);
  if (r.unresolvedNotObservedThroughHorizon > 0) parts.push(`${r.unresolvedNotObservedThroughHorizon} not yet observed through the ${r.horizonMonths}-month horizon`);
  const outsideScores = r.outcomesRecorded - r.matchedToAScore;
  if (outsideScores > 0) parts.push(`${outsideScores} recorded for loans with no stored score`);
  return parts.length ? `Left out, and counted: ${parts.join('; ')}.` : null;
}
