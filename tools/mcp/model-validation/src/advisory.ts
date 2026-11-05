/**
 * When a model artifact or the script that exports it has changed, the golden fixture that holds the Java service to a
 * Python reference is out of date until the owner regenerates it. This says so. It never runs the script: regenerating
 * a fixture is the owner's step (CLAUDE.md, "Rebuilding models"), and a fixture refreshed by a tool that did not check
 * why it changed would hide exactly the drift the test exists to catch.
 */
export interface Watch {
  model: string;
  /** The script the owner runs. */
  script: string;
  /** The Java test that holds the service to it. */
  test: string;
  /** Paths (exact, or prefix when ending in `/`) whose change makes the fixture stale. */
  paths: readonly string[];
}

const R = 'backend/risk-engine/src/main/resources/';

/** What golden_term_structure.py reads (and the scripts that rebuild them). The delinquency fixture reuses the same engine, so it depends on these too. */
const SURVIVAL_INPUTS = [
  `${R}survival_model.onnx`, `${R}survival_model.json`, `${R}category_mappings.json`, `${R}market_rates.json`, `${R}macro_state.json`,
  `${R}hmm_regime.json`, 'backend/export_survival_model.py', 'backend/export_hmm.py', 'backend/build_survival_dataset.py', 'backend/build_crisis_dataset.py',
];

export const WATCHES: readonly Watch[] = [
  { model: 'survival term structure', script: 'golden_term_structure.py', test: 'TermStructureGoldenTest',
    paths: [...SURVIVAL_INPUTS, 'backend/golden_term_structure.py'] },
  { model: 'PD model', script: 'golden_pd_model.py', test: 'PdModelParityTest',
    paths: ['backend/export_model.py', 'backend/golden_pd_model.py', `${R}model.onnx`, `${R}calibration.json`, `${R}feature_order.json`,
      `${R}category_mappings.json`, `${R}market_rates.json`, `${R}pd_model_card.json`] },
  { model: 'delinquency transitions', script: 'golden_multistate.py', test: 'DelinquencyGoldenTest',
    paths: [...SURVIVAL_INPUTS, 'backend/export_delinquency_model.py', 'backend/delinquency_history.py', 'backend/golden_multistate.py',
      `${R}delinquency_model.onnx`, `${R}delinquency_model.json`] },
  { model: 'early warning', script: 'golden_early_warning.py', test: 'EarlyWarningParityTest',
    paths: ['backend/export_early_warning_model.py', 'backend/golden_early_warning.py', `${R}early_warning_model.onnx`,
      `${R}early_warning_calibration.json`, `${R}early_warning_feature_order.json`, `${R}early_warning_category_mappings.json`] },
];

export interface Advice {
  model: string;
  script: string;
  test: string;
  changed: string[];
  message: string;
}

function touches(file: string, path: string): boolean {
  return path.endsWith('/') ? file.startsWith(path) : file === path;
}

export function advise(changedFiles: readonly string[]): Advice[] {
  const out: Advice[] = [];
  for (const w of WATCHES) {
    const changed = changedFiles.filter((f) => w.paths.some((p) => touches(f, p)));
    if (changed.length === 0) continue;
    out.push({
      model: w.model, script: w.script, test: w.test, changed,
      message: `${w.model}: ${changed.join(', ')} changed, so the owner must re-run backend/${w.script} and then ${w.test}. This server does not run `
        + `${w.script}; the fixture stays out of date until it is run.`,
    });
  }
  return out;
}
