import { createHash } from 'node:crypto';

/**
 * Each committed model artifact and where its card records the artifact's checksum. This is the same contract the
 * service enforces at start-up (it refuses to run on a mismatch), checked from outside and without starting it. A
 * checksum is over the file's bytes exactly as they are on disk, so it is only meaningful for files whose line endings
 * Git does not rewrite: the binary ONNX files, and the JSON files pinned to LF in .gitattributes.
 */
export interface Check {
  /** The model in the service's inventory. */
  model: string;
  /** The JSON file holding the declared checksum. */
  card: string;
  /** The field of the card that holds it. */
  field: string;
  /** The artifact it describes. */
  artifact: string;
}

export const RESOURCES = 'backend/risk-engine/src/main/resources';

export const CHECKS: readonly Check[] = [
  { model: 'pd_24m', card: 'pd_model_card.json', field: 'artifact_sha256', artifact: 'model.onnx' },
  { model: 'survival', card: 'survival_model.json', field: 'artifact_sha256', artifact: 'survival_model.onnx' },
  { model: 'delinquency', card: 'delinquency_model.json', field: 'artifact_sha256', artifact: 'delinquency_model.onnx' },
  { model: 'regime_hmm', card: 'hmm_regime_card.json', field: 'artifact_sha256', artifact: 'hmm_regime.json' },
  { model: 'early_warning', card: 'early_warning_card.json', field: 'artifact_sha256', artifact: 'early_warning_model.onnx' },
  { model: 'early_warning', card: 'early_warning_card.json', field: 'calibration_sha256', artifact: 'early_warning_calibration.json' },
  { model: 'segment_graph', card: 'segment_graph_card.json', field: 'artifact_sha256', artifact: 'segment_graph.json' },
];

export type Verdict = 'match' | 'mismatch' | 'missing';

export interface Outcome {
  model: string;
  artifact: string;
  card: string;
  verdict: Verdict;
  declared: string | null;
  actual: string | null;
  /** Why it is `missing`: the artifact, the card, or the field. */
  detail: string | null;
}

export interface Reader {
  read(relative: string): Buffer | undefined;
}

const sha256 = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');

export function verify(reader: Reader, checks: readonly Check[] = CHECKS): { passed: boolean; outcomes: Outcome[] } {
  const outcomes = checks.map((c): Outcome => {
    const base = { model: c.model, artifact: c.artifact, card: c.card };
    const artifact = reader.read(`${RESOURCES}/${c.artifact}`);
    const cardBytes = reader.read(`${RESOURCES}/${c.card}`);
    if (!cardBytes) return { ...base, verdict: 'missing', declared: null, actual: artifact ? sha256(artifact) : null, detail: `the card ${c.card} is missing` };
    let declared: unknown;
    try {
      declared = (JSON.parse(cardBytes.toString('utf8')) as Record<string, unknown>)[c.field];
    } catch {
      return { ...base, verdict: 'missing', declared: null, actual: artifact ? sha256(artifact) : null, detail: `the card ${c.card} is not valid JSON` };
    }
    if (typeof declared !== 'string') return { ...base, verdict: 'missing', declared: null, actual: artifact ? sha256(artifact) : null, detail: `the card ${c.card} has no ${c.field}` };
    if (!artifact) return { ...base, verdict: 'missing', declared, actual: null, detail: `the artifact ${c.artifact} is missing` };
    const actual = sha256(artifact);
    return { ...base, verdict: actual === declared ? 'match' : 'mismatch', declared, actual, detail: null };
  });
  return { passed: outcomes.every((o) => o.verdict === 'match'), outcomes };
}
