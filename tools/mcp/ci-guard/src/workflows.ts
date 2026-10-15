import fs from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { JOBS, type Workflow } from './model.js';

/** The named steps of each job in a workflow file, as GitHub would run them. Unnamed steps (`uses:` only) are set-up. */
export interface WorkflowJob {
  workflow: Workflow;
  id: string;
  steps: string[];
}

export const WORKFLOW_FILES: Record<Workflow, string> = { CI: '.github/workflows/ci.yml', Security: '.github/workflows/security.yml' };

export function readWorkflows(root: string): WorkflowJob[] {
  const out: WorkflowJob[] = [];
  for (const [workflow, file] of Object.entries(WORKFLOW_FILES) as Array<[Workflow, string]>) {
    const doc = parse(fs.readFileSync(path.join(root, file), 'utf8')) as { jobs?: Record<string, { steps?: Array<{ name?: string }> }> };
    for (const [id, job] of Object.entries(doc.jobs ?? {})) {
      out.push({ workflow, id, steps: (job.steps ?? []).map((s) => s.name).filter((n): n is string => typeof n === 'string') });
    }
  }
  return out;
}

/** Every job or named step in the workflows that the model does not describe. Empty when the model is complete. */
export function missingFromModel(workflows: readonly WorkflowJob[], jobs = JOBS): string[] {
  const missing: string[] = [];
  for (const w of workflows) {
    const job = jobs.find((j) => j.id === w.id && j.workflow === w.workflow);
    if (!job) {
      missing.push(`${w.workflow} job '${w.id}'`);
      continue;
    }
    for (const step of w.steps) {
      if (!job.steps.some((s) => s.name === step)) missing.push(`${w.workflow} job '${w.id}' step '${step}'`);
    }
  }
  return missing;
}
