import type { Repo } from './git.js';

/**
 * Concrete, deterministic findings for the failures this repository can actually hit, most of them the
 * Windows-versus-Linux kind that pass on a workstation and fail on the runner. Each names a file or a value.
 */
export interface Finding {
  severity: 'high' | 'medium' | 'low';
  job: string;
  message: string;
}

/** CI's Node major (actions/setup-node in ci.yml). */
export const CI_NODE_MAJOR = 22;

export function staticChecks(repo: Repo, files: readonly string[], nodeVersion: string = process.versions.node): Finding[] {
  const findings: Finding[] = [];
  const add = (severity: Finding['severity'], job: string, message: string) => findings.push({ severity, job, message });
  const changed = new Set(files);

  for (const f of files.filter((x) => /\.sh$/.test(x) || /(^|\/)mvnw$/.test(x))) {
    const bytes = repo.read(f);
    if (bytes?.includes('\r\n')) add('high', 'deployment-files', `${f} has CRLF line endings: it fails on the Linux runner (bad interpreter, shellcheck SC1017).`);
  }
  for (const dir of ['frontend', 'tools/mcp']) {
    if (changed.has(`${dir}/package.json`) && !changed.has(`${dir}/package-lock.json`)) {
      add('high', 'frontend', `${dir}/package.json changed but its package-lock.json did not: npm ci fails when they disagree.`);
    }
  }
  const seen = new Map<string, string>();
  for (const f of repo.trackedFiles()) {
    const key = f.toLowerCase();
    const prior = seen.get(key);
    if (prior !== undefined && prior !== f) add('high', 'all', `Case-only path collision: ${prior} and ${f} are one file on Windows and two on Linux.`);
    seen.set(key, f);
  }
  for (const f of files) {
    if (/(^|\/)(secrets\/|\.env($|\.)(?!example$)|demo-credentials\.txt$)/.test(f) && repo.read(f) !== undefined) {
      add('high', 'secrets', `${f} is a secrets path: it must not be committed (gitleaks fails the Security workflow).`);
    }
  }
  const major = Number(nodeVersion.split('.')[0]);
  if (major !== CI_NODE_MAJOR) add('low', 'frontend', `Local Node is ${major}; CI uses ${CI_NODE_MAJOR}, so a local pass is not a guarantee.`);
  if (files.some((f) => /\.onnx$/.test(f) || /resources\/.*(card|model)\.json$/.test(f) || /^backend\/(export_|golden_)/.test(f))) {
    add('medium', 'backend', 'A model artifact, card or export script changed: the artifact, its card checksum and its golden fixture must change together.');
  }
  if (files.some((f) => f.startsWith('deploy/azure/network/')) && /^(release\/|staging$|prod$)/.test(repo.currentBranch())) {
    add('high', 'branch-policy', 'deploy/azure/network/ is dev-only and is in this release branch.');
  }
  return findings;
}
