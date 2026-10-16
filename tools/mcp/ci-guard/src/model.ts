import type { AllowedCommand } from '@arthadhruva/mcp-kit';

/**
 * One table of this repository's CI, job by job and step by step, mirroring `.github/workflows/ci.yml` and
 * `security.yml` (a test parses both and fails on any named step missing here). Each step is either run
 * locally, through commands on the server's allowlist or a built-in check, or marked CI-only with the reason
 * it cannot be reproduced on a workstation. Nothing here is computed from tool arguments.
 */
export type Workflow = 'CI' | 'Security';

export interface LocalStep {
  /** The step's name exactly as the workflow writes it. */
  name: string;
  /** Allowlisted command ids, run in order; the step fails on the first non-zero exit. */
  commands?: readonly string[];
  /** A check done in-process instead of by a command. */
  check?: 'dev-only-files';
  needsDocker?: boolean;
}

export interface CiOnlyStep {
  name: string;
  ciOnly: string;
}

export type Step = LocalStep | CiOnlyStep;

export interface Job {
  /** The job's key in the workflow file. */
  id: string;
  workflow: Workflow;
  /** The name GitHub reports for the job, which history is keyed by. */
  githubName: string;
  /** Path prefixes that make the job relevant to a diff ('' = every change). */
  paths: readonly string[];
  /** Seconds, used when history has fewer than three successful runs. */
  fallbackSec: number;
  /** Runs after this job in CI (for the longest-chain estimate). */
  after?: string;
  /** Not run by a default preflight; the reason is reported as the skip reason. */
  optIn?: string;
  steps: readonly Step[];
}

export const isCiOnly = (s: Step): s is CiOnlyStep => 'ciOnly' in s;

const SETUP = 'toolchain set-up on the runner; locally the installed JDK and Node are used (static_checks flags a Node major mismatch)';

export const JOBS: readonly Job[] = [
  {
    id: 'backend', workflow: 'CI', githubName: 'backend', paths: ['backend/risk-engine/'], fallbackSec: 300,
    steps: [
      { name: 'Set up JDK 17', ciOnly: SETUP },
      { name: 'Run backend tests', commands: ['backend-test'], needsDocker: true },
    ],
  },
  {
    id: 'frontend', workflow: 'CI', githubName: 'frontend', paths: ['frontend/'], fallbackSec: 150,
    steps: [
      { name: 'Set up Node.js', ciOnly: SETUP },
      { name: 'Install dependencies', commands: ['frontend-ci'] },
      { name: 'Lint', commands: ['frontend-lint'] },
      { name: 'Unit tests', commands: ['frontend-test'] },
      { name: 'Type-check and build', commands: ['frontend-build'] },
      { name: 'Enforce initial-bundle budget', commands: ['frontend-bundle'] },
    ],
  },
  {
    id: 'branch-policy', workflow: 'CI', githubName: 'branch-policy', paths: [''], fallbackSec: 10,
    steps: [
      { name: 'Promotions come from the right branch', ciOnly: 'judges a pull request\'s base and head branch, which exist only on GitHub' },
      { name: 'Dev-only files stay off staging and prod', check: 'dev-only-files' },
    ],
  },
  {
    id: 'deployment-files', workflow: 'CI', githubName: 'deployment-files',
    paths: ['docker-compose', 'observability/', 'deploy/', 'scripts/', 'nginx/', 'postgres/'], fallbackSec: 60,
    steps: [
      { name: 'Compose files parse in every layout', commands: ['compose-all', 'compose-dev', 'compose-prod'], needsDocker: true },
      { name: 'Alert rules pass their unit tests', commands: ['promtool-config', 'promtool-rules'], needsDocker: true },
      { name: 'Shell scripts are sound', commands: ['shellcheck'], needsDocker: true },
      { name: 'The wake function passes its unit tests', commands: ['wake-test'] },
    ],
  },
  {
    id: 'loadtest', workflow: 'CI', githubName: 'loadtest', paths: ['loadtest/', 'backend/', 'docker-compose', 'nginx/'], fallbackSec: 240,
    after: 'backend', optIn: 'boots the full stack and needs a session token minted with the stack\'s JWT secret; CI runs it after backend',
    steps: [
      { name: 'Start the stack', ciOnly: 'part of the k6 smoke test (opt-in)' },
      { name: 'Wait for the backend to be healthy', ciOnly: 'part of the k6 smoke test (opt-in)' },
      { name: 'Mint a session token for the bootstrap admin', ciOnly: 'part of the k6 smoke test (opt-in)' },
      { name: 'Run k6', ciOnly: 'part of the k6 smoke test (opt-in)' },
    ],
  },
  {
    id: 'failover', workflow: 'CI', githubName: 'failover', paths: ['backend/', 'scripts/', 'docker-compose'], fallbackSec: 420,
    after: 'backend', optIn: 'starts two backend replicas and kills one mid-run (several minutes, the whole stack)',
    steps: [{ name: 'Kill a replica in the middle of a run', ciOnly: 'scripts/failover-test.sh boots two replicas; run it by hand or in CI' }],
  },
  {
    id: 'secrets', workflow: 'Security', githubName: 'Secret scan', paths: [''], fallbackSec: 45,
    steps: [{ name: 'Scan every commit with gitleaks', commands: ['gitleaks'], needsDocker: true }],
  },
  {
    id: 'dependency-review', workflow: 'Security', githubName: 'Dependency review', paths: ['frontend/package', 'backend/risk-engine/pom.xml'], fallbackSec: 30,
    steps: [{ name: 'Refuse new dependencies with known vulnerabilities', ciOnly: 'a GitHub action that compares a pull request\'s dependency graph with its base' }],
  },
  {
    id: 'codeql', workflow: 'Security', githubName: 'CodeQL', paths: [''], fallbackSec: 300,
    steps: [],
  },
  {
    id: 'image', workflow: 'Security', githubName: 'Container scan', paths: ['backend/risk-engine/'], fallbackSec: 360,
    optIn: 'builds the backend image and scans it with Trivy (several minutes, needs Docker)',
    steps: [
      { name: 'Build the backend image', commands: ['image-build'], needsDocker: true },
      { name: 'Scan the image for known vulnerabilities', commands: ['trivy-image'], needsDocker: true },
      { name: 'Scan the Dockerfiles for misconfigurations', commands: ['trivy-config'], needsDocker: true },
      { name: 'Report image findings', ciOnly: 'uploads SARIF to GitHub code scanning' },
      { name: 'Report Dockerfile findings', ciOnly: 'uploads SARIF to GitHub code scanning' },
    ],
  },
];

