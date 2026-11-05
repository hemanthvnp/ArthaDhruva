import type { AllowedCommand, RunResult } from '@arthadhruva/mcp-kit';
import { ALLOWED_TEST } from './forbidden.js';

/**
 * TermStructureGoldenTest through the Maven wrapper: the one process this server starts. It holds the Java engine to the
 * independent Python implementation to 1e-6, so a pass means the exported survival model and the service still agree.
 * The command line is fixed here; a caller supplies nothing that reaches it.
 */
export const GOLDEN_ID = 'golden-test';

export function allowlist(windows = process.platform === 'win32'): AllowedCommand[] {
  return [{
    id: GOLDEN_ID,
    // a relative path: cmd.exe does not search the current directory for a bare name when NoDefaultCurrentDirectoryInExePath is set
    command: windows ? '.\\mvnw.cmd' : './mvnw',
    args: ['test', `-Dtest=${ALLOWED_TEST}`, '-Dsurefire.failIfNoSpecifiedTests=true', '-B'],
    cwd: 'backend/risk-engine',
    timeoutMs: 10 * 60_000,
  }];
}

export type GoldenStatus = 'passed' | 'failed' | 'skipped';

export interface GoldenReport {
  status: GoldenStatus;
  durationMs: number;
  testsRun: number | null;
  /** Why it was skipped or timed out; null otherwise. */
  reason: string | null;
  /** The end of Maven's output, kept only when the test did not pass. */
  output: string | null;
}

/** Output that says Maven could not run at all (as opposed to running and failing): the check did not happen. */
const CANNOT_RUN = [
  /JAVA_HOME/i, /Unable to locate a Java/i, /java(\.exe)?: (command )?not found/i, /is not recognized as an internal or external command/i,
  /No such file or directory/i, /Could not find or load main class org\.apache\.maven\.wrapper/i, /Cannot start maven from wrapper/i,
  /Unknown lifecycle phase/i, /spawn error/i,
];

const TESTS = /Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)[^\n]*?-- in [\w.]*TermStructureGoldenTest/;

export function interpret(result: RunResult): GoldenReport {
  const tail = (s: string, lines = 60) => s.split(/\r?\n/).slice(-lines).join('\n');
  const combined = `${result.stdout}\n${result.stderr}`;
  const base = { durationMs: result.durationMs, testsRun: null as number | null };
  if (result.cancelled) return { ...base, status: 'skipped', reason: 'the run was cancelled', output: null };
  if (result.timedOut) return { ...base, status: 'failed', reason: 'it did not finish within the time limit (10 minutes)', output: tail(combined) };
  const summary = TESTS.exec(combined);
  if (summary) {
    const [run, failures, errors, skipped] = [summary[1], summary[2], summary[3], summary[4]].map(Number) as [number, number, number, number];
    const clean = result.code === 0 && failures === 0 && errors === 0 && skipped === 0 && run > 0;
    return { ...base, testsRun: run, status: clean ? 'passed' : 'failed', reason: clean ? null : `${failures} failure(s), ${errors} error(s), ${skipped} skipped of ${run}`, output: clean ? null : tail(combined) };
  }
  // No test summary: Maven either could not start, or stopped before the tests (a compile error is a failure, not a skip).
  if (result.code === 127 || (result.code !== 0 && CANNOT_RUN.some((re) => re.test(combined)) && !/COMPILATION ERROR|BUILD FAILURE/.test(combined))) {
    return { ...base, status: 'skipped', reason: 'the Maven wrapper could not run here (Java or the wrapper is not available)', output: tail(combined, 15) };
  }
  if (result.code === 0) {
    return { ...base, status: 'failed', reason: 'Maven finished but reported no result for TermStructureGoldenTest, so nothing was verified', output: tail(combined) };
  }
  return { ...base, status: 'failed', reason: `Maven stopped before the tests ran (exit ${result.code})`, output: tail(combined) };
}
