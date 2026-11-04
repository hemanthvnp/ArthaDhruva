import { forbidden } from '@arthadhruva/mcp-kit';

/**
 * What this server will not run, and why. The server runs exactly one thing, TermStructureGoldenTest through Maven;
 * everything else is refused, and the refusals that matter say that the OWNER must run it. Exports, the dataset
 * builders, the golden-fixture scripts, the data pipeline and the notebook profiles take a long time, need the raw
 * archives, and change checksummed artifacts that the service refuses to start without: the repository's rules reserve
 * them for the owner. A caller cannot talk a tool into them because no tool takes a command line; this module is what
 * a request that names one meets.
 */
export const ALLOWED_TEST = 'TermStructureGoldenTest';

const OWNER_ONLY: ReadonlyArray<readonly [RegExp, string]> = [
  [/(^|[\s/\\])export_[\w-]+\.py\b/i, 'a model export'],
  [/\bfetch_macro\.py\b/i, 'the macro data fetch'],
  [/\bbuild_(survival|crisis)_dataset\.py\b/i, 'a training-dataset build'],
  [/\bgolden_[\w-]+\.py\b/i, 'a golden-fixture script'],
  [/\bvalidate_[\w-]+\.py\b/i, 'a model-card script (it rewrites a checksummed card)'],
  [/\bpipeline\b/i, 'the data pipeline'],
  [/\bnotebook(s)?\b|\.ipynb\b|--profile\b/i, 'a notebook or pipeline compose profile'],
  [/\bexport\b|\bretrain\b|\brefit\b|\brebuild\b/i, 'a model export or rebuild'],
];

/** Throws Forbidden unless `name` is exactly the one test this server runs. */
export function assertAllowedTest(name: string): void {
  if (name === ALLOWED_TEST) return;
  const owner = OWNER_ONLY.find(([re]) => re.test(name));
  if (owner) {
    throw forbidden(`Refused: that asks for ${owner[1]}. Only the owner runs those, explicitly: they need the raw archives, take a long `
      + `time, and change artifacts whose checksums the service verifies at start-up. This server runs ${ALLOWED_TEST} and nothing else.`);
  }
  throw forbidden(`Refused: this server runs only ${ALLOWED_TEST}.`);
}