/** Cheap jobs first, so a mistake surfaces in seconds rather than after the Maven run. */
export const ORDER = ['branch-policy', 'secrets', 'deployment-files', 'frontend', 'backend', 'image', 'loadtest', 'failover', 'dependency-review', 'codeql'];

export interface Platform {
  windows: boolean;
  /** The repository root with forward slashes, as Docker Desktop accepts it in a volume. */
  rootForDocker: string;
  /** Shell scripts for shellcheck, listed here because cmd.exe does not expand globs. */
  shellScripts: readonly string[];
}

/**
 * The allowlist for this repository, built once at startup from the model's command ids. The only inputs are
 * the platform and the repository's own script list; a tool call cannot add or change an entry.
 */
export function allowlist(p: Platform): AllowedCommand[] {
  const npm = p.windows ? 'npm.cmd' : 'npm';
  const mvnw = p.windows ? 'mvnw.cmd' : './mvnw';
  const promtool = (args: string[]) => ['run', '--rm', '-v', `${p.rootForDocker}/observability:/w:ro`, '--entrypoint', 'promtool', 'prom/prometheus:v2.55.1', ...args];
  const min = 60_000;
  return [
    { id: 'backend-test', command: mvnw, args: ['-B', 'test'], cwd: 'backend/risk-engine', timeoutMs: 45 * min },
    { id: 'frontend-ci', command: npm, args: ['ci', '--no-audit', '--no-fund'], cwd: 'frontend', timeoutMs: 10 * min },
    { id: 'frontend-lint', command: npm, args: ['run', 'lint'], cwd: 'frontend', timeoutMs: 5 * min },
    { id: 'frontend-test', command: npm, args: ['test'], cwd: 'frontend', timeoutMs: 10 * min, env: { CI: 'true' } },
    { id: 'frontend-build', command: npm, args: ['run', 'build'], cwd: 'frontend', timeoutMs: 10 * min },
    { id: 'frontend-bundle', command: npm, args: ['run', 'check:bundle'], cwd: 'frontend', timeoutMs: 2 * min },
    { id: 'compose-all', command: 'docker', args: ['compose', '--profile', 'app', '--profile', 'edge', '--profile', 'observability', '--profile', 'notebooks', '--profile', 'pipeline', 'config', '-q'], timeoutMs: 2 * min },
    { id: 'compose-dev', command: 'docker', args: ['compose', '-f', 'docker-compose.yml', '-f', 'docker-compose.dev.yml', 'config', '-q'], timeoutMs: 2 * min },
    { id: 'compose-prod', command: 'docker', args: ['compose', '-f', 'docker-compose.yml', '-f', 'docker-compose.prod.yml', '-f', 'docker-compose.deploy.yml', '--profile', 'app', '--profile', 'edge', 'config', '-q'], env: { SITE_ADDRESS: 'example.test' }, timeoutMs: 2 * min },
    { id: 'promtool-config', command: 'docker', args: promtool(['check', 'config', '/w/prometheus.yml']), timeoutMs: 5 * min },
    { id: 'promtool-rules', command: 'docker', args: promtool(['test', 'rules', '/w/alerts.test.yml']), timeoutMs: 5 * min },
    { id: 'shellcheck', command: 'docker', args: ['run', '--rm', '-v', `${p.rootForDocker}:/mnt:ro`, '-w', '/mnt', 'koalaman/shellcheck:v0.11.0', ...p.shellScripts], timeoutMs: 5 * min },
    { id: 'wake-test', command: process.execPath, args: ['--test', 'deploy/azure/wake.test.js'], timeoutMs: 2 * min },
    { id: 'gitleaks', command: 'docker', args: ['run', '--rm', '-v', `${p.rootForDocker}:/repo`, 'ghcr.io/gitleaks/gitleaks:v8.30.1', 'git', '/repo', '--redact', '--no-banner'], timeoutMs: 10 * min },
    { id: 'image-build', command: 'docker', args: ['build', '--pull', '-t', 'risk-engine:scan', 'backend/risk-engine'], timeoutMs: 20 * min },
    { id: 'trivy-image', command: 'docker', args: ['run', '--rm', '-v', '/var/run/docker.sock:/var/run/docker.sock', 'aquasec/trivy:0.74.0', 'image', '--scanners', 'vuln', '--severity', 'HIGH,CRITICAL', '--ignore-unfixed', 'risk-engine:scan'], timeoutMs: 15 * min },
    { id: 'trivy-config', command: 'docker', args: ['run', '--rm', '-v', `${p.rootForDocker}:/src:ro`, 'aquasec/trivy:0.74.0', 'config', '--severity', 'MEDIUM,HIGH,CRITICAL', '/src'], timeoutMs: 10 * min },
  ];
}
